package dev.avvento

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

@Serializable
enum class MeetingStatus { PENDING, ACCEPTED, DECLINED, CANCELLED }

@Serializable
enum class EventKind { PROPOSED, MODIFIED, ACCEPTED, DECLINED, CANCELLED }

/** Una riga della "chat" di un incontro: chi ha fatto cosa e quando. */
@Serializable
data class MeetingEvent(
    val at: String,
    val by: String,
    val kind: EventKind,
    val date: String? = null,
    val text: String? = null,
)

@Serializable
data class Meeting(
    val id: String,
    val date: String,
    val text: String,
    val status: MeetingStatus,
    /** Di chi e' il turno di rispondere (null se nessuno deve rispondere). */
    val waitingFor: String?,
    val createdBy: String,
    val events: List<MeetingEvent>,
)

@Serializable
data class MeetingRequest(val date: String, val text: String = "")

class MeetingException(val status: HttpStatusCode, message: String) : Exception(message)

/**
 * Le regole degli incontri:
 *  - uno propone un giorno (+ descrizione fino a 200 parole) -> tocca all'altro
 *  - l'altro accetta, rifiuta o propone una modifica (-> torna il turno al primo)
 *  - un incontro confermato si puo' ancora modificare o annullare
 */
class MeetingService(
    private val store: MeetingStore,
    private val users: UserDirectory,
    private val today: () -> LocalDate,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex() // una modifica alla volta: niente risposte "incrociate"

    suspend fun list(): List<Meeting> = store.all().sortedWith(compareBy({ it.date }, { it.id }))

    suspend fun propose(user: User, req: MeetingRequest): Meeting = mutex.withLock {
        val date = validate(req)
        if (store.count() >= MAX_MEETINGS) throw MeetingException(HttpStatusCode.Conflict, "Troppi incontri salvati")
        val meeting = Meeting(
            id = UUID.randomUUID().toString(),
            date = date.toString(),
            text = req.text.trim(),
            status = MeetingStatus.PENDING,
            waitingFor = otherId(user),
            createdBy = user.id,
            events = listOf(event(user, EventKind.PROPOSED, date.toString(), req.text.trim())),
        )
        store.save(meeting)
        meeting
    }

    suspend fun accept(user: User, id: String) = answer(user, id, EventKind.ACCEPTED, MeetingStatus.ACCEPTED)

    suspend fun decline(user: User, id: String) = answer(user, id, EventKind.DECLINED, MeetingStatus.DECLINED)

    suspend fun modify(user: User, id: String, req: MeetingRequest): Meeting = mutex.withLock {
        val m = load(id)
        if (m.status != MeetingStatus.PENDING && m.status != MeetingStatus.ACCEPTED) {
            throw MeetingException(HttpStatusCode.Conflict, "Questo incontro non si puo' piu' modificare")
        }
        val date = validate(req)
        val updated = m.copy(
            date = date.toString(),
            text = req.text.trim(),
            status = MeetingStatus.PENDING,
            waitingFor = otherId(user),
            events = m.events + event(user, EventKind.MODIFIED, date.toString(), req.text.trim()),
        )
        store.save(updated)
        updated
    }

    suspend fun cancel(user: User, id: String): Meeting = mutex.withLock {
        val m = load(id)
        if (m.status != MeetingStatus.PENDING && m.status != MeetingStatus.ACCEPTED) {
            throw MeetingException(HttpStatusCode.Conflict, "Questo incontro e' gia' chiuso")
        }
        val updated = m.copy(
            status = MeetingStatus.CANCELLED,
            waitingFor = null,
            events = m.events + event(user, EventKind.CANCELLED),
        )
        store.save(updated)
        updated
    }

    private suspend fun answer(user: User, id: String, kind: EventKind, status: MeetingStatus): Meeting = mutex.withLock {
        val m = load(id)
        if (m.status != MeetingStatus.PENDING || m.waitingFor != user.id) {
            throw MeetingException(HttpStatusCode.Conflict, "Non tocca a te rispondere")
        }
        val updated = m.copy(status = status, waitingFor = null, events = m.events + event(user, kind))
        store.save(updated)
        updated
    }

    private suspend fun load(id: String): Meeting =
        store.get(id) ?: throw MeetingException(HttpStatusCode.NotFound, "Incontro non trovato")

    private fun otherId(user: User): String =
        users.other(user)?.id ?: throw MeetingException(HttpStatusCode.Conflict, "Manca l'altra persona")

    private fun event(user: User, kind: EventKind, date: String? = null, text: String? = null) =
        MeetingEvent(at = now().toString(), by = user.id, kind = kind, date = date, text = text)

    private fun validate(req: MeetingRequest): LocalDate {
        val date = try {
            LocalDate.parse(req.date)
        } catch (_: DateTimeParseException) {
            throw MeetingException(HttpStatusCode.BadRequest, "Data non valida")
        }
        val t = today()
        if (date.isBefore(t)) throw MeetingException(HttpStatusCode.BadRequest, "La data e' gia' passata")
        if (date.isAfter(t.plusYears(2))) throw MeetingException(HttpStatusCode.BadRequest, "Data troppo lontana")
        if (req.text.length > MAX_CHARS || wordCount(req.text) > MAX_WORDS) {
            throw MeetingException(HttpStatusCode.BadRequest, "Massimo $MAX_WORDS parole")
        }
        return date
    }

    companion object {
        const val MAX_WORDS = 200
        const val MAX_CHARS = 2000
        const val MAX_MEETINGS = 500

        fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }
    }
}
