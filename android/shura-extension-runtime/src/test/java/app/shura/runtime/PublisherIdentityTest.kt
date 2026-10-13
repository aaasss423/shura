package app.shura.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is allowed to sign for what.
 *
 * The build pinned one certificate, and it was the one signing ProComic - a
 * third party's extension. So "Shura's publisher" was "whoever published
 * ProComic", and an extension Shura built could never have loaded: it cannot be
 * signed with a third party's private key.
 *
 * These are the rules that replace that, and the refusals they are supposed to
 * produce. No APK is involved: what is being decided here is which identity is
 * permitted to speak for a package, and a repository has no say in the answer.
 */
class PublisherIdentityTest {

    private val procomicKey = "b655a474503f4471fdaf6ba35b9385f71d144669f3c28602c5b60b062022c41d"
    private val shuraKey = "3".repeat(64)

    private fun policy(publishers: List<TrustedPublisher>) =
        TrustPolicy(TrustAnchor(procomicKey), publishers)

    // -- scopes ------------------------------------------------------------

    @Test
    fun `a scoped publisher covers its own package and nothing else`() {
        val publisher = TrustedPublisher(procomicKey, listOf("eu.kanade.tachiyomi.extension.ar.procomic"), "procomic")
        assertTrue(publisher.covers("eu.kanade.tachiyomi.extension.ar.procomic"))
        assertTrue(!publisher.covers("app.shura.extension.asurascans"))
        assertTrue(!publisher.covers("eu.kanade.tachiyomi.extension.en.somethingelse"))
        // A prefix scope is a prefix, not a substring match.
        assertTrue(TrustedPublisher(shuraKey, listOf("app.shura.extension."), "shura")
            .covers("app.shura.extension.mangadex"))
        assertTrue(!TrustedPublisher(shuraKey, listOf("app.shura.extension."), "shura")
            .covers("app.shura.extensionevil.mangadex"))
    }

    @Test
    fun `a publisher with no scope is refused at construction`() {
        val failure = runCatching { TrustedPublisher(shuraKey, emptyList()) }.exceptionOrNull()
        assertTrue("an unscoped trusted key was accepted", failure != null)
        assertTrue(failure!!.message!!, failure.message!!.contains("no package scope"))
    }

    // -- the two identities coexist -----------------------------------------

    @Test
    fun `a third-party key cannot sign for a Shura extension`() {
        val policy = policy(listOf(
            TrustedPublisher(procomicKey, listOf("eu.kanade.tachiyomi.extension.ar.procomic"), "procomic"),
            TrustedPublisher(shuraKey, listOf("app.shura.extension."), "shura"),
        ))
        assertEquals("shura", policy.publisherFor("app.shura.extension.asurascans").label)
        assertEquals("procomic",
            policy.publisherFor("eu.kanade.tachiyomi.extension.ar.procomic").label)
    }

    @Test
    fun `a third-party key trusted for one package cannot speak for another`() {
        val policy = policy(listOf(
            TrustedPublisher(procomicKey, listOf("eu.kanade.tachiyomi.extension.ar.procomic"), "procomic"),
        ))
        val failure = runCatching { policy.publisherFor("eu.kanade.tachiyomi.extension.en.other") }.exceptionOrNull()
        assertTrue("a scoped key was accepted for a package outside its scope", failure != null)
        assertTrue(failure!!.message!!, failure.message!!.contains("no trusted publisher is pinned"))
    }

    // -- no silent fallback --------------------------------------------------

    @Test
    fun `a build with no Shura key refuses Shura extensions by name`() {
        // Exactly the state the shipped app is in: the operator's key is not in
        // Git, so the slot is empty and nothing is invented to fill it.
        val policy = policy(listOf(
            TrustedPublisher(procomicKey, listOf("eu.kanade.tachiyomi.extension.ar.procomic"), "procomic"),
        ))
        val failure = runCatching { policy.publisherFor("app.shura.extension.asurascans") }.exceptionOrNull()
        assertTrue("an unconfigured publisher was silently filled in", failure != null)
        val message = failure!!.message!!
        assertTrue(message, message.contains("app.shura.extension.asurascans"))
        assertTrue(message, message.contains("Installing an extension cannot grant this permission"))
    }

    @Test
    fun `a build pinning nobody refuses everything, and says so`() {
        val policy = TrustPolicy(TrustAnchor(procomicKey), emptyList())
        val failure = runCatching { policy.publisherFor("anything.at.all") }.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(failure!!.message!!, failure.message!!.contains("No publisher is pinned at all"))
    }

    @Test
    fun `the refusal names what is pinned so the reason is diagnosable`() {
        val policy = policy(listOf(
            TrustedPublisher(procomicKey, listOf("eu.kanade.tachiyomi.extension.ar.procomic"), "procomic"),
        ))
        val message = runCatching { policy.publisherFor("app.shura.extension.asurascans") }
            .exceptionOrNull()!!.message!!
        assertTrue(message, message.contains("procomic"))
        assertTrue("the pinned publisher should be identifiable",
            message.contains("eu.kanade.tachiyomi.extension.ar.procomic"))
    }

    // -- repository data trust is separate ----------------------------------

    @Test
    fun `the repository anchor is a separate decision from the publishers`() {
        // A repository may declare whatever it likes about its own metadata; the
        // anchor in the build is what decides, and it is not derived from the
        // publisher list.
        val policy = policy(listOf(
            TrustedPublisher(shuraKey, listOf("app.shura.extension."), "shura"),
        ))
        assertEquals(procomicKey, policy.repository.require())
        assertEquals("shura", policy.publisherFor("app.shura.extension.mangadex").label)
    }
}
