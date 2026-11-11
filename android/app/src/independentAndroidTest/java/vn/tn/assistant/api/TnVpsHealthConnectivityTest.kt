package vn.tn.assistant.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tn.assistant.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** Read-only proof that the preview build can reach the configured production health endpoint. */
class TnVpsHealthConnectivityTest {
    @Test
    fun previewBuildCanReachVpsHealth() {
        assertEquals("https://assistant.tnfastvn.com", BuildConfig.API_BASE_URL)
        val url = URL(BuildConfig.API_BASE_URL.trimEnd('/') + "/v1/health")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            assertEquals("Health request must complete without redirect", 200, connection.responseCode)
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertEquals("VPS health must report ok", "ok", JSONObject(body).optString("status"))
            assertTrue("Health response must be non-empty", body.isNotBlank())
        } finally {
            connection.disconnect()
        }
    }
}
