package io.nekohasekai.sagernet.ui.profiles

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.ui.test.RowPhase
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor

/**
 * The texts of one card, computed apart from the views so the grid can align the optional rows of neighbours:
 * the status slot holds the desktop DisplayTestResult (or the live test phase), and the traffic when there is no
 * result; the error line is the Android-only `test_error` of a failed test.
 */
internal class RowContent(
    val status: CharSequence,
    val statusDimmed: Boolean,
    val testing: Boolean,
    val traffic: String,
    val address: String,
    val error: String?,
) {
    val rows: Int
        get() = (if (address.isNotBlank() || traffic.isNotEmpty()) ROW_MIDDLE else 0) or
            (if (error != null) ROW_ERROR else 0)

    companion object {
        const val ROW_MIDDLE = 1
        const val ROW_ERROR = 2

        fun of(context: Context, profile: ProxyEntity, adapter: ProfileListAdapter): RowContent {
            val host = adapter.host
            val secondary = context.getColorAttr(android.R.attr.textColorSecondary)
            val showTraffic = profile.rx + profile.tx != 0L
            val traffic = if (showTraffic) context.getString(
                R.string.traffic,
                Formatter.formatFileSize(context, profile.tx),
                Formatter.formatFileSize(context, profile.rx),
            ) else ""
            var address = if (host?.alwaysShowAddress == true && profile.outbound.name.isNotBlank()) {
                profile.displayAddress()
            } else ""
            if (showTraffic && address.length >= 30) address = address.substring(0, 27) + "..."

            val phase = adapter.phaseOf(profile.id)
            val result = profile.displayTestResult(adapter.testItemsToShow)
            val status = SpannableStringBuilder()
            fun append(text: CharSequence, color: Int) =
                status.append(text, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            when {
                phase == RowPhase.TESTING -> append(context.getString(R.string.connection_test_testing), secondary)
                result.isNotEmpty() -> append(result, profile.latencyColor().takeIf { it != 0 } ?: secondary)
                phase == RowPhase.QUEUED -> append(context.getString(R.string.profiles_test_queued), secondary)
            }
            var middleTraffic = traffic
            if (status.isEmpty() && showTraffic) {
                append(traffic, secondary)
                middleTraffic = ""
            }
            val error = if (phase == null) profile.testError?.takeIf { it.isNotBlank() } else null
            return RowContent(status, phase == RowPhase.QUEUED, phase == RowPhase.TESTING, middleTraffic, address, error)
        }
    }
}

internal class ProfileRowHolder(view: View, private val adapter: ProfileListAdapter) :
    RecyclerView.ViewHolder(view) {

    private val card = view as MaterialCardView
    private val selectCheck: CheckBox = view.findViewById(R.id.select_check)
    private val name: TextView = view.findViewById(R.id.profile_name)
    private val editButton: View = view.findViewById(R.id.edit)
    private val shareButton: View = view.findViewById(R.id.share)
    private val moreButton: View = view.findViewById(R.id.more)
    private val middleRow: View = view.findViewById(R.id.middle_row)
    private val address: TextView = view.findViewById(R.id.profile_address)
    private val traffic: TextView = view.findViewById(R.id.traffic_text)
    private val type: TextView = view.findViewById(R.id.profile_type)
    private val status: TextView = view.findViewById(R.id.profile_status)
    private val testProgress: View = view.findViewById(R.id.test_progress)
    private val error: TextView = view.findViewById(R.id.profile_error)
    private val selectedIndicator: View = view.findViewById(R.id.selected_indicator)

    lateinit var profile: ProxyEntity
        private set

    /** The optional rows of the last bind ([RowContent.rows]), null before the first. */
    var boundRows: Int? = null
        private set
    var boundTx = Long.MIN_VALUE
        private set
    var boundRx = Long.MIN_VALUE
        private set

    init {
        view.setOnClickListener {
            if (::profile.isInitialized) adapter.host?.onRowClick(profile)
        }
        view.setOnLongClickListener { v ->
            ::profile.isInitialized && adapter.host?.onRowLongClick(this, profile, v.isInTouchMode) == true
        }
        editButton.setOnClickListener {
            if (::profile.isInitialized) adapter.host?.itemMenu?.edit(profile, adapter.group)
        }
        shareButton.setOnClickListener {
            if (::profile.isInitialized) adapter.host?.itemMenu?.showShare(it, profile)
        }
        moreButton.setOnClickListener {
            if (::profile.isInitialized) adapter.host?.itemMenu?.showMenu(it, profile, adapter)
        }
        val showError = View.OnClickListener {
            val text = if (::profile.isInitialized) profile.testError else null
            if (!text.isNullOrBlank()) it.context.alert(text).tryToShow()
        }
        status.setOnClickListener(showError)
        error.setOnClickListener(showError)
        status.isFocusable = false
        view.findViewById<ImageView>(R.id.shareIcon).setColorFilter(Color.GRAY)
    }

    fun bind(profile: ProxyEntity) {
        this.profile = profile
        val context = itemView.context
        name.text = profile.displayName()
        type.text = profile.displayType()
        type.setTextColor(context.getProtocolColor(profile.type))
        bindContent()
        bindState()
    }

    /** The result, traffic, address and error rows. */
    fun bindContent() {
        val content = RowContent.of(itemView.context, profile, adapter)
        status.text = content.status
        status.alpha = if (content.statusDimmed) 0.5f else 1f
        testProgress.isVisible = content.testing
        address.text = content.address
        traffic.text = content.traffic
        traffic.isVisible = content.traffic.isNotEmpty()
        error.text = content.error?.let(Protocols::genFriendlyMsg)
        error.maxLines = if (adapter.isGrid) 1 else 2
        status.isClickable = content.error != null
        error.isClickable = content.error != null

        val rows = content.rows
        val reserved = adapter.neighbourRows(bindingAdapterPosition) and rows.inv()
        middleRow.visibility = rowVisibility(rows, reserved, RowContent.ROW_MIDDLE)
        error.visibility = rowVisibility(rows, reserved, RowContent.ROW_ERROR)
        val previous = boundRows
        boundRows = rows
        boundTx = profile.tx
        boundRx = profile.rx
        if (previous != null && previous != rows) adapter.refreshRowNeighbours(bindingAdapterPosition)
    }

    /** A recycled card belongs to no grid row yet: its next bind must not refresh neighbours. */
    fun onRecycled() {
        boundRows = null
        boundTx = Long.MIN_VALUE
        boundRx = Long.MIN_VALUE
    }

    /** A row missing here but shown by a card of the same grid row keeps its height (INVISIBLE). */
    private fun rowVisibility(rows: Int, reserved: Int, row: Int) = when {
        rows and row != 0 -> View.VISIBLE
        reserved and row != 0 -> View.INVISIBLE
        else -> View.GONE
    }

    /** Selection, running state, multi-select check and the row buttons. */
    fun bindState() {
        val host = adapter.host ?: return
        val id = profile.id
        val selecting = host.selection.active
        val buttons = !host.select && !selecting
        selectCheck.isVisible = selecting
        selectCheck.isChecked = selecting && host.selection.isChecked(id)
        editButton.isVisible = buttons && !adapter.isGrid
        shareButton.isVisible = buttons && !adapter.isGrid && !profile.isChain()
        moreButton.isVisible = buttons
        editButton.isEnabled = !host.isStartedProfile(id)
        applyCardColors(host.isSelectedProfile(id), selectCheck.isChecked, host.cardStyle)
    }

    private fun applyCardColors(selected: Boolean, checked: Boolean, cardStyle: Int) {
        val context = card.context
        val surface = context.getColorAttr(R.attr.colorSurface)
        val primary = context.getColorAttr(R.attr.selectedColorPrimary)
        var background = surface
        if (cardStyle == 1) {
            selectedIndicator.isVisible = false
            card.cardElevation = 0f
            card.strokeWidth = context.resources.getDimensionPixelSize(
                if (selected) R.dimen.card_stroke_width_selected else R.dimen.card_stroke_width
            )
            card.strokeColor = if (selected) primary else context.getColour(R.color.card_stroke)
            if (selected) background = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(primary, 26), surface)
        } else {
            selectedIndicator.isVisible = selected
            card.strokeWidth = 0
            card.cardElevation = context.resources.getDimension(R.dimen.profile_card_elevation_classic)
            if (selected) background = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(primary, 20), surface)
        }
        if (checked) {
            val accent = context.getColorAttr(R.attr.colorAccent)
            background = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(accent, 48), background)
        }
        card.setCardBackgroundColor(background)
    }
}
