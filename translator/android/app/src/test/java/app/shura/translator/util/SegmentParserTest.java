package app.shura.translator.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Chapter segmentation.
 *
 * A wrong merge here is much harder to notice in Arabic than a wrong split, so the
 * parser is deliberately literal and the tests pin that literalness.
 */
public class SegmentParserTest {

    @Test
    public void oneSegmentPerNonBlankLine() {
        assertEquals(Arrays.asList("おはよう", "ありがとう", "さようなら"),
                SegmentParser.parse("おはよう\nありがとう\nさようなら"));
    }

    @Test
    public void dropsBlankLinesRatherThanTranslatingThem() {
        assertEquals(Arrays.asList("a", "b"), SegmentParser.parse("a\n\n   \n\nb\n"));
    }

    @Test
    public void handlesWindowsLineEndings() {
        assertEquals(Arrays.asList("a", "b"), SegmentParser.parse("a\r\nb"));
    }

    @Test
    public void handlesNullAndEmptyInput() {
        assertTrue(SegmentParser.parse(null).isEmpty());
        assertTrue(SegmentParser.parse("").isEmpty());
        assertTrue(SegmentParser.parse("   \n  ").isEmpty());
    }

    @Test
    public void keepsPunctuationAndSoundEffectMarkers() {
        assertEquals(Arrays.asList("ドーン！", "「そうか」", "…"), SegmentParser.parse("ドーン！\n「そうか」\n…"));
    }

    @Test
    public void trimsSurroundingWhitespace() {
        assertEquals(Arrays.asList("a", "b"), SegmentParser.parse("   a   \n\tb\t"));
    }

    @Test
    public void normalizesWhitespaceInsideASegmentFromCurvedOcr() {
        assertEquals("俺は お前が 嫌いだ", SegmentParser.normalizeSegment("俺は\n  お前が   嫌いだ"));
        assertEquals("", SegmentParser.normalizeSegment(null));
    }

    @Test
    public void joinsTranslatedSegmentsBackIntoReadableText() {
        assertEquals("أ\nب\nج", SegmentParser.join(Arrays.asList("أ", "ب", "ج"), "\n"));
        assertEquals("أ | ب", SegmentParser.join(Arrays.asList("أ", "ب"), " | "));
        assertEquals("", SegmentParser.join(Collections.emptyList(), "\n"));
        assertEquals("", SegmentParser.join(null, "\n"));
    }

    @Test
    public void countsCharactersForAProgressEstimate() {
        assertEquals(6, SegmentParser.countCharacters(Arrays.asList("abc", "def")));
        assertEquals(0, SegmentParser.countCharacters(Collections.emptyList()));
    }

    @Test
    public void stopsAtTheSegmentCeilingSoOneHugeFileCannotExhaustTheServer() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < SegmentParser.MAX_SEGMENTS + 500; i++) {
            huge.append("line\n");
        }
        assertEquals(SegmentParser.MAX_SEGMENTS, SegmentParser.parse(huge.toString()).size());
    }
}
