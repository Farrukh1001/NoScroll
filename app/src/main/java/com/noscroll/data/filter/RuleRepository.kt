package com.noscroll.data.filter

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.noscroll.data.filterStore
import com.noscroll.domain.filter.RuleSet
import com.noscroll.domain.filter.RuleSetParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Assembles the active rule set from three layers.
 *
 * 1. **Bundled** — ships in the APK and always works, with no network and no server. This is the
 *    baseline the app is allowed to promise.
 * 2. **Last known good update** — a signed rule set that verified successfully, cached on disk. If
 *    an update ever fails to verify, this is what stays in force; there is no state where a bad
 *    update leaves the user unprotected.
 * 3. **User rules** — a local blocklist (added instantly) and a local allowlist (added only after
 *    the protected waiting period, because it weakens protection).
 *
 * The composed set is cached in memory because the VPN packet path queries it per DNS lookup.
 */
class RuleRepository(private val context: Context) {

    private object Keys {
        val USER_BLOCKED = stringSetPreferencesKey("rules.user_blocked")
        val USER_ALLOWED = stringSetPreferencesKey("rules.user_allowed")
        val ACTIVE_VERSION = stringPreferencesKey("rules.active_version")
    }

    private val cached = AtomicReference<RuleSet?>(null)

    private val updatedRulesFile: File
        get() = File(context.filesDir, "rules/last-known-good.txt")

    val userBlocked: Flow<Set<String>> = context.filterStore.data.map { it[Keys.USER_BLOCKED] ?: emptySet() }
    val userAllowed: Flow<Set<String>> = context.filterStore.data.map { it[Keys.USER_ALLOWED] ?: emptySet() }
    val activeVersion: Flow<String> = context.filterStore.data.map { it[Keys.ACTIVE_VERSION] ?: "bundled" }

    /** The composed rule set. Cheap after the first call. */
    suspend fun ruleSet(): RuleSet {
        cached.get()?.let { return it }
        return reload()
    }

    /** Synchronous accessor for the packet path; null before the first [reload]. */
    fun cachedRuleSet(): RuleSet? = cached.get()

    suspend fun reload(): RuleSet = withContext(Dispatchers.IO) {
        val base = readUpdatedRules() ?: readBundledRules()
        val blocked = context.filterStore.data.first()[Keys.USER_BLOCKED] ?: emptySet()
        val allowed = context.filterStore.data.first()[Keys.USER_ALLOWED] ?: emptySet()
        val composed = RuleSet(base.first + blocked, allowed, base.second)
        cached.set(composed)
        composed
    }

    /** Returns the parsed domains and the version string. */
    private fun readBundledRules(): Pair<Set<String>, String> = runCatching {
        val text = context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
        val parsed = RuleSetParser.parse(text).getOrThrow()
        parsed.domains to "bundled-${parsed.manifest.version}"
    }.getOrElse {
        // The bundled asset is part of the APK, so this should be unreachable. Failing to an empty
        // set rather than crashing keeps the rest of the app alive; protection health will report it.
        emptySet<String>() to "bundled-unavailable"
    }

    private fun readUpdatedRules(): Pair<Set<String>, String>? {
        val file = updatedRulesFile
        if (!file.exists()) return null
        return runCatching {
            val parsed = RuleSetParser.parse(file.readText()).getOrThrow()
            parsed.domains to parsed.manifest.version
        }.getOrNull()
    }

    /**
     * Installs a rule set that has already passed signature verification.
     *
     * Written to a temporary file and then renamed, so a process death mid-write leaves the previous
     * known-good set intact rather than a truncated one.
     */
    suspend fun installVerifiedRules(rawText: String, version: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val target = updatedRulesFile
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "last-known-good.tmp")
            temp.writeText(rawText)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            context.filterStore.edit { it[Keys.ACTIVE_VERSION] = version }
            reload()
            true
        }.getOrDefault(false)
    }

    /** Adding a blocked domain strengthens protection, so it applies at once. */
    suspend fun addUserBlocked(domain: String) {
        context.filterStore.edit { prefs ->
            prefs[Keys.USER_BLOCKED] = (prefs[Keys.USER_BLOCKED] ?: emptySet()) + domain.lowercase().trim()
        }
        reload()
    }

    /**
     * Removing a blocked domain and adding an allowlist entry both weaken protection. Neither is
     * called directly from the UI — both arrive here only after their waiting period has elapsed.
     */
    suspend fun removeUserBlocked(domain: String) {
        context.filterStore.edit { prefs ->
            prefs[Keys.USER_BLOCKED] = (prefs[Keys.USER_BLOCKED] ?: emptySet()) - domain.lowercase().trim()
        }
        reload()
    }

    suspend fun addUserAllowed(domain: String) {
        context.filterStore.edit { prefs ->
            prefs[Keys.USER_ALLOWED] = (prefs[Keys.USER_ALLOWED] ?: emptySet()) + domain.lowercase().trim()
        }
        reload()
    }

    suspend fun removeUserAllowed(domain: String) {
        context.filterStore.edit { prefs ->
            prefs[Keys.USER_ALLOWED] = (prefs[Keys.USER_ALLOWED] ?: emptySet()) - domain.lowercase().trim()
        }
        reload()
    }

    companion object {
        const val BUNDLED_ASSET = "rules/bundled-rules.txt"

        /**
         * The public key that signs rule updates.
         *
         * Empty in this build: no public update endpoint is configured yet, and shipping a
         * placeholder key with a placeholder URL would be pretending the update channel works. The
         * bundled list is fully functional on its own; see RELEASE.md for generating a signing key
         * and turning the channel on.
         */
        const val UPDATE_PUBLIC_KEY_BASE64 = ""

        /** Null until a real endpoint exists. The updater treats null as "channel disabled". */
        val UPDATE_URL: String? = null
    }
}
