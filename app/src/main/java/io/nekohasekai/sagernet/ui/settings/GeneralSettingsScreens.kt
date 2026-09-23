package io.nekohasekai.sagernet.ui.settings

import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.remove
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.ColorPickerPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/** Service mode, auto connect (remember_enable) and the connection behaviour switches. */
class GeneralSettingsFragment : SettingsScreenFragment(R.xml.settings_general) {

    override fun bind() {
        pref<Preference>(Key.SERVICE_MODE).setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }
        if (Build.VERSION.SDK_INT < 28) {
            pref<Preference>(Key.METERED_NETWORK).remove()
        }
        pref<SwitchPreference>(Key.HIDE_FROM_RECENT_APPS).setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.applyHideFromRecentApps(newValue as Boolean)
            true
        }
        reloadOn(Key.ACQUIRE_WAKE_LOCK, Key.METERED_NETWORK)
    }
}

/** Theme, language, profile list and notification options (all Android-only). */
class AppearanceSettingsFragment : SettingsScreenFragment(R.xml.settings_appearance) {

    override fun bind() {
        val appTheme = pref<ColorPickerPreference>(Key.APP_THEME)
        val useSystemTheme = pref<SwitchPreference>(Key.USE_SYSTEM_THEME)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            useSystemTheme.isVisible = false
        } else {
            useSystemTheme.setOnPreferenceChangeListener { _, newValue ->
                appTheme.isEnabled = !(newValue as Boolean)
                applyThemeInstantly()
                true
            }
            appTheme.isEnabled = !DataStore.useSystemTheme
        }
        appTheme.setOnPreferenceChangeListener { _, _ ->
            applyThemeInstantly()
            true
        }
        pref<SimpleMenuPreference>(Key.NIGHT_THEME).setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            true
        }
        // AMOLED 纯黑开关：切换后重建宿主界面以重新叠加/移除 overlay
        pref<SwitchPreference>(Key.AMOLED_THEME).setOnPreferenceChangeListener { _, _ ->
            applyThemeInstantly()
            true
        }
        pref<SimpleMenuPreference>(Key.APP_LANGUAGE).setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }
        pref<SimpleMenuPreference>(Key.SPEED_INTERVAL).setOnPreferenceChangeListener { _, _ ->
            needReload()
            true
        }
        reloadOn(Key.SHOW_DIRECT_SPEED)
    }

    // 主题外观设置即时生效：重建宿主 Activity，onCreate 重新按 DataStore 应用主题与
    // AMOLED overlay；post 到下一帧执行，避免与偏好变更/对话框关闭的当前事务冲突
    private fun applyThemeInstantly() {
        val host = activity ?: return
        host.window?.decorView?.post {
            if (!host.isFinishing && !host.isDestroyed) {
                ActivityCompat.recreate(host)
            }
        }
    }
}
