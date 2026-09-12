package androidx.appcompat.app

import android.content.Context
import android.content.DialogInterface

/** androidx AlertDialog stub (distinct from android.app.AlertDialog). */
open class AlertDialog : DialogInterface {
    fun show() {}
    override fun dismiss() {}
    override fun cancel() {}
    fun setOnShowListener(listener: DialogInterface.OnShowListener?) {}
    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {}
    fun setCancelable(cancelable: Boolean) {}

    class Builder {
        constructor(context: Context?)
        constructor(context: Context?, themeResId: Int)

        fun setTitle(title: CharSequence?): Builder = this
        fun setTitle(titleId: Int): Builder = this
        fun setMessage(message: CharSequence?): Builder = this
        fun setView(view: android.view.View?): Builder = this
        fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setPositiveButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setSingleChoiceItems(items: Array<CharSequence>?, checkedItem: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setMultiChoiceItems(items: Array<CharSequence>?, checkedItems: BooleanArray?, listener: DialogInterface.OnMultiChoiceClickListener?): Builder = this
        fun setCancelable(cancelable: Boolean): Builder = this
        fun create(): AlertDialog = AlertDialog()
        fun show(): AlertDialog = AlertDialog()
    }
}
