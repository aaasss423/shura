package app.shura.manga

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.shura.host.SourceHost
import app.shura.runtime.InstallReport
import app.shura.runtime.ShuraRepositoryInstaller
import java.io.File

/**
 * Getting a repository from somewhere onto the device.
 *
 * WHY THIS FILE EXISTS
 *
 * The loader read `repo.json`, `index.shura.json` and its artifacts out of
 * `filesDir/repo`, and nothing in the app ever wrote there. There was no
 * download step, no install step and no way to point the app at a repository,
 * so the only reachable state was an empty directory - and
 * `ShuraExtensionLoader.load()` threw out of composition when it found one,
 * taking the app down on first launch rather than showing a message.
 *
 * WHAT IS AND IS NOT DECIDED HERE
 *
 * Installing decides nothing about trust. It copies files. Whether they are
 * believed is settled afterwards, by the same code as before, against the
 * anchor pinned in the build: a repository whose key does not match is refused
 * at load whatever was copied in.
 *
 * The copy runs through the storage access framework, so no storage permission
 * is added and the app keeps `usesCleartextTraffic="false"`. Nothing here
 * fetches anything over the network.
 */
object ShuraRepository {

    /** Where the loader reads from. */
    fun directory(context: Context): File = File(context.filesDir, "repo")

    /**
     * Where repositories added by URL live.
     *
     * One directory per repository under this root, named by that repository's
     * address. Deliberately not [directory]: the loader reads that one as a whole
     * and the installer replaces it whole, so a second repository merged into it
     * could never be removed without taking the first one - and a row that
     * disappears while its extensions are still installed is the thing this
     * layout exists to make impossible.
     */
    fun remoteRoot(context: Context): File = File(context.filesDir, "repos")

    /** The list of registered repositories, on disk. */
    fun registry(context: Context): RepositoryRegistry = RepositoryRegistry(remoteRoot(context))

    /** Adds, refreshes and removes repositories added by URL. */
    fun manager(context: Context): RepositoryManager = RepositoryManager(registry(context), ShuraExtensions.policy)

    /** Every registered repository as the catalogue reads one. */
    fun remoteSources(context: Context): List<CatalogRepository> {
        val registry = registry(context)
        return registry.records().mapNotNull { record ->
            val directory = registry.directoryOf(record.id)
            if (!directory.isDirectory) null
            else CatalogRepository(directory, RepositoryOrigin(record.id, record.url, directory))
        }
    }

    /**
     * Every directory extensions are loaded from: the shipped repository, then
     * each registered one, in the order they were added.
     */
    fun allDirectories(context: Context): List<File> =
        listOf(directory(context)) + remoteSources(context).map { it.directory }

    /**
     * Copies the repository the user picked into the directory the loader
     * reads, and reports what happened.
     */
    fun installFromTree(context: Context, tree: Uri): InstallReport {
        val staged = File(context.cacheDir, "repository-pick")
        staged.deleteRecursively()
        if (!copyTree(context, tree, staged)) {
            staged.deleteRecursively()
            return InstallReport(emptyList(), "that folder could not be read")
        }
        return try {
            ShuraRepositoryInstaller.install(staged, directory(context))
        } finally {
            staged.deleteRecursively()
        }
    }

    /**
     * Replaces the registered sources with the ones the repositories now yield.
     *
     * Extensions are removed as well as added, so uninstalling a repository
     * actually takes its sources off the list instead of leaving the app
     * showing whatever happened to be there when it started.
     *
     * Every registered repository is read, not only the bundled one: a
     * repository added by URL has its own directory and its own index, and
     * loading only `filesDir/repo` made adding one a thing that produced a row
     * and no sources at all.
     *
     * Returns the failures, if any, to show to the user. It returns rather than
     * throws: a repository that is absent or refused is a state to report, not
     * a reason to stop.
     */
    fun reload(host: SourceHost, context: Context): List<String> {
        val previously = host.all().map { it.id }.toSet()
        val outcomes = allDirectories(context).flatMap { dir ->
            ShuraExtensions.load(context, dir)
        }
        for (id in previously) {
            host.remove(id)
        }
        for (outcome in outcomes) {
            outcome.source?.let { host.register(it) }
        }
        return outcomes.mapNotNull { it.error }
    }

    // -- the storage access framework copy --------------------------------

    /**
     * Copies a picked folder into [into].
     *
     * Handled with `DocumentsContract` rather than `DocumentFile` so the app
     * takes no new dependency for this, and so the copy is explicit about what
     * it reads: the two metadata files and the `apk` folder, by name.
     */
    private fun copyTree(context: Context, tree: Uri, into: File): Boolean {
        return try {
            into.mkdirs()
            copyChildren(context, tree, into, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyChildren(context: Context, parent: Uri, into: File, depth: Int): Boolean {
        if (depth > 2) return true
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent, DocumentsContract.getTreeDocumentId(parent))
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn) ?: continue
                val mime = cursor.getString(mimeColumn) ?: continue
                // Nothing above the artifacts is carried across. A picked
                // folder holding anything else is not a repository, and copying
                // it would put unreviewed files where the loader looks.
                if (depth == 0 && name != "repo.json" && name != "index.shura.json" && name != "apk") {
                    continue
                }
                val uri = DocumentsContract.buildDocumentUriUsingTree(parent, documentId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    File(into, name).mkdirs()
                    copyChildren(context, uri, File(into, name), depth + 1)
                } else {
                    val target = File(into, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        return true
    }
}