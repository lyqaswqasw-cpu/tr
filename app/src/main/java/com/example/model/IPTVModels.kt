package com.example.model

data class AccessCode(
    val code: String = "",
    val isActive: Boolean = true,
    val accountId: String = ""
)

data class ActivationCode(
    val code: String = "",
    val host: String = "",
    val username: String = "",
    val password: String = ""
)

data class IPTVAccount(
    val id: String = "",
    val name: String = "",
    val allowedCategories: List<String> = emptyList(),
    val expiryDate: String = "",
    val host: String = "",
    val username: String = "",
    val password: String = ""
)

data class Channel(
    val id: String = "",
    val name: String = "",
    val logoUrl: String = "",
    val streamUrl: String = "",
    val category: String = "",
    val userAgent: String = "",
    val forcedFormat: String = "ts"
)

data class Movie(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val posterUrl: String = "",
    val streamUrl: String = "",
    val category: String = "",
    val duration: String = "",
    val rating: Double = 0.0,
    val year: Int = 2026
)

data class Series(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val posterUrl: String = "",
    val category: String = "",
    val rating: Double = 0.0,
    val year: Int = 2026
)

data class Season(
    val id: String = "",
    val seriesId: String = "",
    val number: Int = 1,
    val title: String = ""
)

data class Episode(
    val id: String = "",
    val seasonId: String = "",
    val seriesId: String = "",
    val title: String = "",
    val description: String = "",
    val posterUrl: String = "",
    val streamUrl: String = "",
    val duration: String = "",
    val number: Int = 1
)

data class IPTVCategory(
    val id: String = "",
    val name: String = "",
    val type: String = "" // "channel", "movie", "series"
)

data class NewsArticle(
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val pubDate: String = "",
    val link: String = "",
    val sourceId: String = "",
    val creator: String = ""
)

data class AppNotification(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val imageUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

