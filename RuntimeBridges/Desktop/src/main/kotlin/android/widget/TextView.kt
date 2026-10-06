package android.widget

import android.content.Context
import android.graphics.Typeface
import android.view.View

open class TextView(context: Context? = null) : View(context) {
    var text: CharSequence = ""

    open fun setText(text: CharSequence?) { this.text = text ?: "" }
    open fun setTextColor(color: Int) {}
    open fun setTextSize(size: Float) {}
    open fun setTypeface(tf: Typeface?) {}
    open fun setLetterSpacing(spacing: Float) {}
    open fun setLineSpacing(add: Float, mult: Float) {}
    open fun setGravity(gravity: Int) {}
}
