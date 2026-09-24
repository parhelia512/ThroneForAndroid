package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.backup.BackupEntry
import io.nekohasekai.sagernet.databinding.LayoutToolsBinding
import io.nekohasekai.sagernet.widget.applyInsetPadding
import io.nekohasekai.sagernet.widget.applyListInsets

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    /** The pages (Backup, Custom icon) are scroll views: they scroll under the navigation bar. */
    private val pageInsets = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (f is NamedFragment) v.applyListInsets()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.registerFragmentLifecycleCallbacks(pageInsets, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.setTitle(R.string.menu_tools)

        // A backup opened from outside (BackupEntry) goes to the Backup tab's restore dialog.
        val restore = if (savedInstanceState == null) BackupEntry.takePending() else null
        val tools = mutableListOf<NamedFragment>()
        tools.add(BackupFragment.newInstance(restore))
        tools.add(CustomIconFragment())

        val binding = LayoutToolsBinding.bind(view)
        binding.toolsTab.applyInsetPadding(horizontal = true)
        binding.toolsPager.adapter = ToolsAdapter(tools)
        if (restore != null) binding.toolsPager.setCurrentItem(0, false)

        TabLayoutMediator(binding.toolsTab, binding.toolsPager) { tab, position ->
            tab.text = tools[position].name()
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()
    }

    inner class ToolsAdapter(val tools: List<Fragment>) : FragmentStateAdapter(this) {

        override fun getItemCount() = tools.size

        override fun createFragment(position: Int) = tools[position]
    }

}
