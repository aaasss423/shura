package app.shura.manga

import android.content.Context
import app.shura.runtime.TrustAnchor
import app.shura.runtime.ShuraExtensionLoader
import java.io.File

/**
 * Shura's own repository is the only source of extensions. Nothing falls back
 * to any other repository and none is configured. MangaDex stays as the
 * built-in adapter; extensions loaded from the repository are added beside it,
 * never instead of it, so a failing extension cannot take the other sources
 * down with it. Every load failure is surfaced in the Sources tab.
 */
object ShuraExtensions {

    /**
     * The certificate digest this build trusts.
     *
     * A constant in the build on purpose: an anchor read from the repository it
     * certifies proves nothing, since whoever serves the repository also serves
     * that claim. A build that should not trust this repository ships without
     * the constant and every extension is refused rather than silently believed.
     */
    const val TRUSTED_REPOSITORY_ANCHOR: String =
        "b655a474503f4471fdaf6ba35b9385f71d144669f3c28602c5b60b062022c41d"

    fun load(context: Context, repositoryDir: File): List<app.shura.runtime.ExtensionOutcome> =
        ShuraExtensionLoader.load(context, repositoryDir, TrustAnchor(TRUSTED_REPOSITORY_ANCHOR))
}
