package io.nekohasekai.sagernet.outbound.import

import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.types.Custom
import io.nekohasekai.sagernet.outbound.types.OpenConnect
import io.nekohasekai.sagernet.outbound.types.OpenVpn
import io.nekohasekai.sagernet.outbound.types.Shadowsocks
import io.nekohasekai.sagernet.outbound.types.WireGuard

/**
 * The desktop's subscription / clipboard / file parser (src/configs/sub/SubscriptionParser.cpp with the scanning
 * helpers of include/configs/sub/SubscriptionScan.hpp). [parseText] is ParseText: the whole body is base64-decoded
 * when it looks like one blob (standard, then a wrapped standard blob, then the url-safe alphabet), then the
 * document is classified in the desktop's order: JSON (Xray outbounds / configs, sing-box outbounds / endpoints,
 * SIP008), Clash YAML, a WireGuard INI file, an OpenVPN profile, an OpenConnect profile, and finally one item per
 * line (a line opening a bracket yields its balanced JSON block), each of which may itself be base64 or a share
 * link. [parseLine] is Parser::link for one share link.
 */
object ProfileImport {

    /** The profiles produced and the desktop's log / warning lines of the run. */
    class Result(@JvmField val outbounds: List<Outbound>, @JvmField val messages: List<String>)

    private const val MAX_DEPTH = 16

    @JvmStatic
    fun parseText(text: String): List<Outbound> = parse(text).outbounds

    @JvmStatic
    @JvmOverloads
    fun parse(text: String, preference: OutboundFactory.XrayVlessPreference = OutboundFactory.DEFAULT_XRAY_VLESS_PREFERENCE): Result {
        var body = Scan.trim(text)
        if (body.isEmpty()) return Result(emptyList(), emptyList())
        if (Scan.looksLikeBase64(body)) {
            Scan.decodeBase64(body)?.let { body = it }
        } else if (Scan.looksLikeWrappedBase64(body)) {
            Scan.decodeBase64Lenient(body)?.let { body = it }
        } else if (Scan.looksLikeUrlSafeBase64(body)) {
            Scan.decodeBase64UrlSafe(body)?.let { body = it }
        }
        val parser = Parser(preference)
        parser.document(body, allowBase64 = false, needParse = true, depth = 0)
        return Result(parser.produced, parser.messages)
    }

    /** Parser::link on one share link (comments, `vpn://` and unknown schemes give null). */
    @JvmStatic
    @JvmOverloads
    fun parseLine(line: String, preference: OutboundFactory.XrayVlessPreference = OutboundFactory.DEFAULT_XRAY_VLESS_PREFERENCE): Outbound? =
        OutboundFactory.parseLink(Scan.trim(line), preference)

    private enum class SingBoxSubType { OutboundInJson, OutboundJsonArray, OutboundObject, Invalid }
    private enum class XraySubType { OutboundInJson, OutboundJsonArray, OutboundObject, ConfigJsonArray, Invalid }

    private class Parser(private val preference: OutboundFactory.XrayVlessPreference) {
        val produced = ArrayList<Outbound>()
        val messages = ArrayList<String>()

        private fun produce(outbound: Outbound?) {
            if (outbound != null) produced.add(outbound)
        }

        private fun log(line: String) {
            messages.add(line)
        }

        private fun warn(title: String, text: String) {
            messages.add("$title: $text")
        }

        /** Parser::document (SubscriptionParser.cpp:290-338). */
        fun document(raw: String, allowBase64: Boolean, needParse: Boolean, depth: Int) {
            if (depth > MAX_DEPTH) return
            val text = Scan.trim(raw)
            if (text.isEmpty()) return

            if (allowBase64) {
                val decoded = when {
                    Scan.looksLikeBase64(text) -> Scan.decodeBase64(text)
                    Scan.looksLikeUrlSafeBase64(text) -> Scan.decodeBase64UrlSafe(text)
                    else -> null
                }
                if (decoded != null) {
                    document(decoded, allowBase64 = false, needParse = true, depth = depth + 1)
                    return
                }
            }

            // QJsonDocument::fromJson rejects trailing content, which org.json would silently ignore
            if ((text[0] == '{' || text[0] == '[') && Scan.matchingClose(text, 0) == text.length - 1) {
                val value = JsonInput.parseValue(text)
                if (value is JsonObject || value is JsonArray) {
                    json(value, text)
                    return
                }
            }

            if (text.contains("proxies:")) {
                clash(text)
                return
            }

            if (text.contains("[Interface]") && text.contains("[Peer]")) {
                wireguardFile(text)
                return
            }

            if (looksLikeOvpnConfig(text)) {
                openVpnFile(text)
                return
            }

            if (looksLikeOpenConnectProfile(text)) {
                openConnectProfile(text)
                return
            }

            if (needParse && text.contains('\n')) {
                Scan.forEachItem(text) { item -> document(item, allowBase64 = true, needParse = false, depth = depth + 1) }
                return
            }

            link(text)
        }

        /** Parser::json (SubscriptionParser.cpp:340-370): Xray first, its configs share the `outbounds` wrapper with sing-box. */
        private fun json(doc: Any, text: String) {
            val xrayType = xraySubType(doc)
            if (xrayType == XraySubType.OutboundObject) {
                produce(Custom.fromXrayOutbound(doc as JsonObject))
                return
            }
            if (xrayType != XraySubType.Invalid) {
                xray(doc, xrayType)
                return
            }

            val subType = singBoxSubType(doc)
            if (subType == SingBoxSubType.OutboundObject) {
                produce(Custom.fromSingBoxOutboundText(text))
                return
            }
            if (subType != SingBoxSubType.Invalid) {
                singBox(doc, subType)
                return
            }

            if (text.contains("version") && text.contains("servers")) sip008(doc)
        }

        /** getSingBoxSubType (SubscriptionParser.cpp:100-113). */
        private fun singBoxSubType(doc: Any): SingBoxSubType {
            if (doc is JsonObject) {
                if (doc.contains("outbounds") || doc.contains("endpoints")) return SingBoxSubType.OutboundInJson
                if (doc.contains("type")) return SingBoxSubType.OutboundObject
                return SingBoxSubType.Invalid
            }
            if (doc is JsonArray && doc.isNotEmpty()) {
                val first = doc[0]
                if (first is JsonObject && first.contains("type")) return SingBoxSubType.OutboundJsonArray
            }
            return SingBoxSubType.Invalid
        }

        /** getXraySubType (SubscriptionParser.cpp:115-140): Xray tags outbounds with `protocol` where sing-box uses `type`. */
        private fun xraySubType(doc: Any): XraySubType {
            if (doc is JsonObject) {
                if (doc.contains("outbounds") && hasXrayOutbound(doc.array("outbounds"))) return XraySubType.OutboundInJson
                if (doc.contains("protocol")) return XraySubType.OutboundObject
                return XraySubType.Invalid
            }
            if (doc is JsonArray && doc.isNotEmpty()) {
                val first = doc[0]
                if (first is JsonObject) {
                    if (first.contains("protocol")) return XraySubType.OutboundJsonArray
                    if (first.contains("outbounds") && hasXrayOutbound(first.array("outbounds"))) return XraySubType.ConfigJsonArray
                }
            }
            return XraySubType.Invalid
        }

        private fun hasXrayOutbound(outbounds: JsonArray): Boolean =
            outbounds.any { it is JsonObject && it.contains("protocol") }

        /** Parser::singBox (SubscriptionParser.cpp:372-400). */
        private fun singBox(doc: Any, type: SingBoxSubType) {
            val outbounds: JsonArray
            val endpoints: JsonArray
            when (type) {
                SingBoxSubType.OutboundInJson -> {
                    val json = doc as JsonObject
                    outbounds = json.array("outbounds")
                    endpoints = json.array("endpoints")
                }

                SingBoxSubType.OutboundJsonArray -> {
                    outbounds = doc as JsonArray
                    endpoints = JsonArray()
                }

                else -> return
            }
            val handle = { value: Any ->
                val out = value as? JsonObject
                if (out != null) {
                    if (out.isEmpty()) {
                        log("invalid outbound: empty object")
                    } else {
                        val profileType = OutboundFactory.typeForSingBox(out.string("type"))
                        if (profileType != null) {
                            val outbound = OutboundFactory.newByType(profileType)
                            if (outbound.parseFromJson(out)) produce(outbound)
                        }
                    }
                }
            }
            for (outbound in outbounds) handle(outbound)
            for (endpoint in endpoints) handle(endpoint)
        }

        /** Parser::xray (SubscriptionParser.cpp:402-434). */
        private fun xray(doc: Any, type: XraySubType) {
            // Each element is a self-contained config (balancers, dialerProxy chains) that must run verbatim.
            if (type == XraySubType.ConfigJsonArray) {
                for (c in doc as JsonArray) {
                    val cfg = c as? JsonObject ?: continue
                    produce(Custom.fromXrayFullConfig(cfg))
                }
                return
            }
            val outbounds: JsonArray = when (type) {
                XraySubType.OutboundInJson -> (doc as JsonObject).array("outbounds")
                XraySubType.OutboundJsonArray -> doc as JsonArray
                else -> return
            }
            for (o in outbounds) {
                val out = o as? JsonObject ?: continue
                produce(Custom.fromXrayOutbound(out))
            }
        }

        /** Parser::sip008 (SubscriptionParser.cpp:436-447). */
        private fun sip008(doc: Any) {
            val servers = (doc as? JsonObject)?.array("servers") ?: return
            for (o in servers) {
                val out = o as? JsonObject ?: JsonObject()
                if (out.isEmpty()) {
                    log("invalid server object")
                    continue
                }
                val shadowsocks = Shadowsocks()
                if (!shadowsocks.parseFromSip008(out)) continue
                produce(shadowsocks)
            }
        }

        /** Parser::clash (SubscriptionParser.cpp:449-476): VLESS over xhttp or with encryption needs the Xray core. */
        private fun clash(text: String) {
            try {
                val proxies = ClashYaml.proxies(text) ?: return
                for (node in proxies) {
                    val proxy = ClashProxy(node)
                    val profileType = OutboundFactory.typeForClash(proxy.type) ?: continue
                    val encryption = proxy.string("encryption")
                    val outbound = if (proxy.type == "vless" && (proxy.string("network") == "xhttp" || (encryption.isNotEmpty() && encryption != "none"))) {
                        OutboundFactory.newByType("xrayvless")
                    } else {
                        OutboundFactory.newByType(profileType)
                    }
                    if (!outbound.parseFromClash(node)) continue
                    produce(outbound)
                }
            } catch (e: Exception) {
                warn("YAML Exception", e.message ?: "Failed to parse the Clash configuration.")
            }
        }

        /** Parser::wireguardFile (SubscriptionParser.cpp:478-482). */
        private fun wireguardFile(text: String) {
            val wireguard = WireGuard()
            if (!wireguard.parseFromLink(text)) return
            produce(wireguard)
        }

        /** Parser::openVpnFile (SubscriptionParser.cpp:484-494). */
        private fun openVpnFile(text: String) {
            val problems = ArrayList<String>()
            val openVpn = OpenVpn()
            val ok = openVpn.parseOvpnConfig(text, problems)
            for (problem in problems) log("OpenVPN: $problem")
            if (!ok) {
                log("Failed to import the OpenVPN profile.")
                return
            }
            produce(openVpn)
        }

        /** Parser::openConnectProfile (SubscriptionParser.cpp:496-525). */
        private fun openConnectProfile(text: String) {
            val problems = ArrayList<String>()
            if (text.contains("<AnyConnectProfile") || text.contains("<ServerList")) {
                val hosts = ArrayList<OpenConnect>()
                val ok = OpenConnect.parseAnyConnectXml(text, hosts, problems)
                for (problem in problems) log("OpenConnect: $problem")
                if (!ok) {
                    log("Failed to import the OpenConnect profile.")
                    return
                }
                for (host in hosts) produce(host)
                return
            }
            val openConnect = OpenConnect()
            val ok = openConnect.parseOpenConnectProfile(text, problems)
            for (problem in problems) log("OpenConnect: $problem")
            if (!ok) {
                log("Failed to import the OpenConnect profile.")
                return
            }
            produce(openConnect)
        }

        /** Parser::link (SubscriptionParser.cpp:527-554), minus the desktop-only `vpn://` credential links. */
        private fun link(line: String) {
            produce(OutboundFactory.parseLink(line, preference))
        }

        /** looksLikeOvpnConfig (SubscriptionParser.cpp:181-198). */
        private fun looksLikeOvpnConfig(text: String): Boolean {
            var hasRemote = false
            var hasClientMarker = false
            var result = false
            Scan.forEachLine(text) { raw ->
                val line = Scan.trim(raw)
                if (line.isEmpty() || line[0] == '#' || line[0] == ';') return@forEachLine true
                if (line.startsWith("remote ") || line == "<ca>" || line == "<tls-auth>" || line == "<tls-crypt>" ||
                    line == "<tls-crypt-v2>" || line == "<secret>"
                ) {
                    hasRemote = true
                }
                if (line == "client" || line == "tls-client" || line.startsWith("dev ") || line.startsWith("dev-type ") ||
                    line.startsWith("proto ")
                ) {
                    hasClientMarker = true
                }
                result = hasRemote && hasClientMarker
                !result
            }
            return result
        }

        /** looksLikeOpenConnectProfile (SubscriptionParser.cpp:200-224). */
        private fun looksLikeOpenConnectProfile(text: String): Boolean {
            if (text.contains("<AnyConnectProfile") || text.contains("<ServerList")) return true
            var matched = false
            if (text.contains("protocol")) {
                Scan.forEachLine(text) { raw ->
                    if (!raw.contains("protocol")) return@forEachLine true
                    matched = OPENCONNECT_CLI.containsMatchIn(raw) || OPENCONNECT_FILE.matches(raw)
                    !matched
                }
            }
            if (matched) return true
            var result = false
            Scan.forEachLine(text) { raw ->
                val line = Scan.trim(raw)
                if (line.isEmpty() || line[0] == '#') return@forEachLine true
                result = line.startsWith("openconnect ")
                false
            }
            return result
        }
    }

    private val OPENCONNECT_CLI = Regex("""(?:^|\s)--protocol[= ](?:anyconnect|nc|gp|pulse|f5|fortinet)\b""")
    private val OPENCONNECT_FILE = Regex("""[ \t]*protocol[ \t]*=[ \t]*(?:anyconnect|nc|gp|pulse|f5|fortinet)[ \t]*""")

    /** Subscription::scan (include/configs/sub/SubscriptionScan.hpp) on strings. */
    internal object Scan {
        private fun isSpace(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '' || c == ''

        private fun isBase64Char(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='

        private fun isUrlSafeBase64Char(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '='

        /** scan::trim: ASCII whitespace and NBSP on both ends, a BOM at the front. */
        fun trim(s: String): String {
            var start = 0
            var end = s.length
            while (start < end) {
                val c = s[start]
                if (isSpace(c) || c == ' ' || c == '﻿') start++ else break
            }
            while (end > start) {
                val c = s[end - 1]
                if (isSpace(c) || c == ' ') end-- else break
            }
            return s.substring(start, end)
        }

        /** scan::matchingClose: the index of the bracket closing the one at [open], honouring JSON string escapes; -1 when unbalanced. */
        fun matchingClose(s: String, open: Int): Int {
            var depth = 0
            var inString = false
            var escaped = false
            var i = open
            while (i < s.length) {
                val c = s[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else if (c == '"') {
                    inString = true
                } else if (c == '{' || c == '[') {
                    depth++
                } else if (c == '}' || c == ']') {
                    if (--depth == 0) return i
                }
                i++
            }
            return -1
        }

        /** scan::forEachLine: the segments of QString::split('\n'); [fn] returns false to stop. */
        fun forEachLine(s: String, fn: (String) -> Boolean) {
            var idx = 0
            while (true) {
                val nl = s.indexOf('\n', idx)
                if (nl < 0) {
                    fn(s.substring(idx))
                    return
                }
                if (!fn(s.substring(idx, nl))) return
                idx = nl + 1
            }
        }

        /** scan::forEachItem: one item per line, except that a line opening a bracket yields the whole balanced block. */
        fun forEachItem(s: String, fn: (String) -> Unit) {
            val n = s.length
            var idx = 0
            while (idx < n) {
                if (s[idx] == '\n') {
                    idx++
                    continue
                }
                if (s[idx] == '{' || s[idx] == '[') {
                    val end = matchingClose(s, idx)
                    if (end >= 0) {
                        fn(s.substring(idx, end + 1))
                        idx = end + 1
                        continue
                    }
                }
                var nl = s.indexOf('\n', idx)
                if (nl < 0) nl = n
                fn(s.substring(idx, nl))
                idx = nl + 1
            }
        }

        fun looksLikeBase64(s: String): Boolean = s.isNotEmpty() && s.all { isBase64Char(it) }

        /** The url-safe alphabet, which only counts when a `-` or `_` proves it is not standard base64. */
        fun looksLikeUrlSafeBase64(s: String): Boolean =
            s.isNotEmpty() && s.all { isUrlSafeBase64Char(it) } && s.any { it == '-' || it == '_' }

        /** scan::looksLikeWrappedBase64: one blob wrapped at a fixed column, as `base64` and MIME emit it. */
        fun looksLikeWrappedBase64(s: String): Boolean {
            var width = 0
            var lines = 0
            var sawShort = false
            var ok = true
            forEachLine(s) { raw ->
                var line = raw
                while (line.isNotEmpty() && isSpace(line[line.length - 1])) line = line.substring(0, line.length - 1)
                if (line.isEmpty()) return@forEachLine true
                if (!line.all { isBase64Char(it) }) {
                    ok = false
                    return@forEachLine false
                }
                lines++
                if (width == 0) {
                    width = line.length
                    return@forEachLine true
                }
                if (line.length > width || sawShort) {
                    ok = false
                    return@forEachLine false
                }
                if (line.length < width) sawShort = true
                true
            }
            return ok && lines >= 2 && width % 4 == 0
        }

        /** scan::decodeBase64 (AbortOnBase64DecodingErrors): null when invalid or empty. */
        fun decodeBase64(s: String): String? = Base64Strict.decode(s)?.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) }

        fun decodeBase64UrlSafe(s: String): String? =
            Base64Strict.decode(s, urlSafe = true)?.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) }

        /** QByteArray::fromBase64 without abort: characters outside the alphabet (line breaks, padding) are skipped. */
        fun decodeBase64Lenient(s: String): String? {
            val filtered = s.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' }
            return decodeBase64(filtered)
        }
    }
}
