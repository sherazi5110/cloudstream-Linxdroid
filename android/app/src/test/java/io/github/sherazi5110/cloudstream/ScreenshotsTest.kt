package io.github.sherazi5110.cloudstream

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import io.github.sherazi5110.cloudstream.ui.AppRoot
import io.github.sherazi5110.cloudstream.ui.CloudStreamTheme
import io.github.sherazi5110.cloudstream.ui.Nav
import io.github.sherazi5110.cloudstream.ui.Screen
import io.github.sherazi5110.cloudstream.ui.Tab
import io.github.sherazi5110.cloudstream.ui.ThemeState
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pictures of the real Android screens, drawn on the computer (no phone or emulator):
 * ./gradlew :app:recordPaparazziDebug writes them to app/src/test/snapshots/images.
 */
class ScreenshotsTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        Nav.stack.clear()
        Nav.tab = Tab.HOME
    }

    private fun shot(name: String) = paparazzi.snapshot(name) { CloudStreamTheme { AppRoot() } }

    @Test fun phoneHome() { shot("phone-home") }

    @Test fun phoneSettings() { Nav.tab = Tab.MORE; shot("phone-settings") }

    @Test fun phoneAbout() { Nav.tab = Tab.MORE; Nav.stack.add(Screen.SettingsPage("ABOUT")); shot("phone-about") }

    @Test fun phonePlayerSettings() { Nav.tab = Tab.MORE; Nav.stack.add(Screen.SettingsPage("PLAYER")); shot("phone-player-settings") }

    @Test fun phoneSearch() { Nav.tab = Tab.SEARCH; Nav.searchQuery = ""; Nav.searchSubmitted = ""; shot("phone-search") }

    /** Typed but not searched yet: the box must show the typed text (7.2.0 showed the last search instead). */
    @Test fun phoneSearchTyping() { Nav.tab = Tab.SEARCH; Nav.searchQuery = "pixel"; Nav.searchSubmitted = ""; shot("phone-search-typing") }
}

/** Android TV: the icon rail and the settings beside it. */
class TvScreenshotsTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10.copy(orientation = com.android.resources.ScreenOrientation.LANDSCAPE), maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        Nav.stack.clear()
    }

    private fun shot(name: String) = paparazzi.snapshot(name) { CloudStreamTheme { AppRoot() } }

    @Test fun tabletHome() { Nav.tab = Tab.HOME; shot("tablet-home") }

    @Test fun tabletSettings() { Nav.tab = Tab.MORE; shot("tablet-settings") }
}
