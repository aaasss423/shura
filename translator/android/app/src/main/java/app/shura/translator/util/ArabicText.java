package app.shura.translator.util;

/**
 * Arabic text helpers.
 *
 * Small on purpose. The server already normalises Arabic (`normalizeArabic` in
 * `src/server/server.ts`); duplicating shaping logic in the client would create two
 * places to disagree. What the client owns is presentation and detection.
 */
public final class ArabicText {

    private ArabicText() {
    }

    /** Unicode range test; used to decide direction for unlabelled strings. */
    public static boolean containsArabic(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a line should be laid out right-to-left.
     *
     * The server sends an explicit `direction`, and when it does that wins: guessing
     * from the text would mis-handle a line that is mostly Latin names inside Arabic
     * dialogue, which is extremely common in translated manga.
     */
    public static boolean isRtl(String serverDirection, String text) {
        if (serverDirection != null && !serverDirection.isEmpty()) {
            return "rtl".equalsIgnoreCase(serverDirection);
        }
        return containsArabic(text);
    }

    /**
     * Counts Arabic characters, for a quick "did the model actually answer in Arabic"
     * check shown next to the result.
     */
    public static int arabicCharacterCount(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) {
                count++;
            }
        }
        return count;
    }

    /** True when a translation looks like a refusal, an error echo or a prompt repeat. */
    public static boolean looksUnusable(String text) {
        if (text == null || text.trim().isEmpty()) {
            return true;
        }
        String lower = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("i cannot") || lower.startsWith("i can't") || lower.startsWith("sorry,")) {
            return true;
        }
        return lower.startsWith("error") || lower.startsWith("http://") || lower.startsWith("<");
    }
}
