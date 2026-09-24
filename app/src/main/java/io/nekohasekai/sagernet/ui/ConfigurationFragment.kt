package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.profiles.GroupMenu
import io.nekohasekai.sagernet.ui.profiles.ProfileImports
import io.nekohasekai.sagernet.ui.profiles.ProfileItemMenu
import io.nekohasekai.sagernet.ui.profiles.ProfileListFragment
import io.nekohasekai.sagernet.ui.profiles.ProfilesDbWatcher
import io.nekohasekai.sagernet.ui.profiles.ProfilesPagerAdapter
import io.nekohasekai.sagernet.ui.profiles.SelectionMode
import io.nekohasekai.sagernet.ui.test.TestPanelController
import io.nekohasekai.sagernet.ui.test.TestSessionClient
import io.nekohasekai.sagernet.ui.test.TestUiState
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.applyInsetMargin
import io.nekohasekai.sagernet.widget.applyInsetPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The profiles screen: one tab per group in `display_order` (the desktop main window's group tabs), the current tab is
 * `current_group`. Also the profile picker of [ProfileSelectActivity] / [SwitchActivity] ([forSelection]).
 * The pieces live in `ui/profiles`: pages and rows, the group menu, the multi-selection, the row menu, imports.
 */
class ConfigurationFragment : ToolbarFragment(R.layout.layout_group_list),
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    GroupRepo.Listener,
    ProfileManager.Listener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    companion object {
        private const val ARG_SELECT = "select"
        private const val ARG_PICKED_ID = "picked_id"
        private const val ARG_PICKED_GROUP = "picked_group"
        private const val ARG_TITLE = "title"
        private const val ARG_HIDE_AUTO_SELECTORS = "hide_auto_selectors"
        private const val STATE_SORT_DESCENDING = "sort_descending"

        /**
         * The picker: a tap returns the profile to the activity, a [SelectCallback]; [selected] is highlighted.
         * [hideAutoSelectors] for slots that need a fixed server (front / landing proxy, chain hops).
         */
        fun forSelection(selected: ProxyEntity?, @StringRes titleRes: Int, hideAutoSelectors: Boolean = false) =
            ConfigurationFragment().apply {
                arguments = bundleOf(
                    ARG_SELECT to true,
                    ARG_PICKED_ID to (selected?.id ?: 0L),
                    ARG_PICKED_GROUP to (selected?.groupId ?: 0L),
                    ARG_TITLE to titleRes,
                    ARG_HIDE_AUTO_SELECTORS to hideAutoSelectors,
                )
            }
    }

    val select: Boolean get() = arguments?.getBoolean(ARG_SELECT) == true
    val hideAutoSelectors: Boolean get() = arguments?.getBoolean(ARG_HIDE_AUTO_SELECTORS) == true
    private val pickedId: Long get() = arguments?.getLong(ARG_PICKED_ID) ?: 0L
    private val pickedGroup: Long get() = arguments?.getLong(ARG_PICKED_GROUP) ?: 0L

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    /** Double column (groupLayoutMode 1) and the card style, read once per view. */
    var gridLayout = false
        private set
    var cardStyle = 0
        private set

    val groupMenu = GroupMenu(this)
    val selection = SelectionMode(this)
    val itemMenu = ProfileItemMenu(this)
    private val imports = ProfileImports(this)

    private lateinit var tabLayout: TabLayout
    private lateinit var pager: ViewPager2
    private lateinit var pagerAdapter: ProfilesPagerAdapter
    private var mediator: TabLayoutMediator? = null
    private lateinit var groupProgress: View
    private lateinit var runtimeStatus: TextView
    private lateinit var panelContainer: ViewGroup
    private var searchView: SearchView? = null
    private var testPanel: TestPanelController? = null

    private val lists = LinkedHashSet<ProfileListFragment>()
    private var shownGroupId = 0L
    private var query = ""

    var testState = TestUiState()
        private set
    private var subscriptionStates: Map<Long, Int> = emptyMap()
    private val busyGroups = HashSet<Long>()

    /** Height of the test panel over the lists (they pad their bottom by it). */
    var panelHeight = 0
        private set

    private var selectedProxy = 0L
    private var currentProfile = 0L
    private var serviceStarted = false
    private val stateRequests = Channel<Unit>(Channel.CONFLATED)

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (selection.active) selection.finish() else clearSearch()
        }
    }

    /** The profile editor's "Move" names the target group in `editingGroup`: the screen follows the profile there. */
    private val editingGroupListener = object : OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
            if (key != Key.PROFILE_GROUP) return
            runOnMainDispatcher {
                val target = DataStore.editingGroup
                if (view == null || select || target <= 0L || target == currentGroupId) return@runOnMainDispatcher
                val index = pagerAdapter.indexOf(target)
                if (index >= 0) pager.setCurrentItem(index, false) else reloadGroups(switchTo = target)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupMenu.sortDescending = savedInstanceState?.getBoolean(STATE_SORT_DESCENDING) == true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SORT_DESCENDING, groupMenu.sortDescending)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gridLayout = DataStore.groupLayoutMode == 1
        cardStyle = DataStore.profileCardStyle
        readProfileState()

        setUpToolbar()
        tabLayout = view.findViewById(R.id.group_tab)
        pager = view.findViewById(R.id.group_pager)
        groupProgress = view.findViewById(R.id.group_progress)
        runtimeStatus = view.findViewById(R.id.runtime_status)
        panelContainer = view.findViewById(R.id.test_panel_container)
        tabLayout.applyInsetPadding(horizontal = true)
        runtimeStatus.applyInsetPadding(horizontal = true)
        // above the stats bar (the XML margin) and the navigation bar
        panelContainer.applyInsetMargin(bottom = true, horizontal = true)

        pagerAdapter = ProfilesPagerAdapter(this)
        pager.adapter = pagerAdapter
        pager.offscreenPageLimit = 2
        applyGroups(GroupRepo.all(), initialGroupId())
        mediator = TabLayoutMediator(tabLayout, pager) { tab, position ->
            pagerAdapter.groups.getOrNull(position)?.let { tab.text = tabLabel(it) }
            tab.view.setOnLongClickListener { true }
        }.also { it.attach() }
        pager.registerOnPageChangeCallback(pageCallback)
        onPageShown()

        GroupRepo.addListener(this)
        ProfileManager.addListener(this)
        DataStore.profileCacheStore.registerChangeListener(editingGroupListener)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val scope = viewLifecycleOwner.lifecycleScope
        ProfilesDbWatcher(
            onProfiles = { lists.forEach { it.adapter.reload() } },
            onGroups = { reloadGroups() },
        ).start(scope)
        scope.launch {
            for (request in stateRequests) {
                val (selected, current, started) = withContext(Dispatchers.IO) { profileStateSnapshot() }
                applyProfileState(selected, current, started)
            }
        }
        scope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TestSessionClient.state.collect { onTestState(it) } }
                launch { AutoSelectorStatusLine.follow(this@ConfigurationFragment) }
                launch {
                    SubscriptionClient.states.collect {
                        subscriptionStates = it
                        updateGroupProgress()
                    }
                }
            }
        }

        if (!select) {
            testPanel = TestPanelController(this, panelContainer).apply {
                onSelectProfile = { id -> selectProfile(id, toggleOnTv = false) }
                onHeightChanged = { height ->
                    if (height != panelHeight) {
                        panelHeight = height
                        lists.forEach { it.updateBottomPadding() }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        GroupRepo.removeListener(this)
        ProfileManager.removeListener(this)
        DataStore.profileCacheStore.unregisterChangeListener(editingGroupListener)
        testPanel = null
        panelHeight = 0
        pager.unregisterOnPageChangeCallback(pageCallback)
        mediator?.detach()
        mediator = null
        selection.finish()
        lists.clear()
        searchView = null
        super.onDestroyView()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        // only pulls focus in when nothing has it: the toolbar, tabs and panel stay reachable by D-pad
        if (activity?.currentFocus == null) currentList()?.focusList()
        return super.onKeyDown(ketCode, event)
    }

    // ------------------------------------------------------------------------------------------------ toolbar

    private fun setUpToolbar() {
        val toolbar = toolbar ?: return
        toolbar.inflateMenu(R.menu.add_profile_menu)
        if (select) {
            toolbar.menu.findItem(R.id.action_add)?.isVisible = false
            toolbar.menu.findItem(R.id.action_misc)?.isVisible = false
            arguments?.getInt(ARG_TITLE)?.takeIf { it != 0 }?.let(toolbar::setTitle)
            setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener { requireActivity().finish() }
        } else {
            toolbar.inflateMenu(R.menu.profile_selection_menu)
            TvControls.addServerSwitch(toolbar.menu, R.id.group_profiles_toolbar)
            groupMenu.setUp(toolbar.menu)
            imports.prepare(toolbar.menu)
            toolbar.setNavigationOnClickListener {
                if (selection.active) {
                    selection.finish()
                } else {
                    (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
                }
            }
        }
        toolbar.setOnMenuItemClickListener(this)
        searchView = (toolbar.menu.findItem(R.id.action_search)?.actionView as? SearchView)?.apply {
            setOnQueryTextListener(this@ConfigurationFragment)
            maxWidth = Int.MAX_VALUE
            setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus && query.isEmpty()) clearSearch()
            }
        }
        // a tap on the toolbar brings the selected profile into view (or goes back to the top)
        toolbar.setOnClickListener { currentList()?.scrollToProfile(pickedOrSelectedId()) }
    }

    /** The navigation icon in the toolbar's colours (the white theme draws it dark, as ToolbarFragment does). */
    private fun setNavigationIcon(@DrawableRes icon: Int) {
        val toolbar = toolbar ?: return
        toolbar.setNavigationIcon(icon)
        if (Theme.isWhiteTheme()) {
            toolbar.navigationIcon?.setTint(ContextCompat.getColor(requireContext(), R.color.black))
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_misc) {
            toolbar?.menu?.let(groupMenu::prepare)
            return false
        }
        return TvControls.onMenuItemClick(requireContext(), item) || selection.onMenuItemClick(item) ||
            imports.onMenuItemClick(item) || groupMenu.onMenuItemClick(item)
    }

    override fun onQueryTextChange(newText: String): Boolean {
        query = newText
        applyQuery()
        updateBackCallback()
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    private fun clearSearch() {
        searchView?.apply {
            setQuery("", false)
            onActionViewCollapsed()
            clearFocus()
        }
    }

    private fun applyQuery() {
        val current = currentGroupId
        lists.forEach { it.adapter.setQuery(if (it.groupId == current) query else "") }
    }

    private fun updateBackCallback() {
        backCallback.isEnabled = selection.active || query.isNotEmpty()
    }

    // ------------------------------------------------------------------------------------------------ tabs

    private fun tabLabel(group: ProxyGroup): String =
        if (group.archive) getString(R.string.profiles_tab_archived, group.displayName()) else group.displayName()

    private fun initialGroupId(): Long = if (select && pickedGroup > 0) pickedGroup else GroupRepo.currentId()

    /** The current tab's group (`current_group`). */
    val currentGroupId: Long
        get() = if (::pagerAdapter.isInitialized) pagerAdapter.groups.getOrNull(pager.currentItem)?.id ?: 0L else 0L

    fun currentGroup(): ProxyGroup? =
        if (::pagerAdapter.isInitialized) pagerAdapter.groups.getOrNull(pager.currentItem) else null

    /** Every group in tab order. */
    fun groups(): List<ProxyGroup> = if (::pagerAdapter.isInitialized) pagerAdapter.groups else emptyList()

    private fun reloadGroups(switchTo: Long = 0L) {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            val (groups, current) = withContext(Dispatchers.IO) { GroupRepo.all() to GroupRepo.currentId() }
            val target = switchTo.takeIf { id -> groups.any { it.id == id } }
                ?: currentGroupId.takeIf { id -> groups.any { it.id == id } }
                ?: current
            applyGroups(groups, target)
        }
    }

    private fun applyGroups(groups: List<ProxyGroup>, targetGroupId: Long) {
        if (!pagerAdapter.submit(groups)) {
            groups.forEachIndexed { index, group -> tabLayout.getTabAt(index)?.text = tabLabel(group) }
        }
        val single = groups.size < 2
        tabLayout.isGone = single
        toolbar?.elevation = if (single) 0f else dp2px(4).toFloat()
        val index = pagerAdapter.indexOf(targetGroupId).takeIf { it >= 0 } ?: 0
        if (pager.currentItem != index) pager.setCurrentItem(index, false)
        onPageShown()
    }

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = onPageShown()
    }

    /** show_group: current_group follows the tab (the leaving tab stores its scroll row when it pauses). */
    private fun onPageShown() {
        val group = currentGroup() ?: return
        if (group.id == shownGroupId) return
        shownGroupId = group.id
        if (!select) GroupRepo.setCurrent(group.id)
        selection.finish()
        applyQuery()
        updateGroupProgress()
    }

    fun attachList(list: ProfileListFragment) {
        lists.add(list)
        list.adapter.setQuery(if (list.groupId == currentGroupId) query else "")
    }

    fun detachList(list: ProfileListFragment) {
        lists.remove(list)
    }

    fun listFor(groupId: Long): ProfileListFragment? = lists.firstOrNull { it.groupId == groupId }

    fun currentList(): ProfileListFragment? = listFor(currentGroupId)

    override suspend fun groupAdd(group: ProxyGroup) {
        onMainDispatcher { reloadGroups(switchTo = group.id) }
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        onMainDispatcher {
            if (!::pagerAdapter.isInitialized || view == null) return@onMainDispatcher
            val index = pagerAdapter.update(group)
            if (index >= 0) tabLayout.getTabAt(index)?.text = tabLabel(group)
        }
    }

    override suspend fun groupRemoved(groupId: Long) {
        onMainDispatcher { reloadGroups() }
    }

    override suspend fun groupUpdated(groupId: Long) = Unit

    override suspend fun groupsReordered() {
        onMainDispatcher { reloadGroups() }
    }

    // ------------------------------------------------------------------------------------------------ rows

    /** The profile the service uses (or would use); in the picker the passed-in one. */
    fun pickedOrSelectedId(): Long = if (select && pickedId > 0) pickedId else selectedProxy

    fun isSelectedProfile(id: Long) = id == pickedOrSelectedId()

    /** The profile the running service carries. */
    fun isStartedProfile(id: Long) = serviceStarted && id == selectedProxy && id == currentProfile

    fun onRowClick(profile: ProxyEntity) {
        when {
            select -> (activity as? SelectCallback)?.returnProfile(profile.id)
            selection.active -> selection.toggle(profile.id)
            else -> selectProfile(profile.id, toggleOnTv = true)
        }
    }

    /**
     * A long press starts the multi-selection; by touch it also picks the row up, so the same press can turn into a
     * reorder (the drag start selects the row). D-pad long presses only select.
     */
    fun onRowLongClick(holder: RecyclerView.ViewHolder, profile: ProxyEntity, touch: Boolean): Boolean {
        if (select) return false
        val list = listFor(profile.groupId)
        if (list?.adapter?.dragging == true) return true
        if (selection.active) {
            selection.toggle(profile.id)
            return true
        }
        if (touch && list?.startDrag(holder) == true) return true
        selection.start(profile)
        return true
    }

    /** A new selection reloads a running service; re-selecting on a TV toggles it (no FAB within reach). */
    private fun selectProfile(id: Long, toggleOnTv: Boolean) {
        val previous = selectedProxy
        applyProfileState(id, currentProfile, serviceStarted)
        runOnDefaultDispatcher {
            val last = DataStore.selectedProxy
            if (last != id) {
                DataStore.selectedProxy = id
                ProfileManager.postUpdate(last, noTraffic = true)
                if (DataStore.serviceState.canStop) SagerNet.reloadService()
            } else if (toggleOnTv && SagerNet.isTv && previous == id) {
                if (DataStore.serviceState.started) SagerNet.stopService() else SagerNet.startService()
            }
        }
    }

    fun onSelectionModeChanged(active: Boolean) {
        val toolbar = toolbar
        if (toolbar != null) {
            toolbar.menu.setGroupVisible(R.id.group_profiles_toolbar, !active)
            toolbar.menu.setGroupVisible(R.id.group_selection_toolbar, active)
            if (active) {
                searchView?.clearFocus()
                setNavigationIcon(R.drawable.ic_navigation_close)
                toolbar.title = resources.getQuantityString(R.plurals.profiles_selected, selection.count, selection.count)
            } else {
                setNavigationIcon(R.drawable.ic_navigation_menu)
                toolbar.setTitle(R.string.app_name)
            }
        }
        if (::pager.isInitialized) pager.isUserInputEnabled = !active
        updateBackCallback()
        lists.forEach { it.adapter.notifyState(null) }
    }

    fun onSelectionChanged(ids: Collection<Long>?) {
        toolbar?.title = resources.getQuantityString(R.plurals.profiles_selected, selection.count, selection.count)
        listFor(selection.groupId)?.adapter?.notifyState(ids)
    }

    fun setGridLayout(grid: Boolean) {
        if (grid == gridLayout) return
        gridLayout = grid
        runOnDefaultDispatcher { DataStore.groupLayoutMode = if (grid) 1 else 0 }
        lists.forEach { it.switchLayout() }
    }

    fun setCardStyle(style: Int) {
        if (style == cardStyle) return
        cardStyle = style
        runOnDefaultDispatcher { DataStore.profileCardStyle = style }
        lists.forEach { it.adapter.notifyState(null) }
    }

    // ------------------------------------------------------------------------------------------------ state

    /** The selected / running profile changed (MainActivity, the service, the notification, the widgets). */
    fun refreshProfileState() {
        stateRequests.trySend(Unit)
    }

    private fun profileStateSnapshot() =
        Triple(DataStore.selectedProxy, DataStore.currentProfile, DataStore.serviceState.started)

    private fun readProfileState() {
        val (selected, current, started) = profileStateSnapshot()
        selectedProxy = selected
        currentProfile = current
        serviceStarted = started
    }

    private fun applyProfileState(selected: Long, current: Long, started: Boolean) {
        val changed = HashSet<Long>()
        if (selected != selectedProxy) changed += listOf(selectedProxy, selected)
        if (current != currentProfile) changed += listOf(currentProfile, current)
        if (started != serviceStarted) changed += listOf(selectedProxy, currentProfile, selected, current)
        selectedProxy = selected
        currentProfile = current
        serviceStarted = started
        changed.removeAll { it <= 0L }
        if (changed.isNotEmpty()) lists.forEach { it.adapter.notifyState(changed) }
    }

    override suspend fun onAdd(profile: ProxyEntity) = Unit

    override suspend fun onUpdated(data: List<TrafficData>) = Unit

    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
        if (noTraffic) refreshProfileState()
    }

    override suspend fun onRemoved(groupId: Long, profileId: Long) = Unit

    private fun onTestState(state: TestUiState) {
        testState = state
        lists.forEach { it.adapter.onTestState(state) }
    }

    /** The group's subscription update is queued / running, or a group action runs. */
    fun setGroupBusy(groupId: Long, busy: Boolean) {
        if (busy) busyGroups.add(groupId) else busyGroups.remove(groupId)
        updateGroupProgress()
    }

    private fun updateGroupProgress() {
        if (!::groupProgress.isInitialized) return
        val id = currentGroupId
        groupProgress.isVisible = subscriptionStates[id] != null || id in busyGroups
    }

    /**
     * The status line under the tabs, hidden while [text] is null. Wave C: the running auto-selector's summary
     * ("Auto selector on X (3 of 300 working), switched 2m ago"), [onClick] opening its details.
     */
    fun setRuntimeStatus(text: CharSequence?, onClick: (() -> Unit)? = null) {
        if (!::runtimeStatus.isInitialized) return
        runtimeStatus.text = text
        runtimeStatus.isVisible = !text.isNullOrEmpty()
        if (onClick != null) {
            runtimeStatus.setOnClickListener { onClick() }
        } else {
            runtimeStatus.setOnClickListener(null)
            runtimeStatus.isClickable = false
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Background work that must finish even when the screen goes away. */
    fun launchIo(block: suspend CoroutineScope.() -> Unit) = runOnDefaultDispatcher(block)

    /** Back on the main thread, only while the view exists. */
    suspend fun onUi(block: ConfigurationFragment.() -> Unit) {
        val fragment = this
        onMainDispatcher { if (fragment.isAdded && fragment.view != null) fragment.block() }
    }
}
