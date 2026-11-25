package dev.avvento

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
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
    /** Animazione facoltativa nel popup: una di [ANIMATIONS]. */
    val anim: String? = null,
    /** Se true, la casella mostra una foto presa a caso dai ricordi (se ce ne sono abbastanza, se no l'animazione). */
    val withPhoto: Boolean = false,
)

/** Cosa mandiamo al browser: il contenuto c'e' SOLO se la casella e' sbloccata. */
@Serializable
data class AdventDoor(
    val day: Int,
    val unlocked: Boolean,
    val content: AdventEntry? = null,
    /** Id della foto dei ricordi mostrata in questa casella (solo se e' aperta). */
    val photo: String? = null,
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
    /** Le caselle che mostrano una foto dei ricordi. */
    fun photoDays(): List<Int> = entries.values.filter { it.withPhoto }.map { it.day }.sorted()

    /** La casella N si apre dal giorno N dicembre (ora del server, non del telefono). */
    fun isUnlocked(day: Int, today: LocalDate): Boolean =
        !today.isBefore(LocalDate.of(year, 12, day))

    /**
     * Le caselle 1-24 si vedono sempre (chiuse o aperte).
     * La 25 e' la sorpresa di Natale: finche' non e' il 25 non esiste proprio nella risposta.
     */
    fun doors(today: LocalDate): List<AdventDoor> =
        (1..LAST_DAY).filter { it <= 24 || isUnlocked(it, today) }.map { day ->
            val open = isUnlocked(day, today)
            AdventDoor(day = day, unlocked = open, content = if (open) entries[day] else null)
        }

    companion object {
        /** 24 caselle dell'avvento + la 25 speciale di Natale. */
        const val LAST_DAY = 25

        /** Le animazioni che la pagina sa disegnare (vedi web/app.js). */
        val ANIMATIONS = setOf("hearts", "beat", "plane", "kiss", "together", "snow", "gift", "sleigh", "tree", "stars", "aurora", "natale")
        private val json = Json { ignoreUnknownKeys = true }

        fun load(path: String, year: Int): AdventCalendar {
            val file = File(path)
            require(file.exists()) { "File dell'avvento non trovato: ${file.absolutePath}" }
            val list = json.decodeFromString<List<AdventEntry>>(file.readText())
            require(list.all { it.day in 1..LAST_DAY }) { "Ogni casella deve avere day tra 1 e $LAST_DAY" }
            // Un nome di animazione sbagliato non deve bloccare il calendario: la togliamo e lo diciamo nel log.
            val checked = list.map {
                if (it.anim == null || it.anim in ANIMATIONS) it
                else it.copy(anim = null).also { e ->
                    LoggerFactory.getLogger("avvento").warn("Casella {}: anim '{}' sconosciuta (ignorata), usa una tra {}", e.day, it.anim, ANIMATIONS)
                }
            }
            return AdventCalendar(checked.associateBy { it.day }, year)
        }
    }
}
