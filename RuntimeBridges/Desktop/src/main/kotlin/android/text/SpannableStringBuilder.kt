package android.text

open class SpannableStringBuilder(text: CharSequence = "") : CharSequence {
    private val sb = StringBuilder(text)

    override val length: Int get() = sb.length
    override fun get(index: Int): Char = sb[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = sb.subSequence(startIndex, endIndex)
    override fun toString(): String = sb.toString()

    fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {}
}
