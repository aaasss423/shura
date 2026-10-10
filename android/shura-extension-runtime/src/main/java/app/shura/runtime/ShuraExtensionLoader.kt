package app.shura.runtime

import android.content.Context
import app.shura.source.ShuraSource
import java.io.File

/** A loaded extension, or the reason it did not load. */
data class ExtensionOutcome(val source: ShuraSource?, val error: String?)

/**
 * Brings the extensions Shura Core publishes into a source list.
 *
 * Shura's own repository is the only source of extensions. Nothing falls back to
 * any other repository, and none is configured.
 *
 * A failure is always reported rather than dropped: a source that silently
 * disappears is indistinguishable from one that was never installed. Callers
 * register the results alongside their built-in sources, so a broken extension
 * can only ever add a message -- never remove anything else.
 */
object ShuraExtensionLoader {

    fun load(context: Context, repositoryDir: File, anchor: TrustAnchor): List<ExtensionOutcome> {
        val client = ShuraRepositoryClient(repositoryDir, ApkSignatureVerifier(context), anchor)
        val loader = ExtensionLoader(context)
        val outcomes = mutableListOf<ExtensionOutcome>()
        for (entry in client.entries()) {
            try {
                val verified = client.verify(entry)
                val named = verified.copy(sourceClassName = loader.resolveSourceClass(verified.metadata))
                outcomes += ExtensionOutcome(ExtensionSourceAdapter(loader.load(named)), null)
            } catch (e: Throwable) {
                outcomes += ExtensionOutcome(null, "${entry.identity}: ${e.message}")
            }
        }
        return outcomes
    }
}
