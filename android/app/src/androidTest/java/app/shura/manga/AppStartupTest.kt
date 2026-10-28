package app.shura.manga

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppStartupTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstScreenIsVisibleOnFirstAndSubsequentActivityLaunches() {
        compose.onNodeWithText("لا شيء للقراءة بعد").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("لا شيء للقراءة بعد").assertIsDisplayed()
    }
}
