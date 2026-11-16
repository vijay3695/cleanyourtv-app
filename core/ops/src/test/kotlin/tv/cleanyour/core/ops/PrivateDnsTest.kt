package tv.cleanyour.core.ops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PrivateDnsTest {
    @Test fun defaultProviderIsAdGuardDns() {
        assertEquals("dns.adguard-dns.com", PrivateDns.DEFAULT_HOSTNAME)
    }

    @Test fun acceptsAndNormalizesHostnames() {
        assertEquals("dns.adguard-dns.com", PrivateDns.validateHostname(" DNS.AdGuard-DNS.COM. "))
    }

    @Test fun rejectsShellSyntaxAndMalformedNames() {
        listOf("", "localhost", "-bad.example", "bad-.example", "bad..example", "a.example;reboot", "a.example\$(id)")
            .forEach { assertNull(PrivateDns.validateHostname(it), it) }
    }

    @Test fun buildsOnlyValidatedCommands() {
        assertEquals(
            listOf(
                "settings put global private_dns_specifier dns.adguard-dns.com",
                "settings put global private_dns_mode hostname",
            ),
            PrivateDns.applyCommands("dns.adguard-dns.com"),
        )
        assertFailsWith<IllegalArgumentException> { PrivateDns.applyCommands("bad.example;reboot") }
    }

    @Test fun restoresOnlyKnownModesAndValidatedHosts() {
        assertEquals(
            listOf(
                "settings put global private_dns_specifier dns.google",
                "settings put global private_dns_mode hostname",
            ),
            PrivateDns.restoreCommands("hostname", "dns.google"),
        )
        assertEquals(
            listOf(
                "settings delete global private_dns_specifier",
                "settings put global private_dns_mode opportunistic",
            ),
            PrivateDns.restoreCommands("unexpected", "bad.example;reboot"),
        )
    }
}
