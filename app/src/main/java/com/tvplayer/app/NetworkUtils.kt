package com.tvplayer.app
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
object NetworkUtils {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
