package dev.rain.laundry.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReminderEndpointTest {
    @Test fun defaultServerUsesStandardHttps() {
        assertEquals("https://8.153.173.106",
            CloudReminderEndpoint.normalize(CloudReminderEndpoint.DEFAULT_SERVER_URL))
        assertTrue(CloudReminderEndpoint.validate("https://8.153.173.106/") is CloudReminderEndpoint.Result.Valid)
    }

    @Test fun onlyHttpsWithoutAmbiguousUrlPartsIsAccepted() {
        val valid = listOf(
            "https://reminder.example",
            "https://reminder.example/",
            "https://reminder.example:8443/service",
            "https://8.153.173.106"
        )
        for (url in valid) {
            assertTrue(url, CloudReminderEndpoint.validate(url) is CloudReminderEndpoint.Result.Valid)
        }
        val invalid = listOf(
            "", " ", "http://8.153.173.106", "ftp://8.153.173.106",
            "https://user:pass@reminder.example", "https://reminder.example?token=x",
            "https://reminder.example#token", "https://reminder.example/path with space",
            "https://reminder.example\nX-Injected: 1", "not a url",
            "https://reminder.example/" + "a".repeat(2100)
        )
        for (url in invalid) {
            assertTrue(url, CloudReminderEndpoint.validate(url) is CloudReminderEndpoint.Result.Invalid)
        }
    }

    @Test fun normalizedUrlKeepsHttpsAndRemovesOnlyTrailingSlash() {
        assertEquals("https://reminder.example",
            CloudReminderEndpoint.normalize("  https://reminder.example/  "))
        assertEquals("https://reminder.example/base",
            CloudReminderEndpoint.normalize("https://reminder.example/base/"))
    }

    @Test fun resolvedPathsCannotEscapeTheConfiguredBase() {
        val base = CloudReminderEndpoint.httpUrl("https://reminder.example/base")
        assertEquals("https://reminder.example/base/v1/status",
            CloudReminderEndpoint.resolve(base, "v1/status").toString())
        assertTrue(runCatching { CloudReminderEndpoint.resolve(base, "../admin") }.isFailure)
        assertTrue(runCatching { CloudReminderEndpoint.resolve(base, "") }.isFailure)
    }
}
