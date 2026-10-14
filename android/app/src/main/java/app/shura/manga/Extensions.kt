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
 * ## Two identities, not one
 *
 * This build previously pinned a single certificate, and that certificate was
 * the one signing `eu.kanade.tachiyomi.extension.ar.procomic` - an extension
 * downloaded from a third party's GitHub releases. So the app's trust model
 * said "Shura's publisher is whoever published ProComic", and any extension
 * Shura built itself could never load: it cannot be signed with a third
 * party's private key.
 *
 * The two are now separate. [REPOSITORY_ANCHOR] says who may speak for the
 * repository. [publishers] says whose extensions this build will run, each
 * scoped to the packages it was granted. A key trusted for one third-party
 * extension cannot mint another, and cannot sign for a `app.shura.extension.*`
 * package.
 *
 * ## The Shura publisher key is not configured
 *
 * [SHURA_PUBLISHER_CERTIFICATE] is empty, and that is deliberate rather than
 * unfinished: the key is the operator's, it is held out of Git, and no
 * substitute was invented for it. While it is empty, extensions Shura built are
 * refused by name with a message saying why - which is the correct outcome, and
 * strictly safer than falling back to a third party's certificate.
 *
 * Provisioning it is an explicit act and never a source edit. The build reads
 * the digest from `-Pshura.publisherCert=<64 hex>` or `SHURA_PUBLISHER_CERT`
 * in the environment, so the value lives with the key and not in this
 * repository. It is checked against the APK at load, so a wrong value refuses
 * rather than trusts.
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
        "b655a474503f4471fdaf6ba35b9385f71d144669f3c28602c5b60b062022c41d"

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
     * `procomic` is the extension this repository has always carried, taken from
     * `github:lonevertex/mihon-extension-ar-procomic` releases and signed by
     * that project's own certificate. Pinning it to that one package keeps the
     * existing behaviour working without letting the key sign for anything else.
     */
    private val thirdPartyPublishers = listOf(
        TrustedPublisher(
            digest = TRUSTED_REPOSITORY_ANCHOR,
            packageScopes = listOf("eu.kanade.tachiyomi.extension.ar.procomic"),
            label = "procomic",
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
