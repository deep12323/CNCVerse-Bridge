package android.app

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.ListView

/** android.app stubs — verification targets for plugin settings dialogs. */
open class Activity : Context() {
    fun runOnUiThread(action: Runnable) { action.run() }
    fun finish() {}
    fun getIntent(): android.content.Intent? = null
    override fun startActivity(intent: android.content.Intent?) {}
}

class AlertDialog : DialogInterface {
    private var showListener: DialogInterface.OnShowListener? = null
    var isShowing: Boolean = false
        private set

    fun show() { isShowing = true; showListener?.onShow(this) }
    override fun dismiss() { isShowing = false }
    override fun cancel() { isShowing = false }
    fun setOnShowListener(listener: DialogInterface.OnShowListener?) { showListener = listener }
    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {}
    fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) {}
    fun setCancelable(cancelable: Boolean) {}
    fun getListView(): ListView? = null
    fun getWindow(): android.view.Window? = null

    class Builder {
        private var view: View? = null

        constructor(context: Context?)
        constructor(context: Context?, themeResId: Int)

        fun setTitle(title: CharSequence?): Builder = this
        fun setTitle(titleId: Int): Builder = this
        fun setMessage(message: CharSequence?): Builder = this
        fun setView(view: View?): Builder = this.also { this.view = view }
        fun setView(layoutResId: Int): Builder = this
        fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setPositiveButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNeutralButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setItems(items: Array<CharSequence>?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setItems(itemsId: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setSingleChoiceItems(items: Array<CharSequence>?, checkedItem: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setSingleChoiceItems(itemsId: Int, checkedItem: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setMultiChoiceItems(items: Array<CharSequence>?, checkedItems: BooleanArray?, listener: DialogInterface.OnMultiChoiceClickListener?): Builder = this
        fun setMultiChoiceItems(itemsId: Int, checkedItems: BooleanArray?, listener: DialogInterface.OnMultiChoiceClickListener?): Builder = this
        fun setCancelable(cancelable: Boolean): Builder = this
        fun setOnDismissListener(listener: DialogInterface.OnDismissListener?): Builder = this
        fun setOnCancelListener(listener: DialogInterface.OnCancelListener?): Builder = this
        fun create(): AlertDialog = AlertDialog()
        fun show(): AlertDialog = AlertDialog()
    }
}
