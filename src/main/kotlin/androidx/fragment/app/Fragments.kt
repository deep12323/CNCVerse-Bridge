package androidx.fragment.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

/**
 * androidx.fragment stubs. Beyond being verification targets, show() EXECUTES
 * the fragment lifecycle (onCreate → onCreateView → onViewCreated) so plugin
 * settings fragments read their SharedPreferences/DataStore keys — which
 * registers them in the settings schema for the desktop gear dialog. UI calls
 * inside are neutered no-ops (PluginBytecodeTransformer); view binding against
 * the widget stubs keeps the key reads alive.
 */
open class Fragment {
    open fun onCreateView(
        inflater: LayoutInflater?,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = null

    open fun onViewCreated(view: View?, savedInstanceState: Bundle?) {}
    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onDestroyView() {}

    // DialogFragment-style members (plugins call these on bottom-sheet subclasses)
    fun show(manager: FragmentManager?, tag: String?) {
        com.cncverse.stremiobridge.state.ServerState.info("Fragment.show($tag) → running settings lifecycle")
        runCatching {
            onCreate(null)
            val view = onCreateView(LayoutInflater.from(null), null, null)
            com.cncverse.stremiobridge.state.ServerState.info("Settings fragment inflated view: ${view?.javaClass?.simpleName}")
            onViewCreated(view, null)
            onResume()
        }.onFailure {
            com.cncverse.stremiobridge.state.ServerState.warn(
                "Settings fragment lifecycle ended early: ${it::class.simpleName}: ${it.message}"
            )
        }
    }

    fun show(transaction: FragmentTransaction?, tag: String?): Int {
        show(null as FragmentManager?, tag)
        return 0
    }

    fun dismissAllowingStateLoss() {}
    fun dismiss() {}

    fun requireActivity(): android.app.Activity = android.app.Activity()
    fun requireContext(): android.content.Context = android.content.DesktopContext
    fun getChildFragmentManager(): FragmentManager = FragmentManager()
    fun getParentFragmentManager(): FragmentManager = FragmentManager()
    fun isAdded(): Boolean = false
}

open class FragmentManager {
    fun beginTransaction(): FragmentTransaction = FragmentTransaction()
    fun executePendingTransactions(): Boolean = false
    fun findFragmentById(id: Int): Fragment? = null
}

open class FragmentTransaction {
    fun add(id: Int, fragment: Fragment?): FragmentTransaction = this
    fun replace(id: Int, fragment: Fragment?): FragmentTransaction = this
    fun remove(fragment: Fragment?): FragmentTransaction = this
    fun show(fragment: Fragment?): FragmentTransaction = this
    fun hide(fragment: Fragment?): FragmentTransaction = this
    fun commit(): Int = 0
    fun commitAllowingStateLoss(): Int = 0
    fun commitNow() {}
}
