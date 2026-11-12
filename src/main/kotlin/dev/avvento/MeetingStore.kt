package dev.avvento

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Dove salviamo gli incontri. Due versioni: in memoria (sviluppo/test) e PostgreSQL (Neon). */
interface MeetingStore {
    suspend fun all(): List<Meeting>
    suspend fun get(id: String): Meeting?
    suspend fun save(meeting: Meeting)
    suspend fun count(): Int
}

/** Si perde tutto al riavvio: va bene in locale e nei test. */
class InMemoryMeetingStore : MeetingStore {
    private val data = ConcurrentHashMap<String, Meeting>()
    override suspend fun all() = data.values.toList()
    override suspend fun get(id: String) = data[id]
    override suspend fun save(meeting: Meeting) { data[meeting.id] = meeting }
    override suspend fun count() = data.size
}

/**
 * PostgreSQL con JDBC "puro", senza ORM: per una tabella sola basta e avanza.
 * Ogni incontro e' una riga con il suo JSON: niente migrazioni se aggiungiamo campi.
 */
class PostgresMeetingStore(databaseUrl: String) : MeetingStore {
    private val jdbcUrl: String
    private val props = Properties()
    private val mutex = Mutex()
    private var connection: Connection? = null
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // Neon da' un indirizzo tipo postgresql://utente:password@host/db?sslmode=require
        val uri = URI(databaseUrl)
        val (user, password) = uri.rawUserInfo.orEmpty().split(":", limit = 2).map { URLDecoder.decode(it, Charsets.UTF_8) } + listOf("", "")
        val params = uri.rawQuery.orEmpty().split("&").filter { it.isNotBlank() && !it.startsWith("channel_binding") }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        jdbcUrl = "jdbc:postgresql://${uri.host}$port${uri.path}" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        props["user"] = user
        props["password"] = password
        props["ApplicationName"] = "nostro-avvento"
    }

    suspend fun init() = sql { c ->
        c.createStatement().use {
            it.execute(
                """
                CREATE TABLE IF NOT EXISTS meetings (
                    id         TEXT PRIMARY KEY,
                    data       TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.trimIndent(),
            )
        }
    }

    override suspend fun all(): List<Meeting> = sql { c ->
        c.prepareStatement("SELECT data FROM meetings").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(json.decodeFromString<Meeting>(rs.getString(1))) } }
        }
    }

    override suspend fun get(id: String): Meeting? = sql { c ->
        c.prepareStatement("SELECT data FROM meetings WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) json.decodeFromString<Meeting>(rs.getString(1)) else null }
        }
    }

    override suspend fun save(meeting: Meeting) = sql { c ->
        c.prepareStatement(
            "INSERT INTO meetings (id, data) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()",
        ).use { st ->
            st.setString(1, meeting.id)
            st.setString(2, json.encodeToString(Meeting.serializer(), meeting))
            st.executeUpdate()
        }
        Unit
    }

    override suspend fun count(): Int = sql { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM meetings").use { rs -> rs.next(); rs.getInt(1) } }
    }

    /**
     * Una sola connessione, riusata. Neon si addormenta dopo 5 minuti e chiude le connessioni:
     * se la query fallisce per questo, riapriamo la connessione e riproviamo una volta.
     */
    private suspend fun <T> sql(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                block(connect())
            } catch (e: SQLException) {
                runCatching { connection?.close() }
                connection = null
                block(connect())
            }
        }
    }

    private fun connect(): Connection =
        connection?.takeIf { !it.isClosed && it.isValid(2) }
            ?: DriverManager.getConnection(jdbcUrl, props).also { connection = it }
}
