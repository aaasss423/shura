package app.shura.manga

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppStartupTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstScreenIsVisibleOnFirstAndSubsequentActivityLaunches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.waitUntil(60_000) {
            File(context.filesDir, "repo/index.shura.json").isFile
        }
        assertTrue(File(context.filesDir, "repo/repo.json").isFile)
        compose.onNodeWithText("لا شيء للقراءة بعد").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("لا شيء للقراءة بعد").assertIsDisplayed()
    }
}
