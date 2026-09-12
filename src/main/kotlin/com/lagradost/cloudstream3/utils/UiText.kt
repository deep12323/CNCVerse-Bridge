package com.lagradost.cloudstream3.utils

import android.content.Context

open class UiText {
    open fun asString(context: Context?): String = ""

    open class DynamicString(val value: String) : UiText() {
        override fun asString(context: Context?): String = value
    }

    open class StringResource(val resId: Int, vararg val args: Any) : UiText() {
        override fun asString(context: Context?): String = context?.getString(resId, *args) ?: ""
    }
}
