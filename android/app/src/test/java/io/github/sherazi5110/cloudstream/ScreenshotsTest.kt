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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import com.lagradost.cloudstream3.newMovieSearchResponse

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

/** Android TV at 1080p (960x540 to the app): the home banner must fit its title, plot and buttons below the top bar. */
class TvHomeBannerTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10.copy(screenWidth = 1920, screenHeight = 1080,
        density = com.android.resources.Density.XHIGH, orientation = com.android.resources.ScreenOrientation.LANDSCAPE), maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        Nav.stack.clear()
    }

    @Test
    fun banner() {
        val api = object : com.lagradost.cloudstream3.MainAPI() { override var name = "Eclipsia"; override val hasMainPage = true }
        val items = (1..6).map { i ->
            kotlinx.coroutines.runBlocking {
                api.newMovieSearchResponse("Resident Evil: The Final Chapter of a Long Title $i", "https://x/$i", com.lagradost.cloudstream3.TvType.Movie) { year = 2026 }
            }
        }
        val state = io.github.sherazi5110.cloudstream.ui.HomeState().apply {
            providers = listOf(api); this.api = api
            sections += Engine.HomeSection(0, api, com.lagradost.cloudstream3.MainPageData("Trending", ""),
                com.lagradost.cloudstream3.HomePageList("Trending", items))
        }
        paparazzi.snapshot("tv-home-banner") { CloudStreamTheme { io.github.sherazi5110.cloudstream.ui.WideHome(state) } }
    }
}

/** Stream Goat's profile pictures, as the profile editor shows them. */
class AvatarPicturesTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    @Test
    fun pictures() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        paparazzi.snapshot("avatar-pictures") {
            CloudStreamTheme {
                androidx.compose.foundation.layout.FlowRow(
                    androidx.compose.ui.Modifier.background(io.github.sherazi5110.cloudstream.ui.Palette.background).padding(12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    io.github.sherazi5110.cloudstream.ui.Avatars.pictures.forEach { io.github.sherazi5110.cloudstream.ui.Avatar("art:$it", 64.dp) }
                    io.github.sherazi5110.cloudstream.ui.Avatar("cat:green", 64.dp)
                }
            }
        }
    }
}
