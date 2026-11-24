package dev.avvento

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.request.contentLength
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
 * Header di sicurezza su ogni risposta: il browser non "indovina" il tipo dei file,
 * non manda l'indirizzo (con la chiave) ad altri siti e non mostra l'app dentro iframe altrui.
 */
private val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("X-Frame-Options", "DENY")
    }
}

/** Dove stanno incontri e foto. */
class Stores(val meetings: MeetingStore, val photos: PhotoStore, val persistent: Boolean) {
    companion object {
        fun inMemory() = Stores(InMemoryMeetingStore(), InMemoryPhotoStore(), persistent = false)
    }
}

/**
 * Senza DATABASE_URL incontri e foto stanno in memoria (sviluppo); con Postgres sono salvati davvero.
 * Se il database non risponde (es. il Postgres free di Render e' scaduto) NON blocchiamo l'avvio:
 * il calendario dell'avvento deve funzionare comunque.
 */
fun createStores(config: Config): Stores {
    val url = config.databaseUrl ?: return Stores.inMemory().also {
        log.info("Incontri e foto in memoria (DATABASE_URL non impostata)")
    }
    return try {
        val db = Postgres(url)
        val meetings = PostgresMeetingStore(db)
        val photos = PostgresPhotoStore(db)
        runBlocking { meetings.init(); photos.init() }
        log.info("Incontri e foto salvati su PostgreSQL")
        Stores(meetings, photos, persistent = true)
    } catch (e: Exception) {
        log.error("Database non raggiungibile ({}): uso la memoria", e.message)
        Stores.inMemory()
    }
}

fun Application.module(
    config: Config,
    advent: AdventCalendar = AdventCalendar.load(config.adventFile, config.adventYear),
    stores: Stores = createStores(config),
) {
    val users = UserDirectory(config.users)
    val hub = PresenceHub()
    val meetings = MeetingService(stores.meetings, users, today = config::today)
    val photos = PhotoService(
        stores.photos,
        maxTotalBytes = if (stores.persistent) PhotoService.MAX_TOTAL_DB else PhotoService.MAX_TOTAL_MEMORY,
    )

    install(SecurityHeaders)
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
                // Le caselle aperte "con foto": solo l'id, la foto si scarica a parte.
                val assigned = photos.forDoors(advent.photoDays())
                val doors = advent.doors(today).map { d -> if (d.unlocked) d.copy(photo = assigned[d.day]) else d }
                call.respond(AdventResponse(today.toString(), doors))
            }

            meetingRoutes(users, meetings, hub)
            photoRoutes(users, photos, hub)
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
            users.other(me)?.let { hub.sendTo(it.id, MeetingNotice(meeting.id, me.id, last.kind, meeting.date, meeting.time)) }
        } catch (e: ApiException) {
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

private fun Route.photoRoutes(users: UserDirectory, photos: PhotoService, hub: PresenceHub) {
    route("/photos") {
        get {
            val me = call.requireUser(users) ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(PhotosResponse(photos.list()))
        }
        get("/{id}") {
            call.requireUser(users) ?: return@get
            val photo = photos.get(call.parameters["id"]!!)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Foto non trovata"))
            // Una foto non cambia mai (id nuovo a ogni caricamento): il telefono la puo' tenere in cache.
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.respondBytes(photo.bytes, ContentType.parse(photo.mime))
        }
        post {
            val me = call.requireUser(users) ?: return@post
            val len = call.request.contentLength()
            if (len == null || len > PhotoService.MAX_BYTES) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Foto troppo grande"))
            }
            try {
                val meta = photos.add(me, call.receive<ByteArray>())
                call.respond(meta)
                users.other(me)?.let { hub.sendTo(it.id, PhotoNotice(meta.id, me.id)) }
            } catch (e: ApiException) {
                call.respond(e.status, ErrorResponse(e.message ?: "Errore"))
            }
        }
        post("/{id}/delete") {
            call.requireUser(users) ?: return@post
            try {
                photos.delete(call.parameters["id"]!!)
                call.respond(HttpStatusCode.OK, ErrorResponse("ok"))
            } catch (e: ApiException) {
                call.respond(e.status, ErrorResponse(e.message ?: "Errore"))
            }
        }
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
