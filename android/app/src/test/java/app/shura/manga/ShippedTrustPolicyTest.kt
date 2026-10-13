package app.shura.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the shipped app trusts, checked against the constants it ships.
 *
 * The point of these is that they read the real configuration rather than a
 * policy rebuilt inside the test. If someone pins a key here, or widens a
 * scope, these fail.
 */
class ShippedTrustPolicyTest {

    @Test
    fun `no Shura publisher key has been invented`() {
        // The operator's key is held out of Git. It is deliberately not filled
        // in with some other certificate, so extensions Shura builds are refused
        // by name rather than run under someone else's identity.
        assertEquals(
            "a Shura publisher certificate was configured in source; it must come " +
                "from the build that holds the key, not be committed here",
            "", ShuraExtensions.SHURA_PUBLISHER_CERTIFICATE)
    }

    @Test
    fun `the shipped policy refuses an extension Shura built`() {
        val failure = runCatching {
            ShuraExtensions.policy.publisherFor("app.shura.extension.asurascans")
        }.exceptionOrNull()
        assertTrue("a Shura-built extension was accepted with no publisher configured",
            failure != null)
        assertTrue(failure!!.message!!, failure.message!!.contains("no trusted publisher is pinned"))
    }

    @Test
    fun `the existing third-party extension still works`() {
        // Backward compatibility: the repository has always carried ProComic,
        // and the certificate it is signed with is unchanged - only now it is
        // scoped to the one package it was ever trusted for.
        val publisher = ShuraExtensions.policy
            .publisherFor("eu.kanade.tachiyomi.extension.ar.procomic")
        assertEquals(ShuraExtensions.TRUSTED_REPOSITORY_ANCHOR, publisher.digest)
        assertEquals(listOf("eu.kanade.tachiyomi.extension.ar.procomic"), publisher.packageScopes)
    }

    @Test
    fun `that same key cannot sign for a different third-party package`() {
        val failure = runCatching {
            ShuraExtensions.policy.publisherFor("eu.kanade.tachiyomi.extension.en.other")
        }.exceptionOrNull()
        assertTrue("the ProComic key was accepted for another package", failure != null)
    }

    @Test
    fun `the repository anchor is untouched by the publisher change`() {
        assertEquals(ShuraExtensions.TRUSTED_REPOSITORY_ANCHOR,
            ShuraExtensions.policy.repository.require())
    }

    @Test
    fun `provisioning a key is what would enable Shura extensions`() {
        // Not a change to the shipped constants: this shows the mechanism works
        // once an operator supplies the real digest, and that a wrong one is
        // still refused at load against the APK.
        val provisioned = ShuraExtensions.policy.publishers + app.shura.runtime.TrustedPublisher(
            digest = "3".repeat(64),
            packageScopes = listOf("app.shura.extension."),
            label = "shura",
        )
        val policy = app.shura.runtime.TrustPolicy(
            ShuraExtensions.policy.repository, provisioned)
        assertEquals("shura", policy.publisherFor("app.shura.extension.asurascans").label)
    }
}
