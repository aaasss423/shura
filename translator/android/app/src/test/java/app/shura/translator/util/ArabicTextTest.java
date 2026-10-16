package app.shura.translator.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Direction and content sanity checks.
 *
 * The server sends an explicit `direction`, so the client's job is to obey it and
 * only fall back to inspection when the field is missing.
 */
public class ArabicTextTest {

    @Test
    public void detectsArabicScript() {
        assertTrue(ArabicText.containsArabic("صباح الخير"));
        assertFalse(ArabicText.containsArabic("おはよう"));
        assertFalse(ArabicText.containsArabic("Good morning"));
        assertFalse(ArabicText.containsArabic(""));
        assertFalse(ArabicText.containsArabic(null));
    }

    @Test
    public void serverDirectionWinsOverInspection() {
        // A line that is mostly Latin names inside Arabic dialogue is common in
        // translated manga; guessing from the text would flip it to LTR.
        assertTrue(ArabicText.isRtl("rtl", "قال لي Agent Smith نحن متفقون"));
        assertFalse(ArabicText.isRtl("ltr", "صباح الخير"));
    }

    @Test
    public void fallsBackToInspectionWhenTheServerSentNoDirection() {
        assertTrue(ArabicText.isRtl("", "صباح الخير"));
        assertFalse(ArabicText.isRtl(null, "おはよう"));
    }

    @Test
    public void countsArabicCharacters() {
        assertEquals(9, ArabicText.arabicCharacterCount("صباح الخير")); // 4 letters + 5 letters, space excluded
        assertEquals(0, ArabicText.arabicCharacterCount("おはよう"));
        assertEquals(0, ArabicText.arabicCharacterCount(null));
    }

    @Test
    public void flagsOutputThatIsNotUsableArabic() {
        assertTrue(ArabicText.looksUnusable(""));
        assertTrue(ArabicText.looksUnusable("   "));
        assertTrue(ArabicText.looksUnusable(null));
        assertTrue(ArabicText.looksUnusable("I cannot translate this text."));
        assertTrue(ArabicText.looksUnusable("Sorry, I am unable to comply."));
        assertTrue(ArabicText.looksUnusable("ERROR: model not loaded"));
        assertTrue(ArabicText.looksUnusable("<html>502</html>"));
    }

    @Test
    public void acceptsRealArabicOutput() {
        assertFalse(ArabicText.looksUnusable("صباح الخير"));
    }
}
