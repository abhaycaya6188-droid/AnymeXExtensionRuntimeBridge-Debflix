package android.view

import android.content.Context

open class ViewGroup(context: Context? = null) : View(context) {
    open class LayoutParams(@JvmField var width: Int = 0, @JvmField var height: Int = 0) {
        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }

    open fun addView(child: View?) {}
    open fun addView(child: View?, params: LayoutParams?) {}
}
