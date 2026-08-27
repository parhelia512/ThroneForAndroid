package io.throneproj.throne.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.throneproj.throne.R
import io.throneproj.throne.database.preference.EditTextPreferenceModifiers
import io.throneproj.throne.fmt.wireguard.WireGuardBean
import io.throneproj.throne.fmt.PreferenceBinding
import io.throneproj.throne.fmt.PreferenceBindingManager
import io.throneproj.throne.fmt.Type

class WireGuardSettingsActivity : ProfileSettingsActivity<WireGuardBean>() {

    override fun createEntity() = WireGuardBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, "localAddress"))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, "privateKey"))
    private val peerPublicKey = pbm.add(PreferenceBinding(Type.Text, "peerPublicKey"))
    private val peerPreSharedKey = pbm.add(PreferenceBinding(Type.Text, "peerPreSharedKey"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val reserved = pbm.add(PreferenceBinding(Type.Text, "reserved"))
    private val listenPort = pbm.add(PreferenceBinding(Type.TextToInt, "listenPort"))
    private val persistentKeepaliveInterval =
        pbm.add(PreferenceBinding(Type.TextToInt, "persistentKeepaliveInterval"))

    override fun WireGuardBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.wireguard_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (mtu.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (listenPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (persistentKeepaliveInterval.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
    }

}
