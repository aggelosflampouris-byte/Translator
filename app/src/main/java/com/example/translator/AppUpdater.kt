package com.example.translator

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class AppUpdater(private val context: Context) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val repoUrl = "https://api.github.com/repos/aggelosflampouris-byte/Translator/releases/latest"

    suspend fun checkForUpdates() {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(repoUrl).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val release = gson.fromJson(body, GitHubRelease::class.java)
                        val latestVersion = release.tag_name
                        val currentVersion = "v${BuildConfig.VERSION_NAME}"
                        
                        if (latestVersion != currentVersion && release.assets.isNotEmpty()) {
                            val apkUrl = release.assets[0].browser_download_url
                            withContext(Dispatchers.Main) {
                                downloadAndInstallApk(apkUrl, latestVersion)
                            }
                        } else {
                            Log.d("AppUpdater", "App is up to date.")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AppUpdater", "Failed to check for updates", e)
            }
        }
    }

    private fun downloadAndInstallApk(url: String, version: String) {
        Toast.makeText(context, "Downloading update $version...", Toast.LENGTH_SHORT).show()
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Translator-$version.apk")
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Translator Update")
            .setDescription("Downloading latest version")
            .setDestinationUri(Uri.fromFile(destination))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(destination)
                    context.unregisterReceiver(this)
                }
            }
        }
        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
    }

    private fun installApk(file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppUpdater", "Failed to install APK", e)
            Toast.makeText(context, "Failed to start installation", Toast.LENGTH_SHORT).show()
        }
    }

    data class GitHubRelease(val tag_name: String, val assets: List<Asset>)
    data class Asset(val browser_download_url: String)
}
