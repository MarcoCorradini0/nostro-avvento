package dev.avvento

import java.time.LocalDate
import java.time.ZoneId

/**
 * Tutta la configurazione arriva da variabili d'ambiente.
 * In locale usiamo valori di default comodi; su Kubernetes le imposteremo
 * con ConfigMap e Secret (le chiavi segrete NON finiscono mai nel codice).
 */
data class Config(
    val port: Int,
    val users: List<User>,
    val adventFile: String,
    val zone: ZoneId,
    val adventYear: Int,
    /** Solo per i test: finge che oggi sia questa data (es. 2026-12-05). */
    val fakeToday: LocalDate?,
    /** Siti autorizzati a chiamare l'API (es. https://utente.github.io). Vuoto = stessa origine. */
    val allowedOrigins: List<String>,
    /** Cartella con la pagina web, servita solo se esiste (sviluppo locale). */
    val webDir: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val zone = ZoneId.of(env["TIMEZONE"] ?: "Europe/Rome")
            return Config(
                port = env["PORT"]?.toInt() ?: 8080,
                users = listOf(
                    User(id = "u1", name = env["USER1_NAME"] ?: "Io", key = env["USER1_KEY"] ?: "dev-chiave-1"),
                    User(id = "u2", name = env["USER2_NAME"] ?: "Lei", key = env["USER2_KEY"] ?: "dev-chiave-2"),
                ),
                adventFile = env["ADVENT_FILE"] ?: "advent.example.json",
                zone = zone,
                adventYear = env["ADVENT_YEAR"]?.toInt() ?: LocalDate.now(zone).year,
                fakeToday = env["FAKE_TODAY"]?.let(LocalDate::parse),
                allowedOrigins = env["ALLOWED_ORIGINS"].orEmpty().split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() },
                webDir = env["WEB_DIR"] ?: "web",
            )
        }
    }

    fun today(): LocalDate = fakeToday ?: LocalDate.now(zone)
}
