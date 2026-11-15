package com.aura.app.data

import java.time.LocalDate

/** What a goal is measured by. Every metric is verified from watch/phone data, never self-reported. */
enum class Metric(
    val label: String,
    val unit: String,
    val defaultTarget: Double,
    val step: Double,
    val pointsPerDay: Int,
    /** true = the day counts when the value is at or below the target (screen-time limits). */
    val lowerIsBetter: Boolean = false,
) {
    STEPS("Steps", "steps", 8000.0, 1000.0, 10),
    EXERCISE("Exercise", "min", 30.0, 5.0, 15),
    SLEEP("Sleep", "h", 7.0, 0.5, 12),
    CALORIES("Active calories", "kcal", 300.0, 50.0, 12),
    DISTANCE("Distance", "km", 5.0, 0.5, 15),
    SCREEN("Screen time limit", "h", 3.0, 0.5, 12, lowerIsBetter = true);

    fun met(value: Double, target: Double) = if (lowerIsBetter) value <= target else value >= target

    fun fmt(v: Double): String = if (v >= 100 || v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)
}

data class Goal(
    val id: String,
    val title: String,
    val metric: Metric,
    /** per-day target */
    val target: Double,
    /** number of days the target must be hit */
    val days: Int,
    val startEpochDay: Long,
    /** aura awarded once the goal completes; 0 while still open */
    val awarded: Int = 0,
    val completedEpochDay: Long = -1,
) {
    val start: LocalDate get() = LocalDate.ofEpochDay(startEpochDay)

    /** The goal can be finished within this many days: the needed days plus a grace of half again. */
    val windowDays: Int get() = Math.ceil(days * 1.5).toInt()
    val end: LocalDate get() = start.plusDays(windowDays - 1L)
    val reward: Int get() = days * metric.pointsPerDay + BONUS

    companion object { const val BONUS = 50 }
}

data class Profile(val name: String = "", val occupation: String = "")

enum class Status { ACTIVE, COMPLETED, EXPIRED }

/** A goal together with how far it has got, computed from health data. */
data class GoalProgress(
    val goal: Goal,
    val daysMet: Int,
    val today: Double?,
    val status: Status,
    /** the outcome of each day in the window so far, oldest first */
    val dayResults: List<Boolean>,
)

object Aura {
    fun level(points: Int): Int = Math.floor(Math.sqrt(points / 50.0)).toInt() + 1
    fun levelStart(level: Int): Int = (level - 1) * (level - 1) * 50
    fun levelName(level: Int): String = when {
        level < 2 -> "Spark"
        level < 4 -> "Glow"
        level < 7 -> "Radiant"
        level < 11 -> "Blazing"
        else -> "Legend"
    }
}

/** Starter goals offered after the user picks what they do. */
object Occupations {
    val all = listOf("Student", "Software engineer", "Designer", "Teacher", "Doctor / nurse", "Entrepreneur", "Athlete", "Creator")

    fun suggestions(occupation: String): List<Triple<String, Metric, Double>> = when (occupation) {
        "Student" -> listOf(Triple("Study hard, sleep well", Metric.SLEEP, 7.5), Triple("Less doomscrolling", Metric.SCREEN, 3.0), Triple("Walk between classes", Metric.STEPS, 7000.0))
        "Software engineer" -> listOf(Triple("Get up from the desk", Metric.STEPS, 8000.0), Triple("Sleep before the deploy", Metric.SLEEP, 7.0), Triple("Screen detox", Metric.SCREEN, 4.0))
        "Designer" -> listOf(Triple("Walk for fresh ideas", Metric.STEPS, 8000.0), Triple("Rest your eyes", Metric.SCREEN, 4.0), Triple("Move your body", Metric.EXERCISE, 30.0))
        "Teacher" -> listOf(Triple("Recharge after class", Metric.SLEEP, 7.5), Triple("Daily workout", Metric.EXERCISE, 30.0), Triple("Phone down", Metric.SCREEN, 3.0))
        "Doctor / nurse" -> listOf(Triple("Recover from the shift", Metric.SLEEP, 7.0), Triple("Keep moving", Metric.STEPS, 9000.0), Triple("Switch off", Metric.SCREEN, 3.0))
        "Entrepreneur" -> listOf(Triple("Founder fitness", Metric.EXERCISE, 30.0), Triple("Protect your sleep", Metric.SLEEP, 7.0), Triple("Focus time", Metric.SCREEN, 4.0))
        "Athlete" -> listOf(Triple("Training volume", Metric.EXERCISE, 60.0), Triple("Burn it up", Metric.CALORIES, 600.0), Triple("Recovery sleep", Metric.SLEEP, 8.0))
        "Creator" -> listOf(Triple("Touch grass", Metric.STEPS, 8000.0), Triple("Screen limit", Metric.SCREEN, 5.0), Triple("Regular sleep", Metric.SLEEP, 7.0))
        else -> listOf(Triple("Daily steps", Metric.STEPS, 8000.0), Triple("Good sleep", Metric.SLEEP, 7.0), Triple("Screen limit", Metric.SCREEN, 4.0))
    }
}
