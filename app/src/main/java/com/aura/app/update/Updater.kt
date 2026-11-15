package com.aura.app.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.aura.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class Update(val version: String, val notes: List<String>, val apkUrl: String, val size: Long, val sha256: String?)

sealed interface CheckResult {
    data object UpToDate : CheckResult
    data class Available(val update: Update) : CheckResult
    data class Failed(val reason: String) : CheckResult
}

object Updater {
    private const val API = "https://api.github.com/repos/lighty7/auros/releases/latest"
    private const val SNOOZE_MS = 3L * 24 * 3600 * 1000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("aura_update", Context.MODE_PRIVATE)

    private fun nums(v: String): List<Int> =
        (v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)

    fun isNewer(remote: String, local: String = BuildConfig.VERSION_NAME): Boolean {
        val a = nums(remote); val b = nums(local)
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    suspend fun check(ctx: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Aura-Android")
            try {
                when (val code = c.responseCode) {
                    404 -> return@withContext CheckResult.UpToDate
                    200 -> {}
                    else -> return@withContext CheckResult.Failed("HTTP $code")
                }
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = j.optString("tag_name").removePrefix("v")
                if (tag.isBlank() || !isNewer(tag)) return@withContext CheckResult.UpToDate
                val notes = j.optString("body").lines().map { it.trim() }
                    .filter { it.startsWith("- ") || it.startsWith("* ") }.map { it.drop(1).trim().trimStart('-', '*', ' ') }.filter { it.isNotBlank() }.take(8)
                val assets = j.optJSONArray("assets")
                var asset: JSONObject? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { asset = a; break }
                }
                if (asset == null) return@withContext CheckResult.UpToDate
                val sha = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                CheckResult.Available(Update(tag, notes, asset.getString("browser_download_url"), asset.optLong("size"), sha))
            } finally { c.disconnect() }
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun snooze(ctx: Context, version: String) {
        prefs(ctx).edit().putString("skip_version", version).putLong("skip_at", System.currentTimeMillis()).apply()
    }

    fun isSnoozed(ctx: Context, version: String): Boolean {
        val p = prefs(ctx)
        return p.getString("skip_version", null) == version && System.currentTimeMillis() - p.getLong("skip_at", 0) < SNOOZE_MS
    }

    fun notifiedVersion(ctx: Context): String? = prefs(ctx).getString("notified", null)
    fun setNotified(ctx: Context, version: String) { prefs(ctx).edit().putString("notified", version).apply() }

    /** Downloads the apk, reporting 0..1 progress. Throws on failure or checksum mismatch. */
    suspend fun download(ctx: Context, u: Update, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("No storage")
        val file = File(dir, "Aura-${u.version}.apk")
        file.delete()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(u.apkUrl)).setTitle("Aura ${u.version}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(file))
        )
        while (true) {
            var done = false
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) error("Download cancelled")
                when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> done = true
                    DownloadManager.STATUS_FAILED -> error("Download failed")
                    else -> {
                        val got = maxOf(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), if (file.exists()) file.length() else 0L)
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: u.size
                        if (total > 0) onProgress((got.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (done) { onProgress(1f); break }
            delay(400)
        }
        verify(file, u)
    }

    private fun verify(file: File, u: Update): File {
        val want = u.sha256 ?: return file
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (!got.equals(want, ignoreCase = true)) { file.delete(); error("Download is corrupt (checksum mismatch)") }
        return file
    }

    /** Returns false when the user must first allow installs from Aura (settings were opened). */
    fun install(ctx: Context, file: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
