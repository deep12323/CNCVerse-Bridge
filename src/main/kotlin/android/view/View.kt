package android.view

import android.content.Context
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser as PullParser

/**
 * android.view stubs. Plugins reference these in settings-dialog code; the
 * classes must exist so JVM class verification passes. Members are no-ops.
 */
open class View {
    interface OnClickListener {
        fun onClick(v: View?)
    }

    interface OnLongClickListener {
        fun onLongClick(v: View?): Boolean
    }

    interface OnKeyListener {
        fun onKey(v: View?, keyCode: Int, event: Any?): Boolean
    }

    interface OnTouchListener {
        fun onTouch(v: View?, event: Any?): Boolean
    }

    var id: Int = 0
    var isEnabled: Boolean = true
    var visibility: Int = VISIBLE
    var layoutParams: ViewGroup.LayoutParams? = null
    var clickListener: OnClickListener? = null

    /** Walks this view (and children for groups) for a matching id. */
    fun findViewById(id: Int): View? {
        if (this.id == id) return this
        if (this is ViewGroup) {
            for (i in 0 until this.getChildCount()) {
                this.getChildAt(i)?.findViewById(id)?.let { return it }
            }
        }
        return null
    }

    fun setOnClickListener(listener: OnClickListener?) { clickListener = listener }
    fun setOnLongClickListener(listener: OnLongClickListener?) {}
    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {}
    fun setBackgroundColor(color: Int) {}
    fun setBackground(background: Drawable?) {}
    fun setBackgroundDrawable(background: Drawable?) {}
    fun setAlpha(alpha: Float) {}
    fun invalidate() {}
    fun requestLayout() {}
    fun performClick(): Boolean { clickListener?.onClick(this); return true }
    fun bringToFront() {}

    // Kotlin property accessors (plugin bytecode calls the getters directly)
    val paddingLeft: Int get() = 0
    val paddingTop: Int get() = 0
    val paddingRight: Int get() = 0
    val paddingBottom: Int get() = 0

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8

        // Generated ids stay well below the 0x7f...... resource-id range
        private val nextGeneratedId = java.util.concurrent.atomic.AtomicInteger(0x100000)

        @JvmStatic
        fun generateViewId(): Int = nextGeneratedId.incrementAndGet()
    }
}

open class ViewGroup : View() {
    open class LayoutParams {
        @JvmField var width: Int = MATCH_PARENT
        @JvmField var height: Int = WRAP_CONTENT

        constructor(width: Int, height: Int) {
            this.width = width
            this.height = height
        }
        constructor(source: LayoutParams) {
            this.width = source.width
            this.height = source.height
        }
        constructor(c: Context?, attrs: Any?) {}

        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }

    /**
     * Margin params as on Android: public fields so plugin bytecode's PUTFIELD
     * accesses (topMargin = …) resolve, plus the start/end property setters.
     */
    open class MarginLayoutParams : LayoutParams {
        @JvmField var topMargin: Int = 0
        @JvmField var bottomMargin: Int = 0
        @JvmField var leftMargin: Int = 0
        @JvmField var rightMargin: Int = 0
        @JvmField var marginStart: Int = 0
        @JvmField var marginEnd: Int = 0

        constructor(width: Int, height: Int) : super(width, height)
        constructor(source: LayoutParams) : super(source)
        constructor(c: Context?, attrs: Any?) : super(c, attrs)

        fun setMarginStart(start: Int) { marginStart = start }
        fun setMarginEnd(end: Int) { marginEnd = end }
        fun getMarginStart(): Int = marginStart
        fun getMarginEnd(): Int = marginEnd
    }

    private val children = mutableListOf<View>()

    fun addView(child: View?) { child?.let { children.add(it) } }
    fun addView(child: View?, params: LayoutParams?) { child?.let { children.add(it); it.layoutParams = params } }
    fun removeView(view: View?) { children.remove(view) }
    fun removeAllViews() { children.clear() }
    fun getChildCount(): Int = children.size
    fun getChildAt(index: Int): View? = children.getOrNull(index)
}

class Window {
    fun setBackgroundDrawable(background: Drawable?) {}
    fun setLayout(width: Int, height: Int) {}
    fun setGravity(gravity: Int) {}
    fun setDimAmount(dim: Float) {}
    fun getAttributes(): Any? = null
}

class LayoutInflater private constructor() {
    fun inflate(resource: Int, root: ViewGroup?): View = View()
    fun inflate(resource: Int, root: ViewGroup?, attachToRoot: Boolean): View = View()

    /**
     * Functional inflation over an AXML parser: builds the stub widget tree
     * with android:id values attached, so plugin binding code can findViewById
     * and read/write its settings — which registers the keys for the desktop
     * settings dialog.
     */
    fun inflate(parser: org.xmlpull.v1.XmlPullParser?, root: ViewGroup?, attachToRoot: Boolean): View? {
        if (parser == null) return root
        return try {
            var current: View? = null
            val stack = ArrayDeque<ViewGroup>()
            var event = parser.eventType
            while (event != PullParser.END_DOCUMENT) {
                when (event) {
                    PullParser.START_TAG -> {
                        val view = createView(parser.name ?: "View", parser)
                        if (view != null) {
                            val parent = stack.lastOrNull()
                            if (parent != null) {
                                parent.addView(view)
                            } else if (current == null) {
                                current = view
                            }
                            if (view is ViewGroup) stack.addLast(view)
                        }
                    }
                    PullParser.END_TAG -> {
                        val top = stack.lastOrNull()
                        if (top != null && top.javaClass.simpleName.equals(parser.name, ignoreCase = true)) {
                            stack.removeLast()
                        }
                    }
                }
                event = parser.next()
            }
            // Attach to root like Android does when requested
            val result: View? = if (attachToRoot && root != null && current != null) {
                root.addView(current)
                root
            } else {
                current
            }
            result ?: root
        } catch (t: Throwable) {
            root
        }
    }

    private fun createView(name: String, parser: org.xmlpull.v1.XmlPullParser): View? {
        val view: View = when (name) {
            "TextView" -> android.widget.TextView()
            "EditText", "AutoCompleteTextView" -> android.widget.EditText()
            "CheckBox" -> android.widget.CheckBox(null)
            "Switch", "SwitchCompat" -> android.widget.CheckBox(null)
            "RadioButton" -> android.widget.CheckBox(null)
            "Button" -> android.widget.Button(null)
            "ImageButton" -> android.widget.ImageButton(null)
            "LinearLayout" -> android.widget.LinearLayout()
            "RelativeLayout" -> android.widget.RelativeLayout()
            "ScrollView", "NestedScrollView" -> android.widget.ScrollView(null)
            "FrameLayout" -> android.widget.FrameLayout()
            "ListView", "RecyclerView" -> android.widget.ListView(null)
            else -> View()
        }
        // android:id is a resource reference (0x7f......) matching R.id constants
        val attrs = parser as? android.util.AttributeSet
        view.id = attrs?.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "id", 0) ?: 0
        if (view.id == 0) {
            view.id = attrs?.getAttributeResourceValue(null, "id", 0) ?: 0
        }
        return view
    }

    companion object {
        @JvmStatic
        fun from(context: Context?): LayoutInflater = LayoutInflater()
    }
}
