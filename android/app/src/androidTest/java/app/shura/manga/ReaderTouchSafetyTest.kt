package app.shura.manga

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import app.shura.manga.ui.screens.ReaderScreen
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reader under touch, and what this application asks the system for.
 *
 * This file used to call `ReaderScreen(pages, 0, {})`, which stopped compiling when the
 * reader gained real parameters - so the one test that drove the reader on a device
 * had not run for several commits, and CI reported the failure only at
 * `compileDebugAndroidTestKotlin`.
 */
@RunWith(AndroidJUnit4::class)
class ReaderTouchSafetyTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Named arguments on purpose: the positional form is what stopped compiling,
     * and it stops again the moment the reader takes another parameter.
     */
    private fun reader(pages: List<Uri>) {
        compose.setContent {
            MaterialTheme {
                ReaderScreen(
                    pages = pages,
                    sourceId = "fixture",
                    chapterId = "chapter-1",
                    chapterTitle = "Local chapter",
                    savedPage = 0,
                    onProgress = {},
                    onPrevChapter = null,
                    onNextChapter = null,
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun verticalSwipeStillAdvancesReaderContent() {
        reader((1..4).map { Uri.parse("content://fixture/page/$it") })
        compose.onNodeWithTag("reader-pages").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText("Local chapter · 1/4").assertDoesNotExist()
    }

    @Test
    fun theTranslationControlIsUsableEvenWithNoEngine() {
        reader(listOf(Uri.parse("content://fixture/page/1")))
        compose.onNodeWithTag("reader-pages").performTouchInput { click() }
        compose.waitForIdle()
        // Enabled, deliberately: the mode is a reading preference and survives until an
        // engine exists. What it must not be is *missing* - an earlier test asserted
        // this chip was disabled, which contradicted the reader's own documented
        // behaviour and would have failed on a real device.
        compose.onNodeWithText("ترجمة").assertIsEnabled()
    }

    @Test
    fun noUnneededInstallOrScreenOverlayPermissionsAreRequested() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val requested = context.packageManager.getPackageInfo(
            context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()
        assertFalse("screen overlay permission must remain absent", "android.permission.SYSTEM_ALERT_WINDOW" in requested)
        assertFalse(
            "Shura loads verified extensions in-process and must not request package installation",
            "android.permission.REQUEST_INSTALL_PACKAGES" in requested,
        )
    }
}
