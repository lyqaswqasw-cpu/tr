package com.example.data

import android.content.Context
import android.util.Log
import com.example.model.NewsArticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

object NewsService {
    private const val TAG = "NewsService"
    private const val API_KEY = "pub_3888b8ade5c8460b8ca6952f598d4438"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // 30 minutes caching expiry
    private const val CACHE_EXPIRY_MS = 30 * 60 * 1000L

    suspend fun fetchNews(context: Context, query: String, forceRefresh: Boolean = false): List<NewsArticle> = withContext(Dispatchers.IO) {
        val cachePrefs = context.getSharedPreferences("news_cache_prefs", Context.MODE_PRIVATE)
        val cacheKey = "cache_$query"
        val cacheTimeKey = "cache_time_$query"

        val cachedJson = cachePrefs.getString(cacheKey, null)
        val cachedTime = cachePrefs.getLong(cacheTimeKey, 0L)
        val now = System.currentTimeMillis()

        if (!forceRefresh && cachedJson != null && (now - cachedTime < CACHE_EXPIRY_MS)) {
            Log.d(TAG, "Returning cached news for query: $query")
            try {
                return@withContext parseNewsJson(cachedJson)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing cached news: ${e.message}", e)
            }
        }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://newsdata.io/api/1/news?apikey=$API_KEY&q=$encodedQuery&language=ar&category=sports"
        Log.d(TAG, "Fetching news from API: $url")

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("API call failed with code: ${response.code}")
                }

                val bodyString = response.body?.string() ?: throw Exception("Response body is empty")
                
                // Parse to check for success status
                val jsonObj = JSONObject(bodyString)
                val status = jsonObj.optString("status")
                if (status == "success") {
                    val articlesJson = jsonObj.optJSONArray("results")?.toString() ?: "[]"
                    
                    // Save to Cache
                    cachePrefs.edit()
                        .putString(cacheKey, articlesJson)
                        .putLong(cacheTimeKey, now)
                        .apply()

                    return@withContext parseNewsJson(articlesJson)
                } else {
                    val message = jsonObj.optString("message", "Unknown error from NewsData API")
                    throw Exception(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching news from API: ${e.message}", e)
            // Fallback to expired cache if available
            if (cachedJson != null) {
                Log.d(TAG, "Returning expired cache as fallback due to error: ${e.message}")
                try {
                    return@withContext parseNewsJson(cachedJson)
                } catch (pe: Exception) {
                    Log.e(TAG, "Error parsing fallback cached news: ${pe.message}", pe)
                }
            }
            throw e
        }
    }

    private fun parseNewsJson(jsonArrayString: String): List<NewsArticle> {
        val articles = mutableListOf<NewsArticle>()
        val jsonArray = org.json.JSONArray(jsonArrayString)
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val title = item.optString("title", "")
            if (title.isBlank()) continue

            val description = item.optString("description", "")
            val imageUrl = item.optString("image_url", "")
            val pubDate = item.optString("pubDate", "")
            val link = item.optString("link", "")
            val sourceId = item.optString("source_id", "")

            // creator can be null, single string or JSON array
            val creatorArray = item.optJSONArray("creator")
            val creator = if (creatorArray != null && creatorArray.length() > 0) {
                creatorArray.optString(0, "")
            } else {
                item.optString("creator", "")
            }

            articles.add(
                NewsArticle(
                    title = title,
                    description = description,
                    imageUrl = imageUrl,
                    pubDate = pubDate,
                    link = link,
                    sourceId = sourceId,
                    creator = creator
                )
            )
        }
        return articles
    }
}
