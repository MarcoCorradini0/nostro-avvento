package dev.avvento

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.io.File

fun main() {
    val config = Config.fromEnv()
    // CIO: motore HTTP scritto in Kotlin, leggero e veloce ad avviarsi.
    embeddedServer(CIO, port = config.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

@Serializable
data class MeResponse(val name: String, val otherName: String?)

@Serializable
data class ErrorResponse(val error: String)

fun Application.module(
    config: Config,
    advent: AdventCalendar = AdventCalendar.load(config.adventFile, config.adventYear),
) {
    val users = UserDirectory(config.users)

    install(ContentNegotiation) { json() }

    // La pagina sta su GitHub Pages (altro dominio): il browser chiede il permesso (CORS).
    // Accettiamo SOLO le origini configurate, non "tutto il mondo".
    if (config.allowedOrigins.isNotEmpty()) {
        install(CORS) {
            config.allowedOrigins.forEach { allowHost(it.substringAfter("://"), schemes = listOf(it.substringBefore("://"))) }
            allowMethod(HttpMethod.Get)
            allowHeader("X-Key")
            maxAgeInSeconds = 86_400 // il browser ricorda il permesso per un giorno
        }
    }

    routing {
        // Render (e Kubernetes) chiamano questo endpoint per sapere se l'app e' viva.
        get("/healthz") { call.respondText("ok") }

        route("/api") {
            get("/me") {
                val me = users.findByKey(call.keyHeader())
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Link non valido"))
                call.respond(MeResponse(me.name, users.other(me)?.name))
            }

            get("/advent") {
                users.findByKey(call.keyHeader())
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Link non valido"))
                val today = config.today()
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(AdventResponse(today.toString(), advent.doors(today)))
            }
        }

        // Solo in sviluppo: se c'e' la cartella web/ la serviamo, cosi' basta ./gradlew run.
        // In produzione la pagina sta su GitHub Pages e l'immagine Docker non contiene web/.
        File(config.webDir).takeIf { it.isDirectory }?.let { staticFiles("/", it) }
    }
}
