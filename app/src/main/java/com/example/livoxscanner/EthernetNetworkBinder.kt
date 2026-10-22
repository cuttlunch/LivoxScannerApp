package com.example.livoxscanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.Collections

class EthernetNetworkBinder(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _ethernetNetwork = MutableStateFlow<Network?>(null)
    val ethernetNetwork: StateFlow<Network?> = _ethernetNetwork

    private val _localEthernetIp = MutableStateFlow<String?>(null)
    val localEthernetIp: StateFlow<String?> = _localEthernetIp

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _ethernetNetwork.value = network
            updateIpAddress(network)
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            updateIpAddress(network)
        }
        override fun onLost(network: Network) {
            if (_ethernetNetwork.value == network) {
                _ethernetNetwork.value = null
                _localEthernetIp.value = null
            }
        }
    }

    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.requestNetwork(request, networkCallback)
        } catch (e: Exception) {
            Log.e("EthernetBinder", "Error requesting network: ${e.message}")
        }
    }

    fun stopMonitoring() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        _ethernetNetwork.value = null
        _localEthernetIp.value = null
    }

    private fun updateIpAddress(network: Network) {
        val linkProps = connectivityManager.getLinkProperties(network) ?: return
        val ipv4 = linkProps.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
        if (ipv4 != null) {
            _localEthernetIp.value = ipv4.hostAddress
        }
    }

    fun createBoundUdpSocket(localPort: Int): DatagramSocket {
        val network = _ethernetNetwork.value

        if (network == null) {
            Log.w("EthernetBinder", "Active network handle missing. Binding to wildcard socket.")
            val socket = DatagramSocket(null as SocketAddress?)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(localPort))
            
            // Dynamically grab the device's active local IP (e.g. Wi-Fi IP) instead of a hardcoded fake static IP
            _localEthernetIp.value = getLocalIpAddress()
            return socket
        }

        val socket = DatagramSocket(null as SocketAddress?)
        socket.reuseAddress = true
        network.bindSocket(socket)
        socket.bind(InetSocketAddress(localPort))
        return socket
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in Collections.list(ni.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("EthernetBinder", "Error fetching local IP: ${e.message}")
        }
        return null
    }
}
