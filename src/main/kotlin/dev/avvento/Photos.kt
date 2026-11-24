package dev.avvento

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Le foto ricordo.
 *  - Tutti e due possono aggiungere foto al collage (tab "Ricordi").
 *  - Marco (utente 1) puo' anche mettere una foto dentro una casella: resta nascosta
 *    (anche all'API) finche' la casella non si apre, poi entra anche nel collage.
 * Il telefono rimpicciolisce le foto prima di mandarle: qui arrivano JPEG da poche centinaia di KB.
 */
@Serializable
data class PhotoMeta(val id: String, val by: String, val at: String, val day: Int? = null)

class Photo(val meta: PhotoMeta, val mime: String, val bytes: ByteArray)

@Serializable
data class PhotosResponse(val photos: List<PhotoMeta>, val canPin: Boolean)

interface PhotoStore {
    suspend fun list(): List<PhotoMeta>
    suspend fun get(id: String): Photo?
    suspend fun save(photo: Photo)
    suspend fun delete(id: String)
    suspend fun totalBytes(): Long
}

/** In memoria (sviluppo, test, o se il database non c'e'): limite piu' basso per non finire la RAM. */
class InMemoryPhotoStore : PhotoStore {
    private val data = ConcurrentHashMap<String, Photo>()
    override suspend fun list() = data.values.map { it.meta }
    override suspend fun get(id: String) = data[id]
    override suspend fun save(photo: Photo) { data[photo.meta.id] = photo }
    override suspend fun delete(id: String) { data.remove(id) }
    override suspend fun totalBytes() = data.values.sumOf { it.bytes.size.toLong() }
}

class PostgresPhotoStore(private val db: Postgres) : PhotoStore {
    suspend fun init() = db.sql { c ->
        c.createStatement().use {
            it.execute(
                """
                CREATE TABLE IF NOT EXISTS photos (
                    id      TEXT PRIMARY KEY,
                    by_user TEXT NOT NULL,
                    at      TEXT NOT NULL,
                    day     INT,
                    mime    TEXT NOT NULL,
                    data    BYTEA NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    override suspend fun list(): List<PhotoMeta> = db.sql { c ->
        c.prepareStatement("SELECT id, by_user, at, day FROM photos").use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(PhotoMeta(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4).takeUnless { rs.wasNull() }))
                }
            }
        }
    }

    override suspend fun get(id: String): Photo? = db.sql { c ->
        c.prepareStatement("SELECT id, by_user, at, day, mime, data FROM photos WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                val meta = PhotoMeta(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4).takeUnless { rs.wasNull() })
                Photo(meta, rs.getString(5), rs.getBytes(6))
            }
        }
    }

    override suspend fun save(photo: Photo) = db.sql { c ->
        c.prepareStatement("INSERT INTO photos (id, by_user, at, day, mime, data) VALUES (?, ?, ?, ?, ?, ?)").use { st ->
            st.setString(1, photo.meta.id)
            st.setString(2, photo.meta.by)
            st.setString(3, photo.meta.at)
            if (photo.meta.day == null) st.setNull(4, java.sql.Types.INTEGER) else st.setInt(4, photo.meta.day)
            st.setString(5, photo.mime)
            st.setBytes(6, photo.bytes)
            st.executeUpdate()
        }
        Unit
    }

    override suspend fun delete(id: String) = db.sql { c ->
        c.prepareStatement("DELETE FROM photos WHERE id = ?").use { st -> st.setString(1, id); st.executeUpdate() }
        Unit
    }

    override suspend fun totalBytes(): Long = db.sql { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT coalesce(sum(octet_length(data)), 0) FROM photos").use { rs -> rs.next(); rs.getLong(1) }
        }
    }
}

class PhotoService(
    private val store: PhotoStore,
    /** La casella N e' gia' aperta? (la stessa regola del calendario, ora del server) */
    private val isUnlocked: (Int) -> Boolean,
    private val maxTotalBytes: Long,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    /** Chi puo' mettere foto nelle caselle: chi ha preparato il calendario. */
    fun canPin(user: User) = user.id == "u1"

    private fun visible(meta: PhotoMeta) = meta.day == null || isUnlocked(meta.day)

    suspend fun list(): List<PhotoMeta> = store.list().filter(::visible).sortedBy { it.at }

    /** Per chi ha preparato il calendario: anche le foto delle caselle ancora chiuse. */
    suspend fun listFor(user: User): List<PhotoMeta> =
        if (canPin(user)) store.list().sortedBy { it.at } else list()

    suspend fun get(user: User, id: String): Photo? =
        store.get(id)?.takeIf { visible(it.meta) || (canPin(user) && it.meta.by == user.id) }

    suspend fun idForDay(day: Int): String? = store.list().firstOrNull { it.day == day }?.id

    suspend fun add(user: User, bytes: ByteArray, day: Int?): PhotoMeta = mutex.withLock {
        val mime = sniff(bytes) ?: throw MeetingException(HttpStatusCode.UnsupportedMediaType, "Serve una foto JPEG, PNG o WebP")
        if (bytes.size > MAX_BYTES) throw MeetingException(HttpStatusCode.PayloadTooLarge, "Foto troppo grande")
        if (day != null) {
            if (!canPin(user)) throw MeetingException(HttpStatusCode.Forbidden, "Solo chi ha preparato il calendario mette foto nelle caselle")
            if (day !in 1..AdventCalendar.LAST_DAY) throw MeetingException(HttpStatusCode.BadRequest, "Casella non valida")
        }
        val all = store.list()
        if (all.size >= MAX_PHOTOS || store.totalBytes() + bytes.size > maxTotalBytes) {
            throw MeetingException(HttpStatusCode.InsufficientStorage, "Spazio per le foto finito")
        }
        // Una foto per casella: la nuova sostituisce la vecchia.
        if (day != null) all.filter { it.day == day }.forEach { store.delete(it.id) }
        val meta = PhotoMeta(id = UUID.randomUUID().toString(), by = user.id, at = now().toString(), day = day)
        store.save(Photo(meta, mime, bytes))
        meta
    }

    suspend fun delete(user: User, id: String) = mutex.withLock {
        val photo = store.get(id) ?: throw MeetingException(HttpStatusCode.NotFound, "Foto non trovata")
        if (photo.meta.by != user.id) throw MeetingException(HttpStatusCode.Forbidden, "Puoi togliere solo le foto che hai messo tu")
        store.delete(id)
    }

    companion object {
        const val MAX_BYTES = 2_500_000
        const val MAX_PHOTOS = 300
        const val MAX_TOTAL_DB = 700_000_000L // il Postgres free ha 1 GB
        const val MAX_TOTAL_MEMORY = 60_000_000L

        /** Riconosce il formato dai primi byte, non da quello che dice il browser. */
        fun sniff(b: ByteArray): String? = when {
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            b.size > 8 && b[0] == 0x89.toByte() && String(b, 1, 3, Charsets.US_ASCII) == "PNG" -> "image/png"
            b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }
    }
}
