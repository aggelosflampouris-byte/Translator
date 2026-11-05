package com.example.translator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Gestiona la verificación de versiones en GitHub y la redirección segura al navegador.
 *
 * Por qué: Anteriormente la app intentaba descargar e instalar el APK internamente mediante
 * DownloadManager y el permiso REQUEST_INSTALL_PACKAGES. Esto activaba el bloqueo de seguridad
 * de Google Play Protect por comportamiento de 'dropper' (instalador de paquetes).
 * Al delegar la descarga al navegador del sistema mediante un Intent ACTION_VIEW, eliminamos
 * la necesidad de REQUEST_INSTALL_PACKAGES y FileProvider, cumpliendo con las políticas de Play Protect.
 */
class AppUpdater(private val appContext: Context) {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val repoUrl =
        "https://api.github.com/repos/aggelosflampouris-byte/Translator/releases/latest"

    /**
     * Consulta la API de GitHub en busca de una nueva versión disponible.
     * Retorna [UpdateInfo] si existe una versión más reciente, o `null` si la app está al día.
     */
    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(repoUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w("AppUpdater", "GitHub API returned ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val release = gson.fromJson(body, GitHubRelease::class.java)

            val latestVersion = release.tag_name
            val currentVersion = "v${BuildConfig.VERSION_NAME}"

            if (latestVersion == currentVersion) {
                Log.d("AppUpdater", "App is up to date ($currentVersion).")
                return@withContext null
            }

            UpdateInfo(
                version = latestVersion,
                htmlUrl = release.html_url ?: "https://github.com/aggelosflampouris-byte/Translator/releases"
            )
        } catch (e: Exception) {
            Log.e("AppUpdater", "Failed to check for updates", e)
            null
        }
    }

    /**
     * Abre la página de la versión en el navegador para que el usuario descargue la actualización de forma segura.
     * Por qué: El navegador gestiona la descarga y la instalación como instalador verificado del sistema.
     */
    fun openUpdatePage(context: Context, updateInfo: UpdateInfo) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.htmlUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppUpdater", "Error launching update browser intent", e)
        }
    }

    data class UpdateInfo(
        val version: String,
        val htmlUrl: String
    )

    data class GitHubRelease(
        val tag_name: String,
        val html_url: String?
    )
}
