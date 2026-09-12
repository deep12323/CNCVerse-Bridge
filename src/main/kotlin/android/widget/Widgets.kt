package android.widget

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup

/** android.widget stubs — verification targets with a few functional members. */
open class TextView : View {
    constructor() : super()
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    var text: CharSequence = ""
        private set

    fun setText(text: CharSequence?) { this.text = text ?: "" }
    fun setText(resId: Int) { this.text = "" }
    fun setHint(hint: CharSequence?) {}
    fun setTextColor(color: Int) {}
    fun setTextSize(size: Float) {}
    fun setTypeface(tf: Typeface?, style: Int) {}
    fun setTypeface(tf: Typeface?) {}
    fun setGravity(gravity: Int) {}
    fun setSingleLine() {}
    fun setMaxLines(maxLines: Int) {}
    fun setAllCaps(allCaps: Boolean) {}
}

open class EditText : TextView() {
    fun setRawInputType(type: Int) {}
    fun setFilters(filters: Array<Any>) {}
}

open class CompoundButton : TextView() {
    var isChecked: Boolean = false
        set(value) { field = value; listener?.onCheckedChanged(this, value) }
    private var listener: OnCheckedChangeListener? = null

    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) { this.listener = listener }
    fun toggle() { isChecked = !isChecked }

    interface OnCheckedChangeListener {
        fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean)
    }
}

class CheckBox : CompoundButton {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()
}

open class Button : TextView {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()
}

class ImageButton : View {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {}
    fun setImageResource(resId: Int) {}
}

open class LinearLayout : ViewGroup {
    constructor() : super()
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    var orientation: Int = HORIZONTAL

    fun setWeightSum(weightSum: Float) {}

    class LayoutParams : ViewGroup.MarginLayoutParams {
        @JvmField var weight: Float = 0f
        @JvmField var gravity: Int = 0

        constructor(width: Int, height: Int) : super(width, height)
        constructor(width: Int, height: Int, weight: Float) : super(width, height) { this.weight = weight }
        constructor(source: LayoutParams) : super(source) { weight = source.weight; gravity = source.gravity }
        constructor(c: Context?, attrs: Any?) : super(c, attrs)
    }

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}

open class RelativeLayout : ViewGroup {
    constructor() : super()
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    class LayoutParams : ViewGroup.MarginLayoutParams {
        constructor(width: Int, height: Int) : super(width, height)
        constructor(c: Context?, attrs: Any?) : super(c, attrs)
        fun addRule(rule: Int) {}
        fun addRule(verb: Int, subject: Int) {}
    }

    companion object {
        const val TRUE = -1
        const val ABOVE = 2
        const val BELOW = 3
        const val ALIGN_BASELINE = 4
        const val ALIGN_START = 17
        const val ALIGN_END = 18
        const val ALIGN_TOP = 6
        const val ALIGN_BOTTOM = 8
        const val ALIGN_PARENT_TOP = 10
        const val ALIGN_PARENT_BOTTOM = 12
        const val CENTER_IN_PARENT = 13
        const val CENTER_HORIZONTAL = 14
        const val CENTER_VERTICAL = 15
        const val START_OF = 16
        const val END_OF = 19
        const val ALIGN_PARENT_START = 20
        const val ALIGN_PARENT_END = 21
    }
}

open class ScrollView : ViewGroup {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()
    fun setFillViewport(fillViewport: Boolean) {}
}

open class ListView : ViewGroup {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()
    fun setAdapter(adapter: Any?) {}
    fun setDividerHeight(height: Int) {}
    fun setChoiceMode(choiceMode: Int) {}
    fun setOnItemClickListener(listener: Any?) {}
    var checkedItem: Int = -1
}

open class FrameLayout : ViewGroup {
    constructor() : super()
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    class LayoutParams : ViewGroup.MarginLayoutParams {
        @JvmField var gravity: Int = 0
        constructor(width: Int, height: Int) : super(width, height)
        constructor(source: LayoutParams) : super(source) { gravity = source.gravity }
        constructor(c: Context?, attrs: Any?) : super(c, attrs)
    }
}

class Toast private constructor() {
    private var duration = LENGTH_SHORT
    private var text: CharSequence = ""

    fun setDuration(duration: Int) { this.duration = duration }
    fun setText(text: CharSequence) { this.text = text }
    fun show() {
        com.cncverse.stremiobridge.state.ServerState.info("[Toast] $text")
    }
    fun cancel() {}

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast =
            Toast().apply { this.text = text ?: ""; this.duration = duration }

        @JvmStatic
        fun makeText(context: Context?, resId: Int, duration: Int): Toast =
            Toast().apply { this.text = ""; this.duration = duration }
    }
}
