package app.lexet.ui

import app.lexet.ui.settings.normaliseServer
import org.junit.Test
import kotlin.test.assertEquals

class ServerAddressTest {
    @Test fun acceptsWhatPeopleType() {
        assertEquals("https://abebe.github.io/lexet/", normaliseServer("abebe.github.io/lexet"))
        assertEquals("https://abebe.github.io/lexet/", normaliseServer("  https://abebe.github.io/lexet/feed/index.json "))
        assertEquals("http://192.168.1.20:8080/", normaliseServer("http://192.168.1.20:8080"))
        assertEquals("HTTPS://Example.org/", normaliseServer("HTTPS://Example.org///"))
        assertEquals("", normaliseServer("   "))
    }
}
