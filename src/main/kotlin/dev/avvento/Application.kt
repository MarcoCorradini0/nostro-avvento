package dev.avvento

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

fun main() {
    val config = Config.fromEnv()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
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

    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            this@module.environment.log.error("Errore non gestito", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Qualcosa e' andato storto"))
        }
    }

    routing {
        // Kubernetes chiamera' questo endpoint per sapere se il pod e' vivo.
        get("/healthz") { call.respond(mapOf("status" to "ok")) }

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
                call.response.header("Cache-Control", "no-store")
                call.respond(AdventResponse(today.toString(), advent.doors(today)))
            }
        }

        // Il sito (HTML/CSS/JS) sta in src/main/resources/static
        staticResources("/", "static")
    }
}
