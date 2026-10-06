package android.view

import android.content.Context
import android.graphics.drawable.Drawable

open class View(val context: Context? = null) {
    fun interface OnClickListener {
        fun onClick(v: View)
    }

    fun interface OnKeyListener {
        fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean
    }

    @JvmField var visibility: Int = 0
    @JvmField var alpha: Float = 1.0f
    @JvmField var translationX: Float = 0f
    @JvmField var translationY: Float = 0f
    @JvmField var scaleX: Float = 1.0f
    @JvmField var scaleY: Float = 1.0f
    @JvmField var elevation: Float = 0f

    open fun setBackground(drawable: Drawable?) {}
    open fun setBackgroundColor(color: Int) {}
    open fun setLayoutParams(params: ViewGroup.LayoutParams?) {}
    open fun setTranslationX(translationX: Float) { this.translationX = translationX }
    open fun setTranslationY(translationY: Float) { this.translationY = translationY }
    open fun setElevation(elevation: Float) { this.elevation = elevation }
    open fun setAlpha(alpha: Float) { this.alpha = alpha }
    open fun setScaleX(scaleX: Float) { this.scaleX = scaleX }
    open fun setScaleY(scaleY: Float) { this.scaleY = scaleY }
    open fun setVisibility(visibility: Int) { this.visibility = visibility }

    open fun getWidth(): Int = 0
    open fun getHeight(): Int = 0
    open fun requestFocus(): Boolean = true
    open fun setFocusable(focusable: Boolean) {}
    open fun setFocusableInTouchMode(focusable: Boolean) {}
    open fun setClickable(clickable: Boolean) {}
    open fun setOnClickListener(l: OnClickListener?) {}
    open fun setOnKeyListener(l: OnKeyListener?) {}
    open fun getViewTreeObserver(): ViewTreeObserver = ViewTreeObserver()
    open fun animate(): ViewPropertyAnimator = ViewPropertyAnimator()
    open fun post(action: Runnable): Boolean {
        action.run()
        return true
    }
    open fun requestLayout() {}
    open fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {}
}
