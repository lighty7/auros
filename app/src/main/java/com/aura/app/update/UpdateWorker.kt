package com.aura.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aura.app.MainActivity
import com.aura.app.R

class UpdateWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val r = Updater.check(ctx)
        if (r is CheckResult.Available) {
            val v = r.update.version
            val nm = NotificationManagerCompat.from(ctx)
            if (!Updater.isSnoozed(ctx, v) && Updater.notifiedVersion(ctx) != v && nm.areNotificationsEnabled()) {
                ctx.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val n = NotificationCompat.Builder(ctx, "updates").setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Aura $v is available").setContentText("Tap to update")
                    .setAutoCancel(true).setContentIntent(open).build()
                try { nm.notify(4107, n); Updater.setNotified(ctx, v) } catch (_: SecurityException) {}
            }
        }
        return Result.success()
    }
}
