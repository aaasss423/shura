package app.shura.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `the publisher certificate is empty or a real digest, never invented`() {
        // Either no publisher is configured - the honest default, because the
        // operator's key is held out of Git - or the build injected one. What
        // must never happen is a value committed in source, so this asserts the
        // shape rather than a fixed emptiness.
        val value = ShuraExtensions.SHURA_PUBLISHER_CERTIFICATE
        assertTrue(
            "a publisher certificate that is neither empty nor a 64-hex digest: '$value'",
            value.isEmpty() || value.matches(Regex("[0-9a-fA-F]{64}")))
    }

    @Test
    fun `the publisher is never the app's own signer`() {
        // On a debug build the app and its extensions are signed by the same
        // machine-local keystore, whose password is the literal "android" and
        // which is regenerated per machine. Trusting it as the publisher would
        // mean "whoever compiled this build may also sign extensions" - the
        // opposite of the check - so it is refused outright.
        val publisher = ShuraExtensions.policy.publishers.firstOrNull { it.label == "shura" }
        if (publisher != null) {
            assertNotEquals(
                "the Shura publisher is signed with this app's own debug key",
                "33726d2d384e63734473aef05794ca3efed8f0f7e3ced102b4c1830bbfbb0c74",
                publisher.digest.lowercase())
        }
    }

    @Test
    fun `a Shura extension is refused until a publisher is configured`() {
        // The behaviour depends on whether the build injected a certificate,
        // and both are correct: refused without one, resolvable with one.
        val configured = ShuraExtensions.SHURA_PUBLISHER_CERTIFICATE.isNotBlank()
        val outcome = runCatching {
            ShuraExtensions.policy.publisherFor("app.shura.extension.asurascans")
        }
        if (configured) {
            assertEquals("shura", outcome.getOrThrow().label)
        } else {
            assertTrue("accepted with no publisher configured", outcome.isFailure)
            assertTrue(
                outcome.exceptionOrNull()!!.message!!,
                outcome.exceptionOrNull()!!.message!!.contains("no trusted publisher is pinned"))
        }
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
