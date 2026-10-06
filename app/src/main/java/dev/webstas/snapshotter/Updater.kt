package dev.webstas.snapshotter

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the GitHub releases of this project and installs a newer APK through the system installer. */
object Updater {
    private const val LATEST_RELEASE = "https://api.github.com/repos/BigWebstas/Progressive-Snapshotter/releases/latest"

    class Release(val version: String, val apkUrl: String)

    /** The newest release carrying an APK, or null if none or the request fails. Blocking. */
    fun fetchLatest(): Release? = runCatching {
        val body = open(LATEST_RELEASE).inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map(assets::getJSONObject)
            .firstOrNull { it.getString("name").endsWith(".apk") }
        apk?.let { Release(json.getString("tag_name").removePrefix("v"), it.getString("browser_download_url")) }
    }.getOrNull()

    /** True when [latest] is a higher dotted version than [current], e.g. "1.10.0" > "1.9.0". */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    /**
     * Streams the APK into an install session and commits it. Android checks that the APK is signed
     * with this app's key, so only our own releases can replace it. Blocking.
     */
    fun install(context: Context, release: Release) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            open(release.apkUrl).inputStream.use { input ->
                session.openWrite("update.apk", 0, -1).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val result = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("Accept", "application/vnd.github+json")
        connectTimeout = 10_000
        readTimeout = 30_000
    }
}
