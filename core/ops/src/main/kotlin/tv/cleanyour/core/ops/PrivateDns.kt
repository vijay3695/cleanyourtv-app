package tv.cleanyour.core.ops

/**
 * Android 9+ Private DNS uses DNS-over-TLS. The hostname is applied on the TV, not the phone.
 * Keep validation and command construction here so UI input can never become shell syntax.
 */
object PrivateDns {
    const val DEFAULT_HOSTNAME = "dns.adguard-dns.com"

    fun validateHostname(input: String): String? {
        val host = input.trim().trimEnd('.').lowercase()
        if (host.isEmpty() || host.length > 253) return null
        if (host.any { it.code !in 0x21..0x7e }) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { label ->
                label.isEmpty() || label.length > 63 ||
                    label.first() == '-' || label.last() == '-' ||
                    label.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-') }
            }) return null
        return host
    }

    /** Read these values before applying so the previous TV configuration can be restored. */
    val readPreviousCommands = listOf(
        "settings get global private_dns_mode",
        "settings get global private_dns_specifier",
    )

    fun applyCommands(hostname: String): List<String> {
        val safe = validateHostname(hostname) ?: throw IllegalArgumentException("Invalid Private DNS hostname")
        return listOf(
            "settings put global private_dns_specifier $safe",
            "settings put global private_dns_mode hostname",
        )
    }

    fun restoreCommands(mode: String?, hostname: String?): List<String> {
        val safeMode = mode?.takeIf { it in setOf("off", "opportunistic", "hostname") } ?: "opportunistic"
        val safeHost = hostname?.let(::validateHostname)
        return buildList {
            if (safeMode == "hostname" && safeHost != null) add("settings put global private_dns_specifier $safeHost")
            else add("settings delete global private_dns_specifier")
            add("settings put global private_dns_mode $safeMode")
        }
    }
}
