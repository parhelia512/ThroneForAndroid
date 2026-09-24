package io.nekohasekai.sagernet.ui

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.pm.ShortcutManagerCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.TileService
import io.nekohasekai.sagernet.databinding.LayoutCustomIconBinding
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.CustomIconManager
import io.nekohasekai.sagernet.utils.Theme
import android.service.quicksettings.TileService as BaseTileService

class CustomIconFragment : NamedFragment(R.layout.layout_custom_icon) {

    private lateinit var binding: LayoutCustomIconBinding
    private var isTileActive = false

    override fun name0(): String = app.getString(R.string.custom_icon)

    private val pickZipPack = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runOnDefaultDispatcher {
                val result = try {
                    requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                        CustomIconManager.importIconPack(stream, requireContext())
                    } ?: CustomIconManager.ImportResult.Error(getString(R.string.icon_pack_open_failed))
                } catch (e: Exception) {
                    CustomIconManager.ImportResult.Error(e.message ?: getString(R.string.icon_pack_import_failed))
                }

                onMainDispatcher {
                    when (result) {
                        is CustomIconManager.ImportResult.Success -> {
                            snackbar(getString(R.string.custom_icon_import_success)).show()
                            refreshPreview()
                        }
                        is CustomIconManager.ImportResult.MissingFile -> {
                            snackbar(getString(R.string.custom_icon_error_missing, result.fileName)).show()
                        }
                        is CustomIconManager.ImportResult.InvalidDimension -> {
                            snackbar(
                                getString(
                                    R.string.custom_icon_error_dimension,
                                    result.fileName,
                                    result.width,
                                    result.height
                                )
                            ).show()
                        }
                        is CustomIconManager.ImportResult.NotPng -> {
                            snackbar(getString(R.string.custom_icon_error_not_png, result.fileName)).show()
                        }
                        is CustomIconManager.ImportResult.SecurityError -> {
                            snackbar(getString(R.string.custom_icon_error_security, result.reason)).show()
                        }
                        is CustomIconManager.ImportResult.Error -> {
                            snackbar(result.message).show()
                        }
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutCustomIconBinding.bind(view)

        binding.btnImportPack.setOnClickListener {
            // Generic picker; the ZIP is validated in code
            startFilesForResult(pickZipPack, "*/*")
        }

        binding.btnResetDefault.setOnClickListener {
            requireContext().confirmAction(getString(R.string.custom_icon_reset_confirm), null, R.string.confirm_restore) {
                CustomIconManager.reset(requireContext())
                snackbar(getString(R.string.custom_icon_reset_success)).show()
                refreshPreview()
                notifyTileUpdate()
            }
        }

        binding.btnApplyPack.setOnClickListener {
            applyIconPack()
        }

        binding.cardSimulatedTile.setOnClickListener {
            isTileActive = !isTileActive
            updateSimulatedTileUi(isTileActive)
        }

        refreshPreview()
    }

    private fun applyIconPack() {
        val context = requireContext()
        if (!CustomIconManager.hasCustomIcon(context) || !CustomIconManager.hasCustomTile(context)) {
            snackbar(getString(R.string.custom_icon_no_pack_to_apply)).show()
            return
        }

        // 1. apply tile.png and ask the system to refresh the Quick Settings tile
        CustomIconManager.setTileApplied(context, true)
        notifyTileUpdate()

        // 2. pin a launcher shortcut with the custom icon.png (no restart needed)
        val iconBitmap = CustomIconManager.loadIconBitmap(context)
        if (iconBitmap != null) {
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                val shortcutIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                }
                val shortcut = ShortcutInfoCompat.Builder(context, "custom_icon_shortcut")
                    .setShortLabel(getString(R.string.app_name))
                    .setLongLabel(getString(R.string.app_name))
                    .setIcon(IconCompat.createWithBitmap(iconBitmap))
                    .setIntent(shortcutIntent)
                    .build()

                ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
                snackbar(getString(R.string.custom_icon_apply_success)).show()
            } else {
                snackbar(getString(R.string.custom_icon_pin_shortcut_not_supported)).show()
            }
        } else {
            snackbar(getString(R.string.custom_icon_apply_success)).show()
        }
    }

    private fun notifyTileUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                BaseTileService.requestListeningState(
                    requireContext(),
                    ComponentName(requireContext(), TileService::class.java)
                )
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

    private fun refreshPreview() {
        val context = requireContext()

        // 1. app icon preview: the image as is
        val customAppBitmap = CustomIconManager.loadIconBitmap(context)
        if (customAppBitmap != null) {
            binding.ivAppIconPreview.setImageBitmap(customAppBitmap)
        } else {
            binding.ivAppIconPreview.setImageResource(R.mipmap.ic_launcher)
        }

        // 2. tile icon (alpha mask)
        val customTileBitmap = CustomIconManager.loadTileAlphaBitmap(context)
        if (customTileBitmap != null) {
            binding.ivSimulatedTileIcon.setImageBitmap(customTileBitmap)
        } else {
            binding.ivSimulatedTileIcon.setImageResource(R.drawable.ic_throne_tile)
        }

        // 3. refresh the simulated tile states
        updateSimulatedTileUi(isTileActive)
    }

    private fun updateSimulatedTileUi(active: Boolean) {
        val context = context ?: return
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val primaryColor = try {
            context.getColorAttr(androidx.appcompat.R.attr.colorPrimary)
        } catch (e: Throwable) {
            Color.parseColor("#1976D2")
        }
        // Text/icon colour on the primary colour: white in most themes, dark in the White theme
        val onPrimaryColor = try {
            context.getColorAttr(com.google.android.material.R.attr.colorOnPrimary)
        } catch (e: Throwable) {
            Color.WHITE
        }

        if (active) {
            // In the White theme colorPrimary is pure white, and its night fallback with OLED turns it black;
            // both make the active tile unreadable (dark on white, black on black),
            // so use dark grey with white text, matching the White theme's dimmed accent
            val whiteTileOverride = DataStore.appTheme == Theme.WHITE && !Theme.usingMonetTheme() &&
                (Theme.isWhiteTheme() || DataStore.amoledTheme)
            val activeBgColor = if (whiteTileOverride) Color.parseColor("#757575") else primaryColor
            val activeTextColor = if (whiteTileOverride) Color.WHITE else onPrimaryColor
            binding.cardSimulatedTile.setCardBackgroundColor(activeBgColor)
            binding.tvSimulatedTileName.setTextColor(activeTextColor)
            binding.tvSimulatedTileState.setTextColor(activeTextColor)
            binding.tvSimulatedTileState.setText(R.string.custom_icon_tile_state_active)
            binding.ivSimulatedTileIcon.imageTintList = ColorStateList.valueOf(activeTextColor)
        } else {
            // Inactive: mimics a real Quick Settings tile that is off
            val inactiveBgColor = if (isNight) Color.parseColor("#2D3038") else Color.parseColor("#E2E2E6")
            val titleTextColor = if (isNight) Color.parseColor("#E3E2E6") else Color.parseColor("#1A1C1E")
            val subtitleTextColor = if (isNight) Color.parseColor("#C4C6D0") else Color.parseColor("#44474E")

            binding.cardSimulatedTile.setCardBackgroundColor(inactiveBgColor)
            binding.tvSimulatedTileName.setTextColor(titleTextColor)
            binding.tvSimulatedTileState.setTextColor(subtitleTextColor)
            binding.tvSimulatedTileState.setText(R.string.custom_icon_tile_state_inactive)
            binding.ivSimulatedTileIcon.imageTintList = ColorStateList.valueOf(titleTextColor)
        }
    }
}
