package com.noscroll.domain.filter

import java.net.IDN

/**
 * Domain normalisation and matching for the Explicit Content Shield.
 *
 * The two jobs here are unglamorous but are where filters usually break:
 *  - **Normalisation** has to fold away the tricks: trailing dots, mixed case, ports, an IDN
 *    homograph written in Unicode, a hostname with an empty label. Anything that does not normalise
 *    to a well-formed name is rejected rather than guessed at.
 *  - **Matching** must cover subdomains without matching a different registrable domain that merely
 *    ends with the same letters. Rules are compared label by label, so a rule for `adult.example`
 *    matches `cdn.adult.example` but never `notadult.example`.
 */
object DomainMatcher {

    private const val MAX_HOST_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63

    /**
     * Canonicalises a hostname to lowercase ASCII (punycode), or returns null if it is not a usable
     * hostname. Null means "we could not understand this", and every caller treats that as
     * "do not claim it is blocked".
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var host = raw.trim()

        // Strip a scheme and any userinfo/path if a full URL was handed in.
        host.indexOf("://").let { if (it >= 0) host = host.substring(it + 3) }
        host = host.substringBefore('/').substringBefore('?').substringBefore('#')
        host = host.substringAfterLast('@')

        // IPv6 literals arrive bracketed; a bare IP is not a domain rule target.
        if (host.startsWith("[")) return null

        // Strip the port, but only when what follows is numeric.
        val colon = host.lastIndexOf(':')
        if (colon > 0 && host.substring(colon + 1).all { it.isDigit() } && host.substring(colon + 1).isNotEmpty()) {
            host = host.substring(0, colon)
        }

        host = host.trimEnd('.').lowercase()
        if (host.isEmpty()) return null

        // Unicode/IDN folding. USE_STD3_ASCII_RULES rejects names with characters that are not
        // legal in a hostname, which is what closes the homograph hole.
        val ascii = runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) }.getOrNull()?.lowercase()
            ?: return null

        if (ascii.length > MAX_HOST_LENGTH) return null
        val labels = ascii.split('.')
        if (labels.size < 2) return null
        if (labels.any { it.isEmpty() || it.length > MAX_LABEL_LENGTH }) return null
        if (labels.any { label -> label.startsWith('-') || label.endsWith('-') }) return null
        if (labels.any { label -> !label.all { it.isLetterOrDigit() || it == '-' } }) return null

        return ascii
    }

    /**
     * True when [host] is the rule itself or a subdomain of it. Compared label-wise so
     * `notadult.example` does not match a rule for `adult.example`.
     */
    fun matchesRule(host: String, rule: String): Boolean {
        if (host == rule) return true
        return host.endsWith(".$rule")
    }

    /** Every suffix of [host] that a rule could match, longest first. */
    fun candidateRules(host: String): List<String> {
        val labels = host.split('.')
        return (0..labels.size - 2).map { index -> labels.subList(index, labels.size).joinToString(".") }
    }
}

/** What the filter decided, and why — the "why" is what the UI shows without ever naming the domain. */
enum class FilterVerdict {
    /** Explicitly allowed by the user's allowlist; never blocked. */
    ALLOWED,

    /** On a blocklist. */
    BLOCKED,

    /** No rule matched. */
    NEUTRAL,

    /** The hostname could not be understood; treated as neutral by callers. */
    UNPARSEABLE,
}

/**
 * An immutable, already-normalised set of rules.
 *
 * Built once when rules load and then queried from the packet path, so the hot path does no
 * allocation beyond splitting the queried name.
 */
class RuleSet(
    blocked: Set<String>,
    allowed: Set<String> = emptySet(),
    val version: String = "bundled",
) {
    val blocked: Set<String> = blocked.mapNotNull { DomainMatcher.normalize(it) }.toSet()
    val allowed: Set<String> = allowed.mapNotNull { DomainMatcher.normalize(it) }.toSet()

    val size: Int get() = blocked.size

    fun verdict(rawHost: String?): FilterVerdict {
        val host = DomainMatcher.normalize(rawHost) ?: return FilterVerdict.UNPARSEABLE
        val candidates = DomainMatcher.candidateRules(host)
        // The allowlist is checked first and wins outright, so a user-added exception cannot be
        // overridden by a broader bundled rule.
        for (candidate in candidates) {
            if (candidate in allowed) return FilterVerdict.ALLOWED
        }
        for (candidate in candidates) {
            if (candidate in blocked) return FilterVerdict.BLOCKED
        }
        return FilterVerdict.NEUTRAL
    }

    fun isBlocked(rawHost: String?): Boolean = verdict(rawHost) == FilterVerdict.BLOCKED

    fun withUserRules(userBlocked: Set<String>, userAllowed: Set<String>): RuleSet =
        RuleSet(blocked + userBlocked, allowed + userAllowed, version)

    companion object {
        val EMPTY = RuleSet(emptySet(), emptySet(), "empty")
    }
}
