package com.aura.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.Activity
import com.aura.app.data.ActivityStore
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
    val board: List<Person> = emptyList(),
    val results: List<Person> = emptyList(),
    val following: Set<String> = emptySet(),
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

    init { refresh(); if (cloud.uid != null) loadSocial() }

    private fun err(e: Throwable) { _ui.value = _ui.value.copy(cloudBusy = false, cloudError = e.message ?: e.javaClass.simpleName) }

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
        _ui.value = _ui.value.copy(signedIn = false, email = "", myUid = "", feed = emptyList(), board = emptyList(), results = emptyList(), following = emptySet())
    }

    /** Push profile, aura and completed goals (as shareable posts) to the account. */
    private suspend fun syncUp() {
        runCatching {
            val goals = store.goals()
            cloud.pushProfile(store.profile(), store.earnState().total)
            goals.filter { it.awarded > 0 }.forEach { cloud.post(it, store.profile()) }
        }.onFailure(::err)
    }

    /** Post (or take back) a recorded activity. [done] gets true on success. */
    fun postActivity(r: com.aura.app.data.Rec, post: Boolean, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { if (post) cloud.postActivity(r, store.profile()) else cloud.unpostActivity(r.id) }
                .onFailure(::err).isSuccess
            if (ok) { ActivityStore(getApplication()).setPosted(r.id, post); loadSocial() }
            done(ok)
        }
    }

    fun loadSocial() {
        viewModelScope.launch {
            try {
                val f = cloud.following()
                _ui.value = _ui.value.copy(following = f, feed = cloud.feed(f), board = cloud.board())
            } catch (e: Throwable) { err(e) }
        }
    }

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
            _ui.value = _ui.value.copy(
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
        }
    }
}
