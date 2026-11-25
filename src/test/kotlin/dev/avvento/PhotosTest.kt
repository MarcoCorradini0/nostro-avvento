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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhotosTest {
    private val marco = User("u1", "Marco", "k1")
    private val lei = User("u2", "Lei", "k2")
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100) { it.toByte() }

    private fun service() = PhotoService(InMemoryPhotoStore(), maxTotalBytes = PhotoService.MAX_TOTAL_MEMORY)

    @Test
    fun `tutti e due aggiungono foto al collage, ma solo immagini vere`() = runTest {
        val s = service()
        s.add(marco, jpeg)
        s.add(lei, jpeg)
        assertEquals(2, s.list().size)
        val e = assertFailsWith<ApiException> { s.add(lei, "<script>".toByteArray()) }
        assertEquals(HttpStatusCode.UnsupportedMediaType, e.status)
    }

    @Test
    fun `tutti e due possono togliere una foto, anche dell'altro`() = runTest {
        val s = service()
        val sua = s.add(lei, jpeg)
        s.delete(sua.id)
        assertTrue(s.list().isEmpty())
        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiException> { s.delete("non-esiste") }.status)
    }

    @Test
    fun `le caselle con foto ricevono foto diverse, mai doppioni`() = runTest {
        val s = service()
        assertTrue(s.forDoors(listOf(1, 5, 9)).isEmpty()) // nessuna foto: nessuna casella con foto
        repeat(2) { s.add(marco, jpeg) }
        val assigned = s.forDoors(listOf(9, 1, 5))
        assertEquals(setOf(1, 5), assigned.keys) // 2 foto: la 9 tiene la sua animazione
        assertEquals(2, assigned.values.toSet().size)
        assertEquals(assigned, s.forDoors(listOf(1, 5, 9))) // e sempre le stesse
    }

    @Test
    fun `la prima foto caricata va nella prima casella con foto, la seconda nella seconda`() = runTest {
        val times = ArrayDeque(listOf("2026-11-26T09:00:00.500Z", "2026-11-26T09:00:01Z", "2026-11-26T09:00:01.250Z"))
        val s = PhotoService(InMemoryPhotoStore(), PhotoService.MAX_TOTAL_MEMORY, now = { java.time.Instant.parse(times.removeFirst()) })
        val ids = List(3) { s.add(marco, jpeg).id }
        assertEquals(mapOf(1 to ids[0], 7 to ids[1], 9 to ids[2]), s.forDoors(listOf(9, 1, 7, 16)))
        assertEquals(ids, s.list().map { it.id })
    }

    @Test
    fun `via HTTP la foto si carica, si scarica con la chiave e compare solo nelle caselle aperte`() = testApplication {
        val config = Config.fromEnv(mapOf("FAKE_TODAY" to "2026-12-02", "ADVENT_YEAR" to "2026"))
        val calendar = AdventCalendar(
            mapOf(
                2 to AdventEntry(2, title = "Due", withPhoto = true),
                9 to AdventEntry(9, title = "Nove", withPhoto = true),
            ),
            2026,
        )
        application { module(config, calendar, Stores.inMemory()) }

        suspend fun upload(key: String) = client.post("/api/photos") {
            header("X-Key", key)
            contentType(ContentType.Image.JPEG)
            setBody(jpeg)
        }
        val res = upload("dev-chiave-2")
        assertEquals(HttpStatusCode.OK, res.status)
        val id = Regex("\"id\":\"([^\"]+)\"").find(res.bodyAsText())!!.groupValues[1]

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/photos/$id").status)
        assertContentEquals(jpeg, client.get("/api/photos/$id") { header("X-Key", "dev-chiave-1") }.readRawBytes())

        val advent = client.get("/api/advent") { header("X-Key", "dev-chiave-1") }.bodyAsText()
        assertTrue("\"photo\":\"$id\"" in advent) // la casella 2 (aperta) mostra la foto
        assertEquals(1, Regex("\"photo\":\"").findAll(advent).count()) // la 9 e' chiusa: niente foto
    }
}
