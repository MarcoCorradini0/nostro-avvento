package dev.avvento

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import java.sql.SQLException
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cosa succede quando il Postgres free scade (o non risponde), e l'export dei ricordi. */
class ResilienceTest {
    private val calendar = AdventCalendar(mapOf(1 to AdventEntry(1, title = "Uno", withPhoto = true)), 2026)
    private val config = Config.fromEnv(mapOf("FAKE_TODAY" to "2026-12-25", "ADVENT_YEAR" to "2026"))

    private object DownMeetings : MeetingStore {
        override suspend fun all(): List<Meeting> = throw SQLException("scaduto")
        override suspend fun get(id: String): Meeting? = throw SQLException("scaduto")
        override suspend fun save(meeting: Meeting) = throw SQLException("scaduto")
        override suspend fun count(): Int = throw SQLException("scaduto")
        override suspend fun delete(id: String) = throw SQLException("scaduto")
    }

    private object DownPhotos : PhotoStore {
        override suspend fun list(): List<PhotoMeta> = throw SQLException("scaduto")
        override suspend fun get(id: String): Photo? = throw SQLException("scaduto")
        override suspend fun save(photo: Photo) = throw SQLException("scaduto")
        override suspend fun delete(id: String) = throw SQLException("scaduto")
        override suspend fun totalBytes(): Long = throw SQLException("scaduto")
    }

    @Test
    fun `se il database e' giu' il calendario si apre lo stesso, incontri e foto dicono 503`() = testApplication {
        application { module(config, calendar, Stores(DownMeetings, DownPhotos, persistent = true)) }
        val key = "dev-chiave-2"
        assertEquals(HttpStatusCode.OK, client.get("/api/advent") { header("X-Key", key) }.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/meetings") { header("X-Key", key) }.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/photos") { header("X-Key", key) }.status)
    }

    @Test
    fun `l'export e' uno zip con le foto e gli incontri`() = testApplication {
        application { module(config, calendar, Stores.inMemory()) }
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(50)
        client.post("/api/photos") {
            header("X-Key", "dev-chiave-1")
            contentType(ContentType.Image.JPEG)
            setBody(jpeg)
        }
        val zip = client.get("/api/export") { header("X-Key", "dev-chiave-2") }.readRawBytes()
        val names = buildList {
            ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) add((z.nextEntry ?: break).name) }
        }
        assertEquals(2, names.size)
        assertTrue(names.any { it.startsWith("foto/001-") && it.endsWith("-Io.jpg") })
        assertTrue("incontri.json" in names)
    }
}
