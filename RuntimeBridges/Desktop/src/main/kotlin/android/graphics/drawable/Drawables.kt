package android.graphics.drawable

open class Drawable

open class ColorDrawable(val color: Int = 0) : Drawable()

open class GradientDrawable : Drawable {
    enum class Orientation {
        TOP_BOTTOM,
        TR_BL,
        RIGHT_LEFT,
        BR_TL,
        BOTTOM_TOP,
        BL_TR,
        LEFT_RIGHT,
        TL_BR
    }

    constructor() : super()
    constructor(orientation: Orientation, colors: IntArray) : super()

    fun setColor(color: Int) {}
    fun setCornerRadius(radius: Float) {}
    fun setShape(shape: Int) {}
    fun setStroke(width: Int, color: Int) {}

    companion object {
        const val RECTANGLE = 0
        const val OVAL = 1
        const val LINE = 2
        const val RING = 3
    }
}
