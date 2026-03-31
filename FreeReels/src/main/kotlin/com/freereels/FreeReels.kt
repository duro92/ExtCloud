package com.freereels

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FreeReels : MainAPI() {
    override var mainUrl = buildMainUrl()
    private val apiUrl = buildH5ApiBaseUrl()
    private val nativeApiUrl = buildNativeApiBaseUrl()
    override var name = "FreeReels😉"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "popular" to "Populer",
        "new" to "New",
        "coming_soon" to "Segera Hadir",
        "dubbing" to "Dubbing",
        "female" to "Perempuan",
        "male" to "Laki-Laki",
        "anime" to "Anime",
    )

    private val secureRandom = SecureRandom()
    private val cryptoKey = "2r36789f45q01ae5"
    private val nativeLoginSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv"
    private val authSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv&"
    private var deviceId = randomDeviceId()
    private val deviceBrand = "Redmi"
    private val deviceModel = "23090RA98G"
    private val deviceManufacturer = "Xiaomi"
    private val deviceProduct = "sky"
    private val deviceFingerprint = "Redmi/sky_global/sky:14/UKQ1.231003.002/V816.0.11.0.UMWMIXM:user/release-keys"
    private val sessionId = java.util.UUID.randomUUID().toString()
    private var h5Session: Session? = null
    private var nativeSession: Session? = null
    private val nativeCategories = listOf(
        NativeCategory("popular", "Populer", "993", 10000),
        NativeCategory("new", "New", "995", 10000),
        NativeCategory("coming_soon", "Segera Hadir", "1004", 10000, isComingSoon = true),
        NativeCategory("dubbing", "Dubbing", "1002", 10000),
        NativeCategory("female", "Perempuan", "994", 10000),
        NativeCategory("male", "Laki-Laki", "996", 10000),
        NativeCategory("anime", "Anime", "1005", 10001),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = nativeCategories.firstOrNull { it.key == request.data }
            ?: throw ErrorLoadingException("Kategori FreeReels tidak ditemukan")

        val categoryPage = getCategoryPage(category, page)
        val items = categoryPage.items
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = categoryPage.hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val candidates = LinkedHashMap<String, HomeItem>()
        val normalizedKeyword = keyword.lowercase()
        val suggestedTerms = buildList {
            add(keyword)
            addAll(
                getNativeKeywordSuggestions(keyword)
                    .mapNotNull { it.keyword?.trim() }
                    .filter { it.isNotBlank() }
                    .take(10)
            )
        }.distinct()
        val normalizedTerms = suggestedTerms.map { it.lowercase() }

        fun addItems(items: Iterable<HomeItem>) {
            items.forEach { item ->
                val key = item.key?.trim().orEmpty()
                if (key.isNotBlank()) candidates.putIfAbsent(key, item)
            }
        }

        nativeCategories.forEach { category ->
            addItems(getCategoryItems(category))
        }

        return candidates.values
            .mapNotNull { item ->
                val score = normalizedTerms.maxOfOrNull { term -> item.searchScore(term) } ?: 0
                if (score <= 0) null else score to item
            }
            .sortedWith(
                compareByDescending<Pair<Int, HomeItem>> { it.first }
                    .thenByDescending { it.second.title?.trim()?.equals(keyword, true) == true }
                    .thenByDescending { it.second.title?.trim()?.contains(normalizedKeyword, true) == true }
                    .thenByDescending { it.second.followCount ?: 0L }
                    .thenByDescending { it.second.episodeCount ?: 0 }
            )
            .mapNotNull { it.second.toSearchResult() }
            .take(40)
    }

    override suspend fun load(url: String): LoadResponse {
        val seriesKey = extractSeriesKey(url)
        if (seriesKey.isBlank()) throw ErrorLoadingException("Series key tidak ditemukan")

        val localTitle = getQueryParam(url, "title")
        val localPoster = getQueryParam(url, "poster")
        val localPlot = getQueryParam(url, "plot")
        val localTags = getQueryParam(url, "tags")
            ?.split("|")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

        val nativeItem = findNativeItemBySeriesKey(seriesKey)
        val detail = getNativeDramaInfo(seriesKey).data?.info
            ?: getDramaInfo(seriesKey).data?.info

        if (detail != null) {
            val title = localTitle
                ?: detail.name?.takeIf { it.isNotBlank() }
                ?: "FreeReels"

            val episodes = detail.episodeList.orEmpty()
                .sortedBy { it.index ?: Int.MAX_VALUE }
                .filter { it.hasPlayableSource() }
                .mapIndexed { index, episode ->
                    val episodeNumber = episode.index ?: index + 1
                    val episodeName = episode.name
                        ?.takeIf { it.isNotBlank() && !it.equals(title, true) }
                        ?: "Episode $episodeNumber"

                    newEpisode(
                        EpisodeLoadData(
                            seriesKey = seriesKey,
                            episodeId = episode.id,
                            episodeNumber = episodeNumber,
                            episodeName = episodeName,
                            h264Url = episode.externalAudioH264M3u8,
                            h265Url = episode.externalAudioH265M3u8,
                            m3u8Url = episode.m3u8Url,
                            videoUrl = episode.videoUrl,
                            subtitles = episode.subtitleList.orEmpty()
                        ).toJson()
                    ) {
                        name = episodeName
                        this.episode = episodeNumber
                        this.posterUrl = episode.cover
                    }
                }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(
                    title,
                    buildSeriesUrl(seriesKey, title, detail.cover ?: localPoster, localPlot ?: detail.desc, localTags ?: detail.allTags()),
                    TvType.AsianDrama,
                    episodes
                ) {
                    posterUrl = detail.cover ?: localPoster
                    plot = localPlot ?: detail.desc
                    tags = localTags ?: detail.allTags()
                    showStatus = detail.finishStatus.toShowStatus()
                }
            }
        }

        val fallbackEpisode = nativeItem?.episodeInfo
            ?.takeIf { it.hasPlayableSource() }
            ?: throw ErrorLoadingException("Detail FreeReels tidak ditemukan")
        val fallbackTitle = localTitle
            ?: nativeItem?.title?.takeIf { it.isNotBlank() }
            ?: fallbackEpisode.name?.takeIf { it.isNotBlank() }
            ?: "FreeReels"
        val fallbackPlot = localPlot ?: nativeItem?.desc
        val fallbackPoster = fallbackEpisode.cover ?: nativeItem?.cover ?: localPoster
        val fallbackTags = localTags ?: nativeItem?.allTags().orEmpty()
        val fallbackEpisodeNumber = fallbackEpisode.index ?: 1
        val fallbackEpisodeName = fallbackEpisode.name
            ?.takeIf { it.isNotBlank() && !it.equals(fallbackTitle, true) }
            ?: "Episode $fallbackEpisodeNumber"

        return newTvSeriesLoadResponse(
            fallbackTitle,
            buildSeriesUrl(seriesKey, fallbackTitle, fallbackPoster, fallbackPlot, fallbackTags),
            TvType.AsianDrama,
            listOf(
                newEpisode(
                    EpisodeLoadData(
                        seriesKey = seriesKey,
                        episodeId = fallbackEpisode.id,
                        episodeNumber = fallbackEpisodeNumber,
                        episodeName = fallbackEpisodeName,
                        h264Url = fallbackEpisode.externalAudioH264M3u8,
                        h265Url = fallbackEpisode.externalAudioH265M3u8,
                        m3u8Url = fallbackEpisode.m3u8Url,
                        videoUrl = fallbackEpisode.videoUrl,
                        subtitles = fallbackEpisode.subtitleList.orEmpty()
                    ).toJson()
                ) {
                    name = fallbackEpisodeName
                    episode = fallbackEpisodeNumber
                    posterUrl = fallbackPoster
                }
            )
        ) {
            posterUrl = fallbackPoster
            plot = fallbackPlot
            tags = fallbackTags
            showStatus = ShowStatus.Ongoing
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<EpisodeLoadData>(data)
        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
        )

        loadData.subtitles.orEmpty()
            .distinctBy { it.vtt ?: it.subtitle ?: "${it.language}:${it.displayName}" }
            .forEach { subtitle ->
                val subUrl = subtitle.vtt?.takeIf { it.isNotBlank() }
                    ?: subtitle.subtitle?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val lang = subtitle.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: subtitle.language
                    ?: "Unknown"
                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
            }

        var hasLinks = false
        val seen = LinkedHashSet<String>()

        suspend fun emit(label: String, url: String?) {
            val mediaUrl = url?.trim().orEmpty()
            if (mediaUrl.isBlank() || !seen.add(mediaUrl)) return

            hasLinks = true
            val linkType = if (mediaUrl.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback.invoke(
                newExtractorLink(
                    source = "$name $label",
                    name = "$name $label",
                    url = mediaUrl,
                    type = linkType
                ) {
                    this.headers = headers
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        emit("H264", loadData.h264Url)
        emit("H265", loadData.h265Url)

        if (hasLinks) return true

        val seriesKey = loadData.seriesKey ?: return false
        val episodeId = loadData.episodeId ?: return false
        val episode = (
            getNativeDramaInfo(seriesKey).data?.info?.episodeList
                ?: getDramaInfo(seriesKey).data?.info?.episodeList
                ?: emptyList()
            ).firstOrNull { it.id == episodeId } ?: return false

        emit("H264", episode.externalAudioH264M3u8)
        emit("H265", episode.externalAudioH265M3u8)

        episode.subtitleList.orEmpty()
            .distinctBy { it.vtt ?: it.subtitle ?: "${it.language}:${it.displayName}" }
            .forEach { subtitle ->
                val subUrl = subtitle.vtt?.takeIf { it.isNotBlank() }
                    ?: subtitle.subtitle?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val lang = subtitle.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: subtitle.language
                    ?: "Unknown"
                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
            }

        return hasLinks
    }

    private suspend fun findNativeItemBySeriesKey(seriesKey: String): HomeItem? {
        for (category in nativeCategories) {
            val match = getCategoryItems(category).firstOrNull { it.key == seriesKey }
            if (match != null) return match
        }
        return null
    }

    private suspend fun getCategoryItems(category: NativeCategory): List<HomeItem> {
        return if (category.isComingSoon) {
            getComingSoonItems()
        } else {
            getNativeModuleIndex(category).items
                .flatMap { it.items.orEmpty() }
        }
    }

    private suspend fun getCategoryPage(category: NativeCategory, page: Int): CategoryPage {
        if (category.isComingSoon) {
            val items = if (page == 1) getComingSoonItems() else emptyList()
            return CategoryPage(items, false)
        }

        val moduleIndex = getNativeModuleIndex(category)
        if (page <= 1) {
            return CategoryPage(
                items = moduleIndex.items.flatMap { it.items.orEmpty() },
                hasNext = moduleIndex.pageInfo?.hasMore == true
            )
        }

        val feedPages = moduleIndex.items
            .filter { !it.moduleKey.isNullOrBlank() && !it.items.isNullOrEmpty() }
            .mapNotNull { module ->
                fetchNativeFeedPage(
                    moduleKey = module.moduleKey,
                    page = page,
                    initialNext = moduleIndex.pageInfo?.next
                )
            }

        return CategoryPage(
            items = feedPages.flatMap { it.items.orEmpty() },
            hasNext = feedPages.any { it.pageInfo?.hasMore == true }
        )
    }

    private suspend fun getNativeModuleIndex(category: NativeCategory): ModuleIndexData {
        val query = linkedMapOf(
            "tab_key" to category.tabKey,
            "position_index" to category.positionIndex.toString(),
        )
        val body = nativeApiGet("/homepage/v2/tab/index", query)
        return tryParseJson<ModuleIndexResponse>(body)?.data
            ?: throw ErrorLoadingException("Respons kategori FreeReels tidak valid")
    }

    private suspend fun getComingSoonItems(): List<HomeItem> {
        val body = nativeApiGet("/coming-soon/list")
        return tryParseJson<ComingSoonResponse>(body)?.data?.items
            .orEmpty()
            .flatMap { it.items.orEmpty() }
    }

    private suspend fun getNativeFeedItems(moduleKey: String, next: String?): FeedResponse {
        val body = nativeApiPost(
            path = "/homepage/v2/tab/feed",
            body = FeedRequest(
                moduleKey = moduleKey,
                next = next.orEmpty()
            )
        )
        return tryParseJson<FeedResponse>(body)
            ?: throw ErrorLoadingException("Respons feed native FreeReels tidak valid")
    }

    private suspend fun fetchNativeFeedPage(
        moduleKey: String?,
        page: Int,
        initialNext: String?
    ): FeedData? {
        if (moduleKey.isNullOrBlank() || page <= 1) return null

        var next = initialNext
        var current: FeedData? = null
        repeat(page - 1) {
            if (next.isNullOrBlank()) return current
            current = getNativeFeedItems(moduleKey, next).data
            next = current?.pageInfo?.next
        }
        return current
    }

    private suspend fun getNativeKeywordSuggestions(keyword: String): List<SearchKeyword> {
        val body = nativeApiPost(
            path = "/search/keywords",
            body = SearchKeywordRequest(keyword)
        )
        return tryParseJson<SearchKeywordResponse>(body)?.data?.keywords.orEmpty()
    }

    private suspend fun getPrimaryTab(): HomeTab {
        return getTabList().data?.list?.firstOrNull()
            ?: throw ErrorLoadingException("Tab FreeReels tidak ditemukan")
    }

    private suspend fun getPopularItemsForPage(page: Int, moduleResponse: ModuleIndexData): List<HomeItem> {
        val recommendItems = moduleResponse.items
            .firstOrNull { it.type.equals("recommend", true) }
            ?.items
            .orEmpty()
        if (page <= 1) return recommendItems

        return fetchFeedPage(
            moduleKey = moduleResponse.getRecommendModuleKey(),
            page = page,
            initialNext = moduleResponse.pageInfo?.next
        )?.items.orEmpty()
    }

    private suspend fun fetchFeedPage(
        moduleKey: String?,
        page: Int,
        initialNext: String?
    ): FeedData? {
        if (moduleKey.isNullOrBlank() || page <= 1) return null

        var next = initialNext
        var current: FeedData? = null
        repeat(page - 1) {
            if (next.isNullOrBlank()) return current
            current = getFeedItems(moduleKey, next).data
            next = current?.pageInfo?.next
        }
        return current
    }

    private suspend fun getTabList(): TabListResponse {
        val body = apiGet("/h5-api/homepage/v2/tab/list")
        return tryParseJson<TabListResponse>(body)
            ?: throw ErrorLoadingException("Respons tab FreeReels tidak valid")
    }

    private suspend fun getModuleIndex(tab: HomeTab): ModuleIndexData {
        val query = linkedMapOf(
            "tab_key" to tab.tabKey,
            "position_index" to tab.positionIndex?.toString(),
            "first" to "",
        )
        val body = apiGet("/h5-api/homepage/v2/tab/index", query)
        return tryParseJson<ModuleIndexResponse>(body)?.data
            ?: throw ErrorLoadingException("Respons homepage FreeReels tidak valid")
    }

    private suspend fun getFeedItems(moduleKey: String, next: String?): FeedResponse {
        val body = apiPost(
            path = "/h5-api/homepage/v2/tab/feed",
            body = FeedRequest(
                moduleKey = moduleKey,
                next = next.orEmpty()
            )
        )
        return tryParseJson<FeedResponse>(body)
            ?: throw ErrorLoadingException("Respons feed FreeReels tidak valid")
    }

    private suspend fun getDramaInfo(seriesKey: String): DramaInfoResponse {
        val query = mapOf("series_id" to seriesKey)
        val body = apiGet("/h5-api/drama/info", query)
        return tryParseJson<DramaInfoResponse>(body)
            ?: throw ErrorLoadingException("Respons detail FreeReels tidak valid")
    }

    private suspend fun getNativeDramaInfo(seriesKey: String): DramaInfoResponse {
        val query = linkedMapOf(
            "series_id" to seriesKey,
        )
        val body = nativeApiGet("/drama/info_v2", query)
        return tryParseJson<DramaInfoResponse>(body)
            ?: throw ErrorLoadingException("Respons detail native FreeReels tidak valid")
    }

    private suspend fun getKeywordSuggestions(keyword: String): List<SearchKeyword> {
        val body = apiPost(
            path = "/h5-api/search/keywords",
            body = SearchKeywordRequest(keyword)
        )
        return tryParseJson<SearchKeywordResponse>(body)?.data?.keywords.orEmpty()
    }

    private suspend fun apiGet(path: String, query: Map<String, String?> = emptyMap()): String {
        val url = buildApiUrl(path, query)
        return apiRequest("GET", url, null)
    }

    private suspend fun apiPost(path: String, body: Any): String {
        val url = buildApiUrl(path)
        return apiRequest("POST", url, body)
    }

    private suspend fun apiRequest(
        method: String,
        url: String,
        body: Any?,
        allowRetry: Boolean = true
    ): String {
        return runCatching {
            executeRequest(method, url, body)
        }.getOrElse { error ->
            if (!allowRetry) throw error
            h5Session = null
            executeRequest(method, url, body)
        }
    }

    private suspend fun executeRequest(method: String, url: String, body: Any?): String {
        val requestHeaders = buildH5Headers(authenticated = body !is AnonymousLoginRequest)
        val response = when (method.uppercase()) {
            "POST" -> {
                val payload = body?.let { encrypt(it.toJson()) }.orEmpty()
                app.post(
                    url,
                    headers = requestHeaders,
                    requestBody = payload.toRequestBody("application/json".toMediaType())
                )
            }

            else -> app.get(url, headers = requestHeaders)
        }

        val text = response.text.trim()
        if (response.code !in 200..299) {
            throw ErrorLoadingException("HTTP ${response.code}: ${text.take(160)}")
        }
        return decryptIfNeeded(text)
    }

    private suspend fun ensureSession(): Session {
        h5Session?.let { return it }

        val url = buildApiUrl("/h5-api/anonymous/login")
        val payload = AnonymousLoginRequest(deviceId)
        val response = app.post(
            url,
            headers = buildH5Headers(authenticated = false),
            requestBody = encrypt(payload.toJson()).toRequestBody("application/json".toMediaType())
        )
        val body = decryptIfNeeded(response.text.trim())
        val parsed = tryParseJson<AnonymousLoginResponse>(body)
            ?: throw ErrorLoadingException("Login guest FreeReels gagal")
        val data = parsed.data
        val authKey = data?.authKey?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("auth_key kosong")
        val authSecret = data.authSecret?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("auth_secret kosong")

        return Session(authKey, authSecret).also { h5Session = it }
    }

    private suspend fun buildH5Headers(authenticated: Boolean): Map<String, String> {
        val sessionData = if (authenticated) ensureSession() else null
        val authKey = sessionData?.authKey ?: "undefined"
        val authSecret = sessionData?.authSecret ?: "undefined"
        val ts = System.currentTimeMillis().toString()

        return linkedMapOf(
            "app-name" to "com.dramawave.h5",
            "app-version" to "1.2.20",
            "device-hash" to deviceId,
            "device-id" to deviceId,
            "device" to "h5",
            "content-type" to "application/json",
            "language" to "en",
            "language_code" to "en-US",
            "country_code" to "US",
            "authorization" to "oauth_signature=${md5("$authSalt$authSecret")},oauth_token=$authKey,ts=$ts"
        )
    }

    private suspend fun nativeApiGet(path: String, query: Map<String, String?> = emptyMap()): String {
        val url = buildUrl(nativeApiUrl, path, query)
        return nativeApiRequest("GET", url, null)
    }

    private suspend fun nativeApiPost(path: String, body: Any): String {
        val url = buildUrl(nativeApiUrl, path)
        return nativeApiRequest("POST", url, body)
    }

    private suspend fun nativeApiRequest(
        method: String,
        url: String,
        body: Any?,
        allowRetry: Boolean = true
    ): String {
        return runCatching {
            executeNativeRequest(method, url, body)
        }.getOrElse { error ->
            if (!allowRetry) throw error
            nativeSession = null
            executeNativeRequest(method, url, body)
        }
    }

    private suspend fun executeNativeRequest(method: String, url: String, body: Any?): String {
        val authenticated = !url.contains("/anonymous/login")
        val requestHeaders = buildNativeHeaders(authenticated)
        val response = when (method.uppercase()) {
            "POST" -> app.post(
                url,
                headers = requestHeaders,
                requestBody = body?.toJson().orEmpty().toRequestBody("application/json".toMediaType())
            )

            else -> app.get(url, headers = requestHeaders)
        }

        val text = response.text.trim()
        if (response.code !in 200..299) {
            throw ErrorLoadingException("HTTP ${response.code}: ${text.take(160)}")
        }
        return text
    }

    private suspend fun ensureNativeSession(): Session {
        nativeSession?.let { return it }

        val response = app.post(
            buildUrl(nativeApiUrl, "/anonymous/login"),
            headers = buildNativeHeaders(authenticated = false),
            requestBody = NativeAnonymousLoginRequest(
                deviceId = deviceId,
                deviceName = "$deviceBrand $deviceModel",
                sign = md5("$nativeLoginSalt$deviceId")
            ).toJson().toRequestBody("application/json".toMediaType())
        )
        val body = response.text.trim()
        val parsed = tryParseJson<AnonymousLoginResponse>(body)
            ?: throw ErrorLoadingException("Login native FreeReels gagal")
        val data = parsed.data
        val authKey = data?.authKey?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("auth_key native kosong")
        val authSecret = data.authSecret?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("auth_secret native kosong")

        return Session(authKey, authSecret).also { nativeSession = it }
    }

    private suspend fun buildNativeHeaders(authenticated: Boolean): Map<String, String> {
        val sessionData = if (authenticated) ensureNativeSession() else null
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "OpCountryCode" to "ID",
            "X-AppEngine-Country" to "ID",
            "app-language" to "id",
            "prefer_country" to "ID",
            "locale" to "id-ID",
            "language" to "id-ID",
            "country" to "ID",
            "X-Timezone" to "Asia/Jakarta",
            "timezone" to "7",
            "X-Timezone-offset" to "7",
            "network-type" to "WIFI",
            "screen-width" to "411",
            "screen-height" to "891",
            "is-mainland" to "false",
            "device-memory" to "8.00",
            "device-country" to "ID",
            "device-language" to "id-ID",
            "x-device-model" to deviceModel,
            "x-device-manufacturer" to deviceManufacturer,
            "x-device-brand" to deviceBrand,
            "x-device-product" to deviceProduct,
            "x-device-fingerprint" to deviceFingerprint,
            "session-id" to sessionId,
            "app-name" to "com.freereels.app",
            "app-version" to "2.2.40",
            "device-id" to deviceId,
            "device-version" to "34",
            "device" to "android",
        )

        if (authenticated) {
            headers["Authorization"] =
                "oauth_signature=${md5("$authSalt${sessionData?.authSecret.orEmpty()}")},oauth_token=${sessionData?.authKey.orEmpty()},ts=${System.currentTimeMillis()}"
        }

        return headers
    }

    private fun buildApiUrl(path: String, query: Map<String, String?> = emptyMap()): String {
        return buildUrl(apiUrl, path, query)
    }

    private fun buildUrl(baseUrl: String, path: String, query: Map<String, String?> = emptyMap()): String {
        val trimmedPath = if (path.startsWith("/")) path else "/$path"
        val normalizedQuery = query
            .filterValues { it != null }
            .map { "${it.key}=${encodeQuery(it.value.orEmpty())}" }
            .joinToString("&")

        return if (normalizedQuery.isBlank()) {
            "$baseUrl$trimmedPath"
        } else {
            "$baseUrl$trimmedPath?$normalizedQuery"
        }
    }

    private fun decryptIfNeeded(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("{") || text.startsWith("[")) return text
        return runCatching { decrypt(text) }.getOrElse { text }
    }

    private fun encrypt(plainText: String): String {
        val iv = ByteArray(16).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(cryptoKey.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv)
        )
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encryptedText: String): String {
        val decoded = Base64.decode(encryptedText, Base64.DEFAULT)
        val iv = decoded.copyOfRange(0, 16)
        val payload = decoded.copyOfRange(16, decoded.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(cryptoKey.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(payload).toString(Charsets.UTF_8)
    }

    private fun md5(value: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun randomDeviceId(): String {
        val chars = "abcdef0123456789"
        return buildString {
            repeat(16) {
                append(chars[secureRandom.nextInt(chars.length)])
            }
        }
    }

    private fun buildMainUrl(): String {
        val codes = intArrayOf(
            104, 116, 116, 112, 115, 58, 47, 47,
            102, 114, 101, 101, 45, 114, 101, 101, 108, 115, 46, 99, 111, 109
        )
        return decodeCodes(codes)
    }

    private fun buildH5ApiBaseUrl(): String {
        val codes = intArrayOf(
            104, 116, 116, 112, 115, 58, 47, 47,
            97, 112, 105, 46, 109, 121, 100, 114, 97, 109, 97, 119, 97, 118, 101, 46, 99, 111, 109
        )
        return decodeCodes(codes)
    }

    private fun buildNativeApiBaseUrl(): String {
        val codes = intArrayOf(
            104, 116, 116, 112, 115, 58, 47, 47,
            97, 112, 105, 118, 50, 46, 102, 114, 101, 101, 45, 114, 101, 101, 108, 115, 46, 99, 111, 109,
            47, 102, 114, 118, 50, 45, 97, 112, 105
        )
        return decodeCodes(codes)
    }

    private fun decodeCodes(codes: IntArray): String {
        return buildString {
            codes.forEach { append(it.toChar()) }
        }
    }

    private fun HomeItem.toSearchResult(): SearchResponse? {
        val seriesKey = key?.trim().orEmpty()
        val title = title?.trim().orEmpty()
        if (seriesKey.isBlank() || title.isBlank()) return null

        return newTvSeriesSearchResponse(
            title,
            buildSeriesUrl(seriesKey, title, cover, desc, allTags()),
            TvType.AsianDrama
        ) {
            posterUrl = cover
        }
    }

    private fun HomeItem.allTags(): List<String> {
        return (contentTags.orEmpty() + seriesTag.orEmpty())
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun DramaInfo.allTags(): List<String> {
        return (contentTags.orEmpty() + seriesTag.orEmpty())
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun HomeItem.searchScore(query: String): Int {
        val titleText = title?.trim().orEmpty().lowercase()
        val descText = desc?.trim().orEmpty().lowercase()
        val tagsText = allTags().joinToString(" ").lowercase()
        val joined = listOf(titleText, descText, tagsText).joinToString(" ")
        if (joined.isBlank()) return 0

        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            titleText == query -> 120
            titleText.contains(query) -> 100
            tokens.all { titleText.contains(it) } -> 90
            joined.contains(query) -> 70
            tokens.all { joined.contains(it) } -> 50
            else -> 0
        }
    }

    private fun ModuleIndexData.getRecommendModuleKey(): String? {
        return items.firstOrNull { it.type.equals("recommend", true) }?.moduleKey
    }

    private fun buildSeriesUrl(
        seriesKey: String,
        title: String? = null,
        poster: String? = null,
        plot: String? = null,
        tags: List<String>? = null,
    ): String {
        val params = mutableListOf<String>()
        params.add("id=${encodeQuery(seriesKey)}")
        title?.takeIf { it.isNotBlank() }?.let { params.add("title=${encodeQuery(it)}") }
        poster?.takeIf { it.isNotBlank() }?.let { params.add("poster=${encodeQuery(it)}") }
        plot?.takeIf { it.isNotBlank() }?.let { params.add("plot=${encodeQuery(it)}") }
        tags?.takeIf { it.isNotEmpty() }?.let { params.add("tags=${encodeQuery(it.joinToString("|"))}") }
        return "$mainUrl/detail?${params.joinToString("&")}"
    }

    private fun extractSeriesKey(url: String): String {
        return getQueryParam(url, "id").orEmpty().ifBlank {
            getQueryParam(url, "series_key").orEmpty()
        }.ifBlank {
            url.substringAfter("series/").substringBefore("?")
        }.ifBlank {
            url.substringAfter("freereels://").substringBefore("?").substringBefore("/")
        }.ifBlank {
            url.substringAfterLast("/").substringBefore("?")
        }
    }

    private fun encodeQuery(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun getQueryParam(url: String, key: String): String? {
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return null

        val value = query.split("&")
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: value
    }

    private fun Int?.toShowStatus(): ShowStatus? {
        return when (this) {
            1 -> ShowStatus.Completed
            0 -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun DramaEpisode.hasPlayableSource(): Boolean {
        return !externalAudioH264M3u8.isNullOrBlank()
            || !externalAudioH265M3u8.isNullOrBlank()
            || !m3u8Url.isNullOrBlank()
            || !videoUrl.isNullOrBlank()
    }

    data class Session(
        val authKey: String,
        val authSecret: String,
    )

    data class AnonymousLoginRequest(
        @JsonProperty("device_id") val deviceId: String,
    )

    data class NativeAnonymousLoginRequest(
        @JsonProperty("device_id") val deviceId: String,
        @JsonProperty("device_name") val deviceName: String,
        @JsonProperty("sign") val sign: String,
    )

    data class AnonymousLoginResponse(
        @JsonProperty("data") val data: AuthData? = null,
    )

    data class AuthData(
        @JsonProperty("auth_key") val authKey: String? = null,
        @JsonProperty("auth_secret") val authSecret: String? = null,
    )

    data class TabListResponse(
        @JsonProperty("data") val data: TabListData? = null,
    )

    data class TabListData(
        @JsonProperty("list") val list: List<HomeTab>? = null,
    )

    data class HomeTab(
        @JsonProperty("tab_key") val tabKey: String? = null,
        @JsonProperty("position_index") val positionIndex: Int? = null,
        @JsonProperty("business_name") val businessName: String? = null,
    )

    data class ModuleIndexResponse(
        @JsonProperty("data") val data: ModuleIndexData? = null,
    )

    data class ComingSoonResponse(
        @JsonProperty("data") val data: ComingSoonData? = null,
    )

    data class ComingSoonData(
        @JsonProperty("items") val items: List<HomeModule>? = null,
    )

    data class ModuleIndexData(
        @JsonProperty("items") val items: List<HomeModule> = emptyList(),
        @JsonProperty("page_info") val pageInfo: PageInfo? = null,
    )

    data class HomeModule(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("module_key") val moduleKey: String? = null,
        @JsonProperty("items") val items: List<HomeItem>? = null,
    )

    data class FeedRequest(
        @JsonProperty("module_key") val moduleKey: String,
        @JsonProperty("next") val next: String,
    )

    data class FeedResponse(
        @JsonProperty("data") val data: FeedData? = null,
    )

    data class SearchKeywordRequest(
        @JsonProperty("keyword") val keyword: String,
    )

    data class SearchKeywordResponse(
        @JsonProperty("data") val data: SearchKeywordData? = null,
    )

    data class SearchKeywordData(
        @JsonProperty("keywords") val keywords: List<SearchKeyword>? = null,
    )

    data class SearchKeyword(
        @JsonProperty("keyword") val keyword: String? = null,
        @JsonProperty("highlight") val highlight: String? = null,
    )

    data class FeedData(
        @JsonProperty("items") val items: List<HomeItem>? = null,
        @JsonProperty("page_info") val pageInfo: PageInfo? = null,
    )

    data class PageInfo(
        @JsonProperty("next") val next: String? = null,
        @JsonProperty("has_more") val hasMore: Boolean? = null,
    )

    data class HomeItem(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("desc") val desc: String? = null,
        @JsonProperty("series_tag") val seriesTag: List<String?>? = null,
        @JsonProperty("content_tags") val contentTags: List<String?>? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
        @JsonProperty("follow_count") val followCount: Long? = null,
        @JsonProperty("episode_info") val episodeInfo: DramaEpisode? = null,
    )

    data class DramaInfoResponse(
        @JsonProperty("data") val data: DramaInfoData? = null,
    )

    data class DramaInfoData(
        @JsonProperty("info") val info: DramaInfo? = null,
    )

    data class DramaInfo(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("desc") val desc: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
        @JsonProperty("view_count") val viewCount: Long? = null,
        @JsonProperty("follow_count") val followCount: Long? = null,
        @JsonProperty("finish_status") val finishStatus: Int? = null,
        @JsonProperty("content_tags") val contentTags: List<String?>? = null,
        @JsonProperty("series_tag") val seriesTag: List<String?>? = null,
        @JsonProperty("episode_list") val episodeList: List<DramaEpisode>? = null,
    )

    data class DramaEpisode(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("index") val index: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("video_url") val videoUrl: String? = null,
        @JsonProperty("m3u8_url") val m3u8Url: String? = null,
        @JsonProperty("external_audio_h264_m3u8") val externalAudioH264M3u8: String? = null,
        @JsonProperty("external_audio_h265_m3u8") val externalAudioH265M3u8: String? = null,
        @JsonProperty("subtitle_list") val subtitleList: List<DramaSubtitle>? = null,
        @JsonProperty("unlock") val unlock: Boolean? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("episode_price") val episodePrice: Int? = null,
        @JsonProperty("video_type") val videoType: String? = null,
    )

    data class DramaSubtitle(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("subtitle") val subtitle: String? = null,
        @JsonProperty("vtt") val vtt: String? = null,
        @JsonProperty("display_name") val displayName: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class EpisodeLoadData(
        @JsonProperty("seriesKey") val seriesKey: String? = null,
        @JsonProperty("episodeId") val episodeId: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("episodeName") val episodeName: String? = null,
        @JsonProperty("h264Url") val h264Url: String? = null,
        @JsonProperty("h265Url") val h265Url: String? = null,
        @JsonProperty("m3u8Url") val m3u8Url: String? = null,
        @JsonProperty("videoUrl") val videoUrl: String? = null,
        @JsonProperty("subtitles") val subtitles: List<DramaSubtitle>? = null,
    )

    data class NativeCategory(
        val key: String,
        val title: String,
        val tabKey: String,
        val positionIndex: Int,
        val isComingSoon: Boolean = false,
    )

    data class CategoryPage(
        val items: List<HomeItem>,
        val hasNext: Boolean,
    )

}
