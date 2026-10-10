package app.shura.manga

import android.content.Context
import app.shura.runtime.ApkSignatureVerifier
import app.shura.runtime.ExtensionLoader
import app.shura.runtime.ExtensionSourceAdapter
import app.shura.runtime.ShuraRepositoryClient
import app.shura.runtime.TrustAnchor
import app.shura.source.ShuraSource
import java.io.File

/**
 * Brings the extensions Shura Core publishes into the app's source list.
 *
 * Shura's own repository is the only source of extensions. Nothing here falls
 * back to any other repository, and none is configured.
 *
 * What this returns is a real loaded extension, or a reason it did not load. On
 * a failure nothing is silently dropped: the message is surfaced, because a
 * source that quietly disappears looks identical to a source that was never
 * installed.
 */
data class ExtensionLoadOutcome(
    val source: ShuraSource?,
    val error: String?,
)

object ShuraExtensions {

    /**
     * The certificate digest this build trusts.
     *
     * Deliberately a constant here and not something read from the repository:
     * an anchor taken from the repository it certifies proves nothing. A build
     * that should not trust this repository ships without the constant, and every
     * extension is then refused rather than silently believed.
     */
    const val TRUSTED_REPOSITORY_ANCHOR: String =
        "b655a474503f4471fdaf6ba35b9385f71d144669f3c28602c5b60b062022c41d"

    /** Verified, loaded sources from the repository, plus the reasons for any that failed. */
    fun load(context: Context, repositoryDir: File, anchor: TrustAnchor = TrustAnchor(TRUSTED_REPOSITORY_ANCHOR)): List<ExtensionLoadOutcome> {
        val client = ShuraRepositoryClient(repositoryDir, ApkSignatureVerifier(context), anchor)
        val loader = ExtensionLoader(context)
        val outcomes = mutableListOf<ExtensionLoadOutcome>()
        for (entry in client.entries()) {
            try {
                val verified = client.verify(entry)
                val named = verified.copy(
                    sourceClassName = loader.resolveSourceClass(verified.metadata),
                )
                val loaded = loader.load(named)
                outcomes += ExtensionLoadOutcome(ExtensionSourceAdapter(loaded), null)
            } catch (e: Throwable) {
                outcomes += ExtensionLoadOutcome(null, "${entry.identity}: ${e.message}")
            }
        }
        return outcomes
    }
}
