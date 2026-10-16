package app.shura.translator.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits raw chapter text into translatable segments.
 *
 * Manga and manhwa pages are a sequence of short lines — dialogue balloons and sound
 * effects — each of which needs its own translation so the layout survives. Sending
 * a whole page as one blob produces a single paragraph that no balloon can use.
 *
 * The split is by line, which is what OCR output and most source formats give. It is
 * deliberately dumb: no heuristics about punctuation or length, because a wrong
 * merge is much harder to notice in Arabic than a wrong split.
 *
 * Pure Java, no Android types, so the behaviour is unit testable.
 */
public final class SegmentParser {

    /** Server-side hard limit is 5000 segments; stay well under it. */
    public static final int MAX_SEGMENTS = 2000;

    private SegmentParser() {
    }

    /**
     * One entry per non-blank line, in order.
     *
     * Blank lines are dropped rather than translated: an empty balloon is a scanning
     * artefact, and sending it produces a wasted request.
     */
    public static List<String> parse(String raw) {
        List<String> segments = new ArrayList<>();
        if (raw == null) {
            return segments;
        }
        for (String line : raw.split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            segments.add(trimmed);
            if (segments.size() >= MAX_SEGMENTS) {
                break;
            }
        }
        return segments;
    }

    /**
     * Collapses whitespace inside one segment.
     *
     * OCR on a curved balloon often breaks a line mid-word. Joining with a space
     * keeps the word intact rather than letting a stray newline reach the model.
     */
    public static String normalizeSegment(String segment) {
        if (segment == null) {
            return "";
        }
        return segment.replaceAll("\\s+", " ").trim();
    }

    /** Joins translated segments back into readable page text, one per line. */
    public static String join(List<String> segments, String separator) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        String glue = separator == null ? "\n" : separator;
        StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            if (out.length() > 0) {
                out.append(glue);
            }
            out.append(segment == null ? "" : segment);
        }
        return out.toString();
    }

    public static int countCharacters(List<String> segments) {
        int total = 0;
        for (String segment : segments) {
            total += segment == null ? 0 : segment.length();
        }
        return total;
    }
}
