package io.throneproj.throne.ui

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.preference.Preference
import io.throneproj.throne.Key
import io.throneproj.throne.R
import io.throneproj.throne.database.DataStore
import io.throneproj.throne.ktx.Logs
import io.throneproj.throne.ktx.app
import io.throneproj.throne.ui.profile.ConfigEditActivity

class EditConfigPreference : Preference {

    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    )

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context) : super(context)

    init {
        intent = Intent(context, ConfigEditActivity::class.java)
    }

    var configKey = Key.SERVER_CONFIG
    var useConfigStore = false

    fun useConfigStore(key: String) {
        try {
            this.configKey = key
            useConfigStore = true
            intent = intent!!.apply {
                putExtra("useConfigStore", "1")
                putExtra("key", key)
            }
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    override fun getSummary(): CharSequence {
        val config =
            (if (useConfigStore) DataStore.configurationStore.getString(configKey) else DataStore.serverConfig)
                ?: ""
        return if (config.isBlank()) {
            return app.resources.getString(androidx.preference.R.string.not_set)
        } else {
            app.resources.getString(R.string.lines, config.split('\n').size)
        }
    }

    public override fun notifyChanged() {
        super.notifyChanged()
    }

}
