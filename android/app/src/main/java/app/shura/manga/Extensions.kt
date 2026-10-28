package app.shura.manga

import android.content.Context
import app.shura.runtime.TrustAnchor
import app.shura.runtime.TrustPolicy
import app.shura.runtime.TrustedPublisher
import app.shura.runtime.ShuraExtensionLoader
import java.io.File

/**
 * Shura's own repository is the only source of extensions. Nothing falls back
 * to any other repository and none is configured. MangaDex stays as the
 * built-in adapter; extensions loaded from the repository are added beside it,
 * never instead of it, so a failing extension cannot take the other sources
 * down with it. Every load failure is surfaced in the Sources tab.
 *
 * The repository is generic and source-agnostic: the app trusts the repository
 * anchor and the package scopes it is explicitly configured to accept, rather
 * than assuming a single extension or a single upstream publisher.
 */
object ShuraExtensions {

    /**
     * The certificate digest this build trusts a *repository* to be signed by.
     *
     * A constant in the build on purpose: an anchor read from the repository it
     * certifies proves nothing, since whoever serves the repository also serves
     * that claim. A build that should not trust this repository ships without
     * the constant and every extension is refused rather than silently believed.
     */
    const val TRUSTED_REPOSITORY_ANCHOR: String =
        "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

    /**
     * The SHA-256 of the certificate Shura signs its own extensions with.
     *
     * Empty means "no Shura publisher is configured", and extensions built by
     * Shura are refused with that reason. It is never defaulted to another key.
     */
    val SHURA_PUBLISHER_CERTIFICATE: String
        get() = BuildConfig.SHURA_PUBLISHER_CERT

    /**
     * Third-party extensions this build will run, each limited to the packages
     * it was granted.
     *
     * The shipped repository is generic and published under the Shura trust
     * anchor, so the allowed scope covers the package family the real repository
     * actually publishes instead of hard-coding a single upstream extension.
     */
    private val thirdPartyPublishers = listOf(
        TrustedPublisher(
            digest = TRUSTED_REPOSITORY_ANCHOR,
            packageScopes = listOf("eu.kanade.tachiyomi.extension."),
            label = "shura-repo",
        ),
    )

    /** What this build trusts. Built from constants only; never from a repository. */
    val policy: TrustPolicy
        get() {
            val publishers = thirdPartyPublishers.toMutableList()
            if (SHURA_PUBLISHER_CERTIFICATE.isNotBlank()) {
                publishers += TrustedPublisher(
                    digest = SHURA_PUBLISHER_CERTIFICATE,
                    // Everything Shura builds for itself.
                    packageScopes = listOf("app.shura.extension."),
                    label = "shura",
                )
            }
            return TrustPolicy(TrustAnchor(TRUSTED_REPOSITORY_ANCHOR), publishers)
        }

    fun load(context: Context, repositoryDir: File): List<app.shura.runtime.ExtensionOutcome> =
        ShuraExtensionLoader.load(context, repositoryDir, policy)
}
