package dev.avvento

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.seconds

fun main() {
    val config = Config.fromEnv()
    // CIO: motore HTTP scritto in Kotlin, leggero e veloce ad avviarsi.
    embeddedServer(CIO, port = config.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

@Serializable
data class MeResponse(val id: String, val name: String, val otherId: String?, val otherName: String?)

@Serializable
data class MeetingsResponse(val meetings: List<Meeting>)

@Serializable
data class ErrorResponse(val error: String)

private val log = LoggerFactory.getLogger("avvento")

/**
 * Senza DATABASE_URL gli incontri stanno in memoria (sviluppo); con Postgres sono salvati davvero.
 * Se il database non risponde (es. il Postgres free di Render e' scaduto) NON blocchiamo l'avvio:
 * il calendario dell'avvento deve funzionare comunque.
 */
fun createMeetingStore(config: Config): MeetingStore {
    val url = config.databaseUrl ?: return InMemoryMeetingStore().also {
        log.warn("DATABASE_URL non impostata: gli incontri restano in memoria e si perdono al riavvio")
    }
    return try {
        PostgresMeetingStore(url).also { runBlocking { it.init() } }
    } catch (e: Exception) {
        log.error("Database non raggiungibile ({}): uso la memoria", e.message)
        InMemoryMeetingStore()
    }
}

fun Application.module(
    config: Config,
    advent: AdventCalendar = AdventCalendar.load(config.adventFile, config.adventYear),
    store: MeetingStore = createMeetingStore(config),
) {
    val users = UserDirectory(config.users)
    val hub = PresenceHub()
    val meetings = MeetingService(store, users, today = config::today)

    install(ContentNegotiation) { json() }
    install(WebSockets) {
        pingPeriod = 25.seconds // tiene viva la connessione (e sveglio il server finche' la pagina e' aperta)
        timeout = 40.seconds
        maxFrameSize = 4096
    }

    // La pagina sta su GitHub Pages (altro dominio): il browser chiede il permesso (CORS).
    // Accettiamo SOLO le origini configurate, non "tutto il mondo".
    if (config.allowedOrigins.isNotEmpty()) {
        install(CORS) {
            config.allowedOrigins.forEach { allowHost(it.substringAfter("://"), schemes = listOf(it.substringBefore("://"))) }
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowHeader("X-Key")
            allowHeader(HttpHeaders.ContentType)
            maxAgeInSeconds = 86_400 // il browser ricorda il permesso per un giorno
        }
    }

    routing {
        // Render (e Kubernetes) chiamano questo endpoint per sapere se l'app e' viva.
        get("/healthz") { call.respondText("ok") }

        route("/api") {
            get("/me") {
                val me = call.requireUser(users) ?: return@get
                val other = users.other(me)
                call.respond(MeResponse(me.id, me.name, other?.id, other?.name))
            }

            get("/advent") {
                call.requireUser(users) ?: return@get
                val today = config.today()
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(AdventResponse(today.toString(), advent.doors(today)))
            }

            meetingRoutes(users, meetings, hub)
        }

        // Tempo reale: chi e' online + avvisi sugli incontri.
        webSocket("/ws") {
            if (!originAllowed(call, config)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin"))
                return@webSocket
            }
            // Il primo messaggio deve essere la chiave, entro 5 secondi.
            val hello = withTimeoutOrNull(5.seconds) { incoming.receiveCatching().getOrNull() as? Frame.Text }
            val user = hello
                ?.let { runCatching { realtimeJson.decodeFromString<HelloMessage>(it.readText()) }.getOrNull() }
                ?.let { users.findByKey(it.key) }
            if (user == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth"))
                return@webSocket
            }
            hub.join(user, this)
            try {
                for (frame in incoming) { /* il browser non manda altro: ascoltiamo solo la chiusura */ }
            } finally {
                hub.leave(user, this)
            }
        }

        // Solo in sviluppo/k8s: se c'e' la cartella web/ la serviamo, cosi' basta ./gradlew run.
        File(config.webDir).takeIf { it.isDirectory }?.let { staticFiles("/", it) }
    }
}

private fun Route.meetingRoutes(users: UserDirectory, meetings: MeetingService, hub: PresenceHub) {
    // Esegue un'azione sugli incontri, risponde e avvisa l'altra persona in tempo reale.
    suspend fun ApplicationCall.handle(action: suspend (User) -> Meeting) {
        val me = requireUser(users) ?: return
        try {
            val meeting = action(me)
            respond(meeting)
            val last = meeting.events.last()
            users.other(me)?.let { hub.sendTo(it.id, MeetingNotice(meeting.id, me.id, last.kind, meeting.date)) }
        } catch (e: MeetingException) {
            respond(e.status, ErrorResponse(e.message ?: "Errore"))
        } catch (e: BadRequestException) {
            respond(HttpStatusCode.BadRequest, ErrorResponse("Richiesta non valida"))
        }
    }

    route("/meetings") {
        get {
            call.requireUser(users) ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(MeetingsResponse(meetings.list()))
        }
        post { call.handle { me -> meetings.propose(me, call.receive<MeetingRequest>()) } }
        post("/{id}/accept") { call.handle { me -> meetings.accept(me, call.parameters["id"]!!) } }
        post("/{id}/decline") { call.handle { me -> meetings.decline(me, call.parameters["id"]!!) } }
        post("/{id}/cancel") { call.handle { me -> meetings.cancel(me, call.parameters["id"]!!) } }
        post("/{id}/modify") { call.handle { me -> meetings.modify(me, call.parameters["id"]!!, call.receive<MeetingRequest>()) } }
    }
}

/** Restituisce l'utente della chiave, oppure risponde 401 e restituisce null. */
private suspend fun ApplicationCall.requireUser(users: UserDirectory): User? =
    users.findByKey(keyHeader()) ?: run {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Link non valido"))
        null
    }

/** WebSocket: il browser non applica CORS, quindi controlliamo noi da che sito arriva. */
private fun originAllowed(call: ApplicationCall, config: Config): Boolean {
    if (config.allowedOrigins.isEmpty()) return true
    val origin = call.request.headers[HttpHeaders.Origin]?.trimEnd('/') ?: return false
    val sameHost = origin.substringAfter("://") == call.request.headers[HttpHeaders.Host]
    return origin in config.allowedOrigins || sameHost
}
