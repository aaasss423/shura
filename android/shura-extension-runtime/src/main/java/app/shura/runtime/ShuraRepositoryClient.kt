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
     * Reads the signing certificate out of the APK. Injected so the repository
     * logic is testable off-device, but the production wiring always passes the
     * real one: a nil signature check would make every other check decorative.
     */
    private val signatures: ApkSignatureVerifier?,
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
     * Stages the artifact, then verifies *that copy*, and returns a descriptor
     * pointing at the staged bytes.
     *
     * The order is the point. Verifying a file in place and copying it afterwards
     * leaves a window: whatever serves the repository can swap the bytes between
     * the two steps, and the loader would then execute a file nobody checked.
     * So the copy is made first, and the digest, the signature and the manifest
     * are all read from the copy -- the exact bytes the class loader is handed.
     */
    fun verify(entry: RepositoryPackage): VerifiedExtension {
        val downloaded = File(root, "apk").resolve(entry.apk)
        if (!downloaded.isFile) {
            throw LoadFailure.NotVerified("${entry.apk} is not present under ${downloaded.parentFile}")
        }
        val staged = File(stagingDir(), entry.apk)
        staged.parentFile?.mkdirs()
        downloaded.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }

        val digest = sha256(staged)
        if (digest != entry.artifactSha256) {
            staged.delete()
            throw LoadFailure.NotVerified(
                "SHA-256 mismatch for ${entry.apk}: got $digest, repository published ${entry.artifactSha256}"
            )
        }
        val anchor = trustAnchor()
        val fromFile = signatures?.verify(staged, anchor, entry.signingCertificateSha256)
        if (fromFile == null) {
            staged.delete()
            throw LoadFailure.NotVerified(
                "no APK signature verifier was supplied; refusing to trust ${entry.identity} on metadata alone"
            )
        }
        val metadata = ApkManifestReader().read(staged)
        if (metadata.packageName != entry.pkg) {
            staged.delete()
            throw LoadFailure.Manifest(
                "${entry.apk} declares package ${metadata.packageName}, index says ${entry.pkg}"
            )
        }
        val abiText = metadata.metaData["tachiyomix.extensionLib"]
            ?: throw LoadFailure.Manifest("no tachiyomix.extensionLib in ${entry.apk}").also { staged.delete() }
        val abi = abiText.toFloatOrNull()
            ?: throw LoadFailure.Manifest("extensionLib '$abiText' is not a number").also { staged.delete() }

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
            apk = staged,
            packageName = metadata.packageName,
            metadata = metadata,
            abi = abi,
            sourceClassName = "",
        )
    }

    /**
     * Where verified copies live. The real install target on device; a temp
     * directory in unit tests, where no Context exists.
     */
    private fun stagingDir(): File =
        staging ?: File(System.getProperty("java.io.tmpdir"), "shura-staging")

    /** Test seam: the staging root. Production uses the app's files dir. */
    fun useStaging(dir: File) { staging = dir }

    private var staging: File? = null

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