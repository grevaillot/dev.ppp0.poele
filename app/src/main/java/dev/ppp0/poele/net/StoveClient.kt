package dev.ppp0.poele.net

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalTime

data class LogEntry(val time: LocalTime, val path: String, val params: String, val ok: Boolean, val info: String, val ms: Long)

/**
 * Client HTTP du module (SPEC §4.2, §6) : une seule requête en vol, au plus 2 requêtes/s,
 * corps form-urlencoded (avec l'en-tête attendu par le module), timeout 10 s, pas de redirection.
 */
class StoveClient(@Volatile var host: String, @Volatile var network: Network?) {
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log

    suspend fun post(path: String, params: Map<String, Any> = emptyMap()): JSONObject = MUTEX.withLock {
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastStart)
        if (wait > 0) delay(wait)
        lastStart = System.currentTimeMillis()
        val body = params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v.toString(), "UTF-8")
        }
        val t0 = System.currentTimeMillis()
        try {
            val json = withContext(Dispatchers.IO) { request(path, body) }
            record(path, body, true, "OK", t0)
            json
        } catch (e: Exception) {
            record(path, body, false, e.message ?: e.javaClass.simpleName, t0)
            throw e
        }
    }

    private fun request(path: String, body: String): JSONObject = try {
        request(path, body, network)
    } catch (e: java.net.SocketException) {
        // « Binding socket to network … failed » : le réseau Wi-Fi mémorisé n'existe plus (reconnexion).
        // On retente par la route par défaut du téléphone ; l'appelant reprendra le réseau courant.
        if (network == null || e.message?.contains("Binding socket", ignoreCase = true) != true) throw e
        request(path, body, null)
    }

    private fun request(path: String, body: String, net: Network?): JSONObject {
        val url = URL("http://$host$path")
        val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun record(path: String, body: String, ok: Boolean, info: String, t0: Long) {
        val e = LogEntry(LocalTime.now(), path, body, ok, info, System.currentTimeMillis() - t0)
        _log.update { (listOf(e) + it).take(200) }
    }

    companion object {
        const val MIN_GAP_MS = 500L
        /** Partagés par tous les clients du processus : l'appli et la surveillance ne parlent jamais au module en même temps. */
        private val MUTEX = Mutex()
        @Volatile private var lastStart = 0L
    }
}
