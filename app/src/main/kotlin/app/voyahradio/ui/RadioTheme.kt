package app.voyahradio.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import app.voyahradio.R

object RadioTheme {
    fun apply(activity: Activity, theme: String?) {
        activity.setTheme(if ("amber" == theme) R.style.RadioTheme_Amber else R.style.RadioTheme)
    }

    fun color(context: Context, role: Int): Int {
        val value = TypedValue()
        context.getTheme().resolveAttribute(role, value, true)
        return value.data
    }
}
