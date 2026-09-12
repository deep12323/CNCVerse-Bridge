package androidx.core.content.res

/** androidx.core stub: plugins resolve drawables through this helper. */
object ResourcesCompat {
    @JvmStatic
    fun getDrawable(
        res: android.content.res.Resources?,
        id: Int,
        theme: android.content.res.Resources.Theme?,
    ): android.graphics.drawable.Drawable? = res?.getDrawable(id)

    @JvmStatic
    fun getColor(res: android.content.res.Resources?, id: Int, theme: android.content.res.Resources.Theme?): Int = 0

    @JvmStatic
    fun getString(res: android.content.res.Resources?, id: Int): String = res?.getString(id) ?: ""
}
