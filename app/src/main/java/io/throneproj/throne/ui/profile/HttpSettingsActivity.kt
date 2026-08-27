package io.throneproj.throne.ui.profile

import io.throneproj.throne.fmt.http.HttpBean

class HttpSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = HttpBean()

}
