package app.shura.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Shura's real reader, over an index the real publisher just wrote from a
 * genuinely converted extension.
 *
 * `PublishedIndexContractTest` proves the reader parses the publisher's output
 * by reading a committed fixture. That fixture is one extension, mangadex, so
 * it cannot show whether a *generated* extension's index is readable - and the
 * fields a generated extension and a hand-written one differ on (the surrogate
 * versionCode, the decimal extensionLib) are exactly the fields the reader
 * compares against the artifact.
 *
 * So this reads a live one when the conversion has produced it:
 *
 *     SHURA_LIVE_INDEX=/path/to/repo/index.shura.json \
 *         gradle :shura-extension-runtime:test
 *
 * The environment variable is used rather than a system property because Gradle
 * forks the test JVM: a -D on the Gradle command line is set on the daemon and
 * never reaches the test. Without the variable it skips, so it adds no
 * requirement to an ordinary run.
 * It asserts nothing about *which* extension it finds: the point is that
 * whatever the converter produced is something the app's own parser accepts.
 */
class GeneratedExtensionIndexTest {

    @Test
    fun `the reader parses an index produced from a generated extension`() {
        val path = System.getenv("SHURA_LIVE_INDEX") ?: System.getProperty("shura.live.index")
        assumeTrue("no live index was supplied", !path.isNullOrBlank())
        val index = File(path)
        assertTrue("$path does not exist", index.isFile)

        val entries = RepositoryIndex.parse(index.readText())
        assertTrue("the published index has no packages", entries.isNotEmpty())

        for (entry in entries) {
            // Everything ShuraRepositoryClient.verify compares against the
            // artifact it downloaded. An empty value here would make the
            // comparison vacuous rather than passing.
            assertEquals("identity is empty", "${entry.pkg}|${entry.version}", entry.identity)
            assertEquals("artifact digest is empty", 64, entry.artifactSha256.length)
            assertEquals("signing digest is empty", 64, entry.signingCertificateSha256.length)
            assertTrue("extensionLib was not published",
                entry.indexExtensionLib?.isNotBlank() == true)
            assertTrue("versionCode looks like the reader's default",
                entry.indexVersionCode > 0)
        }
    }
}