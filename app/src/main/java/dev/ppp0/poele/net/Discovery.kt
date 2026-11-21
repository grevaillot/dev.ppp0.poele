package dev.ppp0.poele.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Module trouvé. [id] = identifiant renvoyé par le module, ex. WINET-0A1B2C3D. */
data class Found(val ip: String, val id: String, val label: String?, val method: String)

sealed interface DiscoveryEvent {
    data class Step(val text: String) : DiscoveryEvent
    data class Success(val found: Found) : DiscoveryEvent
    data class Failure(val text: String) : DiscoveryEvent
}

/**
 * Découverte UDP du module WiNET (SPEC §4.1).
 * Protocole de l'appli officielle : "###discover" vers le port 1025, réponse
 * `WiNET <ip> "<id>" [<label>]`. On ajoute : port source éphémère, socket liée au Wi-Fi,
 * IP mémorisée, broadcast dirigé + global en 3 essais, puis balayage unicast du /24.
 */
object Discovery {
    const val PORT = 1025
    private val PROBE = "###discover".toByteArray(Charsets.US_ASCII)

    fun parseReply(data: ByteArray, length: Int = data.size): Pair<String, String?>? {
        val tokens = String(data, 0, length, Charsets.US_ASCII).trim().split(" ").filter { it.isNotEmpty() }
        if (tokens.size < 3 || tokens[0] != "WiNET") return null
        return tokens[2].replace("\"", "") to tokens.getOrNull(3)
    }

    fun run(context: Context, lastIp: String?): Flow<DiscoveryEvent> = flow {
        val wifi = WifiNet.current(context)
        if (wifi == null) {
            emit(DiscoveryEvent.Failure("Pas de réseau Wi-Fi : connecte le téléphone au Wi-Fi de la maison."))
            return@flow
        }
        emit(DiscoveryEvent.Step("Wi-Fi : ${wifi.ip.hostAddress}/${wifi.prefix}" + if (wifi.vpnActive) " · VPN actif (contourné)" else ""))

        DatagramSocket(0).use { sock ->
            wifi.network.bindSocket(sock)
            sock.broadcast = true

            suspend fun listen(ms: Long, method: String): Found? {
                val buf = ByteArray(256)
                val end = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < end) {
                    sock.soTimeout = (end - System.currentTimeMillis()).coerceIn(1, 250).toInt()
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val (id, label) = parseReply(pkt.data, pkt.length) ?: continue
                    return Found(pkt.address.hostAddress ?: continue, id, label, method)
                }
                return null
            }

            fun send(to: InetAddress) = sock.send(DatagramPacket(PROBE, PROBE.size, to, PORT))

            // 1. Dernière IP connue (unicast).
            if (lastIp != null) {
                emit(DiscoveryEvent.Step("IP mémorisée $lastIp…"))
                runCatching { send(InetAddress.getByName(lastIp)) }
                listen(1000, "IP mémorisée")?.let { emit(DiscoveryEvent.Success(it)); return@flow }
            }

            // 2. Broadcast dirigé + global, 3 essais.
            val targets = listOf(wifi.broadcast, InetAddress.getByName("255.255.255.255"))
            for (attempt in 1..3) {
                emit(DiscoveryEvent.Step("Broadcast ${wifi.broadcast.hostAddress} + 255.255.255.255, essai $attempt/3…"))
                for (t in targets) runCatching { send(t) }.onFailure { emit(DiscoveryEvent.Step("  envoi vers ${t.hostAddress} refusé : ${it.message}")) }
                listen(1500, "broadcast")?.let { emit(DiscoveryEvent.Success(it)); return@flow }
            }

            // 3. Balayage unicast du /24 (contourne le filtrage du broadcast).
            val hosts = Ipv4.hosts(wifi.ip, wifi.prefix, max = 254)
            emit(DiscoveryEvent.Step("Pas de réponse au broadcast. Balayage unicast de ${hosts.size} adresses…"))
            for ((i, h) in hosts.withIndex()) {
                runCatching { send(h) }
                if (i % 32 == 31) delay(60)
            }
            listen(2500, "balayage unicast")?.let { emit(DiscoveryEvent.Success(it)); return@flow }

            emit(
                DiscoveryEvent.Failure(
                    "Aucun module n'a répondu. Vérifie que le téléphone est sur le même Wi-Fi que le poêle, " +
                        "ou saisis l'adresse IP du module.",
                ),
            )
        }
    }.flowOn(Dispatchers.IO)
}
