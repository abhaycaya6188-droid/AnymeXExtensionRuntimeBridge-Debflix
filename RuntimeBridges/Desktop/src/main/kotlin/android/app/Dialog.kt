package android.app

import android.content.Context
import android.content.DialogInterface
import android.view.Window

open class Dialog(val context: Context? = null) : DialogInterface {
    open fun getWindow(): Window? = Window()
    open fun show() {}
    override fun dismiss() {}
    override fun cancel() {}
}

open class AlertDialog(context: Context? = null) : Dialog(context) {
    open class Builder(val context: Context? = null) {
        fun setTitle(title: CharSequence?): Builder = this
        fun setMessage(message: CharSequence?): Builder = this
        fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setCancelable(cancelable: Boolean): Builder = this
        fun create(): AlertDialog = AlertDialog(context)
        fun show(): AlertDialog = AlertDialog(context).apply { show() }
    }
}
