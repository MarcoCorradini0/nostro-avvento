package dev.avvento

import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/** Messaggi che il server manda al browser sul WebSocket. Il campo "type" dice quale. */
@Serializable
sealed interface ServerMessage

/** Chi e' online adesso, e quando abbiamo visto per l'ultima volta chi non lo e'. */
@Serializable
@SerialName("presence")
data class PresenceMessage(val online: List<String>, val lastSeen: Map<String, String>) : ServerMessage

/** "Ti e' arrivata una proposta / una risposta": la pagina mostra un avviso e aggiorna la lista. */
@Serializable
@SerialName("meeting")
data class MeetingNotice(val id: String, val by: String, val kind: EventKind, val date: String) : ServerMessage

/** Primo messaggio del browser: la chiave. (Il WebSocket del browser non puo' mandare header.) */
@Serializable
data class HelloMessage(val key: String)

val realtimeJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

/**
 * Tiene traccia delle connessioni aperte.
 * Basta la memoria: su Render gira una sola istanza. Con piu' repliche (Kubernetes)
 * servirebbe un canale condiviso tipo Redis pub/sub per farle parlare tra loro.
 */
class PresenceHub(private val now: () -> Instant = Instant::now) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, MutableSet<WebSocketServerSession>>()
    private val lastSeen = mutableMapOf<String, Instant>()

    suspend fun join(user: User, session: WebSocketServerSession) {
        mutex.withLock { sessions.getOrPut(user.id) { mutableSetOf() }.add(session) }
        broadcast(snapshot())
    }

    suspend fun leave(user: User, session: WebSocketServerSession) {
        mutex.withLock {
            val set = sessions[user.id] ?: return@withLock
            set.remove(session)
            // Offline solo quando ha chiuso TUTTE le schede/telefoni.
            if (set.isEmpty()) {
                sessions.remove(user.id)
                lastSeen[user.id] = now()
            }
        }
        broadcast(snapshot())
    }

    suspend fun snapshot(): PresenceMessage = mutex.withLock {
        PresenceMessage(
            online = sessions.keys.sorted(),
            lastSeen = lastSeen.filterKeys { it !in sessions }.mapValues { it.value.toString() },
        )
    }

    suspend fun sendTo(userId: String, message: ServerMessage) {
        val targets = mutex.withLock { sessions[userId]?.toList().orEmpty() }
        send(targets, message)
    }

    private suspend fun broadcast(message: ServerMessage) {
        val targets = mutex.withLock { sessions.values.flatten() }
        send(targets, message)
    }

    private suspend fun send(targets: List<WebSocketServerSession>, message: ServerMessage) {
        val text = realtimeJson.encodeToString(ServerMessage.serializer(), message)
        // Una connessione appena chiusa puo' fallire: la ignoriamo, la toglie leave().
        targets.forEach { runCatching { it.send(Frame.Text(text)) } }
    }
}
