package io.throneproj.throne.ui.profile

import io.throneproj.throne.fmt.v2ray.VMessBean

class VMessSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = VMessBean().apply {
        if (intent?.getBooleanExtra("vless", false) == true) {
            alterId = -1
        }
        initializeDefaultValues()
    }

}
