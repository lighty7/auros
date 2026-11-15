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

/** An achievement a user chose to share: goal name, target and points only. Raw health data never leaves the phone. */
data class Post(
    val id: String, val uid: String, val name: String, val occupation: String,
    val title: String, val metric: String, val target: Double, val days: Int, val points: Int, val ts: Long,
    /** "goal" or "activity" */
    val kind: String = "goal", val sport: String = "", val distM: Double = 0.0, val elapsedS: Int = 0, val hrAvg: Int = 0,
    val detail: String = "",
    /** props received; -1 when the count could not be read (hidden in the UI) */
    val props: Int = -1, val propped: Boolean = false,
)

data class Person(val uid: String, val name: String, val occupation: String, val aura: Int)

/**
 * Firebase Auth + Firestore. Layout:
 *   users/{uid}                 name, nameLower, occupation, aura          (readable by any signed-in user)
 *   users/{uid}/goals/{id}      the user's goals                           (private)
 *   users/{uid}/following/{id}  who the user follows                       (private)
 *   users/{uid}/posts/{id}      shared achievements                        (readable by any signed-in user)
 * Posts live under the user so the feed needs no composite index.
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

    /** Idempotent: the post id is the goal id, so posting twice just rewrites the same post. */
    suspend fun post(g: Goal, p: Profile) {
        val u = uid ?: return
        db.collection("users").document(u).collection("posts").document(g.id).set(
            mapOf(
                "name" to p.name, "occupation" to p.occupation, "title" to g.title, "metric" to g.metric.name,
                "target" to g.target, "days" to g.days, "points" to g.awarded,
                "ts" to (if (g.completedEpochDay >= 0) g.completedEpochDay * 86_400_000L + 43_200_000L else System.currentTimeMillis()),
            )
        ).await()
    }

    /** An explicit post of a recorded activity: sport, distance, time and average heart rate. The route itself is never shared. */
    suspend fun postActivity(r: Rec, p: Profile) {
        val u = uid ?: return
        db.collection("users").document(u).collection("posts").document("act_${r.id}").set(
            mapOf(
                "kind" to "activity", "name" to p.name, "occupation" to p.occupation, "title" to r.title, "sport" to r.sport,
                "distM" to r.distM, "elapsedS" to r.elapsedS, "hrAvg" to r.merged.hrAvg, "points" to 0, "ts" to r.startMs,
            )
        ).await()
    }

    suspend fun unpostActivity(id: Long) {
        val u = uid ?: return
        db.collection("users").document(u).collection("posts").document("act_$id").delete().await()
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

    /** A milestone from the ledger or a new level. Only title, detail and points are shared. Idempotent per [id]. */
    suspend fun postEntry(id: String, kind: String, title: String, detail: String, points: Int, p: Profile) {
        val u = uid ?: return
        db.collection("users").document(u).collection("posts").document(id).set(
            mapOf("kind" to kind, "name" to p.name, "occupation" to p.occupation, "title" to title, "detail" to detail, "points" to points, "ts" to System.currentTimeMillis())
        ).await()
    }

    /** Give or take back props on a post. */
    suspend fun setProps(owner: String, postId: String, give: Boolean) {
        val u = uid ?: return
        val d = db.collection("users").document(owner).collection("posts").document(postId).collection("props").document(u)
        if (give) d.set(mapOf("ts" to System.currentTimeMillis())).await() else d.delete().await()
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

    /** Latest posts from the people you follow and yourself, newest first. */
    suspend fun feed(following: Set<String>): List<Post> {
        val ids = (following + listOfNotNull(uid)).take(30)
        return ids.flatMap { id ->
            runCatching {
                db.collection("users").document(id).collection("posts").orderBy("ts", Query.Direction.DESCENDING).limit(10).get().await().documents.map { d ->
                    Post(
                        d.id, id, d.getString("name") ?: "", d.getString("occupation") ?: "", d.getString("title") ?: "",
                        d.getString("metric") ?: "", d.getDouble("target") ?: 0.0, (d.getLong("days") ?: 0).toInt(),
                        (d.getLong("points") ?: 0).toInt(), d.getLong("ts") ?: 0,
                        d.getString("kind") ?: "goal", d.getString("sport") ?: "", d.getDouble("distM") ?: 0.0,
                        (d.getLong("elapsedS") ?: 0).toInt(), (d.getLong("hrAvg") ?: 0).toInt(), d.getString("detail") ?: "",
                    )
                }
            }.getOrDefault(emptyList())
        }.sortedByDescending { it.ts }.take(30).let { withProps(it) }
    }

    /** Props counts for each post; if Firestore refuses, the counts stay at -1 and the UI hides them. */
    private suspend fun withProps(posts: List<Post>): List<Post> = coroutineScope {
        val me = uid
        posts.map { p ->
            async {
                runCatching {
                    val docs = db.collection("users").document(p.uid).collection("posts").document(p.id).collection("props").get().await().documents
                    p.copy(props = docs.size, propped = docs.any { it.id == me })
                }.getOrDefault(p)
            }
        }.awaitAll()
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
