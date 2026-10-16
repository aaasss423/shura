package app.shura.manga

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Makes a crash say what it was.
 *
 * Three reports in a row said "the app closed when I pressed something", and every
 * one of them cost a guess, because a process that dies takes the reason with it
 * unless something catches it first. So: the stack trace goes to a file the reader
 * can send, and the dialog on screen says it in plain language rather than the app
 * vanishing mid-tap.
 *
 * The handler chains to whatever was installed before it, so this observes the
 * crash and never swallows it.
 */
object CrashReporter {

    private const val FILE_NAME = "last-crash.txt"

    // Literals rather than resources: this dialog is not a composable, and the one
    // thing it has to get right is the stack trace underneath.
    private const val CRASH_TITLE = "The app closed unexpectedly"
    private const val CRASH_TITLE_AR = "التطبيق أُغلق بشكل غير متوقع"

    /** The previous handler, so this adds reporting rather than replacing the system. */
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install(activity: Activity) {
        if (previous != null) return
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val report = buildString {
                append("Shura reader crashed\n")
                append("when: ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("thread: ").append(thread.name).append("\n\n")
                append(StringWriter().also { error.printStackTrace(PrintWriter(it)) })
            }
            // Best effort: if writing the file itself fails there is nothing more
            // to do, and the original handler still runs.
            runCatching {
                File(activity.filesDir, FILE_NAME).writeText(report)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash, if this launch is after one. */
    fun lastCrash(activity: Activity): String? =
        File(activity.filesDir, FILE_NAME).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(activity: Activity) {
        runCatching { File(activity.filesDir, FILE_NAME).delete() }
    }

    /**
     * Shows the crash on screen.
     *
     * The first thing the reader sees is why the app went away, not a blank screen
     * and not a second guessing round.
     */
    fun show(activity: Activity) {
        val crash = lastCrash(activity) ?: return
        clear(activity)
        activity.runOnUiThread {
            runCatching {
                AlertDialog.Builder(activity)
                    .setTitle(CRASH_TITLE)
                    .setMessage("${CRASH_TITLE_AR}\n\n${crash.take(1200)}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}
