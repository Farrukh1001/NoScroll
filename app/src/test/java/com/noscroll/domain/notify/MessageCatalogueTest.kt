package com.noscroll.domain.notify

import com.noscroll.domain.wellbeing.SavingBaseline
import com.noscroll.domain.wellbeing.TimeSavedResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message quality is a product requirement here, not a matter of taste.
 *
 * Every message must be concrete, adult and backed by real data. These tests police the negative
 * space: no motivational filler, no shaming, no exclamation marks, and nothing at all when there is
 * nothing true to say.
 */
class MessageCatalogueTest {

    private val banned = listOf(
        "superstar", "crushed it", "amazing", "awesome", "you failed", "failure", "lazy",
        "shame", "disappointed", "great job", "well done!", "keep it up!", "you got this",
    )

    private fun allMessages(): List<EncouragementMessage> = buildList {
        MessageTone.entries.forEach { tone ->
            MessageCatalogue.timeSaved(TimeSavedResult.Saved(15, SavingBaseline.YESTERDAY), tone)?.let(::add)
            MessageCatalogue.timeSaved(TimeSavedResult.Saved(22, SavingBaseline.SEVEN_DAY_AVERAGE), tone)?.let(::add)
            MessageCatalogue.timeSaved(TimeSavedResult.Higher(30, SavingBaseline.YESTERDAY), tone)?.let(::add)
            MessageCatalogue.timeSaved(TimeSavedResult.Level(SavingBaseline.YESTERDAY), tone)?.let(::add)
            add(MessageCatalogue.instagramWithinPlan(tone))
        }
        add(MessageCatalogue.instagramRemaining(12))
        add(MessageCatalogue.goalRemaining(40))
        add(MessageCatalogue.protectionsIntact())
        MessageCatalogue.blockedCount(8)?.let(::add)
        MessageCatalogue.percentBelowAverage(12)?.let(::add)
    }

    @Test
    fun noMessageUsesFillerOrShame() {
        allMessages().forEach { message ->
            val text = message.text.lowercase()
            banned.forEach { phrase ->
                assertFalse("\"${message.text}\" contains banned phrasing: $phrase", text.contains(phrase))
            }
        }
    }

    @Test
    fun noMessageShouts() {
        allMessages().forEach { message ->
            assertFalse("\"${message.text}\" uses an exclamation mark", message.text.contains("!"))
        }
    }

    @Test
    fun everyMessageHasAStableTemplateId() {
        // Ids drive dedupe and seven-day rotation, so they must be present and non-generic.
        allMessages().forEach { message ->
            assertTrue("empty template id", message.templateId.isNotBlank())
            assertTrue("template id should be namespaced: ${message.templateId}", message.templateId.contains('.'))
        }
    }

    @Test
    fun insufficientDataProducesNoMessage() {
        MessageTone.entries.forEach { tone ->
            assertNull(MessageCatalogue.timeSaved(TimeSavedResult.InsufficientData, tone))
        }
    }

    @Test
    fun theSavingMessageNamesItsBaseline() {
        val yesterday = MessageCatalogue.timeSaved(
            TimeSavedResult.Saved(15, SavingBaseline.YESTERDAY),
            MessageTone.CALM,
        )!!.text
        assertTrue("the baseline must be stated", yesterday.contains("yesterday"))

        val average = MessageCatalogue.timeSaved(
            TimeSavedResult.Saved(22, SavingBaseline.SEVEN_DAY_AVERAGE),
            MessageTone.CALM,
        )!!.text
        assertTrue(average.contains("seven-day average"))
    }

    @Test
    fun higherUsageIsStatedWithoutJudgement() {
        val text = MessageCatalogue.timeSaved(
            TimeSavedResult.Higher(30, SavingBaseline.YESTERDAY),
            MessageTone.CALM,
        )!!.text
        assertTrue(text.contains("30"))
        assertTrue(text.contains("above"))
        // Never rendered as a negative saving.
        assertFalse(text.contains("saved"))
        assertFalse(text.contains("-30"))
    }

    @Test
    fun toneChangesWordingButNotNumbers() {
        val texts = MessageTone.entries.map {
            MessageCatalogue.timeSaved(TimeSavedResult.Saved(15, SavingBaseline.YESTERDAY), it)!!.text
        }
        assertTrue("wording should differ by tone", texts.toSet().size > 1)
        texts.forEach { assertTrue("every tone reports the same 15 minutes", it.contains("15")) }
    }

    @Test
    fun blockedCountsAreNeverExpressedAsTime() {
        val message = MessageCatalogue.blockedCount(8)!!
        assertTrue(message.text.contains("8"))
        assertFalse(message.text.contains("minute"))
        assertFalse(message.text.contains("hour"))
        assertFalse(message.text.contains("saved"))
    }

    @Test
    fun singularAndPluralAreHandled() {
        assertTrue(MessageCatalogue.blockedCount(1)!!.text.startsWith("One"))
        assertTrue(MessageCatalogue.instagramRemaining(1).text.contains("minute remains"))
        assertTrue(MessageCatalogue.instagramRemaining(5).text.contains("minutes remain"))
    }

    @Test
    fun nothingIsClaimedFromZeroOrNegativeInput() {
        assertNull(MessageCatalogue.blockedCount(0))
        assertNull(MessageCatalogue.blockedCount(-3))
        assertNull(MessageCatalogue.percentBelowAverage(0))
        assertNull(MessageCatalogue.percentBelowAverage(-5))
    }

    @Test
    fun protectionMessageIsFactual() {
        val message = MessageCatalogue.protectionsIntact()
        assertNotNull(message)
        assertTrue(message.text.contains("protection"))
    }
}
