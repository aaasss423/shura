package app.shura.runtime

import android.content.Context
import app.shura.source.ShuraSource
import java.io.File

/**
 * Shura's extension repository client.
 *
 * Shura Core is the only source of extensions. There is no fallback to any other
 * repository, and none is configured: [fromRepository] reads exactly the files
 * Shura Core publishes - `repo.json` for the repository trust anchor and
 * `index.shura.json` for the per-extension digests.
 *
 * Why `index.shura.json` and not `index.pb`: it is the artifact that carries
 * `artifact_sha256` and `signing_certificate_sha256`, which are what the
 * verification below needs. `index.pb` carries neither.
 */
class ShuraRepositoryClient(
    private val root: File,
    /**
     * The expected certificate digest, configured independently of anything
     * downloaded.
     *
     * This is the whole point of the trust check. Reading the anchor out of the
     * repository it is meant to certify proves nothing: whoever can serve the APK
     * can serve a repo.json naming their own key, and the comparison would be
     * self-consistent. So the anchor comes from the build, the user's
     * first-use confirmation, or an explicit setting -- never from the network.
     *
     * `null` means "nothing configured", and every entry is then refused.
     */
    private val expectedAnchor: TrustAnchor,
) {

    private val repoMeta: RepositoryMeta by lazy {
        val text = File(root, "repo.json").takeIf { it.isFile }?.readText()
            ?: throw LoadFailure.Manifest("repo.json is missing under $root")
        RepositoryMeta.parse(text)
    }

    /** The repository-level trust anchor: configured, never downloaded. */
    fun trustAnchor(): String = expectedAnchor.require()

    /**
     * Rejects the repository outright when its declared anchor is not the one
     * configured. Checked before any entry is looked at.
     */
    private fun requireAnchorMatches(): String {
        val expected = expectedAnchor.require()
        val declared = repoMeta.signingKeyFingerprint
        if (declared.lowercase() != expected.lowercase()) {
            throw LoadFailure.NotVerified(
                "repository anchor mismatch: it declares $declared but $expected was configured. " +
                    "Either the repository is not the one this build trusts, or its metadata was replaced."
            )
        }
        return declared
    }

    fun entries(): List<RepositoryPackage> {
        requireAnchorMatches()
        val text = File(root, "index.shura.json").takeIf { it.isFile }?.readText()
            ?: throw LoadFailure.Manifest("index.shura.json is missing under $root")
        return RepositoryIndex.parse(text)
    }

    /**
     * Verifies one entry and returns it ready to load.
     *
     * Order matters and is not negotiable: the digest is checked against the
     * value Shura Core published, the certificate against the repository anchor,
     * and only then is the ABI read out of the artifact itself. Nothing is
     * taken on trust from the file we are about to execute.
     */
    fun verify(entry: RepositoryPackage): VerifiedExtension {
        val apk = File(root, "apk").resolve(entry.apk)
        if (!apk.isFile) throw LoadFailure.NotVerified("${entry.apk} is not present under ${apk.parentFile}")

        val digest = sha256(apk)
        if (digest != entry.artifactSha256) {
            throw LoadFailure.NotVerified(
                "SHA-256 mismatch for ${entry.apk}: got $digest, repository published ${entry.artifactSha256}"
            )
        }
        val anchor = trustAnchor()
        if (entry.signingCertificateSha256.lowercase() != anchor.lowercase()) {
            throw LoadFailure.NotVerified(
                "${entry.identity} is pinned to ${entry.signingCertificateSha256}, " +
                    "but this repository trusts $anchor"
            )
        }
        val metadata = ApkManifestReader().read(apk)
        if (metadata.packageName != entry.pkg) {
            throw LoadFailure.Manifest(
                "${entry.apk} declares package ${metadata.packageName}, index says ${entry.pkg}"
            )
        }
        val abiText = metadata.metaData["tachiyomix.extensionLib"]
            ?: throw LoadFailure.Manifest("no tachiyomix.extensionLib in ${entry.apk}")
        val abi = abiText.toFloatOrNull()
            ?: throw LoadFailure.Manifest("extensionLib '$abiText' is not a number")

        // Recorded, never silently reconciled: the index publishes a derived
        // extensionLib/versionCode, the artifact declares its own. The artifact
        // wins, because it is what will actually run.
        if (abiText != entry.indexExtensionLib) {
            indexApkMismatch(entry, "extensionLib", entry.indexExtensionLib, abiText)
        }
        if (metadata.versionCode != entry.indexVersionCode) {
            indexApkMismatch(entry, "versionCode", entry.indexVersionCode.toLong(), metadata.versionCode)
        }

        return VerifiedExtension(
            apk = apk,
            packageName = metadata.packageName,
            metadata = metadata,
            abi = abi,
            sourceClassName = "",
        )
    }

    private fun indexApkMismatch(entry: RepositoryPackage, field: String, published: Any?, declared: Any?) {
        System.err.println(
            "Shura repository: $field for ${entry.identity} is $published in the index but " +
                "$declared in the APK; the APK value is authoritative at runtime"
        )
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}