package com.noscroll.domain.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The codec sits between the protection rules and disk. Its job on the way back is to be paranoid:
 * anything it cannot read completely must decode to "nothing pending", because applying half of a
 * queued weakening change would be worse than losing the request.
 */
class ProtectionCodecTest {

    @Test
    fun `values round trip`() {
        listOf(
            SettingValue.Bool(true),
            SettingValue.Bool(false),
            SettingValue.Num(0),
            SettingValue.Num(30),
            SettingValue.Num(-5),
        ).forEach { value ->
            assertEquals(value, ProtectionCodec.decodeValue(ProtectionCodec.encodeValue(value)))
        }
    }

    @Test
    fun `entries round trip`() {
        val entries = listOf(
            PendingEntry(
                SettingKey.SHIELD_MODULE,
                SettingValue.Bool(true),
                SettingValue.Bool(false),
                "reason",
            ),
            PendingEntry(
                SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES,
                SettingValue.Num(30),
                SettingValue.Num(60),
                "reason",
            ),
        )
        val decoded = ProtectionCodec.decodeEntries(ProtectionCodec.encodeEntries(entries))!!
        assertEquals(2, decoded.size)
        assertEquals(SettingKey.SHIELD_MODULE, decoded[0].key)
        assertEquals(SettingValue.Bool(false), decoded[0].requested)
        assertEquals(SettingValue.Num(60), decoded[1].requested)
    }

    @Test
    fun `the reason is regenerated on decode rather than trusted from disk`() {
        val entries = listOf(
            PendingEntry(
                SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES,
                SettingValue.Num(30),
                SettingValue.Num(90),
                "a stale reason written by an older version",
            )
        )
        val decoded = ProtectionCodec.decodeEntries(ProtectionCodec.encodeEntries(entries))!!
        assertEquals(
            ChangeClassifier.explain(
                SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES,
                SettingValue.Num(30),
                SettingValue.Num(90),
            ),
            decoded.single().reason,
        )
    }

    @Test
    fun `corrupt input decodes to nothing pending`() {
        listOf(
            null,
            "",
            "   ",
            "garbage",
            "unknown.key|b:true|b:false",
            "shield.module|b:true",                 // missing a field
            "shield.module|b:notabool|b:false",     // unreadable value
            "shield.module|x:1|b:false",            // unknown type tag
        ).forEach { input ->
            assertNull("\"$input\" must not decode", ProtectionCodec.decodeEntries(input))
        }
    }

    @Test
    fun `one unreadable entry invalidates the whole bundle`() {
        val valid = ProtectionCodec.encodeEntries(
            listOf(PendingEntry(SettingKey.SHIELD_MODULE, SettingValue.Bool(true), SettingValue.Bool(false), ""))
        )
        assertNull(ProtectionCodec.decodeEntries("$valid;broken|entry"))
    }

    @Test
    fun `every setting key has a stable id`() {
        // Ids are written to disk, so a rename would silently orphan a stored value.
        SettingKey.entries.forEach { key ->
            assertEquals(key, SettingKey.fromId(key.id))
        }
    }

    @Test
    fun `every key has a usable default`() {
        val defaults = ProtectionState.withDefaults()
        SettingKey.entries.forEach { key ->
            when (key.type) {
                SettingType.BOOLEAN -> assertEquals(key.defaultBoolean, defaults.bool(key))
                SettingType.INT -> assertEquals(key.defaultInt, defaults.int(key))
            }
        }
    }

    @Test
    fun `the shipped default profile has every protection on`() {
        val defaults = ProtectionState.withDefaults()
        assertEquals(30, defaults.int(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES))
        assertEquals(24, defaults.int(SettingKey.WEAKENING_DELAY_HOURS))
        listOf(
            SettingKey.YOUTUBE_MODULE,
            SettingKey.YOUTUBE_SHELF_MASK,
            SettingKey.YOUTUBE_TAB_MASK,
            SettingKey.YOUTUBE_VIEWER_BLOCK,
            SettingKey.YOUTUBE_EXTERNAL_LINKS,
            SettingKey.YOUTUBE_MUSIC_MODULE,
            SettingKey.INSTAGRAM_MODULE,
            SettingKey.INSTAGRAM_SHARED_BUDGET,
            SettingKey.INSTAGRAM_REELS_TIMER,
            SettingKey.INSTAGRAM_EXPLORE_TIMER,
            SettingKey.INSTAGRAM_SAVED_TIMER,
            SettingKey.INSTAGRAM_FRIEND_SENT_SINGLE,
            SettingKey.SHIELD_MODULE,
            SettingKey.SHIELD_SAFE_SEARCH,
            SettingKey.SHIELD_FAIL_CLOSED,
        ).forEach { key ->
            assertEquals("$key should default to on", true, defaults.bool(key))
        }
    }
}
