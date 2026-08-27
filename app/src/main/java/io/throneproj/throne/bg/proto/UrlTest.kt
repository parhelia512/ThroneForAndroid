package io.throneproj.throne.bg.proto

import io.throneproj.throne.database.DataStore
import io.throneproj.throne.database.ProxyEntity
import io.throneproj.throne.ktx.Logs

class UrlTest {

    val link = DataStore.connectionTestURL
    private val timeout = DataStore.connectionTestTimeout

    suspend fun doTest(profile: ProxyEntity): Int {
        Logs.d("URLTest ${profile.displayName()}: start, link=$link, timeout=${timeout}ms")
        return TestInstance(profile, link, timeout).doTest()
    }

}
