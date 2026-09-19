package io.nekohasekai.sagernet.ui.profile

import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import androidx.preference.TwoStatePreference
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.ui.SimpleMenuPreference

// Small helpers shared by the profile editors: every function tolerates a screen that omits the preference.

fun PreferenceFragmentCompat.setVisible(visible: Boolean, vararg keys: String) {
    for (key in keys) findPreference<Preference>(key)?.isVisible = visible
}

fun PreferenceFragmentCompat.passwordSummary(vararg keys: String) {
    for (key in keys) findPreference<EditTextPreference>(key)?.summaryProvider =
        ProfileSettingsActivity.PasswordSummaryProvider
}

fun PreferenceFragmentCompat.portInput(vararg keys: String) {
    for (key in keys) findPreference<EditTextPreference>(key)?.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
}

fun PreferenceFragmentCompat.numberInput(vararg keys: String) {
    for (key in keys) findPreference<EditTextPreference>(key)?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
}

/** Multi-line editing for list fields (one item per line) and PEM blocks. */
fun PreferenceFragmentCompat.multilineInput(vararg keys: String) {
    for (key in keys) findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { editText ->
        editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        editText.isSingleLine = false
        editText.minLines = 3
        editText.setSelection(editText.text.length)
    }
}

/** Applies [apply] to the switch's current value and again on every change. */
fun PreferenceFragmentCompat.onSwitch(key: String, apply: (Boolean) -> Unit) {
    val pref = findPreference<TwoStatePreference>(key) ?: return
    apply(pref.isChecked)
    pref.setOnPreferenceChangeListener { _, newValue ->
        apply(newValue as Boolean)
        true
    }
}

/** Applies [apply] to the menu's current value and again on every change. */
fun PreferenceFragmentCompat.onMenu(key: String, apply: (String) -> Unit) {
    val pref = findPreference<SimpleMenuPreference>(key) ?: return
    apply(pref.value ?: "")
    pref.setOnPreferenceChangeListener { _, newValue ->
        apply(newValue as String)
        true
    }
}

private fun PreferenceCategory.setChildrenVisible(visible: Boolean, except: Set<String> = emptySet()) {
    for (i in 0 until preferenceCount) {
        val child = getPreference(i)
        if (child.key !in except) child.isVisible = visible
    }
}

/** The sing-box TLS block (`tls.*`), shared by every type with `hasTls()`. */
object TlsBlock {
    fun bind(pbm: PreferenceBindingManager, prefix: String = "tls") {
        pbm.bool("$prefix.enabled")
        pbm.bool("$prefix.disable_sni")
        pbm.text("$prefix.server_name")
        pbm.bool("$prefix.insecure")
        pbm.text("$prefix.alpn")
        pbm.text("$prefix.min_version")
        pbm.text("$prefix.max_version")
        pbm.text("$prefix.certificate")
        pbm.text("$prefix.certificate_path")
        pbm.text("$prefix.certificate_public_key_sha256")
        pbm.tri("$prefix.fragment", "$prefix.fragment_unspecified")
        pbm.text("$prefix.fragment_fallback_delay")
        pbm.bool("$prefix.record_fragment")
        pbm.tri("$prefix.spoof_enabled", "$prefix.spoof_unspecified")
        pbm.text("$prefix.spoof")
        pbm.text("$prefix.spoof_method")
        pbm.tri("$prefix.tls_tricks", "$prefix.tls_tricks_unspecified")
        pbm.bool("$prefix.utls.enabled")
        pbm.text("$prefix.utls.fingerPrint")
        pbm.bool("$prefix.reality.enabled")
        pbm.text("$prefix.reality.public_key")
        pbm.text("$prefix.reality.short_id")
        pbm.bool("$prefix.ech.enabled")
        pbm.text("$prefix.ech.config")
        pbm.text("$prefix.ech.config_path")
        pbm.text("$prefix.ech.serverName")
    }

    /**
     * Visibility wiring: with [mustTls] the enable switch is hidden and the block is always shown, otherwise the
     * block follows the switch; the uTLS / Reality / ECH switches gate their own fields.
     */
    fun setup(pf: PreferenceFragmentCompat, mustTls: Boolean, prefix: String = "tls") = with(pf) {
        val enabledKey = "$prefix.enabled"
        val security = findPreference<PreferenceCategory>("tlsCategory")
        val camouflage = findPreference<PreferenceCategory>("tlsCamouflageCategory")
        val ech = findPreference<PreferenceCategory>("tlsEchCategory")

        fun applyEnabled(on: Boolean) {
            security?.setChildrenVisible(on, setOf(enabledKey))
            camouflage?.isVisible = on
            ech?.isVisible = on
        }

        multilineInput("$prefix.alpn", "$prefix.certificate", "$prefix.certificate_public_key_sha256", "$prefix.ech.config")
        if (mustTls) {
            findPreference<Preference>(enabledKey)?.isVisible = false
            applyEnabled(true)
        } else {
            onSwitch(enabledKey) { applyEnabled(it) }
        }
        onSwitch("$prefix.utls.enabled") { setVisible(it, "$prefix.utls.fingerPrint") }
        onSwitch("$prefix.reality.enabled") { setVisible(it, "$prefix.reality.public_key", "$prefix.reality.short_id") }
        onSwitch("$prefix.ech.enabled") { setVisible(it, "$prefix.ech.config", "$prefix.ech.config_path", "$prefix.ech.serverName") }
        onMenu("$prefix.spoof_enabled") { setVisible(it == "1", "$prefix.spoof", "$prefix.spoof_method") }
        onMenu("$prefix.fragment") { setVisible(it == "1", "$prefix.fragment_fallback_delay") }
    }
}

/** The sing-box multiplex block (`multiplex.*`), shared by shadowsocks, vmess, vless and trojan. */
object MuxBlock {
    fun bind(pbm: PreferenceBindingManager, prefix: String = "multiplex") {
        pbm.tri("$prefix.enabled", "$prefix.unspecified")
        pbm.text("$prefix.protocol")
        pbm.int("$prefix.max_connections")
        pbm.int("$prefix.min_streams")
        pbm.int("$prefix.max_streams")
        pbm.bool("$prefix.padding")
        pbm.bool("$prefix.brutal.enabled")
        pbm.int("$prefix.brutal.up_mbps")
        pbm.int("$prefix.brutal.down_mbps")
    }

    fun setup(pf: PreferenceFragmentCompat, prefix: String = "multiplex") = with(pf) {
        numberInput("$prefix.max_connections", "$prefix.min_streams", "$prefix.max_streams", "$prefix.brutal.up_mbps", "$prefix.brutal.down_mbps")
        val category = findPreference<PreferenceCategory>("muxCategory")
        onMenu("$prefix.enabled") { state ->
            category?.setChildrenVisible(state == "1", setOf("$prefix.enabled"))
            if (state == "1") {
                val brutal = findPreference<SwitchPreference>("$prefix.brutal.enabled")?.isChecked ?: false
                setVisible(brutal, "$prefix.brutal.up_mbps", "$prefix.brutal.down_mbps")
            }
        }
        onSwitch("$prefix.brutal.enabled") { setVisible(it, "$prefix.brutal.up_mbps", "$prefix.brutal.down_mbps") }
    }
}

/** The QUIC tuning block (`quic.*`) of hysteria, tuic and masque. */
object QuicBlock {
    fun bind(pbm: PreferenceBindingManager, prefix: String = "quic") {
        pbm.text("$prefix.idle_timeout")
        pbm.text("$prefix.keep_alive_period")
        pbm.text("$prefix.stream_receive_window")
        pbm.text("$prefix.connection_receive_window")
        pbm.int("$prefix.max_concurrent_streams")
        pbm.int("$prefix.initial_packet_size")
        pbm.tri("$prefix.disable_path_mtu_discovery", "$prefix.disable_path_mtu_discovery_unspecified")
    }

    fun setup(pf: PreferenceFragmentCompat, prefix: String = "quic") = with(pf) {
        numberInput("$prefix.max_concurrent_streams", "$prefix.initial_packet_size")
    }
}

/** The sing-box V2Ray transport block (`transport.*`) of vmess, vless and trojan. */
object TransportBlock {
    fun bind(pbm: PreferenceBindingManager, prefix: String = "transport") {
        pbm.text("$prefix.type")
        pbm.text("$prefix.host")
        pbm.text("$prefix.path")
        pbm.text("$prefix.method")
        pbm.text("$prefix.headers")
        pbm.text("$prefix.idle_timeout")
        pbm.text("$prefix.ping_timeout")
        pbm.int("$prefix.max_early_data")
        pbm.text("$prefix.early_data_header_name")
        pbm.text("$prefix.service_name")
    }

    fun setup(pf: PreferenceFragmentCompat, prefix: String = "transport") = with(pf) {
        numberInput("$prefix.max_early_data")
        multilineInput("$prefix.headers")
        // the model stores "" for plain TCP; the menu needs a selectable value
        findPreference<SimpleMenuPreference>("$prefix.type")?.let { if (it.value.isNullOrEmpty()) it.value = "tcp" }
        onMenu("$prefix.type") { type ->
            val hostPath = type == "ws" || type == "http" || type == "httpupgrade"
            setVisible(hostPath, "$prefix.host", "$prefix.path")
            setVisible(type == "http", "$prefix.method")
            setVisible(type == "ws" || type == "http" || type == "httpupgrade", "$prefix.headers")
            setVisible(type == "http" || type == "grpc", "$prefix.idle_timeout", "$prefix.ping_timeout")
            setVisible(type == "ws", "$prefix.max_early_data", "$prefix.early_data_header_name")
            setVisible(type == "grpc", "$prefix.service_name")
        }
    }
}

/** The dial fields every outbound carries (`dial.*`). */
object DialBlock {
    fun bind(pbm: PreferenceBindingManager, prefix: String = "dial") {
        pbm.text("$prefix.bind_interface")
        pbm.text("$prefix.inet4_bind_address")
        pbm.text("$prefix.inet6_bind_address")
        pbm.text("$prefix.connect_timeout")
        pbm.bool("$prefix.tcp_fast_open")
        pbm.bool("$prefix.tcp_multi_path")
        pbm.bool("$prefix.udp_fragment")
        pbm.bool("$prefix.reuse_addr")
    }
}
