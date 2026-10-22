package com.example.livoxscanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketAddress

class EthernetNetworkBinder(context: Context) {
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
        connectivityManager.requestNetwork(request, networkCallback)
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
            ?: throw IllegalStateException("USB-C Ethernet network is not connected!")
        val socket = DatagramSocket(null as SocketAddress?)
        socket.reuseAddress = true
        network.bindSocket(socket)
        socket.bind(InetSocketAddress(localPort))
        return socket
    }
}
