package com.aura.app.data

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.aura.app.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

val TOPICS = listOf("Sleep", "Steps", "Training", "Screen time", "Food", "Mind", "General")

/** A forum post. "done" is something achieved (from the ledger, goals or activities); "ask" is a question written by the user. */
data class Post(
    val id: String, val uid: String, val name: String, val occupation: String,
    val type: String, val topic: String, val title: String, val body: String, val points: Int, val ts: Long,
    val up: Int = 0, val down: Int = 0, val score: Int = 0, val comments: Int = 0,
    /** this user's vote: 1, -1 or 0 */
    val myVote: Int = 0,
    /** Ask posts: the id of the comment the author marked as helpful, or empty */
    val helped: String = "",
)

data class Gift(val id: String, val from: String, val fromName: String, val points: Int, val reason: String, val postId: String, val title: String, val ts: Long)

data class Comment(val id: String, val uid: String, val name: String, val text: String, val ts: Long)

data class Person(val uid: String, val name: String, val occupation: String, val aura: Int)

/**
 * Firebase Auth + Firestore. Layout:
 *   users/{uid}                 name, nameLower, occupation, aura          (readable by any signed-in user)
 *   users/{uid}/goals/{id}      the user's goals                           (private)
 *   users/{uid}/following/{id}  who the user follows                       (private)
 *   posts/{id}                  forum posts (done / ask)                   (readable by any signed-in user)
 *   posts/{id}/votes/{uid}      {v: 1 | -1}
 *   posts/{id}/comments/{cid}   {uid, name, text, ts}
 * The feed is one orderBy(ts) query, so no composite index is needed.
 */
class Cloud(private val ctx: Context) {
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    val uid: String? get() = auth.currentUser?.uid
    val email: String? get() = auth.currentUser?.email

    suspend fun signIn(activity: Activity) {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(ctx.getString(R.string.default_web_client_id))
            .build()
        val result = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val cred = result.credential
        require(cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Unexpected sign-in response" }
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
    }

    fun signOut() = auth.signOut()

    suspend fun pushProfile(p: Profile, aura: Int) {
        val u = uid ?: return
        db.collection("users").document(u).set(
            mapOf("name" to p.name, "nameLower" to p.name.lowercase(), "occupation" to p.occupation, "aura" to aura)
        ).await()
    }

    suspend fun pushGoals(goals: List<Goal>) {
        val u = uid ?: return
        val col = db.collection("users").document(u).collection("goals")
        val existing = col.get().await().documents.map { it.id }.toSet()
        val batch = db.batch()
        goals.forEach {
            batch.set(col.document(it.id), mapOf(
                "title" to it.title, "metric" to it.metric.name, "target" to it.target, "days" to it.days,
                "start" to it.startEpochDay, "awarded" to it.awarded, "done" to it.completedEpochDay,
            ))
        }
        (existing - goals.map { it.id }.toSet()).forEach { batch.delete(col.document(it)) }
        batch.commit().await()
    }

    suspend fun pullGoals(): List<Goal> {
        val u = uid ?: return emptyList()
        return db.collection("users").document(u).collection("goals").get().await().documents.mapNotNull { d ->
            runCatching {
                Goal(
                    id = d.id, title = d.getString("title") ?: "", metric = Metric.valueOf(d.getString("metric") ?: "STEPS"),
                    target = d.getDouble("target") ?: 0.0, days = (d.getLong("days") ?: 1).toInt(),
                    startEpochDay = d.getLong("start") ?: 0, awarded = (d.getLong("awarded") ?: 0).toInt(),
                    completedEpochDay = d.getLong("done") ?: -1,
                )
            }.getOrNull()
        }
    }

    /** Create a Done post once. The id is stable, so posting twice never resets votes. */
    suspend fun postDone(key: String, topic: String, title: String, detail: String, points: Int, ts: Long, p: Profile) {
        val u = uid ?: return
        val ref = db.collection("posts").document("${u}_$key")
        db.runTransaction { tx ->
            if (!tx.get(ref).exists()) tx.set(ref, postMap(u, p, "done", topic, title, detail, points, ts))
        }.await()
    }

    suspend fun postAsk(topic: String, title: String, body: String, p: Profile) {
        val u = uid ?: return
        db.collection("posts").document().set(postMap(u, p, "ask", topic, title, body, 0, System.currentTimeMillis())).await()
    }

    private fun postMap(u: String, p: Profile, type: String, topic: String, title: String, body: String, points: Int, ts: Long) = mapOf(
        "uid" to u, "name" to p.name, "occupation" to p.occupation, "type" to type, "topic" to topic,
        "title" to title.take(80), "body" to body.take(500), "points" to points, "ts" to ts,
        "up" to 0, "down" to 0, "score" to 0, "comments" to 0,
    )

    suspend fun deletePost(id: String) { db.collection("posts").document(id).delete().await() }

    suspend fun unpostActivity(id: Long) {
        val u = uid ?: return
        db.collection("posts").document("${u}_act_$id").delete().await()
    }

    /** Set this user's vote on a post: 1, -1, or 0 to remove. Reads the old vote and adjusts the counters in one transaction. */
    suspend fun vote(postId: String, v: Int) {
        val u = uid ?: return
        val post = db.collection("posts").document(postId)
        val mine = post.collection("votes").document(u)
        db.runTransaction { tx ->
            val old = (tx.get(mine).getLong("v") ?: 0L).toInt()
            val snap = tx.get(post)
            if (!snap.exists() || old == v) return@runTransaction
            val up = (snap.getLong("up") ?: 0).toInt() - (if (old == 1) 1 else 0) + (if (v == 1) 1 else 0)
            val down = (snap.getLong("down") ?: 0).toInt() - (if (old == -1) 1 else 0) + (if (v == -1) 1 else 0)
            if (v == 0) tx.delete(mine) else tx.set(mine, mapOf("v" to v))
            tx.update(post, mapOf("up" to up, "down" to down, "score" to up - down))
        }.await()
    }

    suspend fun comments(postId: String): List<Comment> =
        db.collection("posts").document(postId).collection("comments").orderBy("ts", Query.Direction.ASCENDING).limit(200).get().await().documents.map { d ->
            Comment(d.id, d.getString("uid") ?: "", d.getString("name") ?: "", d.getString("text") ?: "", d.getLong("ts") ?: 0)
        }

    suspend fun addComment(postId: String, text: String, p: Profile) {
        val u = uid ?: return
        val post = db.collection("posts").document(postId)
        val c = post.collection("comments").document()
        db.runTransaction { tx ->
            val snap = tx.get(post)
            if (!snap.exists()) return@runTransaction
            tx.set(c, mapOf("uid" to u, "name" to p.name, "text" to text.take(300), "ts" to System.currentTimeMillis()))
            tx.update(post, "comments", (snap.getLong("comments") ?: 0) + 1)
        }.await()
    }

    suspend fun deleteComment(postId: String, commentId: String) {
        val post = db.collection("posts").document(postId)
        val c = post.collection("comments").document(commentId)
        db.runTransaction { tx ->
            val snap = tx.get(post)
            tx.delete(c)
            if (snap.exists()) tx.update(post, "comments", maxOf(0L, (snap.getLong("comments") ?: 0) - 1))
        }.await()
    }

    suspend fun following(): Set<String> {
        val u = uid ?: return emptySet()
        return db.collection("users").document(u).collection("following").get().await().documents.map { it.id }.toSet()
    }

    suspend fun follow(other: String) {
        val u = uid ?: return
        db.collection("users").document(u).collection("following").document(other).set(mapOf("since" to System.currentTimeMillis())).await()
    }

    suspend fun unfollow(other: String) {
        val u = uid ?: return
        db.collection("users").document(u).collection("following").document(other).delete().await()
    }

    /** Users with the same occupation, for suggestions. */
    suspend fun similar(occupation: String): List<Person> {
        if (occupation.isBlank()) return emptyList()
        return db.collection("users").whereEqualTo("occupation", occupation).limit(20).get().await().documents
            .map(::person).filter { it.uid != uid }.sortedByDescending { it.aura }.take(10)
    }

    /** People you follow plus you, by aura. */
    suspend fun boardOf(ids: Set<String>): List<Person> =
        (ids + listOfNotNull(uid)).take(30).mapNotNull { id ->
            runCatching { db.collection("users").document(id).get().await().takeIf { it.exists() }?.let(::person) }.getOrNull()
        }.sortedByDescending { it.aura }

    /** 1-based rank by aura, or 0 when it cannot be worked out. */
    suspend fun rankOf(aura: Int): Int = runCatching {
        db.collection("users").whereGreaterThan("aura", aura).count().get(com.google.firebase.firestore.AggregateSource.SERVER).await().count.toInt() + 1
    }.getOrDefault(0)

    private fun post(d: com.google.firebase.firestore.DocumentSnapshot, mine: Int = 0) = Post(
        d.id, d.getString("uid") ?: "", d.getString("name") ?: "", d.getString("occupation") ?: "",
        d.getString("type") ?: "done", d.getString("topic") ?: "General", d.getString("title") ?: "", d.getString("body") ?: "",
        (d.getLong("points") ?: 0).toInt(), d.getLong("ts") ?: 0, (d.getLong("up") ?: 0).toInt(), (d.getLong("down") ?: 0).toInt(),
        (d.getLong("score") ?: 0).toInt(), (d.getLong("comments") ?: 0).toInt(), mine, d.getString("helped") ?: "",
    )

    /** The latest 100 posts, newest first, each with this user's own vote. Filtering and ranking happen on the client. */
    suspend fun feed(): List<Post> = coroutineScope {
        val me = uid
        db.collection("posts").orderBy("ts", Query.Direction.DESCENDING).limit(100).get().await().documents.map { d ->
            async {
                val mine = if (me == null) 0 else runCatching { d.reference.collection("votes").document(me).get().await().getLong("v")?.toInt() ?: 0 }.getOrDefault(0)
                post(d, mine)
            }
        }.awaitAll()
    }

    /** This user's own posts, for the aura earned from upvotes. */
    suspend fun myPosts(): List<Post> {
        val u = uid ?: return emptyList()
        return db.collection("posts").whereEqualTo("uid", u).limit(EarnRules.POSTS_SCAN.toLong()).get().await().documents.map { post(it) }
    }

    suspend fun gifts(): List<Gift> {
        val u = uid ?: return emptyList()
        return db.collection("users").document(u).collection("gifts").get().await().documents.map { d ->
            Gift(d.id, d.getString("from") ?: "", d.getString("fromName") ?: "", (d.getLong("points") ?: 0).toInt(), d.getString("reason") ?: "",
                d.getString("postId") ?: "", d.getString("title") ?: "", d.getLong("ts") ?: 0)
        }
    }

    /** Aura for another user. The id is fixed per giver and post, so it cannot be sent twice. */
    suspend fun sendGift(to: String, id: String, points: Int, reason: String, postId: String, title: String, fromName: String) {
        val u = uid ?: return
        db.collection("users").document(to).collection("gifts").document(id).set(
            mapOf("from" to u, "fromName" to fromName, "points" to points, "reason" to reason, "postId" to postId, "title" to title.take(80), "ts" to System.currentTimeMillis())
        ).await()
    }

    suspend fun deleteGift(to: String, id: String) {
        db.collection("users").document(to).collection("gifts").document(id).delete().await()
    }

    suspend fun setHelped(postId: String, commentId: String) {
        db.collection("posts").document(postId).update("helped", commentId).await()
    }

    suspend fun board(): List<Person> =
        db.collection("users").orderBy("aura", Query.Direction.DESCENDING).limit(20).get().await().documents.map(::person)

    suspend fun search(q: String): List<Person> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return emptyList()
        return db.collection("users").orderBy("nameLower").startAt(s).endAt(s + "").limit(20).get().await().documents
            .map(::person).filter { it.uid != uid }
    }

    private fun person(d: com.google.firebase.firestore.DocumentSnapshot) =
        Person(d.id, d.getString("name") ?: "", d.getString("occupation") ?: "", (d.getLong("aura") ?: 0).toInt())
}
