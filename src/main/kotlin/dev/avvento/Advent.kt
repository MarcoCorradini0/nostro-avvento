package dev.avvento

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** Una casella cosi' come la scrivi nel file JSON. */
@Serializable
data class AdventEntry(
    val day: Int,
    /** "text" | "song" | "voucher" | "question" ... (il frontend sceglie l'icona) */
    val type: String = "text",
    val title: String,
    val text: String = "",
    val url: String? = null,
)

/** Cosa mandiamo al browser: il contenuto c'e' SOLO se la casella e' sbloccata. */
@Serializable
data class AdventDoor(
    val day: Int,
    val unlocked: Boolean,
    val content: AdventEntry? = null,
)

@Serializable
data class AdventResponse(
    val today: String,
    val doors: List<AdventDoor>,
)

class AdventCalendar(
    private val entries: Map<Int, AdventEntry>,
    private val year: Int,
) {
    /** La casella N si apre dal giorno N dicembre (ora del server, non del telefono). */
    fun isUnlocked(day: Int, today: LocalDate): Boolean =
        !today.isBefore(LocalDate.of(year, 12, day))

    fun doors(today: LocalDate): List<AdventDoor> = (1..24).map { day ->
        val open = isUnlocked(day, today)
        AdventDoor(day = day, unlocked = open, content = if (open) entries[day] else null)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(path: String, year: Int): AdventCalendar {
            val file = File(path)
            require(file.exists()) { "File dell'avvento non trovato: ${file.absolutePath}" }
            val list = json.decodeFromString<List<AdventEntry>>(file.readText())
            require(list.all { it.day in 1..24 }) { "Ogni casella deve avere day tra 1 e 24" }
            return AdventCalendar(list.associateBy { it.day }, year)
        }
    }
}
