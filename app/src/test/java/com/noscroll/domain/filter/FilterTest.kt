package com.noscroll.domain.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filter tests use reserved example domains (`adult.example` and friends), never real explicit
 * hosts. The rules being tested are about matching and normalisation, and dummy names exercise them
 * exactly as well without putting a list of pornography sites in the test source.
 */
class FilterTest {

    private val rules = RuleSet(
        blocked = setOf("adult.example", "explicit.example", "xn--bcher-kva.example"),
        allowed = setOf("health.adult.example"),
    )

    // ---- Normalisation ---------------------------------------------------------------------

    @Test
    fun `case, trailing dots and ports are normalised away`() {
        assertEquals("adult.example", DomainMatcher.normalize("ADULT.Example."))
        assertEquals("adult.example", DomainMatcher.normalize("adult.example:8443"))
        assertEquals("adult.example", DomainMatcher.normalize("https://adult.example/some/path?q=1"))
        assertEquals("adult.example", DomainMatcher.normalize("user:pw@adult.example"))
    }

    @Test
    fun `unicode is folded to punycode`() {
        // A name written in Unicode must match the same rule as its ASCII form.
        assertEquals("xn--bcher-kva.example", DomainMatcher.normalize("bücher.example"))
        assertTrue(rules.isBlocked("bücher.example"))
        assertTrue(rules.isBlocked("xn--bcher-kva.example"))
    }

    @Test
    fun `malformed hostnames are refused rather than guessed at`() {
        listOf(
            null, "", "   ", "localhost", "adult..example", "-adult.example", "adult-.example",
            "[2001:db8::1]", "adult example.com", "a".repeat(64) + ".example",
        ).forEach { input ->
            assertNull("\"$input\" should not normalise", DomainMatcher.normalize(input))
        }
    }

    @Test
    fun `an unparseable host is neutral, not blocked`() {
        assertEquals(FilterVerdict.UNPARSEABLE, rules.verdict("not a host"))
        assertFalse(rules.isBlocked("not a host"))
    }

    // ---- Matching ---------------------------------------------------------------------------

    @Test
    fun `a rule matches the domain and its subdomains`() {
        assertTrue(rules.isBlocked("adult.example"))
        assertTrue(rules.isBlocked("cdn.adult.example"))
        assertTrue(rules.isBlocked("a.b.c.adult.example"))
    }

    @Test
    fun `a rule does not match a different domain that merely ends the same`() {
        // The classic rule-confusion bug: suffix matching without a label boundary.
        assertFalse(rules.isBlocked("notadult.example"))
        assertFalse(rules.isBlocked("adult.example.co"))
        assertFalse(rules.isBlocked("myadult.example"))
    }

    @Test
    fun `the allowlist wins over a broader block rule`() {
        assertEquals(FilterVerdict.ALLOWED, rules.verdict("health.adult.example"))
        assertEquals(FilterVerdict.ALLOWED, rules.verdict("sub.health.adult.example"))
        assertEquals(FilterVerdict.BLOCKED, rules.verdict("other.adult.example"))
    }

    @Test
    fun `an unrelated domain is neutral`() {
        assertEquals(FilterVerdict.NEUTRAL, rules.verdict("wikipedia.example"))
        assertEquals(FilterVerdict.NEUTRAL, rules.verdict("mangareader.example"))
    }

    @Test
    fun `user rules compose with the bundled set`() {
        val composed = rules.withUserRules(setOf("extra.example"), setOf("explicit.example"))
        assertTrue(composed.isBlocked("extra.example"))
        assertEquals(FilterVerdict.ALLOWED, composed.verdict("explicit.example"))
    }

    // ---- Search classification ------------------------------------------------------------------

    @Test
    fun `unambiguously pornographic searches are blocked`() {
        listOf("porn", "free porn videos", "hentai manga", "xxx video", "rule34")
            .forEach { assertTrue("\"$it\" should be blocked", ExplicitQueryHeuristic.isExplicitSearch(it)) }
    }

    @Test
    fun `health, education and support searches are never blocked`() {
        // This is the case that matters most. A false positive here is a real harm.
        listOf(
            "sti symptoms nhs",
            "sexual health clinic near me",
            "sex education curriculum",
            "how to talk to teenagers about consent",
            "porn addiction recovery support",
            "child abuse prevention helpline",
            "pornography law research paper",
            "hiv testing",
            "contraception options",
        ).forEach { assertFalse("\"$it\" must be allowed", ExplicitQueryHeuristic.isExplicitSearch(it)) }
    }

    @Test
    fun `gore, violence and ordinary comics are not targeted`() {
        // Explicitly out of scope for this module, per the product's stated boundaries.
        listOf(
            "gore movies", "violent video games", "true crime documentary", "horror manga",
            "manhwa recommendations", "berserk manga volume 1", "war photography",
            "murder mystery novel", "boxing knockout",
        ).forEach { assertFalse("\"$it\" must not be blocked", ExplicitQueryHeuristic.isExplicitSearch(it)) }
    }

    @Test
    fun `matching respects word boundaries`() {
        // "sex" inside another word must not trigger anything.
        assertFalse(ExplicitQueryHeuristic.isExplicitSearch("essex county council"))
        assertFalse(ExplicitQueryHeuristic.isExplicitSearch("middlesex university"))
        assertFalse(ExplicitQueryHeuristic.isExplicitSearch("scunthorpe weather"))
    }

    // ---- SafeSearch and the browser guard ---------------------------------------------------------

    @Test
    fun `a blocked domain in the address bar is blocked`() {
        val action = SafeSearchEnforcer.evaluate(
            "https://adult.example/page", rules, safeSearchEnabled = true, guardEnabled = true,
        )
        assertEquals(BrowserAction.Block, action)
    }

    @Test
    fun `an explicit search is blocked even on an allowed host`() {
        val action = SafeSearchEnforcer.evaluate(
            "https://www.google.com/search?q=free+porn", rules, safeSearchEnabled = true, guardEnabled = true,
        )
        assertEquals(BrowserAction.Block, action)
    }

    @Test
    fun `an ordinary search is redirected with safesearch on`() {
        val action = SafeSearchEnforcer.evaluate(
            "https://www.google.com/search?q=weather", rules, safeSearchEnabled = true, guardEnabled = true,
        )
        assertTrue(action is BrowserAction.RedirectToSafe)
        assertTrue((action as BrowserAction.RedirectToSafe).url.contains("safe=active"))
    }

    @Test
    fun `a search that already has safesearch is left alone`() {
        val action = SafeSearchEnforcer.evaluate(
            "https://www.google.com/search?q=weather&safe=active", rules, safeSearchEnabled = true, guardEnabled = true,
        )
        assertEquals(BrowserAction.Allow, action)
    }

    @Test
    fun `safesearch off means no redirect`() {
        val action = SafeSearchEnforcer.evaluate(
            "https://www.google.com/search?q=weather", rules, safeSearchEnabled = false, guardEnabled = true,
        )
        assertEquals(BrowserAction.Allow, action)
    }

    @Test
    fun `the guard being off allows everything through it`() {
        val action = SafeSearchEnforcer.evaluate(
            "https://adult.example", rules, safeSearchEnabled = true, guardEnabled = false,
        )
        assertEquals(BrowserAction.Allow, action)
    }

    @Test
    fun `a bare host with no scheme is still evaluated`() {
        assertEquals(
            BrowserAction.Block,
            SafeSearchEnforcer.evaluate("adult.example", rules, true, true),
        )
    }
}
