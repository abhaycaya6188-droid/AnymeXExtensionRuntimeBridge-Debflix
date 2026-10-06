package android.graphics

object Color {
    const val BLACK = -16777216
    const val DKGRAY = -12303292
    const val GRAY = -7829368
    const val LTGRAY = -3355444
    const val WHITE = -1
    const val RED = -65536
    const val GREEN = -16711936
    const val BLUE = -16776961
    const val YELLOW = -256
    const val CYAN = -16711681
    const val MAGENTA = -65281
    const val TRANSPARENT = 0

    @JvmStatic
    fun parseColor(colorString: String): Int {
        if (colorString.startsWith("#")) {
            var color = java.lang.Long.parseLong(colorString.substring(1), 16)
            if (colorString.length == 7) {
                color = color or -0x1000000L
            }
            return color.toInt()
        }
        return 0
    }

    @JvmStatic
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
