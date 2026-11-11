package dev.avvento

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdventTest {

    private val calendar = AdventCalendar(
        entries = mapOf(1 to AdventEntry(day = 1, title = "Primo"), 5 to AdventEntry(day = 5, title = "SEGRETO")),
        year = 2026,
    )

    @Test
    fun `a novembre e' tutto chiuso`() {
        assertTrue(calendar.doors(LocalDate.of(2026, 11, 30)).none { it.unlocked })
    }

    @Test
    fun `il 3 dicembre sono aperte solo le prime 3`() {
        val doors = calendar.doors(LocalDate.of(2026, 12, 3))
        assertEquals(listOf(1, 2, 3), doors.filter { it.unlocked }.map { it.day })
    }

    @Test
    fun `il contenuto di una casella chiusa non viene mai inviato`() {
        val door5 = calendar.doors(LocalDate.of(2026, 12, 4)).first { it.day == 5 }
        assertFalse(door5.unlocked)
        assertEquals(null, door5.content)
    }

    @Test
    fun `senza chiave l'API risponde 401 e con la chiave giusta 200`() = testApplication {
        val config = Config.fromEnv(mapOf("FAKE_TODAY" to "2026-12-01", "ADVENT_YEAR" to "2026"))
        application { module(config, calendar) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/advent").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/advent") { header("X-Key", "sbagliata") }.status)

        val ok = client.get("/api/advent") { header("X-Key", "dev-chiave-2") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertFalse(ok.bodyAsText().contains("SEGRETO"))
    }
}
