package dev.avvento

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealtimeTest {
    private val calendar = AdventCalendar(emptyMap(), 2026)

    private fun ApplicationTestBuilder.setup() {
        val config = Config.fromEnv(mapOf("FAKE_TODAY" to "2026-12-01"))
        application { module(config, calendar, InMemoryMeetingStore()) }
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(WebSockets) }

    private suspend fun DefaultClientWebSocketSession.next(): ServerMessage =
        realtimeJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())

    @Test
    fun `chi e' online, e quando l'altra persona esce`() = testApplication {
        setup()
        val client = wsClient()
        client.webSocket("/ws") {
            send(Frame.Text("""{"key":"dev-chiave-1"}"""))
            assertEquals(listOf("u1"), (next() as PresenceMessage).online)

            client.webSocket("/ws") {
                send(Frame.Text("""{"key":"dev-chiave-2"}"""))
                assertEquals(listOf("u1", "u2"), (next() as PresenceMessage).online)
            }

            // Marco vede Lei entrare (u1+u2) e poi uscire (solo u1, con "vista alle ...")
            assertEquals(listOf("u1", "u2"), (next() as PresenceMessage).online)
            val after = next() as PresenceMessage
            assertEquals(listOf("u1"), after.online)
            assertTrue("u2" in after.lastSeen)
        }
    }

    @Test
    fun `senza chiave valida il WebSocket viene chiuso`() = testApplication {
        setup()
        wsClient().webSocket("/ws") {
            send(Frame.Text("""{"key":"sbagliata"}"""))
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }

    @Test
    fun `una proposta arriva in tempo reale all'altra persona`() = testApplication {
        setup()
        val client = wsClient()
        client.webSocket("/ws") {
            send(Frame.Text("""{"key":"dev-chiave-2"}"""))
            next() // presence

            val res = client.post("/api/meetings") {
                header("X-Key", "dev-chiave-1")
                contentType(ContentType.Application.Json)
                setBody("""{"date":"${LocalDate.of(2026, 12, 20)}","text":"Pattinaggio sul ghiaccio?"}""")
            }
            assertEquals(HttpStatusCode.OK, res.status)

            val notice = next() as MeetingNotice
            assertEquals("u1", notice.by)
            assertEquals(EventKind.PROPOSED, notice.kind)
            assertEquals("2026-12-20", notice.date)
        }
    }
}
