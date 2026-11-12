package io.github.sherazi5110.cloudstream

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.CommonActivity
import io.github.sherazi5110.cloudstream.ui.AppRoot
import io.github.sherazi5110.cloudstream.ui.CloudStreamTheme
import io.github.sherazi5110.cloudstream.ui.Nav
import io.github.sherazi5110.cloudstream.ui.PendingRepo
import io.github.sherazi5110.cloudstream.ui.Tab

/**
 * The app's screen. It is the official app's MainActivity type (an AppCompatActivity), since extensions' settings
 * screens expect one (supportFragmentManager) and some check `activity is MainActivity`.
 */
class MainActivity : com.lagradost.cloudstream3.MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light icons on dark bars, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        CommonActivity.activity = this
        handle(intent)
        // The intro plays once per launch (not after rotation or returning to the app).
        val playIntro = savedInstanceState == null && Settings.startupAnimation
        setContent {
            CloudStreamTheme {
                var intro by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(playIntro) }
                AppRoot()
                if (intro) io.github.sherazi5110.cloudstream.ui.IntroScreen { intro = false }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        if (CommonActivity.activity === this) CommonActivity.activity = null
        super.onDestroy()
    }

    /** cloudstreamrepo://… and https://cs.repo/… links open the Add repository dialog. */
    private fun handle(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        PendingRepo.url = data
        Nav.select(Tab.EXTENSIONS)
    }
}
