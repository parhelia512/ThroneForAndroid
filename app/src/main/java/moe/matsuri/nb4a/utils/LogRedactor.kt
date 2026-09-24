package moe.matsuri.nb4a.utils

/**
 * Best-effort redaction of exported log lines: URL paths/queries and credentials, secret key/value pairs, UUIDs,
 * base64 keys, public IP addresses and, with [hideDestinations], domain names in connection and DNS lines.
 */
class LogRedactor(private val hideDestinations: Boolean) {

    private companion object {
        val URL = Regex("""\b([a-zA-Z][a-zA-Z0-9+.\-]*)://([^\s/@"'<>]*@)?([^\s/?#"'<>]+)([^\s"'<>]*)""")
        val SECRET = Regex(
            """(?i)("?)([\w\-]*(?:password|passwd|pass|secret|token|key|uuid|auth|psk|short_id)[\w\-]*)\1(\s*[:=]\s*)("(?:[^"\\]|\\.)*"|[^\s,;&}\]]+)"""
        )
        val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
        val BASE64 = Regex("""(?<![A-Za-z0-9+/_\-])[A-Za-z0-9+/_\-]{32,}={0,2}(?![A-Za-z0-9+/=_\-])""")
        val IPV4 = Regex("""(?<![\d.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![\d.])""")
        val IPV6 = Regex("""(?<![0-9A-Za-z:])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?![0-9A-Za-z:])""")
        val DESTINATION_LINE = Regex("""(?i)connection (?:to|from)|domain|dns|exchang|lookup|resolv|sniff""")
        val DOMAIN = Regex("""(?<![\w.\-])(?:[a-zA-Z0-9](?:[a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+([a-z]{2,63})\.?(?![\w\-])""")
    }

    fun redact(line: String): String {
        var s = URL.replace(line) { m ->
            val (scheme, credentials, host, rest) = m.destructured
            "$scheme://" + (if (credentials.isNotEmpty()) "<redacted>@" else "") + host +
                    (if (rest.isNotEmpty()) "/<redacted>" else "")
        }
        s = SECRET.replace(s) { m ->
            val (quote, key, separator, value) = m.destructured
            val redacted = if (value.startsWith("\"")) "\"<redacted>\"" else "<redacted>"
            "$quote$key$quote$separator$redacted"
        }
        s = UUID.replace(s, "<uuid>")
        s = BASE64.replace(s) { m ->
            val v = m.value
            if (v.any(Char::isUpperCase) && v.any(Char::isLowerCase) && v.any(Char::isDigit)) "<key>" else v
        }
        s = IPV4.replace(s) { m ->
            val octets = m.groupValues.drop(1).map { it.toInt() }
            if (octets.any { it > 255 } || isPrivate4(octets)) m.value else "<ip>"
        }
        s = IPV6.replace(s) { m ->
            val v = m.value
            if (!NGUtil.isIpv6Address(v) || isPrivate6(v.lowercase())) v else "<ip>"
        }
        if (hideDestinations && DESTINATION_LINE.containsMatchIn(s)) {
            s = DOMAIN.replace(s) { m -> "<domain>.${m.groupValues[1]}" }
        }
        return s
    }

    private fun isPrivate4(o: List<Int>): Boolean = when {
        o[0] == 10 || o[0] == 127 || o[0] == 0 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true
        o[0] == 100 && o[1] in 64..127 -> true
        o[0] >= 224 -> true
        else -> false
    }

    private fun isPrivate6(v: String): Boolean =
        v == "::" || v == "::1" || v.startsWith("fe8") || v.startsWith("fe9") || v.startsWith("fea") ||
                v.startsWith("feb") || v.startsWith("fc") || v.startsWith("fd") || v.startsWith("ff")
}
