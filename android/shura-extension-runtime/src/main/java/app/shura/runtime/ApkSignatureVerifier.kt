package app.shura.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Reads the signing certificate out of the APK file itself.
 *
 * This exists because comparing `signing_certificate_sha256` from the repository
 * against a pinned anchor proves nothing on its own: both are downloaded from the
 * same host as the artifact. The digest that matters is the one Android derives
 * from the APK, `SHA-256(Signature.toByteArray())` over the complete DER
 * certificate -- the same computation `SigningInfo.apkContentsSigners` feeds to
 * any client's trust check.
 *
 * The signature scheme in use is the APK's own; the JAR/v1 block is not assumed
 * to verify (the shipped artifacts report `v1: false` while v2 and v3 verify),
 * and reading through PackageManager honours whichever scheme actually applies.
 *
 * Fail-closed: no signer, several signers when one was expected, or an
 * unreadable archive all refuse.
 */
class ApkSignatureVerifier(private val context: Context) {

    /** All certificates the package declares, in Android's own form. */
    fun certificates(apk: File): List<ByteArray> {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw LoadFailure.NotVerified("${apk.name} is not a readable APK archive")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val legacy = info.signatures
            if (legacy.isNullOrEmpty()) {
                throw LoadFailure.NotVerified("${apk.name} has an empty signer set")
            }
            return legacy.map { it.toByteArray() }
        }
        val signing = info.signingInfo
            ?: throw LoadFailure.NotVerified("${apk.name} carries no signing information")
        val signers = if (signing.hasMultipleSigners()) signing.apkContentsSigners
        else signing.signingCertificateHistory
        if (signers.isNullOrEmpty()) {
            throw LoadFailure.NotVerified("${apk.name} has an empty signer set")
        }
        return signers.map { it.toByteArray() }
    }

    /** SHA-256 of a complete DER certificate, lower-case hex. */
    fun digest(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }

    /**
     * The certificate the APK is signed with.
     *
     * Exactly one is required. A package signed by several keys is refused
     * rather than resolved to "the first" or "any that matches": a repo that
     * can hand out multi-signed artifacts can also hand out an artifact that
     * carries the trusted key alongside a hostile one, and the rotation history
     * has to be checked by the caller that knows the policy.
     */
    fun primaryCertificate(apk: File): Pair<ByteArray, String> {
        val certs = certificates(apk)
        if (certs.size != 1) {
            throw LoadFailure.NotVerified(
                "${apk.name} presents ${certs.size} signing certificates, expected exactly 1"
            )
        }
        return certs.first() to digest(certs.first())
    }

    /**
     * Full check against both the pinned anchor and the published entry pin.
     * Either mismatch refuses; so does anything unreadable.
     */
    fun verify(apk: File, anchor: String, publishedPin: String): String {
        val (_, actual) = primaryCertificate(apk)
        if (!actual.equals(anchor, ignoreCase = true)) {
            throw LoadFailure.NotVerified(
                "${apk.name} is signed by $actual but this build trusts $anchor"
            )
        }
        if (!actual.equals(publishedPin, ignoreCase = true)) {
            throw LoadFailure.NotVerified(
                "${apk.name} is signed by $actual but the repository published $publishedPin"
            )
        }
        return actual
    }
}