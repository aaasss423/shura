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

    fun load(context: Context, repositoryDir: File, policy: TrustPolicy): List<ExtensionOutcome> {
        val outcomes = mutableListOf<ExtensionOutcome>()
        val entries = try {
            ShuraRepositoryClient(repositoryDir, ApkSignatureVerifier(context), policy).entries()
        } catch (e: Exception) {
            // Reading the index is inside this try on purpose.
            //
            // It was not, and a fresh install has no repository directory yet -
            // nothing installs one - so `entries()` threw "repo.json is missing"
            // straight out of `load()` and into composition, taking the whole
            // app down on first launch. A missing or unreadable repository is
            // the normal state before extensions are installed, not an
            // exceptional one, and this class already promises that a failure
            // is always reported rather than dropped.
            return listOf(ExtensionOutcome(null, "repository: ${e.message}"))
        }
        val client = ShuraRepositoryClient(repositoryDir, ApkSignatureVerifier(context), policy)
        val loader = ExtensionLoader(context)
        for (entry in entries.groupBy { it.pkg }.values.map { versions ->
            versions.maxBy { it.indexVersionCode }
        }) {
            try {
                val verified = client.verify(entry)
                val named = verified.copy(sourceClassName = loader.resolveSourceClass(verified.metadata))
                outcomes += ExtensionOutcome(ExtensionSourceAdapter(loader.load(named)), null)
            } catch (e: Exception) {
                outcomes += ExtensionOutcome(null, "${entry.identity}: ${e.message}")
            } catch (e: LinkageError) {
                outcomes += ExtensionOutcome(null, "${entry.identity}: ${e.message}")
            }
        }
        return outcomes
    }
}
