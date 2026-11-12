package com.tvplayer.app

import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StreamResolver {
    suspend fun resolve(url: String): String = withContext(Dispatchers.IO) {
        try {
            val m = Python.getInstance().getModule("resolver")
            m.callAttr("resolve", url).toString()
        } catch (e: Exception) {
            "ERROR: " + (e.message ?: e.toString())
        }
    }
}
