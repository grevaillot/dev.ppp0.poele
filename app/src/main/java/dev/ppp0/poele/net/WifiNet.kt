package dev.ppp0.poele.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Réseau Wi-Fi du téléphone, indépendamment d'un VPN actif (SPEC §4.1 point 8). */
data class WifiInfo(
    val network: Network,
    val ip: Inet4Address,
    val prefix: Int,
    val gateway: Inet4Address?,
    /** true si le réseau par défaut du téléphone est un VPN. */
    val vpnActive: Boolean,
) {
    val broadcast: Inet4Address get() = Ipv4.broadcast(ip, prefix)
}

object WifiNet {
    fun current(context: Context): WifiInfo? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val vpn = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        @Suppress("DEPRECATION") // seul moyen de lister le Wi-Fi quand un VPN est le réseau par défaut
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: continue
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway as Inet4Address?
            return WifiInfo(n, la.address as Inet4Address, la.prefixLength, gw, vpn)
        }
        return null
    }
}

object Ipv4 {
    fun toInt(a: Inet4Address): Int = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }

    fun fromInt(v: Int): Inet4Address = Inet4Address.getByAddress(
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()),
    ) as Inet4Address

    fun mask(prefix: Int): Int = if (prefix <= 0) 0 else (-1 shl (32 - prefix))

    fun broadcast(ip: Inet4Address, prefix: Int): Inet4Address = fromInt(toInt(ip) or mask(prefix).inv())

    /** Hôtes du sous-réseau, sans l'adresse réseau ni le broadcast, limité à [max] adresses autour de [ip]. */
    fun hosts(ip: Inet4Address, prefix: Int, max: Int = 1022): List<Inet4Address> {
        val p = prefix.coerceAtLeast(32 - Integer.numberOfTrailingZeros(Integer.highestOneBit(max + 2)))
        val net = toInt(ip) and mask(p)
        val count = (1L shl (32 - p)) - 2
        return (1..count).map { fromInt(net + it.toInt()) }.filter { it != ip }
    }
}
