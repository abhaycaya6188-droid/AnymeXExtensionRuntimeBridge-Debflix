package android.widget

import android.content.Context
import android.view.ViewGroup

open class LinearLayout(context: Context? = null) : ViewGroup(context) {
    var orientation: Int = 0
    var gravity: Int = 0
    var weightSum: Float = 0f

    fun setOrientation(orientation: Int) { this.orientation = orientation }
    fun setGravity(gravity: Int) { this.gravity = gravity }
    fun setWeightSum(weightSum: Float) { this.weightSum = weightSum }
    fun setClipToOutline(clip: Boolean) {}

    open class LayoutParams : ViewGroup.LayoutParams {
        var topMargin: Int = 0
        var bottomMargin: Int = 0
        var rightMargin: Int = 0
        var leftMargin: Int = 0
        var weight: Float = 0f

        constructor(width: Int, height: Int) : super(width, height)
        constructor(width: Int, height: Int, weight: Float) : super(width, height) {
            this.weight = weight
        }
    }

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}
