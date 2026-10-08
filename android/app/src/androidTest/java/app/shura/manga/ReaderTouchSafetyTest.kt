package app.shura.manga

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertFalse
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class ReaderTouchSafetyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun verticalSwipeStillAdvancesReaderContent() {
        val pages = (1..4).map { Uri.parse("content://fixture/page/$it") }
        compose.setContent { MaterialTheme { ReaderScreen(pages, 0, {}) } }
        compose.onNodeWithTag("reader-pages").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText("Local chapter · 1/4").assertDoesNotExist()
    }

    @Test fun translationControlIsDisabledAndReaderDeclaresNoScreenOverlayPermission() {
        val pages = listOf(Uri.parse("content://fixture/page/1"))
        compose.setContent { MaterialTheme { ReaderScreen(pages, 0, {}) } }
        compose.onNodeWithText("Translate").assertIsNotEnabled()
        // No SYSTEM_ALERT_WINDOW or accessibility service is declared by this application.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val requested = context.packageManager.getPackageInfo(
            context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty().toSet()
        assertFalse("screen overlay permission must remain absent", "android.permission.SYSTEM_ALERT_WINDOW" in requested)
    }
}
