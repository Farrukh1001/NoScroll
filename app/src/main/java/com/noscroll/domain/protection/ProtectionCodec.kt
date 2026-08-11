package com.noscroll.domain.protection

/**
 * Encodes protection state as flat strings for DataStore.
 *
 * Kept as pure functions with an explicit, boring format so that a partially written or
 * hand-corrupted value decodes to "nothing pending" rather than to a bundle with a wrong deadline.
 * Every decode path falls back to a safe default; nothing here throws.
 */
object ProtectionCodec {

    private const val ENTRY_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = "|"

    fun encodeValue(value: SettingValue): String = when (value) {
        is SettingValue.Bool -> "b:${value.value}"
        is SettingValue.Num -> "i:${value.value}"
    }

    fun decodeValue(raw: String?): SettingValue? {
        if (raw == null) return null
        val type = raw.substringBefore(':', "")
        val body = raw.substringAfter(':', "")
        return when (type) {
            "b" -> body.toBooleanStrictOrNull()?.let { SettingValue.Bool(it) }
            "i" -> body.toIntOrNull()?.let { SettingValue.Num(it) }
            else -> null
        }
    }

    fun encodeEntries(entries: List<PendingEntry>): String =
        entries.joinToString(ENTRY_SEPARATOR) { entry ->
            listOf(
                entry.key.id,
                encodeValue(entry.current),
                encodeValue(entry.requested),
            ).joinToString(FIELD_SEPARATOR)
        }

    /**
     * Decodes the entry list. A single unreadable entry invalidates the whole bundle — applying
     * half of a queued change would be worse than making the user request it again.
     */
    fun decodeEntries(raw: String?): List<PendingEntry>? {
        if (raw.isNullOrBlank()) return null
        val entries = raw.split(ENTRY_SEPARATOR).map { chunk ->
            val parts = chunk.split(FIELD_SEPARATOR)
            if (parts.size != 3) return null
            val key = SettingKey.fromId(parts[0]) ?: return null
            val current = decodeValue(parts[1]) ?: return null
            val requested = decodeValue(parts[2]) ?: return null
            PendingEntry(key, current, requested, ChangeClassifier.explain(key, current, requested))
        }
        return entries.ifEmpty { null }
    }
}
