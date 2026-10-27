package app.shura.manga.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuraThemeColorTest {

    @Test
    fun dynamicColorsStayOffByDefault() {
        assertFalse(shouldUseDynamicColor(enabled = false, sdkInt = 35))
    }

    @Test
    fun dynamicColorsAreAvailableFromAndroid12() {
        assertTrue(shouldUseDynamicColor(enabled = true, sdkInt = 31))
    }

    @Test
    fun dynamicColorsFallBackOnOlderAndroidVersions() {
        assertFalse(shouldUseDynamicColor(enabled = true, sdkInt = 30))
    }
}
