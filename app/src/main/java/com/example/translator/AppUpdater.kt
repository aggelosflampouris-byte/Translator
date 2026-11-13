package com.example.translator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Gestiona la verificación de versiones en GitHub, la descarga directa dentro de la app
 * y el lanzamiento seguro del instalador de paquetes de Android.
 *
 * Por qué: Usa un singleton inicializado con `applicationContext` para reutilizar el mismo
 * `OkHttpClient` (pool de conexiones/hilos) y evitar fugas de memoria de `Activity`.
 */
class AppUpdater private constructor(private val appContext: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val repoUrl =
        "https://api.github.com/repos/$REPO_PATH/releases/latest"

    /**
     * Consulta la API de GitHub en busca de una nueva versión disponible.
     * Retorna [UpdateInfo] si existe una versión más reciente, o `null` si la app está al día.
     */
    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(repoUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w("AppUpdater", "GitHub API returned ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val release = gson.fromJson(body, GitHubRelease::class.java)

            val latestVersion = release.tag_name ?: return@withContext null
            val currentVersion = BuildConfig.VERSION_NAME

            if (!isNewerVersion(latestVersion, currentVersion)) {
                Log.d("AppUpdater", "App is up to date (current=$currentVersion, latest=$latestVersion).")
                return@withContext null
            }

            val apkAsset = release.assets?.firstOrNull {
                it.name?.endsWith(".apk", ignoreCase = true) == true
            }
            val fallbackApkUrl = "https://github.com/$REPO_PATH/releases/download/$latestVersion/app-release-signed.apk"
            // Por qué: Nunca se confía en una URL de descarga fuera del repositorio oficial, aunque provenga de la API
            val apkUrl = apkAsset?.browser_download_url?.takeIf { isTrustedDownloadUrl(it) } ?: fallbackApkUrl
            val apkSize = apkAsset?.size ?: 0L
            val htmlUrl = release.html_url?.takeIf { isTrustedDownloadUrl(it) }
                ?: "https://github.com/$REPO_PATH/releases/tag/$latestVersion"

            UpdateInfo(
                version = latestVersion,
                apkUrl = apkUrl,
                apkSize = apkSize,
                htmlUrl = htmlUrl
            )
        } catch (e: Exception) {
            Log.e("AppUpdater", "Failed to check for updates", e)
            null
        }
    }

    /**
     * Retorna el archivo local de destino donde se guardará el APK descargado.
     * Por qué: Se almacena en la caché interna privada de la app para no requerir permisos de almacenamiento externo.
     */
    fun getUpdateFile(version: String): File {
        val safeVersion = version.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
        val dir = File(appContext.cacheDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "OverChat-$safeVersion.apk")
    }

    /**
     * Descarga el archivo APK directamente en la aplicación transmitiendo los bytes en segundo plano
     * e informando del progreso de descarga en tiempo real.
     */
    suspend fun downloadApk(
        apkUrl: String,
        destinationFile: File,
        expectedSize: Long = 0L,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isTrustedDownloadUrl(apkUrl)) {
            Log.w("AppUpdater", "Rejected untrusted APK URL")
            return@withContext false
        }
        try {
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            destinationFile.parentFile?.mkdirs()

            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "OverChat-Android/${BuildConfig.VERSION_NAME}")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("AppUpdater", "Failed to download APK: HTTP ${response.code}")
                return@withContext false
            }
            // Por qué: GitHub redirige a su CDN; se valida también el destino final de la redirección
            if (!isTrustedDownloadUrl(response.request.url.toString())) {
                Log.w("AppUpdater", "Rejected APK download redirected to an untrusted host")
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead: Long = 0L
                    var lastUpdateTimestamp = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        // Por qué: Limitar la emisión de actualizaciones de progreso a 10 veces por segundo para evitar saturar el hilo principal de Compose
                        if (now - lastUpdateTimestamp >= 100L) {
                            lastUpdateTimestamp = now
                            withContext(Dispatchers.Main) {
                                onProgress(totalRead, if (contentLength > 0) contentLength else totalRead)
                            }
                        }
                    }
                    output.flush()
                    withContext(Dispatchers.Main) {
                        onProgress(totalRead, if (contentLength > 0) contentLength else totalRead)
                    }
                }
            }
            // Por qué: Un tamaño distinto al publicado indica una descarga truncada o manipulada
            if (expectedSize > 0L && destinationFile.length() != expectedSize) {
                Log.w("AppUpdater", "Downloaded APK size mismatch")
                destinationFile.delete()
                return@withContext false
            }
            true
        } catch (e: Exception) {
            Log.e("AppUpdater", "Error downloading APK", e)
            destinationFile.delete()
            false
        }
    }

    sealed class InstallResult {
        object Success : InstallResult()
        object PermissionRequired : InstallResult()
        object FileNotFound : InstallResult()
        data class Error(val message: String) : InstallResult()
    }

    /**
     * Inicia el instalador de paquetes del sistema operativo Android para el APK descargado.
     * Por qué: Utiliza FileProvider para otorgar acceso de lectura seguro y temporal al instalador del sistema.
     */
    fun installApk(context: Context, file: File): InstallResult {
        if (!file.exists() || file.length() == 0L) {
            Log.e("AppUpdater", "APK file does not exist or is empty")
            return InstallResult.FileNotFound
        }

        // Por qué: Verifica paquete, versión y certificado de firma antes de entregar el APK al instalador del sistema
        val verificationError = verifyApk(context, file)
        if (verificationError != null) {
            Log.e("AppUpdater", "APK verification failed: $verificationError")
            file.delete()
            return InstallResult.Error(verificationError)
        }

        // Por qué: En Android 8.0+ (Oreo y superior) se debe verificar si la aplicación tiene permiso para instalar paquetes desconocidos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                try {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    return InstallResult.PermissionRequired
                } catch (e: Exception) {
                    Log.e("AppUpdater", "Failed to open unknown app sources settings", e)
                }
            }
        }

        return try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallResult.Success
        } catch (e: Exception) {
            Log.e("AppUpdater", "Failed to launch package installer", e)
            InstallResult.Error(e.message ?: "Failed to launch installer")
        }
    }

    /**
     * Confirms that a downloaded APK is a newer build of this same app, signed with the same certificate.
     * Returns `null` when the APK is safe to install, or a user-facing error message otherwise.
     */
    fun verifyApk(context: Context, file: File): String? {
        return try {
            val pm = context.packageManager
            val archive = readArchiveInfo(pm, file) ?: return "The downloaded file is not a valid APK."
            if (archive.packageName != context.packageName) {
                return "The downloaded APK belongs to a different app."
            }
            val installed = readInstalledInfo(pm, context.packageName)
            if (versionCodeOf(archive) <= versionCodeOf(installed)) {
                return "The downloaded APK is not newer than the installed version."
            }
            val installedCurrent = currentSignerDigests(installed)
            val archiveAuthorized = authorizedSignerDigests(archive)
            // Por qué: Admite la rotación de claves (historial de firmas) pero exige el certificado actual instalado
            if (installedCurrent.isEmpty() || !archiveAuthorized.containsAll(installedCurrent)) {
                return "The downloaded APK is not signed by the original developer."
            }
            null
        } catch (e: Exception) {
            Log.e("AppUpdater", "Failed to verify APK", e)
            "Could not verify the downloaded APK."
        }
    }

    private fun signingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private fun readArchiveInfo(pm: PackageManager, file: File): PackageInfo? {
        val info = pm.getPackageArchiveInfo(file.absolutePath, signingFlags()) ?: return null
        // Por qué: getPackageArchiveInfo no rellena sourceDir, necesario para leer las firmas en algunas versiones
        info.applicationInfo?.sourceDir = file.absolutePath
        info.applicationInfo?.publicSourceDir = file.absolutePath
        return info
    }

    private fun readInstalledInfo(pm: PackageManager, packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, signingFlags())
        }
    }

    private fun versionCodeOf(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun currentSignerDigests(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return legacySignerDigests(info)
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory?.takeLast(1)?.toTypedArray()
            }
            return digestsOf(signers)
        }
        return legacySignerDigests(info)
    }

    private fun authorizedSignerDigests(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return legacySignerDigests(info)
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            return digestsOf(signers)
        }
        return legacySignerDigests(info)
    }

    @Suppress("DEPRECATION")
    private fun legacySignerDigests(info: PackageInfo): Set<String> = digestsOf(info.signatures)

    private fun digestsOf(signatures: Array<Signature>?): Set<String> {
        if (signatures.isNullOrEmpty()) return emptySet()
        val sha256 = MessageDigest.getInstance("SHA-256")
        return signatures.map { signature ->
            sha256.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /**
     * Abre la página de la versión en el navegador como respaldo en caso de fallo en la descarga interna.
     */
    fun openUpdatePage(context: Context, htmlUrl: String) {
        if (!isTrustedDownloadUrl(htmlUrl)) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppUpdater", "Error launching update browser intent", e)
        }
    }

    companion object {
        @Volatile
        private var instance: AppUpdater? = null

        /**
         * Devuelve la instancia única de [AppUpdater] ligada al ciclo de vida de la aplicación.
         */
        fun get(context: Context): AppUpdater {
            return instance ?: synchronized(this) {
                instance ?: AppUpdater(context.applicationContext).also { instance = it }
            }
        }

        const val REPO_PATH = "aggelosflampouris-byte/Translator"

        // Por qué: GitHub sirve los binarios de las versiones desde estos dominios de CDN tras la redirección
        private val TRUSTED_CDN_HOSTS = setOf(
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com"
        )

        /**
         * Accepts only HTTPS URLs that point at this repository on github.com or GitHub's release CDN.
         */
        fun isTrustedDownloadUrl(url: String): Boolean {
            val uri = try {
                java.net.URI(url)
            } catch (e: Exception) {
                return false
            }
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            if (uri.userInfo != null) return false
            val host = uri.host?.lowercase() ?: return false
            if (host in TRUSTED_CDN_HOSTS) return true
            val path = uri.path ?: return false
            return host == "github.com" && path.startsWith("/$REPO_PATH/")
        }

        fun normalizeVersion(v: String): String = v.trim().removePrefix("v").removePrefix("V")

        /**
         * Compara dos versiones semánticas numéricamente.
         * Por qué: Evita errores al comparar cadenas simples (ej: 'v1.0.10' vs 'v1.0.9').
         */
        fun isNewerVersion(latest: String, current: String): Boolean {
            val lParts = normalizeVersion(latest).split(".").mapNotNull { it.toIntOrNull() }
            val cParts = normalizeVersion(current).split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(lParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val l = lParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }
    }

    data class UpdateInfo(
        val version: String,
        val apkUrl: String,
        val apkSize: Long,
        val htmlUrl: String
    )

    data class GitHubRelease(
        val tag_name: String?,
        val html_url: String?,
        val assets: List<Asset>?
    )

    data class Asset(
        val name: String?,
        val size: Long?,
        val browser_download_url: String?
    )
}
