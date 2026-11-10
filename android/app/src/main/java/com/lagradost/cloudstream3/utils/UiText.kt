package com.lagradost.cloudstream3.utils

import android.content.Context

/** The official app's UI text (utils/TextUtil.kt): a plain string or a string resource. */
sealed class UiText {
    data class DynamicString(val value: String) : UiText() {
        override fun toString(): String = value
    }

    class StringResource(val resId: Int, val args: List<Any>) : UiText()

    fun asStringNull(context: Context?): String? = runCatching { asString(context ?: return null) }.getOrNull()

    fun asString(context: Context): String = when (this) {
        is DynamicString -> value
        is StringResource -> runCatching { context.getString(resId, *args.toTypedArray()) }.getOrDefault("")
    }
}

fun txt(value: String?): UiText? = value?.let { UiText.DynamicString(it) }
