package android.view

open class ViewPropertyAnimator {
    fun alpha(value: Float): ViewPropertyAnimator = this
    fun scaleX(value: Float): ViewPropertyAnimator = this
    fun scaleY(value: Float): ViewPropertyAnimator = this
    fun setDuration(duration: Long): ViewPropertyAnimator = this
    fun setInterpolator(interpolator: android.animation.TimeInterpolator?): ViewPropertyAnimator = this
    fun start() {}
}
