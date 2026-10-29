package io.github.pons.linux

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.Inet4Address

/**
 * Linux's /etc/resolv.conf, written at every session start from the DNS servers of
 * Android's current network. A fixed public server can be unreachable (some carriers,
 * hotspots and VPNs drop it); glibc then waits 5 s on it before every lookup, which
 * made Steam's downloads crawl and its start take minutes.
 */
object NetworkConfig {
    private val FALLBACK = listOf("8.8.8.8", "1.1.1.1")

    fun write(context: Context, paths: LinuxPaths) {
        val resolv = File(paths.rootfs, "etc/resolv.conf")
        resolv.delete()
        resolv.writeText(contents(systemDns(context)))
        resolv.setReadable(true, false)
    }

    /** The network's own servers first, then public ones; give up on each after 1 s. */
    fun contents(system: List<String>): String =
        (system + FALLBACK).distinct().take(MAX_SERVERS).joinToString("") { "nameserver $it\n" } +
            "options timeout:1 attempts:2\n"

    private fun systemDns(context: Context): List<String> = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val links = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return emptyList()
        // IPv4 only: glibc handles at most 3 servers, and Linux here may lack IPv6 routes.
        links.dnsServers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private const val MAX_SERVERS = 3
}
