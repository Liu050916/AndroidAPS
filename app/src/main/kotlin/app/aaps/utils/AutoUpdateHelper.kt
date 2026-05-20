package app.aaps.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.L
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL

class AutoUpdateHelper(
    private val context: Context,
    private val uiInteraction: UiInteraction,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy
) {

    private val disposable = CompositeDisposable()
    private val UPDATE_CHECK_URL = "https://raw.githubusercontent.com/Liu050916/AndroidAPS/master/version.json"
    private val APK_DOWNLOAD_URL = "https://github.com/Liu050916/AndroidAPS/releases/latest/download/app-full-release.apk"

    fun checkForUpdates() {
        disposable.add(
            io.reactivex.rxjava3.core.Observable.fromCallable {
                try {
                    val url = URL(UPDATE_CHECK_URL)
                    val reader = BufferedReader(InputStreamReader(url.openStream()))
                    val json = reader.readText()
                    val obj = JSONObject(json)
                    val latestVersion = obj.getString("version")
                    val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    Pair(currentVersion, latestVersion)
                } catch (e: Exception) {
                    L.e(fabricPrivacy, "AutoUpdate: Failed to check updates", e)
                    null
                }
            }
                .subscribeOn(aapsSchedulers.io)
                .observeOn(aapsSchedulers.main)
                .subscribe({ result ->
                    result?.let { (current, latest) ->
                        if (compareVersions(current, latest) < 0) {
                            showUpdateDialog(latest)
                        }
                    }
                }, { error ->
                    L.e(fabricPrivacy, "AutoUpdate: Error checking updates", error)
                })
        )
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".")
        val parts2 = v2.split(".")
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = if (i < parts1.size) parts1[i].toIntOrNull() ?: 0 else 0
            val p2 = if (i < parts2.size) parts2[i].toIntOrNull() ?: 0 else 0
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    private fun showUpdateDialog(latestVersion: String) {
        AlertDialog.Builder(context)
            .setTitle("发现新版本")
            .setMessage("检测到新版本 v$latestVersion，是否立即下载更新？")
            .setPositiveButton("下载") { _, _ ->
                downloadApk()
            }
            .setNegativeButton("稍后") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun downloadApk() {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(APK_DOWNLOAD_URL))
            .setTitle("AndroidAPS 更新")
            .setDescription("正在下载新版本...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "app-full-release.apk")

        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val uri = downloadManager.getUriForDownloadedFile(id)
                    if (uri != null) {
                        openApkInstaller(uri)
                    }
                    context.unregisterReceiver(this)
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun openApkInstaller(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun dispose() {
        disposable.dispose()
    }
}