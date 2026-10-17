package app.shura.manga.reader

/**
 * The reader preferences that are not the layout mode.
 *
 * Separate from [ReaderMode] on purpose: those are per-reading-session choices
 * about how the screen looks, while these are how the reader is configured —
 * brightness, how much is preloaded, whether the screen stays awake. Both live
 * in the same store so one `save` writes a coherent set rather than three
 * half-applied ones.
 *
 * Every value has a default that works, so a store written by an older build, or
 * by none at all, still opens a readable reader.
 */
data class ReaderPreferences(
    /** 0 means "follow the system brightness". */
    val brightness: Float = 0f,
    /** Pages kept warm ahead of the reading position. */
    val preloadPages: Int = 3,
    /** Keeps the screen on while a chapter is open. */
    val keepScreenOn: Boolean = false,
    /** Pages downloaded at once; higher is faster and heavier on the connection. */
    val parallelDownloads: Int = 4,
    /**
     * Delete a chapter from the library once it has been read to the end.
     *
     * Mihon keeps this in its advanced reader settings and defaults it off, and
     * so does this: it frees the space a read chapter was holding, but it is the
     * reader's own library, and a chapter deleted by a setting nobody expected
     * to lose is not a thing to discover afterwards.
     */
    val deleteChapterAfterReading: Boolean = false,
) {
    /** Preload clamped to what a phone can actually hold. */
    val preload: Int get() = preloadPages.coerceIn(0, 10)

    val parallelism: Int get() = parallelDownloads.coerceIn(1, 8)

    /** True when the reader should override the window brightness. */
    val overridesBrightness: Boolean get() = brightness > 0f

    companion object {
        fun brightnessOf(value: Float): Float = value.coerceIn(0.05f, 1f)

        fun preloadOf(value: Int): Int = value.coerceIn(0, 10)

        fun parallelismOf(value: Int): Int = value.coerceIn(1, 8)
    }
}

