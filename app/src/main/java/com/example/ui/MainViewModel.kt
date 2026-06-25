package com.example.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FirebaseService
import com.example.data.NewsService
import com.example.data.await
import com.example.data.ChatService
import com.example.data.ChatMessage
import com.example.model.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("loop_live_prefs", Context.MODE_PRIVATE)

    // UI States
    private val _accessCode = MutableStateFlow<String?>(null)
    val accessCode: StateFlow<String?> = _accessCode.asStateFlow()

    private val _activeAccount = MutableStateFlow<IPTVAccount?>(null)
    val activeAccount: StateFlow<IPTVAccount?> = _activeAccount.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _mainChannels = MutableStateFlow<List<Channel>>(emptyList())
    val mainChannels: StateFlow<List<Channel>> = _mainChannels.asStateFlow()

    private val _movies = MutableStateFlow<List<Movie>>(emptyList())
    val movies: StateFlow<List<Movie>> = _movies.asStateFlow()

    private val _series = MutableStateFlow<List<Series>>(emptyList())
    val series: StateFlow<List<Series>> = _series.asStateFlow()

    private val _categories = MutableStateFlow<List<IPTVCategory>>(emptyList())
    val categories: StateFlow<List<IPTVCategory>> = _categories.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isChannelsLoading = MutableStateFlow(false)
    val isChannelsLoading: StateFlow<Boolean> = _isChannelsLoading.asStateFlow()

    private val _isMoviesLoading = MutableStateFlow(false)
    val isMoviesLoading: StateFlow<Boolean> = _isMoviesLoading.asStateFlow()

    private val _isSeriesLoading = MutableStateFlow(false)
    val isSeriesLoading: StateFlow<Boolean> = _isSeriesLoading.asStateFlow()

    // Interactive UI variables
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String>("all")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    // Favorites & Recent
    private val _favorites = MutableStateFlow<Set<String>>(prefs.getStringSet("saved_favorites", null) ?: emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _continueWatching = MutableStateFlow<List<Movie>>(emptyList())
    val continueWatching: StateFlow<List<Movie>> = _continueWatching.asStateFlow()

    // Navigation and Popups
    private val _selectedMovie = MutableStateFlow<Movie?>(null)
    val selectedMovie: StateFlow<Movie?> = _selectedMovie.asStateFlow()

    private val _selectedSeries = MutableStateFlow<Series?>(null)
    val selectedSeries: StateFlow<Series?> = _selectedSeries.asStateFlow()

    private val _seasons = MutableStateFlow<List<Season>>(emptyList())
    val seasons: StateFlow<List<Season>> = _seasons.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    // Player routing
    private val _playingStreamUrl = MutableStateFlow<String?>(null)
    val playingStreamUrl: StateFlow<String?> = _playingStreamUrl.asStateFlow()

    private val _playingTitle = MutableStateFlow("")
    val playingTitle: StateFlow<String> = _playingTitle.asStateFlow()

    private val _playingIsLive = MutableStateFlow(false)
    val playingIsLive: StateFlow<Boolean> = _playingIsLive.asStateFlow()

    private val _playingUserAgent = MutableStateFlow("")
    val playingUserAgent: StateFlow<String> = _playingUserAgent.asStateFlow()

    private val _forcedFormat = MutableStateFlow("ts")
    val forcedFormat: StateFlow<String> = _forcedFormat.asStateFlow()

    // Player format preferences: "smart", "ts", "m3u8", "normal"
    private val _playerMode = MutableStateFlow(prefs.getString("saved_player_type", "smart") ?: "smart")
    val playerMode: StateFlow<String> = _playerMode.asStateFlow()

    private val _showNotificationIcon = MutableStateFlow(prefs.getBoolean("show_notification_icon", true))
    val showNotificationIcon: StateFlow<Boolean> = _showNotificationIcon.asStateFlow()

    fun setShowNotificationIcon(show: Boolean) {
        _showNotificationIcon.value = show
        prefs.edit().putBoolean("show_notification_icon", show).apply()
    }

    // News system state flows
    private val _newsArticles = MutableStateFlow<List<NewsArticle>>(emptyList())
    val newsArticles: StateFlow<List<NewsArticle>> = _newsArticles.asStateFlow()

    private val _isNewsLoading = MutableStateFlow(false)
    val isNewsLoading: StateFlow<Boolean> = _isNewsLoading.asStateFlow()

    private val _newsError = MutableStateFlow<String?>(null)
    val newsError: StateFlow<String?> = _newsError.asStateFlow()

    private val _newsQuery = MutableStateFlow("\"كرة القدم\" OR \"مباراة\" OR \"كورة\"")
    val newsQuery: StateFlow<String> = _newsQuery.asStateFlow()

    fun setPlayerMode(mode: String) {
        _playerMode.value = mode
        prefs.edit().putString("saved_player_type", mode).apply()
    }

    // Auto landscape setting: saves state and triggers horizontal orientation immediately
    private val _autoLandscape = MutableStateFlow(prefs.getBoolean("saved_auto_landscape", false))
    val autoLandscape: StateFlow<Boolean> = _autoLandscape.asStateFlow()

    fun setAutoLandscape(enabled: Boolean) {
        _autoLandscape.value = enabled
        prefs.edit().putBoolean("saved_auto_landscape", enabled).apply()
    }

    // Theme preferences: "red", "gold", "purple", "emerald", "blue"
    private val _selectedTheme = MutableStateFlow(prefs.getString("saved_theme", "red") ?: "red")
    val selectedTheme: StateFlow<String> = _selectedTheme.asStateFlow()

    fun setTheme(theme: String) {
        _selectedTheme.value = theme
        prefs.edit().putString("saved_theme", theme).apply()
    }

    // Grid Column Count: 2, 3, or 4
    private val _cardColumnsCount = MutableStateFlow(prefs.getInt("saved_card_columns", 3))
    val cardColumnsCount: StateFlow<Int> = _cardColumnsCount.asStateFlow()

    fun setCardColumnsCount(count: Int) {
        _cardColumnsCount.value = count
        prefs.edit().putInt("saved_card_columns", count).apply()
    }

    // Admin Panel Info
    private val _isAdminAuthenticated = MutableStateFlow(false)
    val isAdminAuthenticated: StateFlow<Boolean> = _isAdminAuthenticated.asStateFlow()

    private val _adminCodesList = MutableStateFlow<List<AccessCode>>(emptyList())
    val adminCodesList: StateFlow<List<AccessCode>> = _adminCodesList.asStateFlow()

    private val _adminActionStatus = MutableStateFlow<String?>(null)
    val adminActionStatus: StateFlow<String?> = _adminActionStatus.asStateFlow()

    // App Config Online State (News Section visibility)
    private val _showNewsTab = MutableStateFlow(true)
    val showNewsTab: StateFlow<Boolean> = _showNewsTab.asStateFlow()

    private val _showChatTab = MutableStateFlow(true)
    val showChatTab: StateFlow<Boolean> = _showChatTab.asStateFlow()

    private val _showMainChannelsTab = MutableStateFlow(true)
    val showMainChannelsTab: StateFlow<Boolean> = _showMainChannelsTab.asStateFlow()

    // Secondary Realtime Database Chat States
    private val _isChatEnabledOnline = MutableStateFlow(true)
    val isChatEnabledOnline: StateFlow<Boolean> = _isChatEnabledOnline.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    // Chat Username and Display Name
    private val _chatUsername = MutableStateFlow<String?>(null)
    val chatUsername: StateFlow<String?> = _chatUsername.asStateFlow()

    private val _chatName = MutableStateFlow<String?>(null)
    val chatName: StateFlow<String?> = _chatName.asStateFlow()

    private val _allUsers = MutableStateFlow<Map<String, String>>(emptyMap())
    val allUsers: StateFlow<Map<String, String>> = _allUsers.asStateFlow()

    private val _blockedUsers = MutableStateFlow<List<String>>(emptyList())
    val blockedUsers: StateFlow<List<String>> = _blockedUsers.asStateFlow()

    private val _messagedUsers = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val messagedUsers: StateFlow<List<Pair<String, String>>> = _messagedUsers.asStateFlow()

    init {
        _chatUsername.value = prefs.getString("chat_username", null)
        _chatName.value = prefs.getString("chat_name", null)

        // Periodic check for messaged users and blocked list (continuous update)
        viewModelScope.launch {
            while (true) {
                if (!_chatUsername.value.isNullOrBlank()) {
                    fetchMessagedUsers()
                }
                delay(3000L)
            }
        }

        // Periodic check for secondary DB chat enablement online
        viewModelScope.launch {
            while (true) {
                try {
                    _isChatEnabledOnline.value = ChatService.isChatEnabled()
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to check chat enabled", e)
                }
                delay(10000L)
            }
        }

        // Periodic check for News, Chat and Main Channels Tab online state from RTDB
        viewModelScope.launch {
            while (true) {
                try {
                    val config = FirebaseService.getAppConfig()
                    _showNewsTab.value = config["showNewsTab"] ?: true
                    _showChatTab.value = config["showChatTab"] ?: true
                    _showMainChannelsTab.value = config["showMainChannelsTab"] ?: true
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to check app_config from RTDB", e)
                }
                delay(3000L)
            }
        }

        // Background loop for Realtime Direct Messages and Replies Notifications (simulating FCM)
        viewModelScope.launch {
            delay(5000L)
            while (true) {
                try {
                    val myUser = _chatUsername.value
                    if (!myUser.isNullOrBlank()) {
                        val allChats = ChatService.getAllChats()
                        if (allChats != null) {
                            for ((chanId, messagesMap) in allChats) {
                                if (chanId.startsWith("private_")) {
                                    val parts = chanId.split("_")
                                    if (parts.size >= 3) {
                                        val userA = parts[1]
                                        val userB = parts[2]
                                        if (userA == myUser || userB == myUser) {
                                            for ((msgId, msgData) in messagesMap) {
                                                if (msgData is Map<*, *>) {
                                                    val sender = msgData["sender"] as? String ?: ""
                                                    val text = msgData["text"] as? String ?: ""
                                                    val timestamp = (msgData["timestamp"] as? Number)?.toLong() ?: 0L

                                                    if (sender != myUser && sender.isNotBlank()) {
                                                        if (!notifiedMessageIds.contains(msgId)) {
                                                            val isFresh = (System.currentTimeMillis() - timestamp) < 180_000L // 3 minutes
                                                            if (isFresh) {
                                                                showLocalNotification(sender, text, chanId)
                                                            }
                                                            notifiedMessageIds.add(msgId)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error in private notification loop", e)
                }
                delay(4000L)
            }
        }

        // Automatically seed data if needed and recover previous active code
        viewModelScope.launch {
            _isLoading.value = true
            try {
                FirebaseService.seedDatabaseIfEmpty()
            } catch (t: Throwable) {
                Log.e("MainViewModel", "Error seeding database on startup", t)
            }
            loadMainChannels()
            try {
                val savedCode = prefs.getString("saved_access_code", null)
                if (savedCode != null) {
                    loginWithCode(savedCode)
                } else {
                    _isLoading.value = false
                }
            } catch (t: Throwable) {
                Log.e("MainViewModel", "Error restoring saved code", t)
                _isLoading.value = false
            }
        }
    }

    fun loadMainChannels() {
        viewModelScope.launch {
            try {
                _mainChannels.value = FirebaseService.getMainChannels()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error loading main channels", e)
            }
        }
    }

    /**
     * Logic for logging in with an IPTV Access code (e.g., A1).
     */
    fun loginWithCode(code: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                // 1. Try dynamic activation_codes first
                val activationResult = FirebaseService.verifyActivationCode(code)
                if (activationResult != null) {
                    val accountResult = IPTVAccount(
                        id = activationResult.code,
                        name = "حساب Xtream المفعّل",
                        allowedCategories = emptyList(),
                        expiryDate = "مفتوح تلقائياً",
                        host = activationResult.host,
                        username = activationResult.username,
                        password = activationResult.password
                    )
                    _accessCode.value = activationResult.code
                    _activeAccount.value = accountResult
                    // Save to Shared Preferences
                    prefs.edit().putString("saved_access_code", activationResult.code).apply()

                    // Load content dynamically via Xtream API
                    loadIPTVContent(emptyList())
                } else {
                    // 2. Fall back to old access_codes (mock data / seed data)
                    val codeResult = FirebaseService.verifyAccessCode(code)
                    if (codeResult != null && codeResult.isActive) {
                        val accountResult = FirebaseService.getAccount(codeResult.accountId)
                        if (accountResult != null) {
                            _accessCode.value = codeResult.code
                            _activeAccount.value = accountResult
                            prefs.edit().putString("saved_access_code", codeResult.code).apply()
                            loadIPTVContent(accountResult.allowedCategories)
                        } else {
                            _errorMessage.value = "الكود صحيح ولكن الحساب المرتبط به غير موجود!"
                        }
                    } else {
                        _errorMessage.value = "كود التفعيل غير صحيح أو تم إلغاء تنشيطه!"
                    }
                }
            } catch (e: Throwable) {
                _errorMessage.value = "حدث خطأ أثناء فحص الكود: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Logout and delete local cache.
     */
    fun logout() {
        prefs.edit().remove("saved_access_code").apply()
        FirebaseService.clearCache()
        _accessCode.value = null
        _activeAccount.value = null
        _channels.value = emptyList()
        _movies.value = emptyList()
        _series.value = emptyList()
        _categories.value = emptyList()
        _selectedMovie.value = null
        _selectedSeries.value = null
        _playingStreamUrl.value = null
        _isAdminAuthenticated.value = false
    }

    /**
     * Set selected video category.
     */
    fun selectCategory(categoryId: String) {
        _selectedCategory.value = categoryId
    }

    /**
     * Toggle item in favorites.
     */
    fun toggleFavorite(itemId: String) {
        val current = _favorites.value.toMutableSet()
        if (current.contains(itemId)) {
            current.remove(itemId)
        } else {
            current.add(itemId)
        }
        _favorites.value = current
        prefs.edit().putStringSet("saved_favorites", current).apply()
    }

    /**
     * Set movie to continue watching queue.
     */
    fun addToContinueWatching(movie: Movie) {
        val currentList = _continueWatching.value.toMutableList()
        if (currentList.none { it.id == movie.id }) {
            currentList.add(0, movie)
            if (currentList.size > 5) currentList.removeAt(5)
            _continueWatching.value = currentList
        }
    }

    /**
     * Update search query.
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Fetch news using current news query
     */
    fun fetchNews(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _isNewsLoading.value = true
            _newsError.value = null
            try {
                val articles = NewsService.fetchNews(getApplication(), _newsQuery.value, forceRefresh)
                _newsArticles.value = articles
            } catch (e: Exception) {
                _newsError.value = e.message ?: "حدث خطأ أثناء جلب الأخبار"
            } finally {
                _isNewsLoading.value = false
            }
        }
    }

    /**
     * Update news query term and fetch immediately
     */
    fun updateNewsQuery(query: String) {
        _newsQuery.value = query
        fetchNews(forceRefresh = true)
    }

    /**
     * Load authenticated IPTV Account's assigned contents.
     */
    private suspend fun loadIPTVContent(allowedCategories: List<String>) = coroutineScope {
        _isLoading.value = true
        _isChannelsLoading.value = true
        _isMoviesLoading.value = false
        _isSeriesLoading.value = false
        try {
            val account = _activeAccount.value
            if (account != null && account.host.isNotEmpty()) {
                val host = account.host
                val username = account.username
                val password = account.password
                
                // Categories
                launch {
                    try {
                        _categories.value = FirebaseService.fetchXtreamCategories(host, username, password)
                    } catch (e: Throwable) {
                        Log.e("MainViewModel", "Error loading categories", e)
                    } finally {
                        _isLoading.value = false
                    }
                }

                // Channels
                launch {
                    try {
                        _channels.value = FirebaseService.fetchXtreamChannels(host, username, password)
                    } catch (e: Throwable) {
                        Log.e("MainViewModel", "Error loading channels", e)
                    } finally {
                        _isChannelsLoading.value = false
                    }
                }
            } else {
                // Parallel loaders
                launch {
                    try {
                        val categoriesJob = FirebaseService.getCategories()
                        _categories.value = categoriesJob.filter { cat ->
                            allowedCategories.isEmpty() || allowedCategories.contains(cat.id)
                        }
                    } catch (e: Throwable) {
                        Log.e("MainViewModel", "Error loading categories", e)
                    } finally {
                        _isLoading.value = false
                    }
                }

                launch {
                    try {
                        _channels.value = FirebaseService.getChannels(allowedCategories)
                    } catch (e: Throwable) {
                        Log.e("MainViewModel", "Error loading channels", e)
                    } finally {
                        _isChannelsLoading.value = false
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("MainViewModel", "Error loading IPTV content", e)
            _isLoading.value = false
            _isChannelsLoading.value = false
        }
    }

    /**
     * Load movies on demand to guarantee maximum speed and split queries
     */
    fun loadMoviesOnDemand() {
        val account = _activeAccount.value ?: return
        if (_movies.value.isNotEmpty() || _isMoviesLoading.value) return

        viewModelScope.launch {
            _isMoviesLoading.value = true
            try {
                if (account.host.isNotEmpty()) {
                    _movies.value = FirebaseService.fetchXtreamMovies(account.host, account.username, account.password)
                } else {
                    _movies.value = FirebaseService.getMovies(account.allowedCategories)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error loading movies on demand", e)
            } finally {
                _isMoviesLoading.value = false
            }
        }
    }

    /**
     * Load series on demand to guarantee maximum speed and split queries
     */
    fun loadSeriesOnDemand() {
        val account = _activeAccount.value ?: return
        if (_series.value.isNotEmpty() || _isSeriesLoading.value) return

        viewModelScope.launch {
            _isSeriesLoading.value = true
            try {
                if (account.host.isNotEmpty()) {
                    _series.value = FirebaseService.fetchXtreamSeries(account.host, account.username, account.password)
                } else {
                    _series.value = FirebaseService.getSeries(account.allowedCategories)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error loading series on demand", e)
            } finally {
                _isSeriesLoading.value = false
            }
        }
    }

    /**
     * Select a movie to show details.
     */
    fun selectMovie(movie: Movie?) {
        _selectedMovie.value = movie
        if (movie != null) {
            _selectedSeries.value = null // clear other detail open state
        }
    }

    /**
     * Select a series to expand seasons and episodes.
     */
    fun selectSeries(series: Series?) {
        _selectedSeries.value = series
        if (series != null) {
            _selectedMovie.value = null
            // Fetch seasons
            viewModelScope.launch {
                _isLoading.value = true
                val account = _activeAccount.value
                val listSeasons = if (account != null && account.host.isNotEmpty()) {
                    FirebaseService.fetchXtreamSeasons(account.host, account.username, account.password, series.id)
                } else {
                    FirebaseService.getSeasons(series.id)
                }
                _seasons.value = listSeasons
                if (listSeasons.isNotEmpty()) {
                    loadEpisodes(series.id, listSeasons.first().id)
                } else {
                    _episodes.value = emptyList()
                }
                _isLoading.value = false
            }
        }
    }

    /**
     * Load specific season episodes.
     */
    fun loadEpisodes(seriesId: String, seasonId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val account = _activeAccount.value
            val listEpisodes = if (account != null && account.host.isNotEmpty()) {
                FirebaseService.fetchXtreamEpisodes(account.host, account.username, account.password, seriesId, seasonId)
            } else {
                FirebaseService.getEpisodes(seriesId, seasonId)
            }
            _episodes.value = listEpisodes
            _isLoading.value = false
        }
    }

    /**
     * Route Stream Url to Media Player screen.
     */
    fun startPlaying(url: String, title: String, isLive: Boolean, forceRawUrl: Boolean = false, userAgent: String = "", forcedFormat: String = "") {
        var processedUrl = url
        val currentMode = if (forcedFormat.isNotBlank() && forcedFormat != "normal") forcedFormat else _playerMode.value

        // If forcedFormat is specified, we ALWAYS process the URL extension, bypassing forceRawUrl if necessary
        if (!forceRawUrl || (forcedFormat.isNotBlank() && forcedFormat != "normal")) {
            try {
                if ((isLive && url.contains("/live/")) || url.contains("/movie/") || url.contains("/series/")) {
                    val lastSlash = url.lastIndexOf('/')
                    val dotIndex = url.lastIndexOf('.')
                    if (dotIndex > lastSlash) {
                        val base = url.substring(0, dotIndex)
                        processedUrl = when (currentMode) {
                            "ts" -> "$base.ts"
                            "m3u8" -> "$base.m3u8"
                            "normal" -> if (url.contains("/movie/")) "$base.mp4" else url
                            else -> url
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error processing stream URL extension formatting for mode $currentMode", e)
            }
        }

        Log.d("MainViewModel", "Setting playing stream: $processedUrl (Original: $url) - Mode: $currentMode (Forced: $forcedFormat)")
        _playingStreamUrl.value = processedUrl
        _playingTitle.value = title
        _playingIsLive.value = isLive
        _playingUserAgent.value = userAgent
        _forcedFormat.value = forcedFormat
    }

    fun stopPlaying() {
        _playingStreamUrl.value = null
    }

    /**
     * ADMIN PANEL: Verify admin secret code.
     */
    fun authenticateAdmin(password: String): Boolean {
        if (password == "ali2008#$1") {
            _isAdminAuthenticated.value = true
            loadAdminData()
            return true
        }
        return false
    }

    fun exitAdmin() {
        _isAdminAuthenticated.value = false
    }

    /**
     * Load admin panel reference content.
     */
    fun loadAdminData() {
        viewModelScope.launch {
            try {
                val snapshot = FirebaseFirestore.getInstance().collection("access_codes").get().await()
                _adminCodesList.value = snapshot.documents.map { doc ->
                    AccessCode(
                        code = doc.id,
                        isActive = doc.getBoolean("isActive") ?: true,
                        accountId = doc.getString("accountId") ?: ""
                    )
                }
            } catch (e: Throwable) {
                Log.e("MainViewModel", "Failed to load admin code list", e)
            }
        }
    }

    /**
     * ADMIN CODE OPERATIONS: Create/Activate IPTV activations.
     */
    fun adminSaveAccessCode(code: String, isActive: Boolean, accountId: String) {
        viewModelScope.launch {
            _adminActionStatus.value = "جاري الحفظ..."
            val model = AccessCode(code.trim().uppercase(), isActive, accountId.ifEmpty { "premium_account_a1" })
            val success = FirebaseService.adminSaveAccessCode(model)
            if (success) {
                _adminActionStatus.value = "تم حفظ كود التفعيل بنجاح!"
                loadAdminData()
            } else {
                _adminActionStatus.value = "فشلت العملية!"
            }
        }
    }

    /**
     * ADMIN CODE OPERATIONS: Delete activation codes.
     */
    fun adminDeleteAccessCode(code: String) {
        viewModelScope.launch {
            _adminActionStatus.value = "جاري الحذف..."
            val success = FirebaseService.adminDeleteAccessCode(code)
            if (success) {
                _adminActionStatus.value = "تم الحذف بنجاح!"
                loadAdminData()
            } else {
                _adminActionStatus.value = "فصلت عملية الحذف!"
            }
        }
    }

    /**
     * ADMIN CONTENT OPERATIONS: Upload rapid channels.
     */
    fun adminAddChannel(name: String, cat: String, playUrl: String, logoUrl: String) {
        viewModelScope.launch {
            _adminActionStatus.value = "جاري إضافة القناة بث مباشر..."
            val ch = Channel(
                id = "chan_" + System.currentTimeMillis(),
                name = name,
                category = cat,
                streamUrl = playUrl,
                logoUrl = logoUrl.ifEmpty { "https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=150&q=80" }
            )
            val success = FirebaseService.adminSaveChannel(ch)
            if (success) {
                _adminActionStatus.value = "تمت إضافة القناة البث المباشر بنجاح!"
                // Refresh client data if already authenticated
                _activeAccount.value?.let { loadIPTVContent(it.allowedCategories) }
            } else {
                _adminActionStatus.value = "فشلت إضافة القناة!"
            }
        }
    }

    /**
     * ADMIN CONTENT OPERATIONS: Upload rapid movies.
     */
    fun adminAddMovie(title: String, desc: String, playUrl: String, posterUrl: String, duration: String, cat: String, rating: Double, year: Int) {
        viewModelScope.launch {
            _adminActionStatus.value = "جاري إضافة الفيلم..."
            val movie = Movie(
                id = "movie_" + System.currentTimeMillis(),
                title = title,
                description = desc,
                posterUrl = posterUrl.ifEmpty { "https://images.unsplash.com/photo-1485846234645-a62644f84728?w=400&q=80" },
                streamUrl = playUrl,
                category = cat,
                duration = duration.ifEmpty { "02:00:00" },
                rating = rating,
                year = year
            )
            val success = FirebaseService.adminSaveMovie(movie)
            if (success) {
                _adminActionStatus.value = "تمت إضافة الفيلم بنجاح!"
                _activeAccount.value?.let { loadIPTVContent(it.allowedCategories) }
            } else {
                _adminActionStatus.value = "فشلت إضافة الفيلم!"
            }
        }
    }

    fun clearAdminStatus() {
        _adminActionStatus.value = null
    }

    // Developer Control State values
    private val _isDeveloperAuthenticated = MutableStateFlow(false)
    val isDeveloperAuthenticated: StateFlow<Boolean> = _isDeveloperAuthenticated.asStateFlow()

    private val _developerActionStatus = MutableStateFlow<String?>(null)
    val developerActionStatus: StateFlow<String?> = _developerActionStatus.asStateFlow()

    fun authenticateDeveloper(password: String): Boolean {
        if (password == "ali2008#$1") {
            _isDeveloperAuthenticated.value = true
            _developerActionStatus.value = "مرحباً بك يا مطور في لوحة التحكم الفاخرة!"
            return true
        }
        _developerActionStatus.value = "كلمة المرور خاطئة!"
        return false
    }

    fun logoutDeveloper() {
        _isDeveloperAuthenticated.value = false
        _developerActionStatus.value = null
    }

    fun publishDeveloperCategory(id: String, name: String, type: String) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري نشر القسم الجديد أونلاين..."
            val category = IPTVCategory(id = id.trim(), name = name.trim(), type = type.trim())
            val success = FirebaseService.adminSaveCategory(category)
            if (success) {
                _developerActionStatus.value = "تم نشر القسم (${name}) بنجاح!"
                val allowed = _activeAccount.value?.allowedCategories ?: emptyList()
                loadIPTVContent(allowed)
            } else {
                _developerActionStatus.value = "خطأ: فشل نشر القسم أونلاين!"
            }
        }
    }

    fun deleteDeveloperCategory(catId: String) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري حذف القسم..."
            val success = FirebaseService.adminDeleteContent("categories", catId)
            if (success) {
                _developerActionStatus.value = "تم حذف القسم بنجاح!"
                val allowed = _activeAccount.value?.allowedCategories ?: emptyList()
                loadIPTVContent(allowed)
            } else {
                _developerActionStatus.value = "فشل حذف القسم!"
            }
        }
    }

    fun publishDeveloperMainChannel(id: String, name: String, logoUrl: String, streamUrl: String, categoryId: String, userAgent: String, forcedFormat: String = "ts") {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري نشر القناة الرئيسية..."
            val ch = Channel(
                id = id.ifEmpty { "main_chan_" + System.currentTimeMillis() },
                name = name.trim(),
                logoUrl = logoUrl.trim().ifEmpty { "https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=150&q=80" },
                streamUrl = streamUrl.trim(),
                category = categoryId.trim(),
                userAgent = userAgent.trim(),
                forcedFormat = forcedFormat
            )
            val success = FirebaseService.adminSaveMainChannel(ch)
            if (success) {
                _developerActionStatus.value = "تم نشر القناة الرئيسية (${name}) بنجاح!"
                loadMainChannels()
            } else {
                _developerActionStatus.value = "فشل نشر القناة الرئيسية أونلاين!"
            }
        }
    }

    fun deleteDeveloperMainChannel(chanId: String) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري حذف القناة..."
            val success = FirebaseService.adminDeleteContent("main_channels", chanId)
            if (success) {
                _developerActionStatus.value = "تم حذف القناة الرئيسية بنجاح!"
                loadMainChannels()
            } else {
                _developerActionStatus.value = "فشل حذف القناة الرئيسية!"
            }
        }
    }

    fun publishDeveloperChannel(id: String, name: String, logoUrl: String, streamUrl: String, categoryId: String, userAgent: String, forcedFormat: String = "ts") {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري نشر قناة البث المباشر..."
            val ch = Channel(
                id = id.ifEmpty { "chan_" + System.currentTimeMillis() },
                name = name.trim(),
                logoUrl = logoUrl.trim().ifEmpty { "https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=150&q=80" },
                streamUrl = streamUrl.trim(),
                category = categoryId.trim(),
                userAgent = userAgent.trim(),
                forcedFormat = forcedFormat
            )
            val success = FirebaseService.adminSaveChannel(ch)
            if (success) {
                _developerActionStatus.value = "تم نشر قناة البث المباشر (${name}) بنجاح!"
                val allowed = _activeAccount.value?.allowedCategories ?: emptyList()
                loadIPTVContent(allowed)
            } else {
                _developerActionStatus.value = "فشل نشر قناة البث المباشر أونلاين!"
            }
        }
    }

    fun deleteDeveloperChannel(chanId: String) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري حذف قناة البث المباشر..."
            val success = FirebaseService.adminDeleteContent("channels", chanId)
            if (success) {
                _developerActionStatus.value = "تم حذف قناة البث المباشر بنجاح!"
                val allowed = _activeAccount.value?.allowedCategories ?: emptyList()
                loadIPTVContent(allowed)
            } else {
                _developerActionStatus.value = "فشل حذف قناة البث المباشر!"
            }
        }
    }

    fun publishDeveloperMovie(id: String, title: String, description: String, posterUrl: String, streamUrl: String, categoryId: String, duration: String, rating: Double, year: Int) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري نشر الفيلم للمكتبة..."
            val movie = Movie(
                id = id.ifEmpty { "movie_" + System.currentTimeMillis() },
                title = title.trim(),
                description = description.trim().ifEmpty { "فيلم سينمائي حائز على جوائز متاح الآن للمشاهدة." },
                posterUrl = posterUrl.trim().ifEmpty { "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=400&q=80" },
                streamUrl = streamUrl.trim(),
                category = categoryId.trim(),
                duration = duration.trim().ifEmpty { "02:00:00" },
                rating = rating,
                year = year
            )
            val success = FirebaseService.adminSaveMovie(movie)
            if (success) {
                _developerActionStatus.value = "تم نشر فيلم (${title}) للمكتبة بنجاح!"
                val allowed = _activeAccount.value?.allowedCategories ?: emptyList()
                loadIPTVContent(allowed)
            } else {
                _developerActionStatus.value = "فشل نشر الفيلم أونلاين!"
            }
        }
    }

    fun deleteDeveloperMovie(movieId: String) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري حذف الفيلم..."
            val success = FirebaseService.adminDeleteContent("movies", movieId)
            if (success) {
                _developerActionStatus.value = "تم حذف الفيلم بنجاح!"
                val allowed = _activeAccount.value?.allowedCategories ?: emptyList()
                loadIPTVContent(allowed)
            } else {
                _developerActionStatus.value = "فشل حذف الفيلم!"
            }
        }
    }

    fun publishDeveloperSeries(id: String, title: String, description: String, posterUrl: String, categoryId: String, rating: Double, year: Int) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري نشر المسلسل للمكتبة..."
            val s = Series(
                id = id.ifEmpty { "series_" + System.currentTimeMillis() },
                title = title.trim(),
                description = description.trim().ifEmpty { "مسلسل درامي حصري متاح للمشاهدة الآن." },
                posterUrl = posterUrl.trim().ifEmpty { "https://images.unsplash.com/photo-1438761681033-6461ffad8d80?w=400&q=80" },
                category = categoryId.trim(),
                rating = rating,
                year = year
            )
            val success = FirebaseService.adminSaveSeries(s)
            if (success) {
                _developerActionStatus.value = "تم نشر مسلسل (${title}) للمكتبة بنجاح!"
                val allowed = _activeAccount.value?.allowedCategories ?: emptyList()
                loadIPTVContent(allowed)
            } else {
                _developerActionStatus.value = "فشل نشر المسلسل أونلاين!"
            }
        }
    }

    fun deleteDeveloperSeries(seriesId: String) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري حذف المسلسل..."
            val success = FirebaseService.adminDeleteContent("series", seriesId)
            if (success) {
                _developerActionStatus.value = "تم حذف المسلسل بنجاح!"
                val allowed = _activeAccount.value?.allowedCategories ?: emptyList()
                loadIPTVContent(allowed)
            } else {
                _developerActionStatus.value = "فشل حذف المسلسل!"
            }
        }
    }

    private val notifiedMessageIds = mutableSetOf<String>()

    private fun showLocalNotification(sender: String, messageText: String, channelIdForIntent: String) {
        try {
            val context = getApplication<Application>()
            val intent = Intent(context, com.example.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("channelId", channelIdForIntent)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                sender.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = androidx.core.app.NotificationCompat.Builder(context, "private_chats")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("رسالة جديدة من @$sender")
                .setContentText(messageText)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(sender.hashCode(), builder.build())
            Log.d("MainViewModel", "Local notification sent for sender: $sender")
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to send local notification", e)
        }
    }

    fun setNewsTabVisibilityOnline(visible: Boolean) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري تحديث إعدادات التطبيق..."
            val success = FirebaseService.saveAppConfig("showNewsTab", visible)
            if (success) {
                _developerActionStatus.value = "تم تحديث إعدادات التطبيق بنجاح!"
            } else {
                _developerActionStatus.value = "فشل التحديث: حدث خطأ أثناء الاتصال بقاعدة البيانات"
            }
        }
    }

    fun setChatTabVisibilityOnline(visible: Boolean) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري تحديث إعدادات الدردشة..."
            val success = FirebaseService.saveAppConfig("showChatTab", visible)
            if (success) {
                _developerActionStatus.value = "تم تحديث إعدادات الدردشة بنجاح!"
            } else {
                _developerActionStatus.value = "فشل التحديث: حدث خطأ أثناء الاتصال بقاعدة البيانات"
            }
        }
    }

    fun setMainChannelsTabVisibilityOnline(visible: Boolean) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري تحديث إعدادات القنوات الرئيسية..."
            val success = FirebaseService.saveAppConfig("showMainChannelsTab", visible)
            if (success) {
                _developerActionStatus.value = "تم تحديث إعدادات القنوات الرئيسية بنجاح!"
            } else {
                _developerActionStatus.value = "فشل التحديث: حدث خطأ أثناء الاتصال بقاعدة البيانات"
            }
        }
    }

    fun fetchChatMessages(channelId: String) {
        viewModelScope.launch {
            try {
                val msgs = ChatService.getMessages(channelId)
                _chatMessages.value = msgs
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to fetch chat messages for $channelId", e)
            }
        }
    }

    fun sendChatMessage(
        channelId: String,
        sender: String,
        text: String,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToText: String? = null
    ) {
        viewModelScope.launch {
            try {
                val success = ChatService.postMessage(
                    channelId = channelId,
                    sender = sender,
                    text = text,
                    replyToId = replyToId,
                    replyToSender = replyToSender,
                    replyToText = replyToText
                )
                if (success) {
                    val msgs = ChatService.getMessages(channelId)
                    _chatMessages.value = msgs
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to send chat message", e)
            }
        }
    }

    fun registerChatUser(username: String, name: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val cleanedUsername = username.trim().lowercase()
                if (ChatService.isUsernameTaken(cleanedUsername)) {
                    onResult(false)
                    return@launch
                }
                val success = ChatService.registerUser(cleanedUsername, name)
                if (success) {
                    prefs.edit()
                        .putString("chat_username", cleanedUsername)
                        .putString("chat_name", name)
                        .apply()
                    _chatUsername.value = cleanedUsername
                    _chatName.value = name
                    fetchAllUsers()
                    onResult(true)
                } else {
                    onResult(false)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to register chat user", e)
                onResult(false)
            }
        }
    }

    fun fetchAllUsers() {
        viewModelScope.launch {
            try {
                val users = ChatService.getAllUsers()
                _allUsers.value = users
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to fetch all users", e)
            }
        }
    }

    fun addChatReaction(channelId: String, messageId: String, username: String, emoji: String) {
        viewModelScope.launch {
            try {
                val success = ChatService.addReaction(channelId, messageId, username, emoji)
                if (success) {
                    val msgs = ChatService.getMessages(channelId)
                    _chatMessages.value = msgs
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to add reaction", e)
            }
        }
    }

    fun removeChatReaction(channelId: String, messageId: String, username: String) {
        viewModelScope.launch {
            try {
                val success = ChatService.removeReaction(channelId, messageId, username)
                if (success) {
                    val msgs = ChatService.getMessages(channelId)
                    _chatMessages.value = msgs
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to remove reaction", e)
            }
        }
    }

    fun setChatEnabledOnline(enabled: Boolean) {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري تحديث حالة الدردشة أونلاين..."
            val success = ChatService.setChatEnabled(enabled)
            if (success) {
                _isChatEnabledOnline.value = enabled
                _developerActionStatus.value = "تم تحديث حالة الدردشة أونلاين بنجاح!"
            } else {
                _developerActionStatus.value = "خطأ: فشل تحديث حالة الدردشة أونلاين!"
            }
        }
    }

    fun wipeAllChatMessages() {
        viewModelScope.launch {
            _developerActionStatus.value = "جاري حذف جميع رسائل الدردشة..."
            val success = ChatService.deleteAllMessages()
            if (success) {
                _chatMessages.value = emptyList()
                _developerActionStatus.value = "تم حذف جميع الرسائل من قاعدة البيانات بنجاح!"
            } else {
                _developerActionStatus.value = "خطأ: فشل حذف رسائل قاعدة البيانات!"
            }
        }
    }

    fun clearDeveloperStatus() {
        _developerActionStatus.value = null
    }

    fun fetchMessagedUsers() {
        val myUser = _chatUsername.value ?: return
        viewModelScope.launch {
            try {
                val allChats = ChatService.getAllChats()
                val usersMap = ChatService.getAllUsers()
                val tempMessaged = mutableSetOf<String>()
                if (allChats != null) {
                    for ((chanId, messagesMap) in allChats) {
                        if (chanId.startsWith("private_")) {
                            val parts = chanId.split("_")
                            if (parts.size >= 3) {
                                val userA = parts[1]
                                val userB = parts[2]
                                if (messagesMap.isNotEmpty()) {
                                    if (userA == myUser) {
                                        tempMessaged.add(userB)
                                    } else if (userB == myUser) {
                                        tempMessaged.add(userA)
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Fetch blocked list as well
                val blocked = ChatService.getBlockedUsers(myUser)
                _blockedUsers.value = blocked

                // Filter out any user who is blocked from the messaged list if desired,
                // but let's keep them and let the UI/user handle blocks/unblocks.
                val list = tempMessaged.map { username ->
                    val displayName = usersMap[username] ?: username
                    Pair(username, displayName)
                }
                _messagedUsers.value = list
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to fetch messaged users", e)
            }
        }
    }

    fun updateChatUser(newUsername: String, newName: String, onResult: (Boolean, String?) -> Unit) {
        val oldUsername = _chatUsername.value ?: return
        viewModelScope.launch {
            try {
                val cleanedNewUsername = newUsername.trim().lowercase()
                val cleanedNewName = newName.trim()
                
                if (cleanedNewUsername.isEmpty() || cleanedNewName.isEmpty()) {
                    onResult(false, "يجب ملء جميع الحقول!")
                    return@launch
                }
                
                // If they changed the username, check if the new one is taken
                if (cleanedNewUsername != oldUsername) {
                    if (ChatService.isUsernameTaken(cleanedNewUsername)) {
                        onResult(false, "اسم المستخدم هذا مستخدم بالفعل من قبل شخص آخر!")
                        return@launch
                    }
                }
                
                // Save new user info
                val success = ChatService.registerUser(cleanedNewUsername, cleanedNewName)
                if (success) {
                    // If username changed, delete the old database node
                    if (cleanedNewUsername != oldUsername) {
                        val deleteUrl = "https://loop-7e3d9-default-rtdb.firebaseio.com/users/$oldUsername.json"
                        try {
                            val request = okhttp3.Request.Builder().url(deleteUrl).delete().build()
                            ChatService.client.newCall(request).execute().close()
                        } catch (e: Exception) {
                            Log.e("MainViewModel", "Failed to delete old user node", e)
                        }
                    }
                    
                    // Save in preferences
                    prefs.edit()
                        .putString("chat_username", cleanedNewUsername)
                        .putString("chat_name", cleanedNewName)
                        .apply()
                        
                    _chatUsername.value = cleanedNewUsername
                    _chatName.value = cleanedNewName
                    fetchAllUsers()
                    fetchMessagedUsers()
                    onResult(true, null)
                } else {
                    onResult(false, "حدث خطأ أثناء حفظ التحديثات.")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to update chat user", e)
                onResult(false, "حدث خطأ غير متوقع.")
            }
        }
    }

    fun blockUser(otherUsername: String) {
        val myUser = _chatUsername.value ?: return
        viewModelScope.launch {
            try {
                val success = ChatService.blockUser(myUser, otherUsername)
                if (success) {
                    fetchMessagedUsers()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to block user", e)
            }
        }
    }

    fun unblockUser(otherUsername: String) {
        val myUser = _chatUsername.value ?: return
        viewModelScope.launch {
            try {
                val success = ChatService.unblockUser(myUser, otherUsername)
                if (success) {
                    fetchMessagedUsers()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to unblock user", e)
            }
        }
    }

    fun deleteConversation(channelId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val success = ChatService.deleteConversation(channelId)
                if (success) {
                    fetchMessagedUsers()
                    onResult(true)
                } else {
                    onResult(false)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to delete conversation", e)
                onResult(false)
            }
        }
    }

    // --- Notification System ---

    fun fetchNotifications() {
        viewModelScope.launch {
            try {
                FirebaseFirestore.getInstance().collection("notifications")
                    .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .addSnapshotListener { snapshot, e ->
                        if (e != null) {
                            Log.e("MainViewModel", "Listen failed.", e)
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            val list = snapshot.toObjects(AppNotification::class.java)
                            _notifications.value = list
                        }
                    }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching notifications: ${e.message}")
            }
        }
    }

    fun sendAppNotification(title: String, body: String, imageUrl: String?) {
        viewModelScope.launch {
            try {
                val id = System.currentTimeMillis().toString()
                val notification = AppNotification(
                    id = id,
                    title = title,
                    body = body,
                    imageUrl = if (imageUrl.isNullOrBlank()) null else imageUrl,
                    timestamp = System.currentTimeMillis()
                )
                FirebaseFirestore.getInstance().collection("notifications").document(id).set(notification).await()
                Log.d("MainViewModel", "Notification saved to Firestore: $id")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error sending notification: ${e.message}")
            }
        }
    }

    fun deleteAppNotification(id: String) {
        viewModelScope.launch {
            try {
                FirebaseFirestore.getInstance().collection("notifications").document(id).delete().await()
                Log.d("MainViewModel", "Notification deleted from Firestore: $id")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error deleting notification: ${e.message}")
            }
        }
    }

    // --- Secure Sharing System ---

    fun generateShareLink(title: String, streamUrl: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val shareId = java.util.UUID.randomUUID().toString().substring(0, 8)
                val expiry = System.currentTimeMillis() + (10 * 60 * 60 * 1000) // 10 hours
                
                val shareData = mapOf(
                    "id" to shareId,
                    "title" to title,
                    "url" to streamUrl,
                    "expiry" to expiry,
                    "app" to "25-live"
                )
                
                val success = FirebaseService.saveShareLink(shareId, shareData)
                
                if (success) {
                    val finalLink = "https://25-live.app/play?id=$shareId"
                    onResult(finalLink)
                } else {
                    onResult("")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error generating share link: ${e.message}")
                onResult("")
            }
        }
    }

    fun handleSharedLink(shareId: String, onPlay: (String, String) -> Unit) {
        viewModelScope.launch {
            try {
                val data = FirebaseService.getShareLink(shareId)
                if (data != null) {
                    val expiry = (data["expiry"] as? Number)?.toLong() ?: 0L
                    if (System.currentTimeMillis() < expiry) {
                        val url = data["url"] as? String ?: ""
                        val title = data["title"] as? String ?: "بث مشترك"
                        if (url.isNotEmpty()) {
                            onPlay(url, title)
                        }
                    } else {
                        Log.w("MainViewModel", "Share link expired")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error handling shared link: ${e.message}")
            }
        }
    }
}
