package dev.kurobanekotlin.android.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dev.kurobanekotlin.core.update.UpdateRelease
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Why an update could not be installed; [message] is shown to the user as is. */
class UpdateInstallException(message: String) : Exception(message)

/**
 * Downloads the release APK into the app cache, checks it, and hands it to Android's package installer
 * (which shows its own confirmation). Checks before the hand-off: size and SHA-256 when GitHub reports them,
 * same package name, a higher versionCode, and the same signing certificate as the installed app.
 */
class ApkInstaller(private val context: Context, private val http: HttpClient) {

    private val dir get() = File(context.cacheDir, "downloads").apply { mkdirs() }

    suspend fun download(release: UpdateRelease, onProgress: (done: Long, total: Long) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            val part = File(dir, "kurobane-android.apk.part")
            val target = File(dir, "kurobane-android-${release.version}.apk")
            try {
                dir.listFiles()?.forEach { it.delete() }
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                http.prepareGet(release.apkUrl) {
                    // The shared client's 30 s request timeout would cut off a large download.
                    timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS; socketTimeoutMillis = 60_000 }
                }.execute { resp ->
                    if (!resp.status.isSuccess()) throw UpdateInstallException("The download failed (HTTP ${resp.status.value}).")
                    val total = resp.headers["Content-Length"]?.toLongOrNull() ?: release.apkSize
                    val ch = resp.bodyAsChannel()
                    val buf = ByteArray(64 * 1024)
                    part.outputStream().use { out ->
                        while (true) {
                            val n = ch.readAvailable(buf, 0, buf.size)
                            if (n < 0) break
                            if (n == 0) continue
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                }
                if (release.apkSize > 0 && written != release.apkSize)
                    throw UpdateInstallException("The download was incomplete ($written of ${release.apkSize} bytes). Try again.")
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (release.sha256 != null && !actual.equals(release.sha256, ignoreCase = true))
                    throw UpdateInstallException("The downloaded file does not match the checksum GitHub lists for this release. It was discarded.")
                if (!part.renameTo(target)) throw UpdateInstallException("Could not save the downloaded update.")
                verifyApk(target, release)
                Result.success(target)
            } catch (e: UpdateInstallException) {
                part.delete(); target.delete()
                Result.failure(e)
            } catch (e: Exception) {
                part.delete(); target.delete()
                Result.failure(UpdateInstallException("The download failed: ${e.message ?: e.javaClass.simpleName}. Check your connection and try again."))
            }
        }

    private fun verifyApk(file: File, release: UpdateRelease) {
        val pm = context.packageManager
        val flags = signatureFlags()
        val archive = pm.getPackageArchiveInfo(file.path, flags)
            ?: throw UpdateInstallException("The downloaded file is not a valid Android package.")
        if (archive.packageName != context.packageName)
            throw UpdateInstallException("The downloaded package (${archive.packageName}) is not this app.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed))
            throw UpdateInstallException("The release ${release.tag} is not newer than the installed version.")
        val newSigners = signers(archive)
        val oldSigners = signers(installed)
        if (newSigners.isEmpty() || newSigners.none { it in oldSigners })
            throw UpdateInstallException(
                "This update is signed with a different key than the installed app, so Android would refuse it. " +
                    "If this is a debug or locally built copy, uninstall it and install the release APK once; later updates will work.")
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): List<List<Byte>> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs?.map { it.toByteArray().toList() }.orEmpty()
    }

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system screen where the user allows this app to install packages. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android's own install confirmation; the user must accept it. */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
