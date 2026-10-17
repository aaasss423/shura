package app.shura.manga

import app.shura.manga.ui.Strings
import app.shura.manga.ui.theme.UiLanguage
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * What a repository row says, as words rather than as colour.
 *
 * WHY THIS IS NOT IN THE SCREEN
 *
 * Because every claim here is checkable without a device: the status of a row,
 * the count beside it, the time of the last check and the reason for a refusal
 * are all decided by functions over a [RepositoryRecord]. A screen that built
 * its own sentences would put the judgement - "is this repository good?" -
 * somewhere it cannot be tested, and the judgement is the whole of it.
 *
 * WHY EVERY STATE IS NAMED
 *
 * A row that can only be read by its colour is unreadable to anyone who cannot
 * see it, and "it looks fine" is the state a failed repository looks like in a
 * UI that only dims. So the status is a word, the count is a number, and a
 * refusal shows the reason the runtime gave rather than "something went wrong".
 */
object RepositoryStatusText {

    /** The one word that says whether this repository may be used. */
    fun status(record: RepositoryRecord, language: UiLanguage): String = when (record.status) {
        RepositoryStatus.NEVER_CHECKED -> Strings.t("repos.status.neverChecked", language)
        RepositoryStatus.OK -> Strings.t("repos.status.ok", language)
        RepositoryStatus.FAILED -> Strings.t("repos.status.failed", language)
    }

    /** How many extensions the repository's directory actually yields. */
    fun packages(record: RepositoryRecord, language: UiLanguage): String =
        Strings.t("repos.packages", language, record.packages)

    /**
     * When the repository was last checked, or "" when it never has been.
     *
     * The time is formatted for the language the app is speaking rather than
     * for the device, so an Arabic interface says the date in the same order
     * the rest of the screen does.
     */
    fun checkedAt(record: RepositoryRecord, language: UiLanguage): String {
        if (record.checkedAt <= 0L) return ""
        val locale = if (language == UiLanguage.ARABIC) Locale.forLanguageTag("ar") else Locale.ENGLISH
        val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
            .format(Date(record.checkedAt))
        return Strings.t("repos.checkedAt", language, stamp)
    }

    /** Why the repository was refused, in the user's terms. */
    fun refusal(refusal: RepositoryRefusal, language: UiLanguage): String =
        Strings.t(
            when (refusal) {
                RepositoryRefusal.BAD_URL -> "repos.refusal.badUrl"
                RepositoryRefusal.NOT_HTTPS -> "repos.refusal.notHttps"
                RepositoryRefusal.NETWORK -> "repos.refusal.network"
                RepositoryRefusal.HTTP_STATUS -> "repos.refusal.httpStatus"
                RepositoryRefusal.TOO_LARGE -> "repos.refusal.tooLarge"
                RepositoryRefusal.UNSUPPORTED_INDEX -> "repos.refusal.unsupportedIndex"
                RepositoryRefusal.EMPTY_INDEX -> "repos.refusal.emptyIndex"
                RepositoryRefusal.ANCHOR -> "repos.refusal.anchor"
                RepositoryRefusal.NOT_VERIFIED -> "repos.refusal.notVerified"
                RepositoryRefusal.INSTALL_FAILED -> "repos.refusal.installFailed"
            },
            language,
        )

    /**
     * The meta line under a repository's name: what it is, what state it is in,
     * how many packages it yields and when it was last checked.
     *
     * The reason comes before the count on a failure, because on a failure the
     * reason is the only thing worth reading.
     *
     * [RepositoryRecord.detail] is deliberately *not* drawn. It is the runtime's
     * own sentence, in English, and it exists so a failure can be diagnosed from
     * what the code actually said rather than from the category alone - but a
     * row that mixes an Arabic interface with an English diagnostic is the same
     * defect as a screen with one hardcoded string, and the refusal line below
     * is already specific enough to act on. The detail stays on the record.
     */
    fun meta(record: RepositoryRecord, language: UiLanguage): String {
        val lines = mutableListOf(status(record, language))
        if (record.status == RepositoryStatus.FAILED && record.refusal != null) {
            lines += refusal(record.refusal, language)
        } else {
            lines += packages(record, language)
        }
        checkedAt(record, language).takeIf { it.isNotBlank() }?.let { lines += it }
        return lines.joinToString("\n")
    }

    /** The line above the list, said before the rows rather than inside one. */
    fun summary(records: List<RepositoryRecord>, language: UiLanguage): String =
        Strings.t(
            "repos.summary",
            language,
            records.size,
            records.sumOf { it.packages },
        )

    /**
     * What one add or refresh did, said in the reader's language.
     *
     * A refusal is reported as its own reason and nothing else: "the address
     * could not be reached" is the answer, and padding it with a second
     * sentence about what failed underneath would only make it harder to act on.
     */
    fun notice(outcome: RepositoryOutcome, language: UiLanguage): String = when (outcome) {
        is RepositoryOutcome.Registered -> Strings.t(
            if (outcome.added) "repos.added" else "repos.updated",
            language,
            outcome.record.name,
            outcome.record.packages,
        )
        is RepositoryOutcome.Refused -> refusal(outcome.refusal, language)
    }

    /**
     * What removing a repository did.
     *
     * Two outcomes, and they must not be blurred: the files went, or they did
     * not and the row is still there. Saying "removed" over a directory that is
     * still on disk is the one lie this screen cannot tell.
     */
    fun removed(record: RepositoryRecord, deleted: Boolean, language: UiLanguage): String =
        Strings.t(
            if (deleted) "repos.removed" else "repos.removeFailed",
            language,
            record.name,
        )
}