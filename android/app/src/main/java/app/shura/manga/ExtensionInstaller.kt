package app.shura.manga

import android.content.Context
import app.shura.runtime.ApkSignatureVerifier
import app.shura.runtime.LoadFailure
import app.shura.runtime.RepositoryPackage
import app.shura.runtime.TrustPolicy
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What happened to one install attempt, in terms the store can show. */
sealed interface InstallResult {
    data class Installed(val version: String) : InstallResult

    /** The download itself failed: offline, timeout, server gone. */
    data class DownloadFailed(val reason: String) : InstallResult

    /**
     * The bytes arrived but were refused.
     *
     * Never silent: an extension that fails its signature or trust check is
     * deleted rather than kept, because a file that is present but unverified
     * is the shape an attack wants.
     */
    data class Refused(val reason: String) : InstallResult
}

/**
 * Installs an extension the repository index offers.
 *
 * WHY THIS EXISTS
 *
 * The store could only list what had been copied in by hand through the
 * storage picker. There was no path from "the index says this exists" to "it
 * works", which is why it was a list rather than a store.
 *
 * The order is deliberate and is the whole safety argument:
 *
 *  1. download to a staging file, never over a live one;
 *  2. check the artifact hash the index published;
 *  3. check the signing certificate against this build's trust policy;
 *  4. only then move it into the repository directory.
 *
 * A refusal deletes the staged bytes. Nothing unverified is ever left where
 * the loader will read it, so a failed install cannot become a later "already
 * installed".
 */
class ExtensionInstaller(
    private val context: Context,
    private val policy: TrustPolicy,
) {

    /**
     * Reads the certificate out of the APK with Android's own package manager.
     *
     * Not a reimplementation: comparing the index's own claim about the signer
     * to the same index proves nothing, because whoever serves the repository
     * serves both. The digest that matters is the one Android derives from the
     * file, and that is what is checked.
     */
    private val verifier = ApkSignatureVerifier(context)

    /**
     * Downloads and installs [entry] from [baseUrl].
     *
     * [onProgress] receives bytes downloaded so far and the total, when the
     * server declares one, so the row can show real progress rather than an
     * indeterminate spinner.
     */
    suspend fun install(
        entry: RepositoryPackage,
        baseUrl: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): InstallResult {
        val directory = ShuraRepository.directory(context)
        val apkDir = File(directory, ExtensionCatalog.APK_DIR).apply { mkdirs() }
        val staged = File.createTempFile("shura-ext-", ".apk", context.cacheDir)
        return try {
            // The repository this build ships has already been unpacked onto the
            // device, so its extensions are on disk before anything is installed.
            // Fetching them over the network anyway meant an extension the app
            // carries in its own APK could only be installed by reaching a server
            // that may not exist - and an offline install refused for that reason is
            // not a trust decision, it is a wiring bug.
            //
            // Only the transport changes. The hash, the signature and the scope
            // checks below run exactly as they do for a downloaded file, because
            // "it was already here" is not evidence that it is the right file.
            val local = File(apkDir, entry.apk.substringAfterLast('/'))
            val declared = when {
                local.isFile && local.length() > 0L -> {
                    local.copyTo(staged, overwrite = true)
                    onProgress?.invoke(local.length(), local.length())
                    local.length()
                }
                else -> {
                    val url = URL(baseUrl.trimEnd('/') + "/" + entry.apk.trimStart('/'))
                    when (val head = fetch(url, staged, onProgress)) {
                        is Head.Failed -> return InstallResult.DownloadFailed(head.reason)
                        is Head.Done -> head.total
                    }
                }
            }
            // The index says what the bytes should hash to. A mismatch means
            // the artifact is not the one that was published, whatever it is.
            if (entry.artifactSha256.isNotBlank()) {
                val actual = sha256(staged)
                if (!actual.equals(entry.artifactSha256, ignoreCase = true)) {
                    return InstallResult.Refused(
                        "The downloaded file does not match the hash the repository published."
                    )
                }
            }
            // Signature and trust, re-checked here against the same policy the
            // loader uses and read from the APK itself. A digest that merely
            // looks right is not enough, and neither is a signer this build has
            // no publisher for - the scope check is the point of the policy.
            val expected = try {
                policy.publisherFor(entry.pkg)
            } catch (e: Exception) {
                return InstallResult.Refused(
                    e.message ?: "no trusted publisher is pinned for ${entry.pkg}"
                )
            }
            try {
                verifier.verify(staged, expected.digest, entry.signingCertificateSha256)
            } catch (e: LoadFailure) {
                return InstallResult.Refused(e.message ?: "the extension was refused")
            } catch (e: Exception) {
                return InstallResult.Refused(e.message ?: "the extension could not be verified")
            }

            val destination = File(apkDir, safeName(entry.apk))
            staged.copyTo(destination, overwrite = true)
            InstallResult.Installed(entry.version)
        } catch (e: Exception) {
            InstallResult.DownloadFailed(e.message ?: "the download did not complete")
        } finally {
            // Whatever happened, no unverified bytes survive.
            staged.delete()
        }
    }

    private sealed interface Head {
        data class Done(val total: Long) : Head
        data class Failed(val reason: String) : Head
    }

    private fun fetch(
        url: URL,
        into: File,
        onProgress: ((Long, Long) -> Unit)?,
    ): Head {
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) return Head.Failed("the server answered $code")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var done = 0L
            connection.inputStream.use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress?.invoke(done, total)
                    }
                }
            }
            if (into.length() == 0L) Head.Failed("the download was empty") else Head.Done(total)
        } catch (e: Exception) {
            Head.Failed(e.message ?: "the connection failed")
        } finally {
            connection?.disconnect()
        }
    }

    private fun safeName(name: String): String {
        // Remote input. Anything that could address a file outside apk/ is
        // refused outright rather than sanitised into something plausible.
        require(name.isNotBlank()) { "the index names no artifact" }
        require(!name.contains('/') && !name.contains('\\') && !name.contains("..")) {
            "the index names an artifact outside the repository: $name"
        }
        return name
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }


}