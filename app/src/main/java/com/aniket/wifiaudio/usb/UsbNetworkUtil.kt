package com.aniket.wifiaudio.usb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object UsbNetworkUtil {
    fun activeIpv4Addresses(): List<String> = buildList {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@buildList
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) add(address.hostAddress)
            }
        }
    }

    fun defaultGateway(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.allNetworks.forEach { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@forEach
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@forEach
            val lp = cm.getLinkProperties(network) ?: return@forEach
            lp.routes.firstOrNull { it.isDefaultRoute }?.gateway?.let { gateway ->
                if (gateway is Inet4Address) return gateway.hostAddress
            }
        }
        return null
    }
}
