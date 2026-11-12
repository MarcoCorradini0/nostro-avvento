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
    fun `la casella 25 e' invisibile fino a Natale`() {
        assertTrue(calendar.doors(LocalDate.of(2026, 12, 24)).none { it.day == 25 })
        assertEquals(24, calendar.doors(LocalDate.of(2026, 12, 24)).size)
    }

    @Test
    fun `il 25 dicembre compare la casella speciale, gia' aperta`() {
        val doors = calendar.doors(LocalDate.of(2026, 12, 25))
        assertEquals(25, doors.size)
        assertTrue(doors.last().let { it.day == 25 && it.unlocked })
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

    @Test
    fun `CORS accetta solo il sito autorizzato`() = testApplication {
        val config = Config.fromEnv(mapOf("ALLOWED_ORIGINS" to "https://esempio.github.io"))
        application { module(config, calendar) }

        val allowed = client.get("/api/me") {
            header("Origin", "https://esempio.github.io")
            header("X-Key", "dev-chiave-1")
        }
        assertEquals("https://esempio.github.io", allowed.headers["Access-Control-Allow-Origin"])

        val other = client.get("/api/me") {
            header("Origin", "https://sito-cattivo.com")
            header("X-Key", "dev-chiave-1")
        }
        assertEquals(HttpStatusCode.Forbidden, other.status)
    }
}
