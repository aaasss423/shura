package app.shura.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * `repo.json`: the repository's own trust anchor.
 *
 * [signingKeyFingerprints] is every publisher whose artifacts this repository
 * carries. It started as a single field and became a list because a repository
 * that can only speak for one key can only ever contain one publisher's
 * extensions: Shura signs its own with its own key and a third-party Arabic
 * source with theirs, so a single-key index structurally cannot hold both. That
 * is the mechanical reason the store showed exactly one extension.
 *
 * The anchor still means what it meant. It is pinned out of band and is never
 * read from the repository it certifies. What changed is that each artifact is
 * checked against the publisher trusted for *its own package*, and the
 * repository only has to declare that it carries that publisher.
 */
data class RepositoryMeta(
    val name: String,
    val website: String,
    val signingKeyFingerprint: String,
    val signingKeyFingerprints: List<String> = listOf(signingKeyFingerprint),
) {
    companion object {
        private val HEX = Regex("[0-9a-fA-F]{64}")

        fun parse(text: String): RepositoryMeta {
            val meta = JSONObject(text).optJSONObject("meta")
                ?: throw LoadFailure.Manifest("repo.json has no meta object")
            val legacy = meta.optString("signingKeyFingerprint")
            if (!legacy.matches(HEX)) {
                throw LoadFailure.Manifest(
                    "repo.json signingKeyFingerprint '$legacy' is not a 64-hex certificate digest"
                )
            }
            val declared = meta.optJSONArray("signingKeyFingerprints")
                ?.let { array -> (0 until array.length()).map { array.optString(it).lowercase() } }
                .orEmpty()
            // A malformed entry is refused rather than skipped: a repository
            // that declares a key it cannot spell is not one to read loosely.
            val publishers = (declared.ifEmpty { listOf(legacy.lowercase()) }).map {
                if (!it.matches(HEX)) {
                    throw LoadFailure.Manifest(
                        "repo.json signingKeyFingerprints entry '$it' is not a 64-hex digest"
                    )
                }
                it
            }
            if (legacy.lowercase() !in publishers) {
                throw LoadFailure.Manifest(
                    "repo.json declares $legacy but does not list it among its publishers"
                )
            }
            return RepositoryMeta(
                meta.optString("name"),
                meta.optString("website"),
                legacy.lowercase(),
                publishers.distinct(),
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

/**
 * An index, and the set of publishers whose artifacts it carries.
 *
 * The publisher set travels with the index rather than living in repo.json
 * because repo.json is the Keiyoushi-compatible shape, whose exact keys are a
 * contract other clients read. index.shura.json is Shura's own file.
 */
data class ParsedRepositoryIndex(
    val packages: List<RepositoryPackage>,
    val publishers: List<String>,
)

object RepositoryIndex {
    private val HEX64 = Regex("[0-9a-fA-F]{64}")

    fun parse(text: String): List<RepositoryPackage> = RepositoryPackage.parseAll(text)

    /**
     * Parses the packages and the declared publishers.
     *
     * An index with no publisher list falls back to its own singular
     * fingerprint, so a repository written before multi-publisher support still
     * parses. One with neither falls back to [fallbackPublishers] - and if that
     * is empty too, the anchor check refuses everything rather than trusting a
     * repository that never said who signs for it.
     */
    fun parseIndex(text: String, fallbackPublishers: List<String> = emptyList()): ParsedRepositoryIndex {
        val declared = try {
            JSONObject(text).optJSONArray("signing_key_fingerprints")
                ?.let { array -> (0 until array.length()).map { array.optString(it).lowercase() } }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val legacy = JSONObject(text).optString("signing_key_fingerprint").lowercase()
        val publishers = when {
            declared.isNotEmpty() -> declared
            HEX64.matches(legacy) -> listOf(legacy)
            else -> fallbackPublishers
        }.filter { HEX64.matches(it) }.distinct()
        return ParsedRepositoryIndex(RepositoryPackage.parseAll(text), publishers)
    }
}

/**
 * A publisher this build is willing to run code from, and the packages it may.
 *
 * WHY THIS IS NOT JUST ANOTHER ANCHOR
 *
 * The build pinned exactly one certificate, and it happened to be the one that
 * signs `eu.kanade.tachiyomi.extension.ar.procomic` - an extension downloaded
 * from a third party's GitHub releases, not one Shura built. So the app's trust
 * model said "the publisher of Shura is whoever published ProComic".
 *
 * That is wrong twice over. Shura builds its own extensions, and they cannot be
 * signed with a third party's private key; and a repository that carried both a
 * third-party extension and a Shura-built one would have been refused by the
 * publisher for holding two signers. The two identities were fused into one,
 * which is why no Shura-built extension could ever have loaded.
 *
 * So an identity is a certificate *and the packages it speaks for*. A trusted
 * publisher cannot sign for a package outside its scope, so a key trusted for
 * one third-party extension cannot be used to mint another one.
 *
 * Provisioned out of band. A repository may declare its own signers, and those
 * are claims; only what this build was configured with counts.
 */
data class TrustedPublisher(
    val digest: String,
    /** Packages this key may sign for, as exact names or `prefix.`-scoped. */
    val packageScopes: List<String>,
    /** Shown in a refusal, so the user knows which identity was involved. */
    val label: String = digest.take(8),
) {
    init {
        if (!digest.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw LoadFailure.NotVerified(
                "a trusted publisher needs a 64-hex certificate digest, got '$digest'")
        }
        if (packageScopes.isEmpty()) {
            throw LoadFailure.NotVerified(
                "trusted publisher ${digest.take(8)} was given no package scope; an " +
                    "unscoped key would be trusted for every package in the repository")
        }
    }

    /** Whether this key may sign for [pkg]. */
    fun covers(pkg: String): Boolean = packageScopes.any { scope ->
        pkg == scope || (scope.endsWith('.') && pkg.startsWith(scope))
    }

    fun coversAny(packages: Collection<String>): Boolean = packages.any { covers(it) }
}

/**
 * Everything this build trusts: the repository's own metadata key, and the
 * publishers whose code it will run.
 *
 * These are separate on purpose. The repository anchor says who may speak for
 * the repository; the publisher list says whose extensions may run inside the
 * app. Collapsing them is what made a third-party key stand in for Shura's.
 */
data class TrustPolicy(
    val repository: TrustAnchor,
    val publishers: List<TrustedPublisher>,
) {
    /**
     * The publisher that may sign [pkg], or a refusal naming what is pinned.
     *
     * Empty is a normal, configured state: a build with no Shura publisher key
     * simply refuses Shura-built extensions, which is the correct outcome
     * rather than a reason to fall back to somebody else's key.
     */
    fun publisherFor(pkg: String): TrustedPublisher =
        publishers.firstOrNull { it.covers(pkg) }
            ?: throw LoadFailure.NotVerified(
                "no trusted publisher is pinned for '$pkg' in this build. " +
                    (if (publishers.isEmpty())
                        "No publisher is pinned at all, so no extension can run."
                    else
                        "Pinned publishers: " + publishers.joinToString { "${it.label} (${it.packageScopes.joinToString()})" }) +
                    " Installing an extension cannot grant this permission."
            )
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
