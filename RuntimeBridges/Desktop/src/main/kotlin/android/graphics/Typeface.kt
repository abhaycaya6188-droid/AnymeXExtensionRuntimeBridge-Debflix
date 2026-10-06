package android.graphics

open class Typeface {
    companion object {
        @JvmField
        val DEFAULT = Typeface()
        @JvmField
        val DEFAULT_BOLD = Typeface()

        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2
        const val BOLD_ITALIC = 3
    }
}
