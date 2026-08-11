package com.noscroll.domain.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The rule-update path is a place where getting verification wrong would be worse than having no
 * update channel at all, so every rejection case has a test: a bad signature, a truncated download,
 * an expired set, a downgrade, and an unsupported format.
 */
class RuleUpdateTest {

    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val publicKeyBase64: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    private val verifier = RuleUpdateVerifier(publicKeyBase64)

    private val now = 1_770_000_000_000L
    private val farFuture = 4_102_444_800_000L

    private fun ruleSetText(
        version: String = "2026.07.01",
        domains: List<String> = listOf("adult.example", "explicit.example"),
        count: Int = domains.size,
        expires: Long = farFuture,
        format: Int = 1,
    ): String = buildString {
        appendLine("# test rule set")
        appendLine("format=$format")
        appendLine("version=$version")
        appendLine("issued=$now")
        appendLine("expires=$expires")
        appendLine("count=$count")
        appendLine("--")
        domains.forEach { appendLine(it) }
    }

    private fun sign(payload: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(payload)
        }.sign()
        return Base64.getEncoder().encodeToString(signature)
    }

    private fun rejectionOf(result: Result<*>): RuleRejection =
        (result.exceptionOrNull() as RuleRejectedException).rejection

    // ---- Parsing --------------------------------------------------------------------------

    @Test
    fun `a well formed rule set parses`() {
        val parsed = RuleSetParser.parse(ruleSetText()).getOrThrow()
        assertEquals("2026.07.01", parsed.manifest.version)
        assertEquals(2, parsed.domains.size)
        assertTrue(parsed.domains.contains("adult.example"))
    }

    @Test
    fun `a truncated download is caught by the declared count`() {
        // The commonest real failure: a short read looks exactly like a shorter list.
        val truncated = ruleSetText(domains = listOf("adult.example"), count = 2)
        assertEquals(RuleRejection.COUNT_MISMATCH, rejectionOf(RuleSetParser.parse(truncated)))
    }

    @Test
    fun `an unsupported format is rejected`() {
        assertEquals(RuleRejection.UNSUPPORTED_FORMAT, rejectionOf(RuleSetParser.parse(ruleSetText(format = 99))))
    }

    @Test
    fun `a missing separator or header field is rejected`() {
        assertEquals(RuleRejection.MALFORMED, rejectionOf(RuleSetParser.parse("format=1\nversion=1\n")))
        assertEquals(RuleRejection.MALFORMED, rejectionOf(RuleSetParser.parse("format=1\n--\nadult.example\n")))
    }

    @Test
    fun `an empty rule set is rejected`() {
        assertEquals(RuleRejection.EMPTY, rejectionOf(RuleSetParser.parse(ruleSetText(domains = emptyList(), count = 0))))
    }

    @Test
    fun `unparseable entries are dropped without failing the whole set`() {
        // One bad line should not cost the user every other rule; the count reflects what survives.
        val text = ruleSetText(domains = listOf("adult.example", "not a domain", "explicit.example"), count = 2)
        val parsed = RuleSetParser.parse(text).getOrThrow()
        assertEquals(2, parsed.domains.size)
    }

    // ---- Signature verification -----------------------------------------------------------

    @Test
    fun `a correctly signed update verifies`() {
        val payload = ruleSetText().toByteArray()
        val result = verifier.verify(payload, sign(payload), currentVersion = "2026.01.01", nowMillis = now)
        assertTrue(result.isSuccess)
        assertEquals("2026.07.01", result.getOrThrow().manifest.version)
    }

    @Test
    fun `a tampered payload fails verification`() {
        val payload = ruleSetText().toByteArray()
        val signature = sign(payload)
        val tampered = ruleSetText(domains = listOf("adult.example", "injected.example")).toByteArray()

        assertEquals(
            RuleRejection.BAD_SIGNATURE,
            rejectionOf(verifier.verify(tampered, signature, "2026.01.01", now)),
        )
    }

    @Test
    fun `an unsigned or garbage signature fails`() {
        val payload = ruleSetText().toByteArray()
        assertEquals(RuleRejection.BAD_SIGNATURE, rejectionOf(verifier.verify(payload, "", "2026.01.01", now)))
        assertEquals(RuleRejection.BAD_SIGNATURE, rejectionOf(verifier.verify(payload, "bm90LWEtc2ln", "2026.01.01", now)))
    }

    @Test
    fun `a signature from the wrong key fails`() {
        val otherKey = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val payload = ruleSetText().toByteArray()
        val foreignSignature = Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(otherKey.private)
                update(payload)
            }.sign()
        )
        assertEquals(
            RuleRejection.BAD_SIGNATURE,
            rejectionOf(verifier.verify(payload, foreignSignature, "2026.01.01", now)),
        )
    }

    @Test
    fun `an expired rule set is rejected`() {
        val payload = ruleSetText(expires = now - 1).toByteArray()
        assertEquals(
            RuleRejection.EXPIRED,
            rejectionOf(verifier.verify(payload, sign(payload), "2026.01.01", now)),
        )
    }

    @Test
    fun `a downgrade is refused`() {
        // Replaying an old, signed rule set must not remove newer rules.
        val payload = ruleSetText(version = "2025.01.01").toByteArray()
        assertEquals(
            RuleRejection.NOT_NEWER,
            rejectionOf(verifier.verify(payload, sign(payload), currentVersion = "2026.06.01", nowMillis = now)),
        )
    }

    @Test
    fun `an identical version is refused`() {
        val payload = ruleSetText(version = "2026.06.01").toByteArray()
        assertEquals(
            RuleRejection.NOT_NEWER,
            rejectionOf(verifier.verify(payload, sign(payload), "2026.06.01", now)),
        )
    }

    @Test
    fun `a first install with no current version accepts any valid set`() {
        val payload = ruleSetText().toByteArray()
        assertTrue(verifier.verify(payload, sign(payload), currentVersion = null, nowMillis = now).isSuccess)
    }

    @Test
    fun `version comparison is component wise, not lexical`() {
        // "2026.10.01" must be newer than "2026.9.01", which a string comparison gets wrong.
        val payload = ruleSetText(version = "2026.10.01").toByteArray()
        assertTrue(verifier.verify(payload, sign(payload), "2026.9.01", now).isSuccess)
    }

    /**
     * The bundled list is the offline baseline, and it has to parse. If this fails, the shipped APK
     * would start with no rules at all.
     */
    @Test
    fun `the bundled rule format used by the app is valid`() {
        val bundled = ruleSetText(
            version = "1.0.0",
            domains = listOf("adult.example", "explicit.example", "another.example"),
            count = 3,
        )
        val parsed = RuleSetParser.parse(bundled).getOrThrow()
        assertEquals(3, parsed.domains.size)
        assertEquals(RuleSetParser.SUPPORTED_FORMAT, parsed.manifest.formatVersion)
    }
}
