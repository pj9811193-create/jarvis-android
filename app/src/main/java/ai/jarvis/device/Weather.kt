package ai.jarvis.device

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================  WEATHER  ============================
 * Current conditions from wttr.in — a plain-text endpoint, so there is no API
 * key and no JSON parsing. Runs off the main thread.
 * =================================================================
 */
class Weather {

    suspend fun current(city: String): String = withContext(Dispatchers.IO) {
        val place = city.ifBlank { "" }
        val url = "https://wttr.in/" + Uri.encode(place) + "?format=%l:+%c+%t+(feels+%f),+humidity+%h,+wind+%w"
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "curl/8")   // wttr.in serves plain text to curl
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
            if (code !in 200..299 || text.isBlank()) {
                "I couldn't fetch the weather just now, sir."
            } else {
                "Weather for $text."
            }
        } catch (e: Exception) {
            "I couldn't reach the weather service, sir."
        }
    }
}
