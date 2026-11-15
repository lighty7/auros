package com.aura.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.Activity
import com.aura.app.data.ActivityStore
import com.aura.app.data.Aura
import com.aura.app.data.Cloud
import com.aura.app.data.Earn
import com.aura.app.data.EarnRules
import com.aura.app.data.Earned
import com.aura.app.data.TodayItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.aura.app.data.Goal
import com.aura.app.data.Person
import com.aura.app.data.Post
import com.aura.app.data.Comment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.firebase.firestore.FirebaseFirestoreException
import com.aura.app.data.GoalProgress
import com.aura.app.data.Metric
import com.aura.app.data.Profile
import com.aura.app.data.Status
import com.aura.app.data.Store
import com.aura.app.health.Day
import com.aura.app.health.DayInsight
import com.aura.app.health.HealthSource
import com.aura.app.update.CheckResult
import com.aura.app.update.Update
import com.aura.app.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

data class UiState(
    val profile: Profile = Profile(),
    val goals: List<GoalProgress> = emptyList(),
    val aura: Int = 0,
    val ledger: List<Earned> = emptyList(),
    val today: List<TodayItem> = emptyList(),
    val streaks: Map<String, Int> = emptyMap(),
    val healthAvailable: Boolean = true,
    val healthOk: Boolean = false,
    val usageOk: Boolean = false,
    val loading: Boolean = true,
    /** aura earned (goals and daily earnings) during the last refresh, to celebrate once */
    val justEarned: Int = 0,
    val signedIn: Boolean = false,
    val email: String = "",
    val myUid: String = "",
    val feed: List<Post> = emptyList(),
    /** comments of the open thread, oldest first */
    val comments: List<Comment> = emptyList(),
    val commentsFor: String = "",
    /** the last 28 days for the Trends page; days without data are absent or hold nulls */
    val trends: Map<LocalDate, Day> = emptyMap(),
    val trendsBusy: Boolean = false,
    /** this user's own posts, for the aura earned from upvotes */
    val myPosts: List<Post> = emptyList(),
    val board: List<Person> = emptyList(),
    val results: List<Person> = emptyList(),
    val following: Set<String> = emptySet(),
    val boardFollowing: List<Person> = emptyList(),
    val similar: List<Person> = emptyList(),
    /** 0 = unknown */
    val myRank: Int = 0,
    /** a level just reached, shown once as a moment; 0 = none */
    val levelUp: Int = 0,
    val cloudBusy: Boolean = false,
    val cloudError: String = "",
    /** heart rate merged with app use for one day; null until first read */
    val insight: DayInsight? = null,
    /** which day the insight shows: 0 = today, 1 = yesterday... */
    val insightBack: Int = 0,
    val insightBusy: Boolean = false,
    val update: Update? = null,
    /** null = not downloading */
    val updateProgress: Float? = null,
    val updateMsg: String = "",
)

class AuraViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    val source = HealthSource(app)
    private val cloud = Cloud(app)
    private val _ui = MutableStateFlow(UiState(profile = store.profile(), signedIn = cloud.uid != null, email = cloud.email ?: "", myUid = cloud.uid ?: ""))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private fun err(e: Throwable) {
        val denied = e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
        _ui.value = _ui.value.copy(cloudBusy = false, cloudError = if (denied) "Circle needs the updated Firestore rules." else e.message ?: e.javaClass.simpleName)
    }

    private fun topicOfKind(kind: String): String {
        val k = if (kind.startsWith("STREAK_")) kind.split("_").getOrElse(1) { "" } else kind
        return when (k) { "REST" -> "Sleep"; "STEPS" -> "Steps"; "MOVE" -> "Training"; "FOCUS" -> "Screen time"; else -> "General" }
    }

    private fun topicOfMetric(m: Metric) = when (m) {
        Metric.SLEEP -> "Sleep"; Metric.STEPS, Metric.DISTANCE -> "Steps"; Metric.EXERCISE, Metric.CALORIES -> "Training"; Metric.SCREEN -> "Screen time"
    }

    fun saveProfile(p: Profile) {
        store.saveProfile(p)
        _ui.value = _ui.value.copy(profile = p)
        if (cloud.uid != null) viewModelScope.launch { runCatching { cloud.pushProfile(p, _ui.value.aura) }.onFailure(::err) }
    }

    fun signIn(activity: Activity) {
        _ui.value = _ui.value.copy(cloudBusy = true, cloudError = "")
        viewModelScope.launch {
            try {
                cloud.signIn(activity)
                // a fresh install restores goals from the account; otherwise this phone's goals go up
                if (store.goals().isEmpty()) store.saveGoals(cloud.pullGoals()) else cloud.pushGoals(store.goals())
                _ui.value = _ui.value.copy(signedIn = true, email = cloud.email ?: "", myUid = cloud.uid ?: "")
                refresh()
                syncUp()
                loadSocial()
            } catch (e: Throwable) { err(e) }
            _ui.value = _ui.value.copy(cloudBusy = false)
        }
    }

    fun signOut() {
        cloud.signOut()
        _ui.value = _ui.value.copy(signedIn = false, email = "", myUid = "", feed = emptyList(), comments = emptyList(), board = emptyList(), results = emptyList(), following = emptySet(), boardFollowing = emptyList(), similar = emptyList(), myRank = 0)
    }

    /** Push profile, aura and completed goals (as shareable posts) to the account. */
    private suspend fun syncUp() {
        runCatching {
            val goals = store.goals()
            cloud.pushProfile(store.profile(), store.earnState().total)
            goals.filter { it.awarded > 0 }.forEach {
                val ts = if (it.completedEpochDay >= 0) it.completedEpochDay * 86_400_000L + 43_200_000L else System.currentTimeMillis()
                cloud.postDone(it.id, topicOfMetric(it.metric), it.title, com.aura.app.ui.describe(it.metric.name, it.days, it.target), it.awarded, ts, store.profile())
            }
        }.onFailure(::err)
    }

    /** Post (or take back) a recorded activity. [done] gets true on success. */
    fun postActivity(r: com.aura.app.data.Rec, post: Boolean, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { if (post) cloud.postDone("act_${r.id}", "Training", r.title,
                "${com.aura.app.ui.km(r.distM)} km, ${com.aura.app.ui.clock(r.elapsedS)}, ${com.aura.app.ui.pace(r.sport, r.distM, r.elapsedS)}", 0, r.startMs, store.profile()) else cloud.unpostActivity(r.id) }
                .onFailure(::err).isSuccess
            if (ok) { ActivityStore(getApplication()).setPosted(r.id, post); loadSocial() }
            done(ok)
        }
    }

    fun loadSocial() {
        viewModelScope.launch {
            try {
                val f = cloud.following()
                val board = cloud.board()
                _ui.value = _ui.value.copy(following = f, feed = cloud.feed(), board = board)
                val me = cloud.uid
                val aura = _ui.value.aura
                val rank = board.indexOfFirst { it.uid == me }.let { if (it >= 0) it + 1 else cloud.rankOf(aura) }
                val mine = _ui.value.profile.occupation
                val rest = runCatching { Triple(cloud.boardOf(f), cloud.similar(mine), rank) }.getOrNull()
                if (rest != null) _ui.value = _ui.value.copy(boardFollowing = rest.first, similar = rest.second, myRank = rest.third)
            } catch (e: Throwable) { err(e) }
        }
    }

    private fun patchPost(id: String, f: (Post) -> Post) = _ui.update { st -> st.copy(feed = st.feed.map { if (it.id == id) f(it) else it }) }

    /** Vote on a post: tapping the same arrow again removes the vote. Counts update at once and roll back if Firestore refuses. */
    fun vote(p: Post, dir: Int) {
        val v = if (p.myVote == dir) 0 else dir
        val up = p.up - (if (p.myVote == 1) 1 else 0) + (if (v == 1) 1 else 0)
        val down = p.down - (if (p.myVote == -1) 1 else 0) + (if (v == -1) 1 else 0)
        patchPost(p.id) { it.copy(myVote = v, up = up, down = down, score = up - down) }
        viewModelScope.launch { runCatching { cloud.vote(p.id, v) }.onFailure { patchPost(p.id) { _ -> p }; err(it) } }
    }

    fun postAsk(topic: String, title: String, body: String, done: () -> Unit) {
        viewModelScope.launch {
            runCatching { cloud.postAsk(topic, title.trim(), body.trim(), store.profile()) }.onSuccess { done(); loadSocial() }.onFailure(::err)
        }
    }

    fun deletePost(id: String, done: () -> Unit) {
        viewModelScope.launch {
            runCatching { cloud.deletePost(id) }.onSuccess { _ui.update { st -> st.copy(feed = st.feed.filterNot { it.id == id }) }; done() }.onFailure(::err)
        }
    }

    fun openThread(id: String) {
        _ui.update { it.copy(comments = emptyList(), commentsFor = id) }
        viewModelScope.launch { runCatching { cloud.comments(id) }.onSuccess { c -> _ui.update { it.copy(comments = c) } }.onFailure(::err) }
    }

    fun addComment(postId: String, text: String, done: () -> Unit) {
        viewModelScope.launch {
            runCatching { cloud.addComment(postId, text.trim(), store.profile()) }.onSuccess { done(); openThread(postId); refreshCount(postId) }.onFailure(::err)
        }
    }

    fun deleteComment(postId: String, id: String) {
        viewModelScope.launch {
            runCatching { cloud.deleteComment(postId, id) }.onSuccess { openThread(postId); refreshCount(postId) }.onFailure(::err)
        }
    }

    private suspend fun refreshCount(postId: String) {
        runCatching { cloud.comments(postId).size }.onSuccess { n -> patchPost(postId) { it.copy(comments = n) } }
    }

    fun loadTrends() {
        _ui.update { it.copy(trendsBusy = true) }
        viewModelScope.launch {
            val today = LocalDate.now()
            val d = withContext(Dispatchers.IO) { runCatching { source.days(today.minusDays(27), today, source.hasHealthAccess(), source.hasUsageAccess()) }.getOrDefault(emptyMap()) }
            _ui.update { it.copy(trends = d, trendsBusy = false) }
        }
    }

    /** Post a ledger milestone (streak or full day). */
    fun postEntry(e: Earned) {
        viewModelScope.launch {
            runCatching { cloud.postDone("ent_${e.epochDay}_${e.kind}", topicOfKind(e.kind), e.title, e.detail, e.points, System.currentTimeMillis(), store.profile()) }.onFailure(::err)
            loadSocial()
        }
    }

    fun postLevel(level: Int, aura: Int) {
        viewModelScope.launch {
            runCatching { cloud.postDone("lvl_$level", "General", "Reached level $level", Aura.levelName(level), aura, System.currentTimeMillis(), store.profile()) }.onFailure(::err)
            loadSocial()
        }
    }

    fun levelSeen() { _ui.update { it.copy(levelUp = 0) } }

    fun search(q: String) {
        viewModelScope.launch { runCatching { cloud.search(q) }.onSuccess { _ui.value = _ui.value.copy(results = it, cloudError = "") }.onFailure(::err) }
    }

    fun toggleFollow(uid: String) {
        viewModelScope.launch {
            runCatching { if (uid in _ui.value.following) cloud.unfollow(uid) else cloud.follow(uid) }.onFailure(::err)
            loadSocial()
        }
    }

    fun addGoal(title: String, metric: Metric, target: Double, days: Int) {
        store.saveGoals(store.goals() + Goal(UUID.randomUUID().toString(), title.trim(), metric, target, days, LocalDate.now().toEpochDay()))
        pushGoalsBg()
        refresh()
    }

    fun deleteGoal(id: String) {
        store.saveGoals(store.goals().filterNot { it.id == id })
        pushGoalsBg()
        refresh()
    }

    private fun pushGoalsBg() {
        if (cloud.uid != null) viewModelScope.launch { runCatching { cloud.pushGoals(store.goals()) }.onFailure(::err) }
    }

    fun celebrated() { _ui.value = _ui.value.copy(justEarned = 0) }

    private var insightJob: Job? = null

    /** Read one day of heart rate and app use. [back] is how many days ago. */
    fun loadInsight(back: Int = _ui.value.insightBack) {
        insightJob?.cancel()
        _ui.update { it.copy(insightBack = back, insightBusy = true) }
        insightJob = viewModelScope.launch {
            val d = source.dayInsight(LocalDate.now().minusDays(back.toLong()), source.hasHealthAccess(), source.hasUsageAccess())
            _ui.update { it.copy(insight = d, insightBusy = false) }
        }
    }

    /** Re-read health data and re-evaluate every goal. Call when the app returns to the foreground. */
    private var lastUpdateCheck = 0L

    fun checkForUpdates(manual: Boolean) {
        if (_ui.value.updateProgress != null) return
        val ctx = getApplication<Application>()
        if (manual) _ui.update { it.copy(updateMsg = "Checking...") }
        lastUpdateCheck = System.currentTimeMillis()
        viewModelScope.launch {
            when (val r = Updater.check(ctx)) {
                is CheckResult.Available ->
                    if (manual || !Updater.isSnoozed(ctx, r.update.version)) _ui.update { it.copy(update = r.update, updateMsg = "") }
                    else _ui.update { it.copy(updateMsg = "") }
                CheckResult.UpToDate -> _ui.update { it.copy(updateMsg = if (manual) "You're on the latest version" else "") }
                is CheckResult.Failed -> _ui.update { it.copy(updateMsg = if (manual) "Couldn't check" else "") }
            }
        }
    }

    fun startUpdate() {
        val u = _ui.value.update ?: return
        if (_ui.value.updateProgress != null) return
        val ctx = getApplication<Application>()
        _ui.update { it.copy(updateProgress = 0f, updateMsg = "") }
        viewModelScope.launch {
            try {
                val f = Updater.download(ctx, u) { p -> _ui.update { it.copy(updateProgress = p) } }
                if (Updater.install(ctx, f)) _ui.update { it.copy(updateProgress = null, update = null) }
                else _ui.update { it.copy(updateProgress = null, updateMsg = "Allow installs from Aura, then come back and tap Update again.") }
            } catch (e: Exception) {
                _ui.update { it.copy(updateProgress = null, updateMsg = e.message ?: "Update failed") }
            }
        }
    }

    fun snoozeUpdate() {
        _ui.value.update?.let { Updater.snooze(getApplication(), it.version) }
        _ui.update { it.copy(update = null, updateMsg = "") }
    }

    private val earnLock = Mutex()

    fun refresh() {
        if (System.currentTimeMillis() - lastUpdateCheck > 6 * 3600 * 1000L) checkForUpdates(false)
        viewModelScope.launch {
            val health = source.hasHealthAccess()
            val usage = source.hasUsageAccess()
            val today = LocalDate.now()
            var goals = store.goals()

            val open = goals.filter { it.awarded == 0 }
            val recorded = ActivityStore(getApplication()).kmPerDay()
            val data: Map<LocalDate, Day> =
                if (open.isEmpty()) emptyMap()
                else source.days(open.minOf { it.start }, today, health, usage).mapValues { (d, day) ->
                    // distance: whichever is larger, the watch's total or what was recorded in the app
                    val rec = recorded[d]
                    if (rec == null) day else day.copy(distKm = maxOf(day.distKm ?: 0.0, rec))
                }

            var earned = 0
            val progress = goals.map { g ->
                if (g.awarded > 0) return@map GoalProgress(g, g.days, null, Status.COMPLETED, emptyList())
                val last = minOf(today, g.end)
                val results = generateSequence(g.start) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { d ->
                    val v = data[d]?.value(g.metric)
                    // a limit can only be judged once the day is over; otherwise a quiet morning would "pass"
                    v != null && g.metric.met(v, g.target) && !(g.metric.lowerIsBetter && d == today)
                }.toList()
                val met = results.count { it }
                when {
                    met >= g.days -> {
                        earned += g.reward
                        GoalProgress(g.copy(awarded = g.reward, completedEpochDay = today.toEpochDay()), met, data[today]?.value(g.metric), Status.COMPLETED, results)
                    }
                    today.isAfter(g.end) -> GoalProgress(g, met, data[today]?.value(g.metric), Status.EXPIRED, results)
                    else -> GoalProgress(g, met, data[today]?.value(g.metric), Status.ACTIVE, results)
                }
            }
            if (earned > 0) {
                goals = progress.map { it.goal }
                store.saveGoals(goals)
                if (cloud.uid != null) { pushGoalsBg(); syncUp(); loadSocial() }
            }
            val allGoals = progress.map { it.goal }
            val (es, gained, todayDay) = earnLock.withLock {
                var st = store.earnState()
                val t = today.toEpochDay()
                if (st.start <= 0) st = st.copy(start = t, lastEval = t - 1)
                val access = health || usage
                val from = maxOf(st.lastEval + 1, t - EarnRules.MAX_BACK_DAYS, st.start)
                val ed = if (access) source.days(LocalDate.ofEpochDay(from), today, health, usage) else emptyMap()
                val r = Earn.run(st, today, ed, access, allGoals)
                store.saveEarn(r.state)
                Triple(r.state, r.gained, ed[today])
            }
            if (es.total != _ui.value.aura && cloud.uid != null) viewModelScope.launch { runCatching { cloud.pushProfile(store.profile(), es.total) } }
            val prefs = getApplication<Application>().getSharedPreferences("aura_moments", android.content.Context.MODE_PRIVATE)
            val lvlNow = Aura.level(es.total)
            val lvlSeen = prefs.getInt("level", 0)
            if (lvlSeen != lvlNow) prefs.edit().putInt("level", lvlNow).apply()
            _ui.value = _ui.value.copy(
                levelUp = if (lvlSeen in 1 until lvlNow) lvlNow else _ui.value.levelUp,
                goals = progress,
                aura = es.total,
                ledger = Earn.newestFirst(es.ledger),
                today = if (health || usage) Earn.todayItems(es.ledger, today, todayDay) else emptyList(),
                streaks = Earn.streaks(es.ledger, today),
                healthAvailable = source.available,
                healthOk = health,
                usageOk = usage,
                loading = false,
                justEarned = _ui.value.justEarned + earned + gained,
            )
            loadInsight()
            if (cloud.uid != null) syncSocial()
        }
    }

    /** Aura from Circle: upvotes on your posts and gifts sent to you. Idempotent, keyed by post and gift. */
    private suspend fun syncSocial() {
        val mine = runCatching { cloud.myPosts() }.getOrNull()
        val gifts = runCatching { cloud.gifts() }.getOrNull()
        if (mine == null && gifts == null) return
        val entries = ArrayList<Earned>()
        mine?.forEach {
            val day = it.ts / 86_400_000L
            entries.add(Earned(day, "VOTES:${it.id}", it.score.coerceIn(0, EarnRules.VOTE_CAP), "Upvotes", it.title))
        }
        gifts?.forEach {
            val day = it.ts / 86_400_000L
            val title = if (it.reason == "helped") "Helped ${it.fromName}" else "Aura from ${it.fromName}"
            entries.add(Earned(day, "GIFT:${it.id}", it.points, title, it.title))
        }
        val (es, gained) = earnLock.withLock {
            val r = Earn.social(store.earnState(), entries, gifts?.map { "GIFT:${it.id}" }?.toSet())
            store.saveEarn(r.state)
            r.state to r.gained
        }
        if (es.total != _ui.value.aura) runCatching { cloud.pushProfile(store.profile(), es.total) }
        _ui.update { it.copy(aura = es.total, ledger = Earn.newestFirst(es.ledger), myPosts = mine ?: it.myPosts, justEarned = it.justEarned + gained) }
    }

    /** Give 5 aura to the author of a post. Costs the giver the same. */
    fun give(p: Post) {
        val st = _ui.value
        val today = LocalDate.now().toEpochDay()
        if (p.uid == st.myUid || Earn.giveBlock(st.ledger, st.aura, p.id, today) != null) return
        viewModelScope.launch {
            runCatching {
                cloud.sendGift(p.uid, "gave_${p.id}_${st.myUid}", EarnRules.GIVE_COST, "gave", p.id, p.title, store.profile().name)
                earnLock.withLock {
                    val s = store.earnState()
                    store.saveEarn(s.copy(ledger = s.ledger + Earned(today, "GAVE:${p.id}", -EarnRules.GIVE_COST, "Gave aura", p.name)))
                }
            }.onFailure(::err)
            refresh()
        }
    }

    /** Mark one comment on your Ask as helpful (+10 to its author); marking the same one again clears it. */
    fun markHelped(p: Post, c: Comment) {
        viewModelScope.launch {
            runCatching {
                val old = _ui.value.comments.firstOrNull { it.id == p.helped }
                if (old != null) runCatching { cloud.deleteGift(old.uid, "helped_${p.id}") }
                val next = if (p.helped == c.id) "" else c.id
                if (next.isNotEmpty()) cloud.sendGift(c.uid, "helped_${p.id}", EarnRules.HELPED_PTS, "helped", p.id, p.title, store.profile().name)
                cloud.setHelped(p.id, next)
                patchPost(p.id) { it.copy(helped = next) }
            }.onFailure(::err)
        }
    }

    // Kept last on purpose: refresh() launches a coroutine that runs immediately on the main thread and
    // reads properties declared above (earnLock, lastUpdateCheck...). Initialising them after it was a crash.
    init { refresh(); if (cloud.uid != null) loadSocial() }
}
