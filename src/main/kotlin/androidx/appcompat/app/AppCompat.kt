package androidx.appcompat.app

import android.app.Activity
import android.os.Bundle

/**
 * androidx.appcompat stubs. Some plugins reference AppCompatActivity in
 * settings-dialog signatures; the class must exist for reflection/introspection
 * to succeed on the desktop JVM. Method calls on it are neutered at the
 * bytecode level by PluginBytecodeTransformer.
 */
open class AppCompatActivity : Activity() {
    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun setContentView(view: android.view.View?) {}
    fun setContentView(layoutResId: Int) {}
    fun getSupportFragmentManager(): Any? = null
    fun getSupportActionBar(): Any? = null
    fun setSupportActionBar(toolbar: Any?) {}
    fun invalidateOptionsMenu() {}
}
