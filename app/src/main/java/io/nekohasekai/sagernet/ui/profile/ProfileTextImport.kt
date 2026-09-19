package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.`import`.ProfileImport

/**
 * The UI's entry to the profile text parser (WP-S `ProfileImport`): clipboard text, QR payloads and file contents
 * (links, sing-box / Clash / SIP008 documents, WireGuard INI, `.ovpn`, openconnect config, AnyConnect XML).
 */
object ProfileTextImport {

    /** Every outbound found in [text]; empty when nothing parsed. */
    fun parse(text: String): List<Outbound> = ProfileImport.parseText(text)

    /** The subscription link when the whole text is one `clash://install-config?url=` link, else null. */
    fun subscriptionLink(text: String): String? {
        val line = text.trim()
        if (line.contains('\n')) return null
        return if (line.startsWith("clash://install-config", ignoreCase = true)) line else null
    }

    /** Drops the outbounds whose desktop dedup key repeats an earlier one or one of [existingKeys]. */
    fun deduplicate(outbounds: List<Outbound>, existingKeys: Collection<String>): List<Outbound> {
        val seen = HashSet(existingKeys)
        return outbounds.filter { seen.add(it.exportJsonLink(stripMetadata = true)) }
    }
}
