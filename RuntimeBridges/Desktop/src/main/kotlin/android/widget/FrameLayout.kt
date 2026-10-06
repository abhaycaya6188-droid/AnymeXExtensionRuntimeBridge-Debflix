package android.widget

import android.content.Context
import android.view.ViewGroup

open class FrameLayout(context: Context? = null) : ViewGroup(context) {
    open class LayoutParams : ViewGroup.LayoutParams {
        var gravity: Int = 0
        var leftMargin: Int = 0
        var topMargin: Int = 0
        var rightMargin: Int = 0
        var bottomMargin: Int = 0

        constructor(width: Int, height: Int) : super(width, height)
        constructor(width: Int, height: Int, gravity: Int) : super(width, height) {
            this.gravity = gravity
        }

        fun setMargins(left: Int, top: Int, right: Int, bottom: Int) {
            leftMargin = left
            topMargin = top
            rightMargin = right
            bottomMargin = bottom
        }
    }
}
