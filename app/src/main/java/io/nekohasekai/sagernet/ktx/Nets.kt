@file:Suppress("SpellCheckingInspection")

package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.utils.NGUtil
import okhttp3.ConnectionSpec
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

const val LOCALHOST = "127.0.0.1"

class HttpFetchResult(val body: String, private val headers: Headers) {
    fun header(name: String): String = headers[name] ?: ""
}

// App-internal HTTP (subscriptions, update check): through the mixed inbound while the service is
// connected and that inbound exists, direct otherwise. The main process never loads the core.
fun newHttpClient(
    viaProxy: Boolean = true,
    allowInsecure: Boolean = false,
    restrictTls13: Boolean = false,
    timeoutSeconds: Long = 30,
): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
    if (viaProxy && DataStore.serviceState.connected && !DataStore.mixedInboundDisabled) {
        builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOCALHOST, DataStore.mixedPort)))
        if (DataStore.mixedInboundNeedsAuth) SocksAuthenticator.install()
    }
    if (restrictTls13) {
        builder.connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3)
                    .build(),
                ConnectionSpec.CLEARTEXT,
            )
        )
    }
    if (allowInsecure) {
        val trustManager = TrustAllManager
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        builder.hostnameVerifier { _, _ -> true }
    }
    return builder.build()
}

fun fetchText(
    url: String,
    userAgent: String = USER_AGENT,
    viaProxy: Boolean = true,
    allowInsecure: Boolean = false,
    restrictTls13: Boolean = false,
): HttpFetchResult {
    val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
    newHttpClient(viaProxy, allowInsecure, restrictTls13).newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code}")
        return HttpFetchResult(response.body?.string().orEmpty(), response.headers)
    }
}

// java.net's SOCKS5 client only takes credentials from the process-wide Authenticator.
private object SocksAuthenticator : Authenticator() {
    private val installed = AtomicBoolean(false)

    fun install() {
        if (installed.compareAndSet(false, true)) setDefault(this)
    }

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        if (requestingProtocol?.startsWith("SOCKS", ignoreCase = true) != true) return null
        if (requestingHost != LOCALHOST && requestingSite?.isLoopbackAddress != true) return null
        return PasswordAuthentication(DataStore.mixedUsername, DataStore.mixedPassword.toCharArray())
    }
}

@Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
private object TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

fun linkBuilder() = HttpUrl.Builder().scheme("https")

fun HttpUrl.Builder.toLink(scheme: String, appendDefaultPort: Boolean = true): String {
    var url = build()
    val defaultPort = HttpUrl.defaultPort(url.scheme)
    var replace = false
    if (appendDefaultPort && url.port == defaultPort) {
        url = url.newBuilder().port(14514).build()
        replace = true
    }
    return url.toString().replace("${url.scheme}://", "$scheme://").let {
        if (replace) it.replace(":14514", ":$defaultPort") else it
    }
}

fun String.isIpAddress(): Boolean {
    return NGUtil.isIpv4Address(this) || NGUtil.isIpv6Address(this)
}

fun String.isIpAddressV6(): Boolean {
    return NGUtil.isIpv6Address(this)
}

// [2001:4860:4860::8888] -> 2001:4860:4860::8888
fun String.unwrapIPV6Host(): String {
    if (startsWith("[") && endsWith("]")) {
        return substring(1, length - 1).unwrapIPV6Host()
    }
    return this
}

// [2001:4860:4860::8888] or 2001:4860:4860::8888 -> [2001:4860:4860::8888]
fun String.wrapIPV6Host(): String {
    val unwrapped = this.unwrapIPV6Host()
    if (unwrapped.isIpAddressV6()) {
        return "[$unwrapped]"
    } else {
        return this
    }
}

fun mkPort(): Int {
    val socket = Socket()
    socket.reuseAddress = true
    socket.bind(InetSocketAddress(0))
    val port = socket.localPort
    socket.close()
    return port
}

const val USER_AGENT = "Throne/Android/" + BuildConfig.VERSION_NAME
