package android.view

open class KeyEvent {
    fun getAction(): Int = 0

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
    }
}
