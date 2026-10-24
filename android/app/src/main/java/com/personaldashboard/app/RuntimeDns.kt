package com.personaldashboard.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.AtomicFile
import android.util.Log
import java.io.File

/** PRoot/glibc cannot use Android's resolver service; mirror the active network's DNS. */
class RuntimeDns(context: Context, root: File) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val resolver = File(root, "etc/resolv.conf")
    private val hosts = File(root, "etc/hosts")
    private var closed = false
    private var registered = false
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (connectivity.activeNetwork == network) update(properties)
            }
        }

    fun start() {
        if (!hosts.exists()) hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        connectivity.activeNetwork?.let { connectivity.getLinkProperties(it) }?.let(::update)
        connectivity.registerDefaultNetworkCallback(callback)
        registered = true
    }

    @Synchronized
    private fun update(properties: LinkProperties) {
        if (closed) return
        val servers = properties.dnsServers.mapNotNull { it.hostAddress }.distinct()
        if (servers.isEmpty())
            return // Offline history still works; retain the last network configuration.
        val content =
            servers.joinToString("\n") { "nameserver $it" } + "\noptions timeout:2 attempts:2\n"
        val file = AtomicFile(resolver)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(content.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (_: Exception) {
            if (stream != null) file.failWrite(stream)
            Log.w("PD-RT", "Runtime DNS update failed")
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
    }
}
