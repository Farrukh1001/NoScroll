package com.noscroll.domain.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled blocklist ships inside the APK and is the offline baseline the app is allowed to
 * promise. If it fails to parse, a shipped build starts with no rules at all — so it is checked
 * here, against the real file, rather than trusted.
 *
 * This test reads the asset from the source tree so it can run as a plain JVM test.
 */
class BundledRulesTest {

    private val assetFile = File("src/main/assets/rules/bundled-rules.txt")

    private fun parsed(): ParsedRuleSet {
        assertTrue(
            "bundled-rules.txt is missing at ${assetFile.absolutePath}",
            assetFile.exists(),
        )
        return RuleSetParser.parse(assetFile.readText()).getOrThrow()
    }

    @Test
    fun theBundledListParses() {
        val rules = parsed()
        assertEquals(RuleSetParser.SUPPORTED_FORMAT, rules.manifest.formatVersion)
        assertTrue("the list should not be trivially small", rules.domains.size >= 50)
    }

    @Test
    fun theDeclaredCountMatches() {
        // The same check that catches a truncated download also catches a hand-edit that forgot to
        // update the header.
        val rules = parsed()
        assertEquals(rules.manifest.declaredCount, rules.domains.size)
    }

    @Test
    fun everyEntryNormalisesToAUsableDomain() {
        parsed().domains.forEach { domain ->
            assertEquals("$domain should already be normalised", domain, DomainMatcher.normalize(domain))
        }
    }

    @Test
    fun theListDoesNotBlockThingsOutsideItsStatedScope() {
        // The module is scoped to sexually explicit content. These are all explicitly out of scope,
        // and a rule matching any of them would be a bug rather than a judgement call.
        val mustNotBeBlocked = listOf(
            "mangadex.org",
            "webtoons.com",
            "viz.com",
            "crunchyroll.com",
            "goodreads.com",
            "reddit.com",
            "twitter.com",
            "x.com",
            "telegram.org",
            "wikipedia.org",
            "nhs.uk",
            "who.int",
            "plannedparenthood.org",
            "cdc.gov",
            "rainn.org",
            "nspcc.org.uk",
            "samaritans.org",
            "bbc.co.uk",
            "theguardian.com",
            "pubmed.ncbi.nlm.nih.gov",
            "youtube.com",
            "instagram.com",
        )
        val ruleSet = RuleSet(parsed().domains)
        mustNotBeBlocked.forEach { host ->
            assertFalse("$host must not be blocked by the bundled list", ruleSet.isBlocked(host))
        }
    }

    @Test
    fun theListCarriesItsLicenceAndScopeInAComment() {
        val text = assetFile.readText()
        assertTrue("the licence should be stated in the file", text.contains("GPL-3.0-only"))
        assertTrue(
            "the file should state that it excludes non-sexual categories",
            text.contains("gore") || text.contains("violence"),
        )
    }

    @Test
    fun theBundledListHasNotExpired() {
        val rules = parsed()
        assertTrue(
            "the bundled list must not carry an expiry that has already passed",
            rules.manifest.expiresAtMillis > System.currentTimeMillis(),
        )
    }
}
