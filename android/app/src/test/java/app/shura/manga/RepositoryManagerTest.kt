package app.shura.manga

import app.shura.runtime.TrustAnchor
import app.shura.runtime.TrustPolicy
import app.shura.runtime.TrustedPublisher
import app.shura.manga.ui.Strings
import app.shura.manga.ui.theme.UiLanguage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Adding a repository, against a real HTTP server serving the real files.
 *
 * There is no mock here and no fake index: a socket is opened on the loopback
 * interface, the repository this project actually publishes is served off it byte
 * for byte, and the bytes are put through the same [RepositoryManager] the
 * Repositories screen calls. A synthetic fixture would pass while the published
 * format failed, which is the failure this whole flow had - the machinery was
 * complete and unreachable, and nothing ever read a repository this project had
 * actually written.
 *
 * Two repositories are served, because there are two honest answers:
 *
 *  - the copy this build pins ([SHIPPED]), whose anchor is the build constant, so
 *    it is accepted, listed and persisted;
 *  - the copy the publisher writes ([PUBLISHED]), whose `repo.json` declares a
 *    different certificate, so it is REFUSED - not added, not half added, and
 *    the previous repository it replaced is still the one on file.
 *
 * Nothing here trusts anything: the anchor is [ShuraExtensions.TRUSTED_REPOSITORY_ANCHOR],
 * a build constant, and it is compared against what the repository declares about
 * itself.
 */
class RepositoryManagerTest {

    @get:Rule val folder = TemporaryFolder()

    /** The repository this build ships, and therefore the one it trusts. */
    private val shipped = File("src/main/assets/repository")

    /** A deliberately mismatched repository: the build pins one anchor, and the
     *  test repo must be different so the refusal branch is exercised without
     *  depending on a separate checkout being present.
     */
    private val foreignAnchor = "f".repeat(64)

    private var server: LoopbackRepositoryServer? = null

    @Before
    fun setUp() {
        assumeTrue("this build ships no repository at $shipped", File(shipped, "repo.json").isFile)
        assumeTrue("this build ships no index", File(shipped, "index.shura.json").isFile)
    }

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun policy() = TrustPolicy(
        TrustAnchor(ShuraExtensions.TRUSTED_REPOSITORY_ANCHOR),
        listOf(
            TrustedPublisher(
                ShuraExtensions.TRUSTED_REPOSITORY_ANCHOR,
                listOf("eu.kanade.tachiyomi.extension."),
                "shura-repo",
            ),
        ),
    )

    private fun manager(allowCleartext: Boolean = true) = RepositoryManager(
        registry = RepositoryRegistry(folder.newFolder("repos")),
        policy = policy(),
        allowCleartext = allowCleartext,
        now = { 1_700_000_000_000L },
    )

    // -- the address, before anything is fetched ---------------------------

    @Test
    fun `an address is validated before a socket is opened`() {
        assertEquals(
            AddressProblem.EMPTY,
            (RepositoryAddress.parse("  ") as RepositoryAddress.Result.Failure).problem,
        )
        assertEquals(
            AddressProblem.MALFORMED,
            (RepositoryAddress.parse("not a url") as RepositoryAddress.Result.Failure).problem,
        )
        assertEquals(
            AddressProblem.MALFORMED,
            (RepositoryAddress.parse("ftp://example.org/repo") as RepositoryAddress.Result.Failure).problem,
        )
        assertEquals(
            AddressProblem.HAS_USER_INFO,
            (RepositoryAddress.parse("https://me@example.org/repo") as RepositoryAddress.Result.Failure).problem,
        )
        assertEquals(
            AddressProblem.HAS_QUERY,
            (RepositoryAddress.parse("https://example.org/repo?x=1") as RepositoryAddress.Result.Failure).problem,
        )
        // Plain HTTP is refused for a real host even when the build allows
        // cleartext, and refused with its own reason so the row can say it.
        assertEquals(
            AddressProblem.NOT_HTTPS,
            (RepositoryAddress.parse("http://example.org/repo", allowCleartext = true)
                as RepositoryAddress.Result.Failure).problem,
        )
        assertEquals(
            AddressProblem.NOT_HTTPS,
            (RepositoryAddress.parse("http://example.org/repo", allowCleartext = false)
                as RepositoryAddress.Result.Failure).problem,
        )
    }

    @Test
    fun `a loopback address is accepted only when the build allows cleartext`() {
        assertTrue(RepositoryAddress.isValid("http://127.0.0.1:8080/repo", allowCleartext = true))
        assertFalse(RepositoryAddress.isValid("http://127.0.0.1:8080/repo", allowCleartext = false))
        assertTrue(RepositoryAddress.isValid("https://example.org/repo"))
    }

    @Test
    fun `the directory name is derived from the address so two paths cannot collide`() {
        fun idOf(raw: String) =
            (RepositoryAddress.parse(raw, allowCleartext = true) as RepositoryAddress.Result.Success)
                .address.id
        val one = idOf("https://example.org/a")
        val two = idOf("https://example.org/b")
        assertEquals("two repositories shared one directory", one, idOf("https://example.org/a/"))
        assertFalse("two paths on one host collided", one == two)
    }

    // -- adding the repository this build trusts ---------------------------

    @Test
    fun `a repository that passes the anchor check is fetched, listed and persisted`() {
        val serving = serve(shipped)
        val manager = manager()

        val outcome = manager.add(serving.base)

        val registered = outcome as? RepositoryOutcome.Registered
            ?: error("expected a registration, got $outcome")
        assertTrue("the repository was not reported as added", registered.added)
        val record = registered.record
        assertEquals(RepositoryStatus.OK, record.status)
        assertNull(record.refusal)
        assertTrue("nothing was listed: ${record.packages}", record.packages > 0)
        assertTrue("the name was not taken from repo.json", record.name.isNotBlank())
        assertEquals(1_700_000_000_000L, record.checkedAt)

        // The two metadata files landed in the repository's own directory.
        val directory = manager.registry.directoryOf(record.id)
        assertTrue(File(directory, "repo.json").isFile)
        assertTrue(File(directory, "index.shura.json").isFile)
        // And nothing else was: no artifact is fetched before it is asked for.
        assertFalse("artifacts were fetched up front", File(directory, "apk").isDirectory)

        // Every package in the index is accounted for, and the index this build
        // ships is the one that was read.
        val index = File(directory, "index.shura.json").readText()
        val shippedIndex = File(shipped, "index.shura.json").readText()
        assertEquals("the fetched index is not the published one", shippedIndex, index)

        // The list of repositories is a file, so it survives the app being
        // closed: a fresh registry over the same directory reads it back.
        val reopened = RepositoryRegistry(manager.registry.root)
        val reread = reopened.records().single()
        assertEquals(record.id, reread.id)
        assertEquals(record.url, reread.url)
        assertEquals(record.packages, reread.packages)
        assertEquals(RepositoryStatus.OK, reread.status)
        assertEquals(record.checkedAt, reread.checkedAt)
        assertEquals(listOf(directory.canonicalPath), reopened.roots().map { it.canonicalPath })
    }

    @Test
    fun `the same address twice is one repository, fetched again`() {
        val serving = serve(shipped)
        val manager = manager()

        val first = manager.add(serving.base) as RepositoryOutcome.Registered
        val second = manager.add(serving.base) as RepositoryOutcome.Registered

        assertTrue("the first add was not new", first.added)
        assertFalse("a second add claimed a new repository", second.added)
        assertEquals(1, manager.list().size)
    }

    private fun foreignRepository(): File {
        val root = folder.newFolder("foreign-repo")
        val index = JSONObject()
            .put("packages", JSONArray().put(
                JSONObject()
                    .put("pkg", "eu.kanade.tachiyomi.extension.ar.anyonemanga")
                    .put("version", "1.0")
                    .put("lang", "ar")
                    .put("identity", "eu.kanade.tachiyomi.extension.ar.anyonemanga|1.0")
                    .put("apk", "sample.apk")
                    .put("artifact_sha256", "a".repeat(64))
                    .put("signing_certificate_sha256", foreignAnchor)
                    .put("index_version_code", 1)
                    .put("index_extension_lib", "1.0"),
            ))
        val repo = JSONObject()
            .put("meta", JSONObject().put("name", "foreign").put("signingKeyFingerprint", foreignAnchor))
        File(root, "apk").mkdirs()
        File(root, "apk/sample.apk").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "index.shura.json").writeText(index.toString())
        File(root, "repo.json").writeText(repo.toString())
        return root
    }

    @Test
    fun `a repository that fails the anchor check is refused and never added`() {
        val serving = serve(foreignRepository())
        val manager = manager()

        val outcome = manager.add(serving.base)

        val refused = outcome as? RepositoryOutcome.Refused
            ?: error("a repository this build does not pin was registered: $outcome")
        assertEquals(RepositoryRefusal.ANCHOR, refused.refusal)
        assertTrue(
            "the refusal did not say what the anchor was: ${refused.detail}",
            refused.detail.contains("anchor mismatch"),
        )
        // Refused means refused: no record, no directory, no row.
        assertTrue("a refused repository was registered", manager.list().isEmpty())
        assertFalse(
            "a refused repository left files behind",
            manager.registry.directoryOf(
                (RepositoryAddress.parse(serving.base, allowCleartext = true)
                    as RepositoryAddress.Result.Success).address.id,
            ).exists(),
        )
    }

    @Test
    fun `a repository that fails the anchor check does not replace a working one`() {
        val trusted = serve(shipped)
        val manager = manager()
        val good = manager.add(trusted.base) as RepositoryOutcome.Registered

        val other = serve(foreignRepository())

        val refused = manager.add(other.base) as? RepositoryOutcome.Refused
        assertNotNull("a refused repository was registered", refused)

        // The record that was working before is still the one on file, still
        // OK, and its files are still there.
        val still = manager.list().single()
        assertEquals(good.record.id, still.id)
        assertEquals(RepositoryStatus.OK, still.status)
        assertTrue(File(manager.registry.directoryOf(still.id), "index.shura.json").isFile)
    }

    // -- honest failures ----------------------------------------------------

    @Test
    fun `an address that serves nothing is refused with its own reason`() {
        // A loopback port with nothing on it: the "server is down" case.
        val closed = ServerSocket(0).use { it.localPort }
        val manager = manager()

        val refused = manager.add("http://127.0.0.1:$closed") as? RepositoryOutcome.Refused
        assertNotNull("an unreachable repository was registered", refused)
        assertEquals(RepositoryRefusal.NETWORK, refused!!.refusal)
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `an address that serves something else is refused`() {
        // The bytes arrive and they are not what the button promised: this is an
        // index this build cannot read, not an address that could not be reached.
        val serving = serve(shipped, routes = mapOf("repo.json" to ("not json at all" to "text/plain")))
        val manager = manager()

        val refused = manager.add(serving.base) as? RepositoryOutcome.Refused
        assertNotNull("a repository that is not a repository was registered", refused)
        assertEquals(RepositoryRefusal.UNSUPPORTED_INDEX, refused!!.refusal)
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `a repository with no index is refused and says so`() {
        val serving = serve(
            shipped,
            routes = mapOf(
                "repo.json" to (File(shipped, "repo.json").readText() to Mime.JSON),
                "index.shura.json" to ("""{"packages":[]}""" to Mime.JSON),
            ),
        )
        val manager = manager()

        val refused = manager.add(serving.base) as? RepositoryOutcome.Refused
        assertNotNull("a repository with no extensions was registered", refused)
        assertEquals(RepositoryRefusal.EMPTY_INDEX, refused!!.refusal)
    }

    // -- refreshing and removing --------------------------------------------

    @Test
    fun `a refresh re-reads the same repository`() {
        val serving = serve(shipped)
        val manager = manager()
        val added = manager.add(serving.base) as RepositoryOutcome.Registered

        val again = manager.update(added.record.id)

        assertTrue(again is RepositoryOutcome.Registered)
        assertFalse("a refresh claimed a new repository", (again as RepositoryOutcome.Registered).added)
        assertEquals(added.record.id, again.record.id)
        assertEquals(added.record.packages, again.record.packages)
    }

    @Test
    fun `refreshing a repository that cannot be reached keeps what was already there`() {
        val serving = serve(shipped)
        val manager = manager()
        val added = manager.add(serving.base) as RepositoryOutcome.Registered
        val port = serving.port
        serving.stop()

        val refused = manager.update(added.record.id) as? RepositoryOutcome.Refused
        assertNotNull(refused)
        // The files the previous successful fetch left are still offered, and the
        // row says the last check failed rather than implying the count is fresh.
        val record = manager.list().single()
        assertEquals(RepositoryStatus.FAILED, record.status)
        assertEquals(added.record.packages, record.packages)
        assertTrue(File(manager.registry.directoryOf(record.id), "index.shura.json").isFile)
        assertTrue(port > 0)
    }

    @Test
    fun `removing a repository takes its row and its files`() {
        val serving = serve(shipped)
        val manager = manager()
        val added = manager.add(serving.base) as RepositoryOutcome.Registered
        val directory = manager.registry.directoryOf(added.record.id)
        assertTrue(directory.isDirectory)

        assertTrue("the removal reported failure", manager.remove(added.record.id))

        assertTrue("the row survived", manager.list().isEmpty())
        assertFalse("the files survived", directory.exists())
        assertTrue(
            "the repository is still offered",
            RepositoryRegistry(manager.registry.root).roots().isEmpty(),
        )
    }

    @Test
    fun `removing a repository that was never added does not claim a removal`() {
        assertFalse(manager().remove("nothing-registered-here"))
    }

    // -- what the row says --------------------------------------------------

    @Test
    fun `every refusal has a sentence in Arabic and in English`() {
        for (language in UiLanguage.entries) {
            RepositoryRefusal.entries.forEach { refusal ->
                val text = RepositoryStatusText.refusal(refusal, language)
                assertTrue("${refusal.name} says nothing in ${language.name}", text.isNotBlank())
                assertFalse(
                    "${refusal.name} renders as its key in ${language.name}",
                    text.startsWith("repos.refusal."),
                )
            }
        }
    }

    @Test
    fun `what an add says is the repository's own name and its package count`() {
        val serving = serve(shipped)
        val manager = manager()
        val outcome = manager.add(serving.base)
        val added = outcome as RepositoryOutcome.Registered

        val arabic = RepositoryStatusText.notice(outcome, UiLanguage.ARABIC)
        val english = RepositoryStatusText.notice(outcome, UiLanguage.ENGLISH)
        assertTrue("the Arabic notice dropped the count: $arabic", arabic.contains(added.record.packages.toString()))
        assertTrue("the Arabic notice dropped the name: $arabic", arabic.contains(added.record.name))
        assertTrue("the English notice dropped the count: $english", english.contains(added.record.packages.toString()))

        val refused = RepositoryManager(
            RepositoryRegistry(folder.newFolder("r2")), policy(), allowCleartext = true,
        ).add("http://example.org/repo") as RepositoryOutcome.Refused
        for (language in UiLanguage.entries) {
            val text = RepositoryStatusText.notice(refused, language)
            assertFalse("a refusal rendered as its key: $text", text.startsWith("repos."))
            assertTrue("a refusal said nothing: $text", text.isNotBlank())
        }
    }

    @Test
    fun `a row never shows the runtimes own English sentence`() {
        // The refusal line is the localised sentence; the runtime's message is
        // kept on the record for diagnosis, because an Arabic interface with one
        // English diagnostic in it is the same defect as a hardcoded string.
        val record = RepositoryRecord(
            id = "r", url = "https://example.org", name = "Example",
            status = RepositoryStatus.FAILED,
            refusal = RepositoryRefusal.ANCHOR,
            detail = "repository anchor mismatch: this build trusts b655...",
        )
        for (language in UiLanguage.entries) {
            val meta = RepositoryStatusText.meta(record, language)
            assertFalse("the runtime's sentence reached the row: $meta", meta.contains("anchor mismatch"))
            assertTrue("the row says nothing about the refusal", meta.isNotBlank())
        }
    }

    @Test
    fun `a row states its status, its count and when it was checked`() {
        val record = RepositoryRecord(
            id = "r", url = "https://example.org", name = "Example",
            status = RepositoryStatus.OK, checkedAt = 1_700_000_000_000L, packages = 72,
        )
        for (language in UiLanguage.entries) {
            val meta = RepositoryStatusText.meta(record, language)
            assertTrue("no count in the meta line: $meta", meta.contains("72"))
            assertFalse("the status was not a word: $meta", meta.startsWith("repos."))
        }
        assertEquals("72 extensions", RepositoryStatusText.packages(record, UiLanguage.ENGLISH))
    }

    @Test
    fun `the list survives the manager being rebuilt from scratch`() {
        val serving = serve(shipped)
        val root = folder.newFolder("persisted")
        val first = RepositoryManager(RepositoryRegistry(root), policy(), allowCleartext = true)
        val added = first.add(serving.base) as RepositoryOutcome.Registered

        // A different manager, the same directory: what a cold start sees.
        val second = RepositoryManager(RepositoryRegistry(root), policy(), allowCleartext = true)
        val record = second.list().single()
        assertEquals(added.record.id, record.id)
        assertEquals(added.record.url, record.url)
        assertEquals(added.record.packages, record.packages)
        // And its bytes are still where the catalogue will look for them: the
        // store reads repositories by directory, and this is one the registry
        // still names.
        val directory = second.registry.directoryOf(record.id)
        assertTrue(File(directory, ExtensionCatalog.INDEX_FILE).isFile)
        assertTrue(File(directory, "repo.json").isFile)
        assertEquals(listOf(directory), second.registry.roots())
    }

    // -- the loopback server ------------------------------------------------

    private fun serve(root: File, routes: Map<String, Pair<String, String>> = emptyMap()) =
        LoopbackRepositoryServer(root, routes).also { server = it }

    /**
     * A repository, served over HTTP on the loopback interface.
     *
     * Written out rather than pulled in as a dependency so the test is exactly
     * the thing it says it is: a socket, a request line, and the bytes of the
     * files this project publishes. `com.sun.net.httpserver` is not on Android's
     * classpath, and a hand-written one is small enough to read.
     */
    private class LoopbackRepositoryServer(
        private val root: File,
        private val overrides: Map<String, Pair<String, String>> = emptyMap(),
    ) {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val threads = mutableListOf<Thread>()

        val port: Int get() = socket.localPort
        val base: String get() = "http://127.0.0.1:$port"

        init {
            val worker = Thread {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        return@Thread
                    }
                    val thread = Thread { serveOne(client) }
                    threads += thread
                    thread.start()
                }
            }
            worker.isDaemon = true
            worker.start()
        }

        fun stop() {
            runCatching { socket.close() }
            threads.forEach { it.interrupt() }
        }

        private fun serveOne(client: Socket) = client.use { open ->
            val input = open.getInputStream().bufferedReader()
            // "GET /repo.json HTTP/1.1" -> the path, and nothing else.
            val target = input.readLine()?.split(" ")?.getOrNull(1) ?: return
            val request = target.removePrefix("/")
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }
            val override = overrides[request]
            val file = File(root, request)
            val body = when {
                override != null -> override.first
                file.isFile -> file.readText()
                else -> null
            }
            val mime = override?.second ?: Mime.JSON
            val payload = (body ?: "").toByteArray(Charsets.UTF_8)
            if (body == null) {
                write(open.outputStream, "HTTP/1.1 404 Not Found", "text/plain", "not found".toByteArray())
                return
            }
            write(
                open.outputStream,
                "HTTP/1.1 200 OK",
                mime,
                payload,
                "Content-Length: ${payload.size}\r\n",
            )
        }

        private fun write(out: OutputStream, status: String, type: String, body: ByteArray, extra: String = "") {
            out.write(
                (status + "\r\n" +
                    "Content-Type: $type; charset=utf-8\r\n" +
                    extra +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            out.write(body)
            out.flush()
        }
    }

    private object Mime {
        const val JSON = "application/json"
    }
}
