package app.shura.runtime

import org.json.JSONArray
import org.json.JSONObject

/** `repo.json`: the repository trust anchor. */
data class RepositoryMeta(
    val name: String,
    val website: String,
    val signingKeyFingerprint: String,
) {
    companion object {
        fun parse(text: String): RepositoryMeta {
            val meta = JSONObject(text).optJSONObject("meta")
                ?: throw LoadFailure.Manifest("repo.json has no meta object")
            val fingerprint = meta.optString("signingKeyFingerprint")
            if (!fingerprint.matches(Regex("[0-9a-fA-F]{64}"))) {
                throw LoadFailure.Manifest(
                    "repo.json signingKeyFingerprint '$fingerprint' is not a 64-hex certificate digest"
                )
            }
            return RepositoryMeta(
                meta.optString("name"),
                meta.optString("website"),
                fingerprint.lowercase(),
            )
        }
    }
}

/** One entry of `index.shura.json`. */
data class RepositoryPackage(
    val pkg: String,
    val version: String,
    val apk: String,
    val lang: String,
    val identity: String,
    val artifactSha256: String,
    val signingCertificateSha256: String,
    /** Derived by Shura Core from the version string, not read from the APK. */
    val indexVersionCode: Long,
    /** Derived by Shura Core from the version string, not read from the APK. */
    val indexExtensionLib: String?,
) {
    companion object {
        fun parseAll(text: String): List<RepositoryPackage> {
            val packages = JSONObject(text).optJSONArray("packages")
                ?: throw LoadFailure.Manifest("index.shura.json has no packages array")
            return (0 until packages.length()).mapNotNull { index ->
                val o = packages.optJSONObject(index) ?: return@mapNotNull null
                RepositoryPackage(
                    pkg = o.optString("pkg"),
                    version = o.optString("version"),
                    apk = o.optString("apk"),
                    lang = o.optString("lang", "und"),
                    identity = o.optString("identity"),
                    artifactSha256 = o.optString("artifact_sha256").lowercase(),
                    signingCertificateSha256 = o.optString("signing_certificate_sha256").lowercase(),
                    indexVersionCode = o.optString("index_version_code").toLongOrNull() ?: 0L,
                    indexExtensionLib = o.optString("index_extension_lib").ifEmpty { null },
                )
            }
        }
    }
}

object RepositoryIndex {
    fun parse(text: String): List<RepositoryPackage> = RepositoryPackage.parseAll(text)
}
/**
 * The certificate digest this build expects a repository to be signed with.
 *
 * Held out of band on purpose. A value read from the repository it certifies is
 * not a trust anchor -- it is a claim the repository makes about itself, and
 * whoever controls the repository controls the claim.
 */
@JvmInline
value class TrustAnchor(val digest: String?) {
    init {
        val d = digest
        if (d != null && !d.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw LoadFailure.NotVerified("a trust anchor must be a 64-hex certificate digest, got '$d'")
        }
    }

    fun isConfigured(): Boolean = digest != null

    fun require(): String = digest
        ?: throw LoadFailure.NotVerified(
            "no repository trust anchor is configured, so nothing here can be trusted. " +
                "A repository must be pinned out of band (build constant or user confirmation) " +
                "before any extension from it may run."
        )
}
