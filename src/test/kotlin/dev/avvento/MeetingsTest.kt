package dev.avvento

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeetingsTest {
    private val marco = User("u1", "Marco", "k1")
    private val lei = User("u2", "Lei", "k2")
    private val today = LocalDate.of(2026, 12, 1)
    private fun service() = MeetingService(InMemoryMeetingStore(), UserDirectory(listOf(marco, lei)), today = { today })

    @Test
    fun `se il database non risponde il server parte lo stesso, in memoria`() {
        val config = Config.fromEnv(mapOf("DATABASE_URL" to "postgresql://utente:pw@127.0.0.1:1/nessuno"))
        assertTrue(createMeetingStore(config) is InMemoryMeetingStore)
    }

    @Test
    fun `proposta, controproposta e accettazione`() = runTest {
        val s = service()
        val m = s.propose(marco, MeetingRequest("2026-12-20", "Mercatini di Natale"))
        assertEquals(MeetingStatus.PENDING, m.status)
        assertEquals("u2", m.waitingFor)

        val modified = s.modify(lei, m.id, MeetingRequest("2026-12-21", "Meglio domenica"))
        assertEquals("u1", modified.waitingFor) // ora tocca di nuovo a Marco
        assertEquals("2026-12-21", modified.date)

        val accepted = s.accept(marco, m.id)
        assertEquals(MeetingStatus.ACCEPTED, accepted.status)
        assertNull(accepted.waitingFor)
        assertEquals(listOf(EventKind.PROPOSED, EventKind.MODIFIED, EventKind.ACCEPTED), accepted.events.map { it.kind })
    }

    @Test
    fun `non puoi accettare la tua stessa proposta`() = runTest {
        val s = service()
        val m = s.propose(marco, MeetingRequest("2026-12-20"))
        val e = assertFailsWith<MeetingException> { s.accept(marco, m.id) }
        assertEquals(HttpStatusCode.Conflict, e.status)
    }

    @Test
    fun `rifiuto e annullamento chiudono l'incontro`() = runTest {
        val s = service()
        val a = s.propose(marco, MeetingRequest("2026-12-20"))
        assertEquals(MeetingStatus.DECLINED, s.decline(lei, a.id).status)
        assertFailsWith<MeetingException> { s.modify(marco, a.id, MeetingRequest("2026-12-22")) }

        val b = s.propose(lei, MeetingRequest("2026-12-27"))
        s.accept(marco, b.id)
        assertEquals(MeetingStatus.CANCELLED, s.cancel(lei, b.id).status)
    }

    @Test
    fun `date passate, testi troppo lunghi e incontri inesistenti sono rifiutati`() = runTest {
        val s = service()
        assertEquals(HttpStatusCode.BadRequest, assertFailsWith<MeetingException> { s.propose(marco, MeetingRequest("2026-11-30")) }.status)
        assertEquals(HttpStatusCode.BadRequest, assertFailsWith<MeetingException> { s.propose(marco, MeetingRequest("domani")) }.status)
        val tooLong = List(201) { "ciao" }.joinToString(" ")
        assertEquals(HttpStatusCode.BadRequest, assertFailsWith<MeetingException> { s.propose(marco, MeetingRequest("2026-12-20", tooLong)) }.status)
        assertEquals(HttpStatusCode.NotFound, assertFailsWith<MeetingException> { s.accept(lei, "non-esiste") }.status)
        // 200 parole esatte vanno bene
        s.propose(marco, MeetingRequest("2026-12-20", List(200) { "ciao" }.joinToString(" ")))
    }
}
