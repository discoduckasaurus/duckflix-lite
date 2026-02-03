package com.duckflix.lite.util

import android.content.Context
import android.util.Log
import java.net.NetworkInterface

object NetworkDetector {

    private const val TAG = "NetworkDetector"

    /**
     * Detects if device is on the local DuckFlix network (192.168.4.x)
     * Returns true if on local network, false if remote
     */
    fun isOnLocalNetwork(context: Context): Boolean {
        return try {
            // Get all network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // Check if it's IPv4 and on 192.168.4.x network
                    if (!address.isLoopbackAddress &&
                        address is java.net.Inet4Address) {
                        val ip = address.hostAddress ?: continue

                        // Check if on local DuckFlix network
                        if (ip.startsWith("192.168.4.")) {
                            return true
                        }
                    }
                }
            }
            false
        } catch (e: Exception) {
            // If we can't detect, assume remote
            false
        }
    }

    /**
     * Gets the appropriate API base URL based on network location
     * Both point to same dev server (3001) - local via IP, remote via domain
     */
    fun getApiBaseUrl(context: Context): String {
        val isLocal = isOnLocalNetwork(context)
        val url = if (isLocal) {
            // Local network - direct IP to dev server
            "https://192.168.4.66:3001/api/"
        } else {
            // Remote network - domain proxied to dev server
            "https://lite.duckflix.tv/api/"
        }

        Log.i(TAG, "Network detected: ${if (isLocal) "LOCAL" else "REMOTE"}")
        Log.i(TAG, "Using API URL: $url")

        return url
    }
}
