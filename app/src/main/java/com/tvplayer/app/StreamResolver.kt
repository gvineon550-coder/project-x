package com.tvplayer.app
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
object StreamResolver {
    suspend fun resolve(url: String): String = withContext(Dispatchers.IO) {
        try { Python.getInstance().getModule("resolver").callAttr("resolve", url).toString() } catch (e: Exception) { "ERROR: ${e.message}" }
    }
    suspend fun getThumbnail(url: String): String = withContext(Dispatchers.IO) {
        try { Python.getInstance().getModule("resolver").callAttr("get_thumbnail", url).toString() } catch (_: Exception) { "" }
    }
}
