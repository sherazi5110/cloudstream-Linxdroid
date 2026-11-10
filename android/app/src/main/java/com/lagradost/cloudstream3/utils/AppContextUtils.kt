package com.lagradost.cloudstream3.utils

import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import io.github.sherazi5110.cloudstream.ui.Nav
import io.github.sherazi5110.cloudstream.ui.Screen

/** The official app's AppContextUtils, as far as extensions use it: open a title page, focus a dialog button. */
object AppContextUtils {
    fun loadResult(url: String, apiName: String, name: String, startAction: Int = 0, startValue: Int = 0) {
        Handler(Looper.getMainLooper()).post { Nav.open(Screen.Details(apiName, url, name, null)) }
    }

    fun AlertDialog.setDefaultFocus(buttonFlag: Int = DialogInterface.BUTTON_POSITIVE) {
        getButton(buttonFlag)?.run { isFocusableInTouchMode = true; requestFocus() }
    }
}
