package com.google.android.material.button

/**
 * Verification target for plugins using Material buttons in settings UIs.
 * Extends Button so text/isAllCaps property access from plugin code works.
 */
open class MaterialButton : android.widget.Button {
    constructor(context: android.content.Context?) : super(context)
    constructor(context: android.content.Context?, attrs: Any?) : super(context, attrs)
}
