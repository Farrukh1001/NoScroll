package com.noscroll.domain.filter

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Header of a signed rule set. The bundled list ships with the APK and always works offline; an
 * update can only ever replace it after its signature verifies.
 */
data class RuleManifest(
    val formatVersion: Int,
    val version: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val declaredCount: Int,
)

data class ParsedRuleSet(val manifest: RuleManifest, val domains: Set<String>)

enum class RuleRejection {
    MALFORMED,
    UNSUPPORTED_FORMAT,
    COUNT_MISMATCH,
    EXPIRED,
    NOT_NEWER,
    BAD_SIGNATURE,
    EMPTY,
    TOO_LARGE,
}

class RuleRejectedException(val rejection: RuleRejection) : Exception(rejection.name)

/**
 * Parser for the rule-set file format.
 *
 * A deliberately boring line format rather than JSON: the bytes that get signed are then exactly the
 * bytes on disk, with no serialiser in the middle that might reorder a field and invalidate a
 * signature. It also parses in pure Kotlin, so signature and rollback behaviour are unit-testable
 * without a device.
 *
 * ```
 * # NoScroll rule set
 * format=1
 * version=2026.06.01
 * issued=1770000000000
 * expires=1780000000000
 * count=2
 * --
 * adult.example
 * more.adult.example
 * ```
 */
object RuleSetParser {

    const val SUPPORTED_FORMAT = 1
    private const val MAX_DOMAINS = 2_000_000

    fun parse(text: String): Result<ParsedRuleSet> {
        val lines = text.lineSequence().iterator()
        val header = mutableMapOf<String, String>()
        var separatorSeen = false

        while (lines.hasNext()) {
            val line = lines.next().trim()
            if (line == "--") { separatorSeen = true; break }
            if (line.isEmpty() || line.startsWith("#")) continue
            val key = line.substringBefore('=', "").trim()
            val value = line.substringAfter('=', "").trim()
            if (key.isEmpty() || value.isEmpty()) return fail(RuleRejection.MALFORMED)
            header[key] = value
        }
        if (!separatorSeen) return fail(RuleRejection.MALFORMED)

        val formatVersion = header["format"]?.toIntOrNull() ?: return fail(RuleRejection.MALFORMED)
        if (formatVersion != SUPPORTED_FORMAT) return fail(RuleRejection.UNSUPPORTED_FORMAT)

        val manifest = RuleManifest(
            formatVersion = formatVersion,
            version = header["version"] ?: return fail(RuleRejection.MALFORMED),
            issuedAtMillis = header["issued"]?.toLongOrNull() ?: return fail(RuleRejection.MALFORMED),
            expiresAtMillis = header["expires"]?.toLongOrNull() ?: return fail(RuleRejection.MALFORMED),
            declaredCount = header["count"]?.toIntOrNull() ?: return fail(RuleRejection.MALFORMED),
        )

        val domains = mutableSetOf<String>()
        while (lines.hasNext()) {
            val line = lines.next().trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (domains.size > MAX_DOMAINS) return fail(RuleRejection.TOO_LARGE)
            // Unparseable entries are dropped rather than failing the whole set; one bad line in a
            // large list should not cost the user every other rule.
            DomainMatcher.normalize(line)?.let { domains += it }
        }

        if (domains.isEmpty()) return fail(RuleRejection.EMPTY)
        // A truncated download is the common failure, and it looks exactly like a short list.
        if (domains.size != manifest.declaredCount) return fail(RuleRejection.COUNT_MISMATCH)

        return Result.success(ParsedRuleSet(manifest, domains))
    }

    private fun <T> fail(rejection: RuleRejection): Result<T> = Result.failure(RuleRejectedException(rejection))
}

/**
 * Verifies and gates rule updates.
 *
 * Signatures are ECDSA over P-256 (`SHA256withECDSA`) rather than Ed25519. Ed25519 would be the
 * nicer choice, but `Signature.getInstance("Ed25519")` is only available from Android 13, and
 * NoScroll supports Android 8 — an update mechanism that silently cannot verify on older devices
 * would be worse than one using a slightly older, universally available curve.
 */
class RuleUpdateVerifier(private val publicKeyBase64: String) {

    fun verify(
        payload: ByteArray,
        signatureBase64: String,
        currentVersion: String?,
        nowMillis: Long,
    ): Result<ParsedRuleSet> {
        val signatureOk = runCatching {
            val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(payload)
            }.verify(Base64.getDecoder().decode(signatureBase64))
        }.getOrDefault(false)

        if (!signatureOk) return Result.failure(RuleRejectedException(RuleRejection.BAD_SIGNATURE))

        val parsed = RuleSetParser.parse(String(payload, Charsets.UTF_8)).getOrElse { return Result.failure(it) }

        if (parsed.manifest.expiresAtMillis <= nowMillis) {
            return Result.failure(RuleRejectedException(RuleRejection.EXPIRED))
        }
        if (currentVersion != null && !isNewer(parsed.manifest.version, currentVersion)) {
            return Result.failure(RuleRejectedException(RuleRejection.NOT_NEWER))
        }
        return Result.success(parsed)
    }

    /** Versions are dotted numeric (`2026.06.01`); comparison is component-wise, then lexical. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.')
        val b = current.split('.')
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrNull(index)?.toIntOrNull()
            val right = b.getOrNull(index)?.toIntOrNull()
            if (left == null || right == null) return candidate > current
            if (left != right) return left > right
        }
        return false
    }
}
