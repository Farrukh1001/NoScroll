package com.noscroll.domain.filter

/**
 * The browser-side half of the Explicit Content Shield.
 *
 * The DNS filter catches known hosts. This catches the two things DNS cannot: a search typed into a
 * search engine that resolves perfectly well, and a request to turn SafeSearch off.
 *
 * The design bias here is the opposite of the domain list's. A domain either is or is not on a list;
 * a search phrase is ambiguous, and the cost of a false positive is high — blocking somebody looking
 * up an STI symptom, a helpline, or a piece of sex-education material would be a genuine harm. So the
 * classifier requires an unambiguous sexual term **and** the absence of any health, education, news
 * or support context before it will act.
 *
 * Explicitly out of scope: gore, violence, horror, crime, ordinary manga and manhwa, and mature
 * fiction without explicit sexual content. None of those terms appear below, and none are blocked.
 */
object ExplicitQueryHeuristic {

    /**
     * Unambiguous pornography terms. Kept short and specific on purpose — every entry here is a term
     * whose only common search intent is pornographic.
     */
    private val EXPLICIT_TERMS = setOf(
        "porn", "porno", "pornhub", "xxx", "xvideos", "xnxx", "redtube", "youporn", "brazzers",
        "hentai", "rule34", "nsfw porn", "sex video", "sex videos", "sex tape", "nude video",
        "nudes leak", "onlyfans leak", "camgirl", "cam girls", "escort service", "milf",
        "blowjob", "creampie", "gangbang", "hardcore sex", "anal sex video", "adult video",
        "erotica video", "sexo", "doujinshi hentai", "eroge",
    )

    /**
     * Context that flips an otherwise-explicit-looking search back to allowed. If any of these
     * appear, we assume good faith and let it through — a missed block is recoverable, a blocked
     * helpline search is not.
     */
    private val PROTECTED_CONTEXT = setOf(
        "health", "medical", "medicine", "doctor", "clinic", "nhs", "symptom", "symptoms",
        "std", "sti", "hiv", "contraception", "pregnancy", "fertility", "menopause", "puberty",
        "education", "educational", "curriculum", "school", "teacher", "lesson", "academic",
        "research", "study", "journal", "statistics", "policy", "law", "legal", "court",
        "abuse", "assault", "trafficking", "exploitation", "grooming", "prevention", "helpline",
        "support", "recovery", "addiction", "therapy", "counselling", "counseling", "rehab",
        "news", "report", "investigation", "documentary", "history", "definition", "meaning",
        "consent", "safeguarding", "charity", "hotline",
    )

    /**
     * True only when the phrase is unambiguously a search for pornography.
     */
    fun isExplicitSearch(rawQuery: String?): Boolean {
        val query = rawQuery?.lowercase()?.trim() ?: return false
        if (query.isEmpty()) return false

        val hasProtectedContext = PROTECTED_CONTEXT.any { context -> containsWord(query, context) }
        if (hasProtectedContext) return false

        return EXPLICIT_TERMS.any { term ->
            if (term.contains(' ')) query.contains(term) else containsWord(query, term)
        }
    }

    /** Word-boundary match, so "assessment" never matches "sex" and "scunthorpe" stays searchable. */
    private fun containsWord(haystack: String, needle: String): Boolean {
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            val beforeOk = index == 0 || !haystack[index - 1].isLetterOrDigit()
            val after = index + needle.length
            val afterOk = after >= haystack.length || !haystack[after].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            index = haystack.indexOf(needle, index + 1)
        }
        return false
    }
}

sealed interface BrowserAction {
    data object Allow : BrowserAction

    /** Cover the page and step back. The URL and query are never stored. */
    data object Block : BrowserAction

    /** Re-issue the same search with SafeSearch switched on. */
    data class RedirectToSafe(val url: String) : BrowserAction
}

/**
 * Re-issues searches with the engine's own SafeSearch parameter attached.
 *
 * This is done in the browser rather than by rewriting DNS answers. Forcing SafeSearch through DNS
 * means answering a lookup with an address of somebody else's choosing, and NoScroll deliberately
 * never rewrites a DNS answer — a blocked name gets NXDOMAIN and nothing else. Adding the parameter
 * to the URL achieves the same result with no ambiguity about what the tunnel is doing.
 */
object SafeSearchEnforcer {

    private data class EngineRule(val hostSuffix: String, val pathPrefix: String, val parameter: String, val value: String)

    private val ENGINES = listOf(
        EngineRule("google.com", "/search", "safe", "active"),
        EngineRule("google.co.uk", "/search", "safe", "active"),
        EngineRule("bing.com", "/search", "adlt", "strict"),
        EngineRule("duckduckgo.com", "/", "kp", "1"),
        EngineRule("search.yahoo.com", "/search", "vm", "r"),
        EngineRule("yandex.com", "/search", "fyandex", "1"),
        EngineRule("youtube.com", "/results", "safe", "active"),
    )

    /**
     * Decides what to do with a URL seen in a browser's address bar.
     *
     * Order matters: an explicit search is blocked outright, and only an otherwise-acceptable search
     * gets the SafeSearch nudge.
     */
    fun evaluate(rawUrl: String?, ruleSet: RuleSet, safeSearchEnabled: Boolean, guardEnabled: Boolean): BrowserAction {
        if (!guardEnabled) return BrowserAction.Allow
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return BrowserAction.Allow

        val uri = runCatching { java.net.URI(normalizeForParsing(url)) }.getOrNull() ?: return BrowserAction.Allow
        val host = uri.host?.lowercase() ?: return BrowserAction.Allow

        if (ruleSet.isBlocked(host)) return BrowserAction.Block

        val query = extractQueryTerm(uri)
        if (ExplicitQueryHeuristic.isExplicitSearch(query)) return BrowserAction.Block

        if (safeSearchEnabled && query != null) {
            val engine = ENGINES.firstOrNull { rule ->
                (host == rule.hostSuffix || host.endsWith(".${rule.hostSuffix}")) &&
                    uri.path.orEmpty().startsWith(rule.pathPrefix)
            }
            if (engine != null && !hasParameter(uri.rawQuery, engine.parameter, engine.value)) {
                return BrowserAction.RedirectToSafe(appendParameter(url, engine.parameter, engine.value))
            }
        }

        return BrowserAction.Allow
    }

    /** Address bars often show a bare host; give the parser a scheme so it can find the host. */
    private fun normalizeForParsing(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"

    /** The search term, from whichever parameter the engine uses. Read transiently, never stored. */
    fun extractQueryTerm(uri: java.net.URI): String? {
        val raw = uri.rawQuery ?: return null
        for (pair in raw.split('&')) {
            val name = pair.substringBefore('=')
            if (name in setOf("q", "p", "query", "text", "search_query", "wd")) {
                val value = pair.substringAfter('=', "")
                if (value.isEmpty()) return null
                return runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrNull()
            }
        }
        return null
    }

    private fun hasParameter(rawQuery: String?, name: String, value: String): Boolean {
        val query = rawQuery ?: return false
        return query.split('&').any { it.equals("$name=$value", ignoreCase = true) }
    }

    private fun appendParameter(url: String, name: String, value: String): String {
        val separator = if (url.contains('?')) "&" else "?"
        return "$url$separator$name=$value"
    }
}
