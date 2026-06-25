package com.example.data

import android.util.Log
import com.example.model.*
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

// Extension helper to use Coroutines with Firebase Task API safely
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (result != null) {
            cont.resume(result)
        } else {
            cont.resumeWithException(NullPointerException("Firebase request returned null"))
        }
    }
    addOnFailureListener { exception ->
        cont.resumeWithException(exception)
    }
    addOnCanceledListener {
        cont.cancel()
    }
}

object FirebaseService {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private const val TAG = "FirebaseService"
    private const val RTDB_URL = "https://loop-7e3d9-default-rtdb.firebaseio.com"

    private suspend fun getDeletedIds(collection: String): Set<String> = withContext(Dispatchers.IO) {
        val url = "$RTDB_URL/deleted_items/$collection.json"
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val result = mutableSetOf<String>()
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (json.optBoolean(key, false)) {
                                result.add(key)
                            }
                        }
                        return@withContext result
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting deleted IDs for $collection", e)
        }
        return@withContext emptySet()
    }

    suspend fun getAppConfig(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val url = "$RTDB_URL/app_config.json"
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        return@withContext mapOf(
                            "showNewsTab" to json.optBoolean("showNewsTab", true),
                            "showChatTab" to json.optBoolean("showChatTab", true),
                            "showMainChannelsTab" to json.optBoolean("showMainChannelsTab", true)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app config from RTDB", e)
        }
        return@withContext mapOf("showNewsTab" to true, "showChatTab" to true, "showMainChannelsTab" to true)
    }

    suspend fun saveAppConfig(key: String, value: Boolean): Boolean = withContext(Dispatchers.IO) {
        val url = "$RTDB_URL/app_config.json"
        try {
            val current = getAppConfig().toMutableMap()
            current[key] = value
            val json = JSONObject().apply {
                current.forEach { (k, v) -> put(k, v) }
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)
            val request = Request.Builder().url(url).put(body).build()
            okHttpClient.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving app config to RTDB", e)
        }
        return@withContext false
    }

    suspend fun saveShareLink(shareId: String, data: Map<String, Any>): Boolean = withContext(Dispatchers.IO) {
        val url = "$RTDB_URL/secure_shares/$shareId.json"
        try {
            val json = JSONObject(data)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)
            val request = Request.Builder().url(url).put(body).build()
            okHttpClient.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving share link to RTDB", e)
        }
        return@withContext false
    }

    suspend fun getShareLink(shareId: String): Map<String, Any>? = withContext(Dispatchers.IO) {
        val url = "$RTDB_URL/secure_shares/$shareId.json"
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val result = mutableMapOf<String, Any>()
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            result[key] = json.get(key)
                        }
                        return@withContext result
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting share link from RTDB", e)
        }
        return@withContext null
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Static Memory Cache to yield ultra-rapid, instant UI retrieval
    private var lastCachedHost = ""
    private var lastCachedUsername = ""
    private var cachedCategories: List<IPTVCategory>? = null
    private var cachedChannels: List<Channel>? = null
    private var cachedMovies: List<Movie>? = null
    private var cachedSeries: List<Series>? = null

    fun clearCache() {
        lastCachedHost = ""
        lastCachedUsername = ""
        cachedCategories = null
        cachedChannels = null
        cachedMovies = null
        cachedSeries = null
    }

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            response.body?.string() ?: ""
        }
    }

    private suspend fun <T> httpGetStream(url: String, block: (java.io.Reader) -> T): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val body = response.body ?: throw IOException("Empty response body")
            body.charStream().use { reader ->
                block(reader)
            }
        }
    }

    private fun nextStringOrCoerce(reader: android.util.JsonReader): String {
        return try {
            when (reader.peek()) {
                android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> reader.nextString()
                android.util.JsonToken.BOOLEAN -> reader.nextBoolean().toString()
                android.util.JsonToken.NULL -> { reader.nextNull(); "" }
                else -> { reader.skipValue(); "" }
            }
        } catch (e: Exception) {
            try { reader.skipValue() } catch (ex: Exception) {}
            ""
        }
    }

    private fun nextDoubleOrCoerce(reader: android.util.JsonReader): Double {
        return try {
            when (reader.peek()) {
                android.util.JsonToken.NUMBER, android.util.JsonToken.STRING -> {
                    val str = reader.nextString()
                    str.toDoubleOrNull() ?: 0.0
                }
                android.util.JsonToken.BOOLEAN -> {
                    reader.skipValue()
                    0.0
                }
                android.util.JsonToken.NULL -> { reader.nextNull(); 0.0 }
                else -> { reader.skipValue(); 0.0 }
            }
        } catch (e: Exception) {
            try { reader.skipValue() } catch (ex: Exception) {}
            0.0
        }
    }

    private fun formatHostUrl(host: String): String {
        var formatted = host.trim()
        if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
            formatted = "http://$formatted"
        }
        if (formatted.endsWith("/")) {
            formatted = formatted.substring(0, formatted.length - 1)
        }
        return formatted
    }

    private suspend fun fetchActivationCodeRest(code: String): ActivationCode? {
        return withContext(Dispatchers.IO) {
            val trimmedCode = code.trim()
            val projectIds = listOf("loop-7e3d9", "loop-96ca6")
            val collections = listOf("activation_codes", "activation_code")
            val codesToTry = listOf(trimmedCode, trimmedCode.uppercase())

            for (projId in projectIds) {
                for (colName in collections) {
                    for (c in codesToTry) {
                        try {
                            val url = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/$colName/$c"
                            Log.d(TAG, "Fetching from REST URL: $url")
                            val request = Request.Builder().url(url).build()
                            okHttpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    val bodyStr = response.body?.string() ?: ""
                                    if (bodyStr.isNotEmpty()) {
                                        val json = JSONObject(bodyStr)
                                        if (json.has("fields")) {
                                            val fields = json.getJSONObject("fields")
                                            
                                            val hostObj = fields.optJSONObject("host")
                                            val host = hostObj?.optString("stringValue") ?: ""
                                            
                                            val usernameObj = fields.optJSONObject("username")
                                            val username = usernameObj?.optString("stringValue") ?: ""
                                            
                                            val passwordObj = fields.optJSONObject("password")
                                            val password = passwordObj?.optString("stringValue") ?: ""
                                            
                                            Log.d(TAG, "Successfully retrieved activation code $c from collection $colName via REST from $projId!")
                                            return@withContext ActivationCode(
                                                code = c,
                                                host = host,
                                                username = username,
                                                password = password
                                            )
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "REST call failed for code $c on $projId ($colName): HTTP ${response.code}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching code via REST for $projId / $colName", e)
                        }
                    }
                }
            }
            null
        }
    }

    /**
     * Searches for the document with ID equal to 'code' inside the collection 'activation_codes' or 'activation_code' in Firestore.
     */
    suspend fun verifyActivationCode(code: String): ActivationCode? {
        // 1. Try REST API because it works 100% of the time without SDK credentials/SHA-1 issues
        try {
            val restResult = fetchActivationCodeRest(code)
            if (restResult != null) {
                return restResult
            }
        } catch (t: Throwable) {
            Log.e(TAG, "REST verification failed, falling back to Firestore SDK", t)
        }

        // 2. SDK Fallback
        return try {
            val trimmedCode = code.trim()
            val collections = listOf("activation_codes", "activation_code")
            val codes = listOf(trimmedCode, trimmedCode.uppercase())
            
            for (colName in collections) {
                for (c in codes) {
                    val doc = db.collection(colName).document(c).get().await()
                    if (doc.exists()) {
                        val host = doc.getString("host") ?: ""
                        val username = doc.getString("username") ?: ""
                        val password = doc.getString("password") ?: ""
                        return ActivationCode(code = doc.id, host = host, username = username, password = password)
                    }
                }
            }
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Error seeking activation code via SDK: $code", e)
            null
        }
    }

    suspend fun fetchXtreamCategories(host: String, username: String, password: String): List<IPTVCategory> {
        if (host == lastCachedHost && username == lastCachedUsername && cachedCategories != null) {
            Log.d(TAG, "Returning cached categories instantly")
            return cachedCategories!!
        }
        return try {
            val formattedHost = formatHostUrl(host)
            
            // 1. Fetch live categories
            val liveUrl = "$formattedHost/player_api.php?username=$username&password=$password&action=get_live_categories"
            val liveJson = httpGet(liveUrl)
            val liveCats = parseCategoriesJson(liveJson, "channel")

            // 2. Fetch VOD categories
            val vodUrl = "$formattedHost/player_api.php?username=$username&password=$password&action=get_vod_categories"
            val vodJson = httpGet(vodUrl)
            val vodCats = parseCategoriesJson(vodJson, "movie")

            // 3. Fetch Series categories
            val seriesUrl = "$formattedHost/player_api.php?username=$username&password=$password&action=get_series_categories"
            val seriesJson = httpGet(seriesUrl)
            val seriesCats = parseCategoriesJson(seriesJson, "series")

            val combined = liveCats + vodCats + seriesCats
            val result = if (combined.isEmpty()) {
                // Fallback categories if server returns nothing
                listOf(
                    IPTVCategory("all", "الكل", "channel"),
                    IPTVCategory("sports", "الرياضة Live", "channel"),
                    IPTVCategory("movies", "أحدث الأفلام VOD", "movie"),
                    IPTVCategory("series", "المسلسلات الحصرية", "series")
                )
            } else {
                combined
            }
            lastCachedHost = host
            lastCachedUsername = username
            cachedCategories = result
            result
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching Xtream categories", e)
            // Return defaults if API calls fail
            val fallback = listOf(
                IPTVCategory("all", "الكل", "channel"),
                IPTVCategory("sports", "الرياضة Live", "channel"),
                IPTVCategory("movies", "أحدث الأفلام VOD", "movie"),
                IPTVCategory("series", "المسلسلات الحصرية", "series")
            )
            fallback
        }
    }

    private fun parseCategoriesJson(jsonStr: String, type: String): List<IPTVCategory> {
        val list = mutableListOf<IPTVCategory>()
        if (jsonStr.isEmpty() || !jsonStr.trim().startsWith("[")) return list
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("category_id")
                val name = obj.optString("category_name")
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    list.add(IPTVCategory(id = id, name = name, type = type))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing categories json", e)
        }
        return list
    }

    suspend fun fetchXtreamChannels(host: String, username: String, password: String): List<Channel> {
        if (host == lastCachedHost && username == lastCachedUsername && cachedChannels != null) {
            Log.d(TAG, "Returning cached channels instantly")
            return cachedChannels!!
        }
        return try {
            val formattedHost = formatHostUrl(host)
            val url = "$formattedHost/player_api.php?username=$username&password=$password&action=get_live_streams"
            val list = httpGetStream(url) { reader ->
                val resultList = mutableListOf<Channel>()
                val jsonReader = android.util.JsonReader(reader)
                jsonReader.isLenient = true
                try {
                    if (jsonReader.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                        jsonReader.beginArray()
                        while (jsonReader.hasNext()) {
                            if (jsonReader.peek() == android.util.JsonToken.BEGIN_OBJECT) {
                                jsonReader.beginObject()
                                var streamId = ""
                                var name = ""
                                var logoUrl = ""
                                var categoryId = ""
                                while (jsonReader.hasNext()) {
                                    val key = jsonReader.nextName()
                                    if (jsonReader.peek() == android.util.JsonToken.NULL) {
                                        jsonReader.skipValue()
                                        continue
                                    }
                                    when (key) {
                                        "stream_id" -> streamId = nextStringOrCoerce(jsonReader)
                                        "name" -> name = nextStringOrCoerce(jsonReader)
                                        "stream_icon" -> logoUrl = nextStringOrCoerce(jsonReader)
                                        "category_id" -> categoryId = nextStringOrCoerce(jsonReader)
                                        else -> jsonReader.skipValue()
                                    }
                                }
                                jsonReader.endObject()
                                if (streamId.isNotEmpty()) {
                                    val streamUrl = "$formattedHost/live/$username/$password/$streamId.ts"
                                    resultList.add(
                                        Channel(
                                            id = streamId,
                                            name = name,
                                            logoUrl = logoUrl,
                                            streamUrl = streamUrl,
                                            category = categoryId
                                        )
                                    )
                                }
                            } else {
                                jsonReader.skipValue()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming channels parse error", e)
                } finally {
                    try { jsonReader.close() } catch (ignored: Exception) {}
                }
                resultList
            }
            cachedChannels = list
            list
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching channels from Xtream", e)
            emptyList()
        }
    }

    suspend fun fetchXtreamMovies(host: String, username: String, password: String): List<Movie> {
        if (host == lastCachedHost && username == lastCachedUsername && cachedMovies != null) {
            Log.d(TAG, "Returning cached movies instantly")
            return cachedMovies!!
        }
        return try {
            val formattedHost = formatHostUrl(host)
            val url = "$formattedHost/player_api.php?username=$username&password=$password&action=get_vod_streams"
            val list = httpGetStream(url) { reader ->
                val resultList = mutableListOf<Movie>()
                val jsonReader = android.util.JsonReader(reader)
                jsonReader.isLenient = true
                try {
                    if (jsonReader.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                        jsonReader.beginArray()
                        while (jsonReader.hasNext()) {
                            if (jsonReader.peek() == android.util.JsonToken.BEGIN_OBJECT) {
                                jsonReader.beginObject()
                                var streamId = ""
                                var title = ""
                                var posterUrl = ""
                                var categoryId = ""
                                var rating = 0.0
                                var ext = "mp4"
                                while (jsonReader.hasNext()) {
                                    val key = jsonReader.nextName()
                                    if (jsonReader.peek() == android.util.JsonToken.NULL) {
                                        jsonReader.skipValue()
                                        continue
                                    }
                                    when (key) {
                                        "stream_id" -> streamId = nextStringOrCoerce(jsonReader)
                                        "name" -> title = nextStringOrCoerce(jsonReader)
                                        "stream_icon" -> posterUrl = nextStringOrCoerce(jsonReader)
                                        "category_id" -> categoryId = nextStringOrCoerce(jsonReader)
                                        "rating" -> rating = nextDoubleOrCoerce(jsonReader)
                                        "container_extension" -> ext = nextStringOrCoerce(jsonReader).ifEmpty { "mp4" }
                                        else -> jsonReader.skipValue()
                                    }
                                }
                                jsonReader.endObject()
                                if (streamId.isNotEmpty()) {
                                    val streamUrl = "$formattedHost/movie/$username/$password/$streamId.$ext"
                                    resultList.add(
                                        Movie(
                                            id = streamId,
                                            title = title,
                                            description = "محتوى مميز تم جلبه عبر السيرفر الخاص بك بجودة عالية.",
                                            posterUrl = posterUrl,
                                            streamUrl = streamUrl,
                                            category = categoryId,
                                            duration = "02:00:00",
                                            rating = rating,
                                            year = 2026
                                        )
                                    )
                                }
                            } else {
                                jsonReader.skipValue()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming movies parse error", e)
                } finally {
                    try { jsonReader.close() } catch (ignored: Exception) {}
                }
                resultList
            }
            cachedMovies = list
            list
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching movies", e)
            emptyList()
        }
    }

    suspend fun fetchXtreamSeries(host: String, username: String, password: String): List<Series> {
        if (host == lastCachedHost && username == lastCachedUsername && cachedSeries != null) {
            Log.d(TAG, "Returning cached series instantly")
            return cachedSeries!!
        }
        return try {
            val formattedHost = formatHostUrl(host)
            val url = "$formattedHost/player_api.php?username=$username&password=$password&action=get_series"
            val list = httpGetStream(url) { reader ->
                val resultList = mutableListOf<Series>()
                val jsonReader = android.util.JsonReader(reader)
                jsonReader.isLenient = true
                try {
                    if (jsonReader.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                        jsonReader.beginArray()
                        while (jsonReader.hasNext()) {
                            if (jsonReader.peek() == android.util.JsonToken.BEGIN_OBJECT) {
                                jsonReader.beginObject()
                                var seriesId = ""
                                var title = ""
                                var posterUrl = ""
                                var categoryId = ""
                                var rating = 0.0
                                var desc = "مسلسل درامي مميز متاح للمشاهدة المباشرة."
                                while (jsonReader.hasNext()) {
                                    val key = jsonReader.nextName()
                                    if (jsonReader.peek() == android.util.JsonToken.NULL) {
                                        jsonReader.skipValue()
                                        continue
                                    }
                                    when (key) {
                                        "series_id" -> seriesId = nextStringOrCoerce(jsonReader)
                                        "name" -> title = nextStringOrCoerce(jsonReader)
                                        "cover" -> posterUrl = nextStringOrCoerce(jsonReader)
                                        "category_id" -> categoryId = nextStringOrCoerce(jsonReader)
                                        "rating" -> rating = nextDoubleOrCoerce(jsonReader)
                                        "plot" -> desc = nextStringOrCoerce(jsonReader).ifEmpty { "مسلسل درامي مميز متاح للمشاهدة المباشرة." }
                                        else -> jsonReader.skipValue()
                                    }
                                }
                                jsonReader.endObject()
                                if (seriesId.isNotEmpty()) {
                                    resultList.add(
                                        Series(
                                            id = seriesId,
                                            title = title,
                                            description = desc,
                                            posterUrl = posterUrl,
                                            category = categoryId,
                                            rating = rating,
                                            year = 2026
                                        )
                                    )
                                }
                            } else {
                                jsonReader.skipValue()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming series parse error", e)
                } finally {
                    try { jsonReader.close() } catch (ignored: Exception) {}
                }
                resultList
            }
            cachedSeries = list
            list
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching series", e)
            emptyList()
        }
    }

    suspend fun fetchXtreamSeasons(host: String, username: String, password: String, seriesId: String): List<Season> {
        return try {
            val formattedHost = formatHostUrl(host)
            val url = "$formattedHost/player_api.php?username=$username&password=$password&action=get_series_info&series_id=$seriesId"
            val jsonStr = httpGet(url)
            val list = mutableListOf<Season>()
            if (jsonStr.isNotEmpty() && jsonStr.trim().startsWith("{")) {
                val rootObj = JSONObject(jsonStr)
                if (rootObj.has("seasons")) {
                    val arr = rootObj.getJSONArray("seasons")
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val seasonNum = obj.optInt("season_number", i + 1)
                        val title = obj.optString("name", "الموسم $seasonNum")
                        list.add(
                            Season(
                                id = seasonNum.toString(),
                                seriesId = seriesId,
                                number = seasonNum,
                                title = title
                            )
                        )
                    }
                }
            }
            if (list.isEmpty()) {
                list.add(Season(id = "1", seriesId = seriesId, number = 1, title = "الموسم الأول"))
            }
            list.sortedBy { it.number }
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching seasons for $seriesId", e)
            listOf(Season(id = "1", seriesId = seriesId, number = 1, title = "الموسم الأول"))
        }
    }

    suspend fun fetchXtreamEpisodes(host: String, username: String, password: String, seriesId: String, seasonId: String): List<Episode> {
        return try {
            val formattedHost = formatHostUrl(host)
            val url = "$formattedHost/player_api.php?username=$username&password=$password&action=get_series_info&series_id=$seriesId"
            val jsonStr = httpGet(url)
            val list = mutableListOf<Episode>()
            if (jsonStr.isNotEmpty() && jsonStr.trim().startsWith("{")) {
                val rootObj = JSONObject(jsonStr)
                if (rootObj.has("episodes")) {
                    val epsObj = rootObj.getJSONObject("episodes")
                    if (epsObj.has(seasonId)) {
                        val arr = epsObj.getJSONArray(seasonId)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val id = obj.optString("id")
                            val num = obj.optInt("episode_num", i + 1)
                            val title = obj.optString("title", "الحلقة $num")
                            val ext = obj.optString("container_extension", "mp4")
                            
                            var duration = "00:45:00"
                            var desc = "تفاصيل الحلقة الحصرية المتاحة للمشاهدة الفورية."
                            if (obj.has("info")) {
                                val info = obj.getJSONObject("info")
                                duration = info.optString("duration", duration)
                                desc = info.optString("plot", desc)
                            }
                            
                            val streamUrl = "$formattedHost/series/$username/$password/$id.$ext"
                            
                            list.add(
                                Episode(
                                    id = id,
                                    seasonId = seasonId,
                                    seriesId = seriesId,
                                    title = title,
                                    description = desc,
                                    posterUrl = "",
                                    streamUrl = streamUrl,
                                    duration = duration,
                                    number = num
                                )
                            )
                        }
                    }
                }
            }
            list.sortedBy { it.number }
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching episodes", e)
            emptyList()
        }
    }

    /**
     * Verifies if an access code exists and is active.
     */
    suspend fun verifyAccessCode(code: String): AccessCode? {
        val trimmedCode = code.trim().uppercase()
        // Reliable offline & local-override bypass for codes A1 through A9
        if (trimmedCode.matches(Regex("^A[1-9]$"))) {
            Log.d(TAG, "Local override activated for code: $trimmedCode")
            return AccessCode(code = trimmedCode, isActive = true, accountId = "premium_account_a1")
        }
        return try {
            val doc = db.collection("access_codes").document(trimmedCode).get().await()
            if (doc.exists()) {
                val isActive = doc.getBoolean("isActive") ?: true
                val accountId = doc.getString("accountId") ?: ""
                AccessCode(code = trimmedCode, isActive = isActive, accountId = accountId)
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error verifying access code: $code, using fallback checks", e)
            // Even under Firestore offline/network error states, A1..A9 should strictly execute success!
            if (trimmedCode.matches(Regex("^A[1-9]$"))) {
                AccessCode(code = trimmedCode, isActive = true, accountId = "premium_account_a1")
            } else {
                null
            }
        }
    }

    /**
     * Retrieves the IPTV account metadata for a specific account.
     */
    suspend fun getAccount(accountId: String): IPTVAccount? {
        return try {
            val doc = db.collection("accounts").document(accountId).get().await()
            if (doc.exists()) {
                val name = doc.getString("name") ?: "Premium Account"
                val allowedCategories = doc.get("allowedCategories") as? List<String> ?: emptyList()
                val expiryDate = doc.getString("expiryDate") ?: "2027-12-31"
                IPTVAccount(id = accountId, name = name, allowedCategories = allowedCategories, expiryDate = expiryDate)
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching account details: $accountId", e)
            null
        }
    }

    /**
     * Fetches channels belonging to categories approved for the active user.
     */
    suspend fun getChannels(allowedCategories: List<String>): List<Channel> = withContext(Dispatchers.IO) {
        val firestoreList = try {
            val snapshot = db.collection("channels").get().await()
            snapshot.documents.map { doc ->
                Channel(
                    id = doc.id,
                    name = doc.getString("name") ?: "",
                    logoUrl = doc.getString("logoUrl") ?: "",
                    streamUrl = doc.getString("streamUrl") ?: "",
                    category = doc.getString("category") ?: "",
                    userAgent = doc.getString("userAgent") ?: ""
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching channels from firestore", e)
            emptyList()
        }

        val deletedIds = getDeletedIds("channels")

        val rtdbList = mutableListOf<Channel>()
        val url = "$RTDB_URL/channels.json"
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val obj = json.optJSONObject(key)
                            if (obj != null) {
                                rtdbList.add(
                                    Channel(
                                        id = obj.optString("id", key),
                                        name = obj.optString("name", ""),
                                        logoUrl = obj.optString("logoUrl", ""),
                                        streamUrl = obj.optString("streamUrl", ""),
                                        category = obj.optString("category", ""),
                                        userAgent = obj.optString("userAgent", "")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching channels from RTDB", e)
        }

        val combined = (firestoreList.filterNot { deletedIds.contains(it.id) } + rtdbList)
            .distinctBy { it.id }
            .filter { allowedCategories.isEmpty() || allowedCategories.contains(it.category) }
        return@withContext combined
    }

    /**
     * Fetches movies for categories.
     */
    suspend fun getMovies(allowedCategories: List<String>): List<Movie> = withContext(Dispatchers.IO) {
        val firestoreList = try {
            val snapshot = db.collection("movies").get().await()
            snapshot.documents.map { doc ->
                Movie(
                    id = doc.id,
                    title = doc.getString("title") ?: "",
                    description = doc.getString("description") ?: "",
                    posterUrl = doc.getString("posterUrl") ?: "",
                    streamUrl = doc.getString("streamUrl") ?: "",
                    category = doc.getString("category") ?: "",
                    duration = doc.getString("duration") ?: "0",
                    rating = doc.getDouble("rating") ?: 0.0,
                    year = doc.getLong("year")?.toInt() ?: 2026
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching movies from firestore", e)
            emptyList()
        }

        val deletedIds = getDeletedIds("movies")

        val rtdbList = mutableListOf<Movie>()
        val url = "$RTDB_URL/movies.json"
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val obj = json.optJSONObject(key)
                            if (obj != null) {
                                rtdbList.add(
                                    Movie(
                                        id = obj.optString("id", key),
                                        title = obj.optString("title", ""),
                                        description = obj.optString("description", ""),
                                        posterUrl = obj.optString("posterUrl", ""),
                                        streamUrl = obj.optString("streamUrl", ""),
                                        category = obj.optString("category", ""),
                                        duration = obj.optString("duration", "0"),
                                        rating = obj.optDouble("rating", 0.0),
                                        year = obj.optInt("year", 2026)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching movies from RTDB", e)
        }

        val combined = (firestoreList.filterNot { deletedIds.contains(it.id) } + rtdbList)
            .distinctBy { it.id }
            .filter { allowedCategories.isEmpty() || allowedCategories.contains(it.category) }
        return@withContext combined
    }

    /**
     * Fetches series matching authorized categories.
     */
    suspend fun getSeries(allowedCategories: List<String>): List<Series> = withContext(Dispatchers.IO) {
        val firestoreList = try {
            val snapshot = db.collection("series").get().await()
            snapshot.documents.map { doc ->
                Series(
                    id = doc.id,
                    title = doc.getString("title") ?: "",
                    description = doc.getString("description") ?: "",
                    posterUrl = doc.getString("posterUrl") ?: "",
                    category = doc.getString("category") ?: "",
                    rating = doc.getDouble("rating") ?: 0.0,
                    year = doc.getLong("year")?.toInt() ?: 2026
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching series from firestore", e)
            emptyList()
        }

        val deletedIds = getDeletedIds("series")

        val rtdbList = mutableListOf<Series>()
        val url = "$RTDB_URL/series.json"
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val obj = json.optJSONObject(key)
                            if (obj != null) {
                                rtdbList.add(
                                    Series(
                                        id = obj.optString("id", key),
                                        title = obj.optString("title", ""),
                                        description = obj.optString("description", ""),
                                        posterUrl = obj.optString("posterUrl", ""),
                                        category = obj.optString("category", ""),
                                        rating = obj.optDouble("rating", 0.0),
                                        year = obj.optInt("year", 2026)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching series from RTDB", e)
        }

        val combined = (firestoreList.filterNot { deletedIds.contains(it.id) } + rtdbList)
            .distinctBy { it.id }
            .filter { allowedCategories.isEmpty() || allowedCategories.contains(it.category) }
        return@withContext combined
    }

    /**
     * Fetches seasons of a specific Series.
     */
    suspend fun getSeasons(seriesId: String): List<Season> {
        return try {
            val snapshot = db.collection("seasons")
                .whereEqualTo("seriesId", seriesId)
                .get()
                .await()
            snapshot.documents.map { doc ->
                Season(
                    id = doc.id,
                    seriesId = doc.getString("seriesId") ?: "",
                    number = doc.getLong("number")?.toInt() ?: 1,
                    title = doc.getString("title") ?: ""
                )
            }.sortedBy { it.number }
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching seasons for series: $seriesId", e)
            emptyList()
        }
    }

    /**
     * Fetches episodes of a specific season.
     */
    suspend fun getEpisodes(seriesId: String, seasonId: String): List<Episode> {
        return try {
            val snapshot = db.collection("episodes")
                .whereEqualTo("seriesId", seriesId)
                .whereEqualTo("seasonId", seasonId)
                .get()
                .await()
            snapshot.documents.map { doc ->
                Episode(
                    id = doc.id,
                    seasonId = doc.getString("seasonId") ?: "",
                    seriesId = doc.getString("seriesId") ?: "",
                    title = doc.getString("title") ?: "",
                    description = doc.getString("description") ?: "",
                    posterUrl = doc.getString("posterUrl") ?: "",
                    streamUrl = doc.getString("streamUrl") ?: "",
                    duration = doc.getString("duration") ?: "",
                    number = doc.getLong("number")?.toInt() ?: 1
                )
            }.sortedBy { it.number }
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching episodes", e)
            emptyList()
        }
    }

    /**
     * Fetches categories.
     */
    suspend fun getCategories(): List<IPTVCategory> = withContext(Dispatchers.IO) {
        val firestoreList = try {
            val snapshot = db.collection("categories").get().await()
            snapshot.documents.map { doc ->
                IPTVCategory(
                    id = doc.id,
                    name = doc.getString("name") ?: "",
                    type = doc.getString("type") ?: ""
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching categories from firestore", e)
            emptyList()
        }

        val deletedIds = getDeletedIds("categories")

        val rtdbList = mutableListOf<IPTVCategory>()
        val url = "$RTDB_URL/categories.json"
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val obj = json.optJSONObject(key)
                            if (obj != null) {
                                rtdbList.add(
                                    IPTVCategory(
                                        id = obj.optString("id", key),
                                        name = obj.optString("name", ""),
                                        type = obj.optString("type", "")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching categories from RTDB", e)
        }

        val combined = (firestoreList.filterNot { deletedIds.contains(it.id) } + rtdbList)
            .distinctBy { it.id }
        return@withContext combined
    }

    /**
     * ADMIN TASKS: Adds/Updates an access code directly in Firestore.
     */
    suspend fun adminSaveAccessCode(code: AccessCode): Boolean = withContext(Dispatchers.IO) {
        val upperCode = code.code.trim().uppercase()
        val url = "$RTDB_URL/access_codes/$upperCode.json"
        val rtdbSuccess = try {
            val json = JSONObject().apply {
                put("code", upperCode)
                put("isActive", code.isActive)
                put("accountId", code.accountId)
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)
            val request = Request.Builder().url(url).put(body).build()
            okHttpClient.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving access code to RTDB", e)
            false
        }

        try {
            db.collection("access_codes").document(upperCode).set(
                mapOf(
                    "code" to upperCode,
                    "isActive" to code.isActive,
                    "accountId" to code.accountId
                )
            ).await()
        } catch (e: Throwable) {
            Log.e(TAG, "Admin operation Firestore write failed: save access code", e)
        }
        return@withContext rtdbSuccess
    }

    /**
     * ADMIN TASKS: Deletes an access code.
     */
    suspend fun adminDeleteAccessCode(code: String): Boolean = withContext(Dispatchers.IO) {
        val upperCode = code.trim().uppercase()
        val url = "$RTDB_URL/access_codes/$upperCode.json"
        val rtdbSuccess = try {
            val request = Request.Builder().url(url).delete().build()
            okHttpClient.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting access code from RTDB", e)
            false
        }

        try {
            db.collection("access_codes").document(upperCode).delete().await()
        } catch (e: Throwable) {
            Log.e(TAG, "Admin operation Firestore write failed: delete access code", e)
        }
        return@withContext rtdbSuccess
    }

    /**
     * ADMIN TASKS: Create or update a movie.
     */
    suspend fun adminSaveMovie(movie: Movie): Boolean = withContext(Dispatchers.IO) {
        val movieId = movie.id.ifEmpty { "movie_" + System.currentTimeMillis() }
        val finalMovie = movie.copy(id = movieId)
        val url = "$RTDB_URL/movies/$movieId.json"
        val rtdbSuccess = try {
            val json = JSONObject().apply {
                put("id", finalMovie.id)
                put("title", finalMovie.title)
                put("description", finalMovie.description)
                put("posterUrl", finalMovie.posterUrl)
                put("streamUrl", finalMovie.streamUrl)
                put("category", finalMovie.category)
                put("duration", finalMovie.duration)
                put("rating", finalMovie.rating)
                put("year", finalMovie.year)
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)
            val request = Request.Builder().url(url).put(body).build()
            okHttpClient.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving movie to RTDB", e)
            false
        }

        try {
            db.collection("movies").document(movieId).set(finalMovie).await()
        } catch (e: Throwable) {
            Log.e(TAG, "Admin save movie to firestore failed", e)
        }
        return@withContext rtdbSuccess
    }

    /**
     * ADMIN TASKS: Create or update a channel.
     */
    suspend fun adminSaveChannel(channel: Channel): Boolean = withContext(Dispatchers.IO) {
        val chanId = channel.id.ifEmpty { "chan_" + System.currentTimeMillis() }
        val finalChan = channel.copy(id = chanId)
        val url = "$RTDB_URL/channels/$chanId.json"
        val rtdbSuccess = try {
            val json = JSONObject().apply {
                put("id", finalChan.id)
                put("name", finalChan.name)
                put("logoUrl", finalChan.logoUrl)
                put("streamUrl", finalChan.streamUrl)
                put("category", finalChan.category)
                put("userAgent", finalChan.userAgent)
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)
            val request = Request.Builder().url(url).put(body).build()
            okHttpClient.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving channel to RTDB", e)
            false
        }

        try {
            db.collection("channels").document(chanId).set(finalChan).await()
        } catch (e: Throwable) {
            Log.e(TAG, "Admin save channel to firestore failed", e)
        }
        return@withContext rtdbSuccess
    }

    /**
     * ADMIN TASKS: Create or update a main channel.
     */
    suspend fun adminSaveMainChannel(channel: Channel): Boolean = withContext(Dispatchers.IO) {
        val chanId = channel.id.ifEmpty { "main_chan_" + System.currentTimeMillis() }
        val finalChan = channel.copy(id = chanId)
        val url = "$RTDB_URL/main_channels/$chanId.json"
        val rtdbSuccess = try {
            val json = JSONObject().apply {
                put("id", finalChan.id)
                put("name", finalChan.name)
                put("logoUrl", finalChan.logoUrl)
                put("streamUrl", finalChan.streamUrl)
                put("category", finalChan.category)
                put("userAgent", finalChan.userAgent)
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)
            val request = Request.Builder().url(url).put(body).build()
            okHttpClient.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving main channel to RTDB", e)
            false
        }

        try {
            db.collection("main_channels").document(chanId).set(finalChan).await()
        } catch (e: Throwable) {
            Log.e(TAG, "Admin save main channel to firestore failed", e)
        }
        return@withContext rtdbSuccess
    }

    /**
     * ADMIN TASKS: Create or update a Category.
     */
    suspend fun adminSaveCategory(category: IPTVCategory): Boolean = withContext(Dispatchers.IO) {
        val catId = category.id.ifEmpty { "cat_" + System.currentTimeMillis() }
        val finalCategory = category.copy(id = catId)
        val url = "$RTDB_URL/categories/$catId.json"
        val rtdbSuccess = try {
            val json = JSONObject().apply {
                put("id", finalCategory.id)
                put("name", finalCategory.name)
                put("type", finalCategory.type)
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)
            val request = Request.Builder().url(url).put(body).build()
            okHttpClient.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving category to RTDB", e)
            false
        }

        try {
            db.collection("categories").document(catId).set(finalCategory).await()
        } catch (e: Throwable) {
            Log.e(TAG, "Admin save category to firestore failed", e)
        }
        return@withContext rtdbSuccess
    }

    /**
     * ADMIN TASKS: Create or update a series.
     */
    suspend fun adminSaveSeries(series: Series): Boolean = withContext(Dispatchers.IO) {
        val seriesId = series.id.ifEmpty { "series_" + System.currentTimeMillis() }
        val finalSeries = series.copy(id = seriesId)
        val url = "$RTDB_URL/series/$seriesId.json"
        val rtdbSuccess = try {
            val json = JSONObject().apply {
                put("id", finalSeries.id)
                put("title", finalSeries.title)
                put("description", finalSeries.description)
                put("posterUrl", finalSeries.posterUrl)
                put("category", finalSeries.category)
                put("rating", finalSeries.rating)
                put("year", finalSeries.year)
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)
            val request = Request.Builder().url(url).put(body).build()
            okHttpClient.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving series to RTDB", e)
            false
        }

        try {
            db.collection("series").document(seriesId).set(finalSeries).await()
        } catch (e: Throwable) {
            Log.e(TAG, "Admin save series to firestore failed", e)
        }
        return@withContext rtdbSuccess
    }

    /**
     * ADMIN TASKS: Delete content by collection and ID.
     */
    suspend fun adminDeleteContent(collection: String, docId: String): Boolean = withContext(Dispatchers.IO) {
        val rtdbCollectionName = if (collection == "categories") "categories"
                                 else if (collection == "channels") "channels"
                                 else if (collection == "main_channels") "main_channels"
                                 else if (collection == "movies") "movies"
                                 else if (collection == "series") "series"
                                 else collection

        val url = "$RTDB_URL/$rtdbCollectionName/$docId.json"
        val deleteMarkerUrl = "$RTDB_URL/deleted_items/$rtdbCollectionName/$docId.json"
        val rtdbSuccess = try {
            val request = Request.Builder().url(url).delete().build()
            okHttpClient.newCall(request).execute().use {}
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = "true".toRequestBody(mediaType)
            val markerRequest = Request.Builder().url(deleteMarkerUrl).put(body).build()
            okHttpClient.newCall(markerRequest).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting content $collection / $docId in RTDB", e)
            false
        }

        try {
            db.collection(collection).document(docId).delete().await()
        } catch (e: Throwable) {
            Log.e(TAG, "Admin delete content from firestore failed for $collection / $docId", e)
        }
        return@withContext rtdbSuccess
    }

    /**
     * SEEDS THE DATABASE ON STARTUP IF EMPTY
     */
    suspend fun seedDatabaseIfEmpty() {
        try {
            val doc = db.collection("access_codes").document("A1").get().await()
            if (doc.exists()) {
                Log.d(TAG, "Database already seeded!")
                try {
                    val mainChans = db.collection("main_channels").limit(1).get().await()
                    if (mainChans.isEmpty) {
                        seedMainChannelsOnly()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking main_channels in existing database", e)
                }
                return
            }

            Log.d(TAG, "Seeding Firestore with premium IPTV mockup parameters...")

            // 1. Seed Access Codes A1 to A9 (Default active codes)
            for (i in 1..9) {
                db.collection("access_codes").document("A$i").set(
                    mapOf("code" to "A$i", "isActive" to true, "accountId" to "premium_account_a1")
                ).await()
            }

            // 2. Seed IPTV Account info
            db.collection("accounts").document("premium_account_a1").set(
                mapOf(
                    "id" to "premium_account_a1",
                    "name" to "VIP Premium User (Loop Live)",
                    "allowedCategories" to listOf("sports", "action", "news", "scientific", "entertainment"),
                    "expiryDate" to "2028-12-31"
                )
            ).await()

            // 3. Seed Categories
            val categories = listOf(
                IPTVCategory("sports", "القنوات الرياضية", "channel"),
                IPTVCategory("news", "قنوات الأخبار بث مباشر", "channel"),
                IPTVCategory("action", "أفلام الأكشن والإثارة", "movie"),
                IPTVCategory("scientific", "أفلام وثائقية وعلمية", "movie"),
                IPTVCategory("entertainment", "مسلسلات الدراما الحصرية", "series")
            )
            for (cat in categories) {
                db.collection("categories").document(cat.id).set(cat).await()
            }

            // 4. Seed Live Channels
            // Standard live streams (M3U8)
            val channels = listOf(
                Channel(
                    id = "chan_bein_1",
                    name = "beIN SPORTS Premium HD",
                    logoUrl = "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=150&q=80",
                    streamUrl = "http://sample.vodobox.com/planete_hd_short_clip/planete_hd_short_clip.m3u8", // Working Test HLS stream
                    category = "sports"
                ),
                Channel(
                    id = "chan_aljazeera",
                    name = "الجزيرة الإخبارية مباشر HD",
                    logoUrl = "https://images.unsplash.com/photo-1585829365294-bb7c6384ef81?w=150&q=80",
                    streamUrl = "https://live-aljazeera.akamaized.net/hls/live/2003940/aljazeera/index.m3u8", // Live Arabic Al Jazeera URL
                    category = "news"
                ),
                Channel(
                    id = "chan_alarabiya",
                    name = "العربية مباشر HD",
                    logoUrl = "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=150&q=80",
                    streamUrl = "https://alarabiya-live.akamaized.net/hls/live/2004419/alarabiya/index.m3u8", // Live Arabic Al Arabiya URL
                    category = "news"
                )
            )
            for (chan in channels) {
                db.collection("channels").document(chan.id).set(chan).await()
            }

            // 5. Seed Movies (using real trailer clips/working HLS files)
            val movies = listOf(
                Movie(
                    id = "movie_sintel",
                    title = "فيلم الخيال العلمي: سينتيل (Sintel)",
                    description = "فيلم رسومي فاخر وجميل بتقنية 4K يحكي قصة فتاة تبحث عن تنينها الصغير الضائع في رحلة ملحمية مذهلة.",
                    posterUrl = "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=400&q=80",
                    streamUrl = "https://cph-pms.dplay.com/sintel/global/sintel.m3u8", // Working HLS movie stream
                    category = "scientific",
                    duration = "00:15:00",
                    rating = 8.9,
                    year = 2025
                ),
                Movie(
                    id = "movie_bipbop",
                    title = "وثائقي التقنية والشبكات: بيب بوب (BipBop)",
                    description = "عرض واقعي وتجريبي لأنظمة البث المتقدمة في السيرفرات العالمية بطريقة تقنية مثيرة للغاية.",
                    posterUrl = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=400&q=80",
                    streamUrl = "https://playertest.longtailvideo.com/adaptive/bipbop/bipbop.m3u8", // Working complex HLS streams
                    category = "scientific",
                    duration = "00:30:15",
                    rating = 7.4,
                    year = 2026
                ),
                Movie(
                    id = "movie_action_matrix",
                    title = "ماتريكس ريفولوشن: البداية (The Matrix)",
                    description = "فيلم أكشن ملحمي يروي قصة البطل نيو في تحرير عقول البشرية من الآلات المستعبدة بلغة بصرية فريدة.",
                    posterUrl = "https://images.unsplash.com/photo-1478760329108-5c3ed9d495a0?w=400&q=80",
                    streamUrl = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8", // Real high-quality HLS streaming action movie
                    category = "action",
                    duration = "02:16:30",
                    rating = 9.2,
                    year = 2024
                )
            )
            for (m in movies) {
                db.collection("movies").document(m.id).set(m).await()
            }

            // 6. Seed Series
            val series = listOf(
                Series(
                    id = "series_thrones",
                    title = "مسلسل الممالك السبع الحصري",
                    description = "صراع عروش ومكائد دموية لا تنتهي من أجل الهيمنة وتتويج الملك على مقاعد الحديد المفقودة.",
                    posterUrl = "https://images.unsplash.com/photo-1438761681033-6461ffad8d80?w=400&q=80",
                    category = "entertainment",
                    rating = 9.5,
                    year = 2025
                )
            )
            for (s in series) {
                db.collection("series").document(s.id).set(s).await()
            }

            // 7. Seed Seasons
            db.collection("seasons").document("season_thrones_1").set(
                Season(id = "season_thrones_1", seriesId = "series_thrones", number = 1, title = "الموسم الأول")
            ).await()

            // 8. Seed Episodes
            val episodes = listOf(
                Episode(
                    id = "ep_1",
                    seasonId = "season_thrones_1",
                    seriesId = "series_thrones",
                    title = "الحلقة 1: الشتاء قادم",
                    description = "بداية ملحمية من أرض الشمال حيث يتوجب على لورد وينترفل الاستعداد لخطر يهدد المملكة برمتها.",
                    posterUrl = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=350&q=80",
                    streamUrl = "https://cph-pms.dplay.com/sintel/global/sintel.m3u8", // Working play link
                    duration = "14 دقيقة",
                    number = 1
                ),
                Episode(
                    id = "ep_2",
                    seasonId = "season_thrones_1",
                    seriesId = "series_thrones",
                    title = "الحلقة 2: طريق الخلافة المستعر",
                    description = "تزداد المؤامرات في بلاط الملك ويتعين على لورد ند ستارك مواجهة خصوم غامضين وشرسين.",
                    posterUrl = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=350&q=80",
                    streamUrl = "https://playertest.longtailvideo.com/adaptive/bipbop/bipbop.m3u8", // Working play link
                    duration = "22 دقيقة",
                    number = 2
                )
            )
            for (ep in episodes) {
                db.collection("episodes").document(ep.id).set(ep).await()
            }

            // 9. Seed Main Channels (Virus Database style)
            val mainChannelsSeer = listOf(
                Channel(
                    id = "virus_bein_1",
                    name = "beIN SPORTS Premium 1 HD",
                    logoUrl = "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=150&q=80",
                    streamUrl = "https://cph-pms.dplay.com/sintel/global/sintel.m3u8",
                    category = "sports"
                ),
                Channel(
                    id = "virus_mbc_action",
                    name = "MBC ACTION HD",
                    logoUrl = "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=150&q=80",
                    streamUrl = "http://sample.vodobox.com/planete_hd_short_clip/planete_hd_short_clip.m3u8",
                    category = "entertainment"
                ),
                Channel(
                    id = "virus_aljazeera",
                    name = "الجزيرة الإخبارية HD (M3U8 Official)",
                    logoUrl = "https://images.unsplash.com/photo-1585829365294-bb7c6384ef81?w=150&q=80",
                    streamUrl = "https://live-aljazeera.akamaized.net/hls/live/2003940/aljazeera/index.m3u8",
                    category = "news"
                ),
                Channel(
                    id = "virus_alarabiya",
                    name = "العربية الأخبار HD (M3U8 Official)",
                    logoUrl = "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=150&q=80",
                    streamUrl = "https://alarabiya-live.akamaized.net/hls/live/2004419/alarabiya/index.m3u8",
                    category = "news"
                ),
                Channel(
                    id = "virus_bein_news",
                    name = "beIN SPORTS News HD (TS Stream Demo)",
                    logoUrl = "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=150&q=80",
                    streamUrl = "http://qthttp.apple.com.edgesuite.net/1010qwoihg9209wqadhb04Kwbscf/1010_vod.m3u8",
                    category = "sports"
                )
            )
            for (chan in mainChannelsSeer) {
                db.collection("main_channels").document(chan.id).set(chan).await()
            }

            Log.d(TAG, "Seeding Firestore completed perfectly!")
        } catch (e: Throwable) {
            Log.e(TAG, "Error seeding database", e)
        }
    }

    private suspend fun seedMainChannelsOnly() {
        try {
            Log.d(TAG, "Seeding main channels specifically...")
            val mainChannelsSeer = listOf(
                Channel(
                    id = "virus_bein_1",
                    name = "beIN SPORTS Premium 1 HD",
                    logoUrl = "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=150&q=80",
                    streamUrl = "https://cph-pms.dplay.com/sintel/global/sintel.m3u8",
                    category = "sports"
                ),
                Channel(
                    id = "virus_mbc_action",
                    name = "MBC ACTION HD",
                    logoUrl = "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=150&q=80",
                    streamUrl = "http://sample.vodobox.com/planete_hd_short_clip/planete_hd_short_clip.m3u8",
                    category = "entertainment"
                ),
                Channel(
                    id = "virus_aljazeera",
                    name = "الجزيرة الإخبارية HD (M3U8 Official)",
                    logoUrl = "https://images.unsplash.com/photo-1585829365294-bb7c6384ef81?w=150&q=80",
                    streamUrl = "https://live-aljazeera.akamaized.net/hls/live/2003940/aljazeera/index.m3u8",
                    category = "news"
                ),
                Channel(
                    id = "virus_alarabiya",
                    name = "العربية الأخبار HD (M3U8 Official)",
                    logoUrl = "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=150&q=80",
                    streamUrl = "https://alarabiya-live.akamaized.net/hls/live/2004419/alarabiya/index.m3u8",
                    category = "news"
                ),
                Channel(
                    id = "virus_bein_news",
                    name = "beIN SPORTS News HD (TS Stream Demo)",
                    logoUrl = "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=150&q=80",
                    streamUrl = "http://qthttp.apple.com.edgesuite.net/1010qwoihg9209wqadhb04Kwbscf/1010_vod.m3u8",
                    category = "sports"
                )
            )
            for (chan in mainChannelsSeer) {
                db.collection("main_channels").document(chan.id).set(chan).await()
            }
            Log.d(TAG, "Completed seeding main_channels collection successfully!")
        } catch (e: Throwable) {
            Log.e(TAG, "Error seeding main channels only", e)
        }
    }

    /**
     * Fetches public main channels from the vviruslove styled database.
     */
    suspend fun getMainChannels(): List<Channel> = withContext(Dispatchers.IO) {
        val firestoreList = try {
            val snapshot = db.collection("main_channels").get().await()
            snapshot.documents.map { doc ->
                Channel(
                    id = doc.id,
                    name = doc.getString("name") ?: "",
                    logoUrl = doc.getString("logoUrl") ?: "",
                    streamUrl = doc.getString("streamUrl") ?: "",
                    category = doc.getString("category") ?: "",
                    userAgent = doc.getString("userAgent") ?: ""
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching main channels from firestore", e)
            emptyList()
        }

        val deletedIds = getDeletedIds("main_channels")

        val rtdbList = mutableListOf<Channel>()
        val url = "$RTDB_URL/main_channels.json"
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val obj = json.optJSONObject(key)
                            if (obj != null) {
                                rtdbList.add(
                                    Channel(
                                        id = obj.optString("id", key),
                                        name = obj.optString("name", ""),
                                        logoUrl = obj.optString("logoUrl", ""),
                                        streamUrl = obj.optString("streamUrl", ""),
                                        category = obj.optString("category", ""),
                                        userAgent = obj.optString("userAgent", "")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching main channels from RTDB", e)
        }

        val combined = (firestoreList.filterNot { deletedIds.contains(it.id) } + rtdbList)
            .distinctBy { it.id }
        return@withContext combined
    }
}
