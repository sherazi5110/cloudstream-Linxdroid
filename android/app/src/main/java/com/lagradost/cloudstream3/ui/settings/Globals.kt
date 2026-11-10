package com.lagradost.cloudstream3.ui.settings

import android.content.res.Configuration
import android.content.res.Resources
import io.github.sherazi5110.cloudstream.App

/** The official app's layout flags (ui/settings/Globals.kt): extensions check TV vs phone with isLayout(TV). */
object Globals {
    const val PHONE: Int = 0b00001
    const val TV: Int = 0b00010
    const val EMULATOR: Int = 0b00100

    fun isLayout(flags: Int): Boolean = ((if (App.isTv) TV else PHONE) and flags) != 0

    fun isLandscape(): Boolean = isLayout(TV or EMULATOR) ||
        Resources.getSystem().configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}
