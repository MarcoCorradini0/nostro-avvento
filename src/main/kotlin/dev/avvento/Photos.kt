package dev.avvento

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Le foto ricordo: tutti e due le caricano, finiscono nel collage della sezione "Ricordi"
 * e tutti e due le possono togliere (es. doppioni).
 * Alcune caselle (quelle con "withPhoto": true nel file dell'avvento) mostrano una foto
 * presa a caso dal collage, senza ripetizioni finche' ci sono abbastanza foto.
 * Il telefono rimpicciolisce le foto prima di mandarle: qui arrivano JPEG da poche centinaia di KB.
 */
@Serializable
data class PhotoMeta(val id: String, val by: String, val at: String)

class Photo(val meta: PhotoMeta, val mime: String, val bytes: ByteArray)

@Serializable
data class PhotosResponse(val photos: List<PhotoMeta>)

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
                    mime    TEXT NOT NULL,
                    data    BYTEA NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    override suspend fun list(): List<PhotoMeta> = db.sql { c ->
        c.prepareStatement("SELECT id, by_user, at FROM photos").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(PhotoMeta(rs.getString(1), rs.getString(2), rs.getString(3))) } }
        }
    }

    override suspend fun get(id: String): Photo? = db.sql { c ->
        c.prepareStatement("SELECT id, by_user, at, mime, data FROM photos WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs ->
                if (rs.next()) Photo(PhotoMeta(rs.getString(1), rs.getString(2), rs.getString(3)), rs.getString(4), rs.getBytes(5)) else null
            }
        }
    }

    override suspend fun save(photo: Photo) = db.sql { c ->
        c.prepareStatement("INSERT INTO photos (id, by_user, at, mime, data) VALUES (?, ?, ?, ?, ?)").use { st ->
            st.setString(1, photo.meta.id)
            st.setString(2, photo.meta.by)
            st.setString(3, photo.meta.at)
            st.setString(4, photo.mime)
            st.setBytes(5, photo.bytes)
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
    private val maxTotalBytes: Long,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    suspend fun list(): List<PhotoMeta> = store.list().sortedBy { it.at }

    suspend fun get(id: String): Photo? = store.get(id)

    /**
     * Quale foto va in ogni casella "con foto". Gli id sono UUID casuali: ordinandoli otteniamo
     * un ordine a caso ma stabile. Casella k-esima -> foto k-esima, mai doppioni:
     * se le foto sono meno delle caselle, le ultime caselle restano con la loro animazione.
     */
    suspend fun forDoors(days: List<Int>): Map<Int, String> =
        days.sorted().zip(store.list().map { it.id }.sorted()).toMap()

    suspend fun add(user: User, bytes: ByteArray): PhotoMeta = mutex.withLock {
        val mime = sniff(bytes) ?: throw ApiException(HttpStatusCode.UnsupportedMediaType, "Serve una foto JPEG, PNG o WebP")
        if (bytes.size > MAX_BYTES) throw ApiException(HttpStatusCode.PayloadTooLarge, "Foto troppo grande")
        if (store.list().size >= MAX_PHOTOS || store.totalBytes() + bytes.size > maxTotalBytes) {
            throw ApiException(HttpStatusCode.InsufficientStorage, "Spazio per le foto finito")
        }
        val meta = PhotoMeta(id = UUID.randomUUID().toString(), by = user.id, at = now().toString())
        store.save(Photo(meta, mime, bytes))
        meta
    }

    /** Tutti e due possono togliere qualsiasi foto (doppioni, foto venute male...). */
    suspend fun delete(id: String) = mutex.withLock {
        store.get(id) ?: throw ApiException(HttpStatusCode.NotFound, "Foto non trovata")
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
