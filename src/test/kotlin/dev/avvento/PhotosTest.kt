package dev.avvento

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotosTest {
    private val marco = User("u1", "Marco", "k1")
    private val lei = User("u2", "Lei", "k2")
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100) { it.toByte() }

    private fun service(today: Int = 5) =
        PhotoService(InMemoryPhotoStore(), isUnlocked = { it <= today }, maxTotalBytes = PhotoService.MAX_TOTAL_MEMORY)

    @Test
    fun `tutti e due aggiungono foto al collage, ma solo immagini vere`() = runTest {
        val s = service()
        s.add(marco, jpeg, null)
        s.add(lei, jpeg, null)
        assertEquals(2, s.list().size)
        val e = assertFailsWith<MeetingException> { s.add(lei, "<script>".toByteArray(), null) }
        assertEquals(HttpStatusCode.UnsupportedMediaType, e.status)
    }

    @Test
    fun `la foto di una casella resta nascosta finche' la casella e' chiusa`() = runTest {
        val s = service(today = 5)
        val chiusa = s.add(marco, jpeg, 10)
        val aperta = s.add(marco, jpeg, 3)
        assertEquals(listOf(aperta.id), s.list().map { it.id }) // il collage di lei
        assertNull(s.get(lei, chiusa.id))
        assertEquals(chiusa.id, s.get(marco, chiusa.id)?.meta?.id) // Marco la vede per controllarla
        assertEquals(2, s.listFor(marco).size)
        // Lei non puo' mettere foto nelle caselle, e ogni casella ha una foto sola
        assertEquals(HttpStatusCode.Forbidden, assertFailsWith<MeetingException> { s.add(lei, jpeg, 7) }.status)
        val nuova = s.add(marco, jpeg, 3)
        assertEquals(nuova.id, s.idForDay(3))
    }

    @Test
    fun `ognuno toglie solo le sue foto`() = runTest {
        val s = service()
        val mia = s.add(lei, jpeg, null)
        assertEquals(HttpStatusCode.Forbidden, assertFailsWith<MeetingException> { s.delete(marco, mia.id) }.status)
        s.delete(lei, mia.id)
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `via HTTP la foto si carica, si scarica con la chiave e la casella la mostra solo da aperta`() = testApplication {
        val config = Config.fromEnv(mapOf("FAKE_TODAY" to "2026-12-02", "ADVENT_YEAR" to "2026"))
        val calendar = AdventCalendar(mapOf(2 to AdventEntry(2, title = "Due"), 9 to AdventEntry(9, title = "Nove")), 2026)
        application { module(config, calendar, Stores.inMemory()) }

        suspend fun upload(key: String, day: Int? = null) = client.post("/api/photos" + (day?.let { "?day=$it" } ?: "")) {
            header("X-Key", key)
            contentType(ContentType.Image.JPEG)
            setBody(jpeg)
        }
        val collage = upload("dev-chiave-2")
        assertEquals(HttpStatusCode.OK, collage.status)
        val id = Regex("\"id\":\"([^\"]+)\"").find(collage.bodyAsText())!!.groupValues[1]

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/photos/$id").status)
        assertContentEquals(jpeg, client.get("/api/photos/$id") { header("X-Key", "dev-chiave-1") }.readRawBytes())

        upload("dev-chiave-1", day = 2)
        val nascosta = Regex("\"id\":\"([^\"]+)\"").find(upload("dev-chiave-1", day = 9).bodyAsText())!!.groupValues[1]
        assertEquals(HttpStatusCode.NotFound, client.get("/api/photos/$nascosta") { header("X-Key", "dev-chiave-2") }.status)
        val advent = client.get("/api/advent") { header("X-Key", "dev-chiave-2") }.bodyAsText()
        assertTrue("\"photo\"" in advent) // la casella 2 (aperta) ha la sua foto
        assertFalse(nascosta in advent) // la 9 no
    }
}
