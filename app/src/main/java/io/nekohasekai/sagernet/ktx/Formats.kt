package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.import.ProfileImport
import io.nekohasekai.sagernet.outbound.link.LinkParser
import moe.matsuri.nb4a.utils.JavaUtil.gson
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// JSON helpers of the config editor and the backup export

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

inline fun JSONObject.forEach(action: (String, Any) -> Unit) {
    for (k in this.keys()) {
        action(k, this.get(k))
    }
}

fun isJsonObjectValid(j: Any): Boolean {
    if (j is JSONObject) return true
    if (j is JSONArray) return true
    try {
        JSONObject(j as String)
    } catch (ex: JSONException) {
        try {
            JSONArray(j)
        } catch (ex1: JSONException) {
            return false
        }
    }
    return true
}

fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? {
    return try {
        getBoolean(name)
    } catch (ignored: Exception) {
        null
    }
}

fun JSONObject.getIntNya(name: String): Int? {
    return try {
        getInt(name)
    } catch (ignored: Exception) {
        null
    }
}

// Subscriptions

class SubscriptionFoundException(val link: String) : RuntimeException()

/**
 * A pasted text that is one subscription link rather than a profile: `clash://install-config?`, or a bare
 * http(s) URL that points at a document (a non-root path, or a query without an
 * explicit port; an HTTP proxy share link is `http(s)://user:pass@host:port/`). The subscription dialog
 * understands the `clash://install-config?url=` form, which is what a bare URL is rewritten to.
 */
fun detectSubscriptionLink(text: String): String? {
    val line = text.trim()
    if (line.isEmpty() || line.contains('\n')) return null
    if (line.startsWith("clash://install-config?")) return line
    if (!line.startsWith("http://") && !line.startsWith("https://")) return null
    val url = LinkParser.parse(line)
    if (!url.isValid || url.host.isEmpty()) return null
    val document = (url.path.isNotEmpty() && url.path != "/") || (url.port < 0 && !url.query.isEmpty())
    if (!document) return null
    return HttpUrl.Builder()
        .scheme("https")
        .host("install-config")
        .addQueryParameter("url", line)
        .build()
        .toString()
        .replaceFirst("https://", "clash://")
}

/**
 * The profiles of a pasted / scanned / opened text (ProfileImport.parseText); a lone subscription link throws
 * [SubscriptionFoundException] so the caller can offer to add it as a subscription instead.
 */
fun parseProxies(text: String): List<Outbound> {
    detectSubscriptionLink(text)?.let { throw SubscriptionFoundException(it) }
    return ProfileImport.parseText(text)
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}

// Deduplication: the desktop's key (Profile.cpp:87-90) is the JSON link without the tag

fun Outbound.dedupKey(): String {
    return exportJsonLink(stripMetadata = true)
}

fun List<Outbound>.deduplicateProxies(): List<Outbound> {
    val seen = HashSet<String>()
    return filter { seen.add(it.dedupKey()) }
}
