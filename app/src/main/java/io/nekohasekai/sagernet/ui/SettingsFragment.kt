package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.settings.SettingsScreenFragment
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.ListListener

/** Hosts the settings root and its sub-screens; back and the toolbar arrow return to the previous screen. */
class SettingsFragment : ToolbarFragment(R.layout.layout_config_settings),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            childFragmentManager.popBackStack()
        }
    }

    private val backStackListener = FragmentManager.OnBackStackChangedListener { syncToolbar() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        childFragmentManager.addOnBackStackChangedListener(backStackListener)

        // A recreated activity (theme switch) restores the open sub-screen and its back stack.
        if (childFragmentManager.findFragmentById(R.id.settings) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settings, SettingsPreferenceFragment())
                .commitAllowingStateLoss()
            arguments?.getString(ARG_SCREEN)?.let { name ->
                openScreen(name, arguments?.getCharSequence(ARG_SCREEN_TITLE), Bundle())
            }
        }
        syncToolbar()
    }

    override fun onDestroyView() {
        childFragmentManager.removeOnBackStackChangedListener(backStackListener)
        super.onDestroyView()
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val name = pref.fragment ?: return false
        openScreen(name, pref.title, pref.extras)
        return true
    }

    private fun openScreen(name: String, title: CharSequence?, extras: Bundle) {
        val fragment = childFragmentManager.fragmentFactory.instantiate(requireContext().classLoader, name)
        fragment.arguments = Bundle(extras).apply {
            putCharSequence(SettingsScreenFragment.ARG_TITLE, title)
        }
        childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.settings, fragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }

    companion object {
        private const val ARG_SCREEN = "screen"
        private const val ARG_SCREEN_TITLE = "screenTitle"

        /** The settings with the sub-screen [fragmentClass] open on top of the root. */
        fun forScreen(fragmentClass: String, title: CharSequence) = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SCREEN, fragmentClass)
                putCharSequence(ARG_SCREEN_TITLE, title)
            }
        }
    }

    private fun syncToolbar() {
        val toolbar = toolbar ?: return
        val nested = childFragmentManager.backStackEntryCount > 0
        backCallback.isEnabled = nested
        val current = childFragmentManager.findFragmentById(R.id.settings)
        toolbar.title = current?.arguments?.getCharSequence(SettingsScreenFragment.ARG_TITLE)
            ?.takeIf { nested } ?: getString(R.string.settings)
        if (nested) {
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationOnClickListener { childFragmentManager.popBackStack() }
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
            toolbar.setNavigationOnClickListener {
                (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
            }
        }
        // 纯白模式下工具栏为白底，导航图标切换为深色保证可读
        if (Theme.isWhiteTheme()) {
            toolbar.navigationIcon?.setTint(ContextCompat.getColor(requireContext(), R.color.black))
        }
    }

}
