package android.view

open class ViewTreeObserver {
    fun interface OnGlobalLayoutListener {
        fun onGlobalLayout()
    }

    fun addOnGlobalLayoutListener(listener: OnGlobalLayoutListener) {}
    fun removeOnGlobalLayoutListener(listener: OnGlobalLayoutListener) {}
}
