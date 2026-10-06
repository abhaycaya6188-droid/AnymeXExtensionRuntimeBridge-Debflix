package android.content.res

import android.util.DisplayMetrics

open class Resources {
    private val metrics = DisplayMetrics()
    open fun getDisplayMetrics(): DisplayMetrics = metrics
}
