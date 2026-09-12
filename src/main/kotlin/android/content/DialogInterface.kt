package android.content

/** android.content.DialogInterface stubs (used in settings-dialog signatures). */
interface DialogInterface {
    fun dismiss()
    fun cancel()

    interface OnClickListener {
        fun onClick(dialog: DialogInterface?, which: Int)
    }

    interface OnDismissListener {
        fun onDismiss(dialog: DialogInterface?)
    }

    interface OnCancelListener {
        fun onCancel(dialog: DialogInterface?)
    }

    interface OnShowListener {
        fun onShow(dialog: DialogInterface?)
    }

    interface OnMultiChoiceClickListener {
        fun onClick(dialog: DialogInterface?, which: Int, isChecked: Boolean)
    }

    companion object {
        const val BUTTON_POSITIVE = -1
        const val BUTTON_NEGATIVE = -2
        const val BUTTON_NEUTRAL = -3
    }
}
