package com.MovieBox

import android.annotation.SuppressLint
import android.net.Uri
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

class MovieBoxProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    // User-facing site URL (moviebox.ph). Requests are made to `apiUrl`.
    override var mainUrl = "https://moviebox.ph"
    override var name = "Moviebox🤕"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiUrl = "https://h5-api.aoneroom.com"
    private val apiHostParam = "moviebox.ph"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

    private fun apiHeaders(referer: String = "$mainUrl/") = mapOf(
        "accept" to "application/json",
        "accept-language" to "en-US,en;q=0.5",
        "user-agent" to userAgent,
        "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
        "referer" to referer,
    )

    private fun downloadHeaders() = mapOf(
        "accept" to "*/*",
        "user-agent" to userAgent,
        "origin" to "https://videodownloader.site",
        "referer" to "https://videodownloader.site/",
    )

    private val secretKeyDefault = base64Decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")
    private val secretKeyAlt = base64Decode("WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ==")

        private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    private fun reverseString(input: String): String = input.reversed()

    private fun generateXClientToken(hardcodedTimestamp: Long? = null): String {
        val timestamp = (hardcodedTimestamp ?: System.currentTimeMillis()).toString()
        val reversed = reverseString(timestamp)
        val hash = md5(reversed.toByteArray())
        return "$timestamp,$hash"
    }

    @SuppressLint("UseKtx")
    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val parsed = Uri.parse(url)
        val path = parsed.path ?: ""
        
        // Build query string with sorted parameters (if any)
        val query = if (parsed.queryParameterNames.isNotEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key ->
                parsed.getQueryParameters(key).joinToString("&") { value ->
                    "$key=$value"  // Don't URL encode here - Python doesn't do it
                }
            }
        } else ""
        
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n" +
                "${accept ?: ""}\n" +
                "${contentType ?: ""}\n" +
                "$bodyLength\n" +
                "$timestamp\n" +
                "$bodyHash\n" +
                canonicalUrl
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        useAltKey: Boolean = false,
        hardcodedTimestamp: Long? = null
    ): String {
        val timestamp = hardcodedTimestamp ?: System.currentTimeMillis()
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secret = if (useAltKey) secretKeyAlt else secretKeyDefault
        val secretBytes = base64DecodeArray(secret)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = base64Encode(signature)

        return "$timestamp|2|$signatureB64"
    }

    override val mainPage = mainPageOf(
        "home" to "Home",
        "trending" to "Most Trending",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        val mapper = jacksonObjectMapper()

        fun toSearchResponses(items: JsonNode): List<SearchResponse> {
            return items.mapNotNull { item ->
                val subjectId =
                    item["subjectId"]?.asText()
                        ?: item["subject"]?.get("subjectId")?.asText()
                        ?: return@mapNotNull null
                val detailPath =
                    item["detailPath"]?.asText()
                        ?: item["subject"]?.get("detailPath")?.asText()
                        ?: return@mapNotNull null
                val title = item["title"]?.asText() ?: item["subject"]?.get("title")?.asText() ?: return@mapNotNull null

                val posterUrl =
                    item["cover"]?.get("url")?.asText()
                        ?: item["image"]?.get("url")?.asText()
                        ?: item["subject"]?.get("cover")?.get("url")?.asText()

                val subjectType = item["subjectType"]?.asInt() ?: item["subject"]?.get("subjectType")?.asInt() ?: 1
                val type = if (subjectType == 2) TvType.TvSeries else TvType.Movie

                val rating = item["imdbRatingValue"]?.asText() ?: item["subject"]?.get("imdbRatingValue")?.asText()

                newMovieSearchResponse(
                    name = title.substringBefore("["),
                    url = "$mainUrl/detail/$detailPath?id=$subjectId",
                    type = type
                ) {
                    this.posterUrl = posterUrl
                    this.score = Score.from10(rating)
                }
            }
        }

        suspend fun fetchHomeOperatingList(): JsonNode? {
            val url = "$apiUrl/wefeed-h5api-bff/home?host=$apiHostParam"
            val response = app.get(url, headers = apiHeaders())
            val root = mapper.readTree(response.text)
            return root["data"]?.get("operatingList")
        }

        return when (request.data) {
            "home" -> {
                if (page != 1) return newHomePageResponse(emptyList())

                val operatingList = fetchHomeOperatingList() ?: return newHomePageResponse(emptyList())

                val featured = operatingList.firstOrNull { op ->
                    op["type"]?.asText()?.equals("BANNER", ignoreCase = true) == true
                }?.get("banner")?.get("items")
                    ?.takeIf { it.isArray && it.size() > 0 }
                    ?.let { items ->
                        val results = toSearchResponses(items).take(15)
                        if (results.isEmpty()) null else HomePageList("Featured", results)
                    }

                val sections = operatingList.mapNotNull { op ->
                    if (op["type"]?.asText()?.equals("SUBJECTS_MOVIE", ignoreCase = true) != true) return@mapNotNull null
                    val title = op["title"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val subjects = op["subjects"] ?: return@mapNotNull null
                    if (!subjects.isArray || subjects.size() == 0) return@mapNotNull null
                    val results = toSearchResponses(subjects)
                    if (results.isEmpty()) return@mapNotNull null
                    HomePageList(title, results)
                }

                val lists = buildList {
                    if (featured != null) add(featured)
                    addAll(sections)
                }

                newHomePageResponse(lists)
            }

            "trending" -> {
                val apiPage = (page - 1).coerceAtLeast(0)
                val url = "$apiUrl/wefeed-h5api-bff/subject/trending?page=$apiPage&perPage=20"
                val response = app.get(url, headers = apiHeaders())
                val root = mapper.readTree(response.text)
                val data = root["data"] ?: return newHomePageResponse(emptyList())
                val items = data["subjectList"] ?: return newHomePageResponse(emptyList())
                val pager = data["pager"]
                val hasNext = pager?.get("hasMore")?.asBoolean() == true

                val results = toSearchResponses(items)
                newHomePageResponse(HomePageList(request.name, results), hasNext = hasNext)
            }

            else -> newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/wefeed-h5api-bff/subject/search"
        val jsonBody =
            """{"keyword":"${query.replace("\"", "\\\"")}","page":1,"perPage":24,"subjectType":0}"""
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val response = app.post(url, headers = apiHeaders(), requestBody = requestBody)

        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(response.text)
        val items = root["data"]?.get("items") ?: return emptyList()

        return items.mapNotNull { item ->
            val subjectId = item["subjectId"]?.asText() ?: return@mapNotNull null
            val detailPath = item["detailPath"]?.asText() ?: return@mapNotNull null
            val title = item["title"]?.asText() ?: return@mapNotNull null
            val posterUrl = item["cover"]?.get("url")?.asText()
            val subjectType = item["subjectType"]?.asInt() ?: 1
            val type = if (subjectType == 2) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(
                name = title.substringBefore("["),
                url = "$mainUrl/detail/$detailPath?id=$subjectId",
                type = type
            ) {
                this.posterUrl = posterUrl
                this.score = Score.from10(item["imdbRatingValue"]?.asText())
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val parsed = Uri.parse(url)

        val detailPath = parsed.getQueryParameter("detailPath")
            ?: run {
                val segs = parsed.pathSegments
                val detailIndex = segs.indexOf("detail")
                when {
                    detailIndex >= 0 && segs.size > detailIndex + 1 -> segs[detailIndex + 1]
                    segs.isNotEmpty() -> segs.last()
                    else -> null
                }
            }
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Missing detailPath")

        val detailUrl = "$apiUrl/wefeed-h5api-bff/detail?detailPath=${URLEncoder.encode(detailPath, "UTF-8")}"
        val response = app.get(detailUrl, headers = apiHeaders())

        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(response.text)
        val data = root["data"] ?: throw ErrorLoadingException("No data")
        val subject = data["subject"] ?: throw ErrorLoadingException("No subject")

        val subjectId = parsed.getQueryParameter("id")
            ?: subject["subjectId"]?.asText()
            ?: throw ErrorLoadingException("No subjectId")

        val safeDetailPath = subject["detailPath"]?.asText() ?: detailPath
        val pageUrl = "$mainUrl/detail/$safeDetailPath?id=$subjectId"

        val title = subject["title"]?.asText()?.substringBefore("[") ?: throw ErrorLoadingException("No title")
        val description = subject["description"]?.asText()?.takeIf { !it.isNullOrBlank() }
            ?: data["metadata"]?.get("description")?.asText()
        val releaseDate = subject["releaseDate"]?.asText()
        val year = releaseDate?.take(4)?.toIntOrNull()
        val durationMinutes = subject["duration"]?.asInt()?.let { it / 60 }
        val tags = subject["genre"]?.asText()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

        val posterUrl =
            subject["cover"]?.get("url")?.asText()
                ?: data["metadata"]?.get("image")?.asText()
        val backgroundUrl = posterUrl

        val actors = data["stars"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { star ->
                val name = star["name"]?.asText() ?: return@mapNotNull null
                val avatarUrl = star["avatarUrl"]?.asText()
                val character = star["character"]?.asText()
                ActorData(Actor(name, avatarUrl), roleString = character)
            }
            ?.distinctBy { it.actor.name }
            ?: emptyList()

        val subjectType = subject["subjectType"]?.asInt() ?: 1
        val type = if (subjectType == 2) TvType.TvSeries else TvType.Movie
        val score = Score.from10(subject["imdbRatingValue"]?.asText())

        if (type == TvType.TvSeries) {
            val seasons = data["resource"]?.get("seasons")
            val episodes = mutableListOf<Episode>()

            if (seasons != null && seasons.isArray) {
                seasons.forEach { seasonNode ->
                    val se = seasonNode["se"]?.asInt() ?: 1
                    val maxEp = seasonNode["maxEp"]?.asInt() ?: 1
                    for (ep in 1..maxEp) {
                        episodes.add(
                            newEpisode("$subjectId|$safeDetailPath|$se|$ep") {
                                this.name = "S${se}E$ep"
                                this.season = se
                                this.episode = ep
                                this.posterUrl = posterUrl
                            }
                        )
                    }
                }
            }

            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode("$subjectId|$safeDetailPath|1|1") {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = posterUrl
                    }
                )
            }

            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
                this.plot = description
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = score
                this.duration = durationMinutes
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, "$subjectId|$safeDetailPath|0|0") {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backgroundUrl
            this.plot = description
            this.year = year
            this.tags = tags
            this.actors = actors
            this.score = score
            this.duration = durationMinutes
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parts = data.split("|")
            val subjectId = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return false
            val detailPath = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return false
            val season = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val episode = parts.getOrNull(3)?.toIntOrNull() ?: 0

            val downloadUrl = buildString {
                append(apiUrl).append("/wefeed-h5api-bff/subject/download")
                append("?subjectId=").append(URLEncoder.encode(subjectId, "UTF-8"))
                append("&se=").append(season)
                append("&ep=").append(episode)
                append("&detailPath=").append(URLEncoder.encode(detailPath, "UTF-8"))
            }

            val mapper = jacksonObjectMapper()
            val root = mapper.readTree(app.get(downloadUrl, headers = downloadHeaders()).text)
            val dataNode = root["data"] ?: return false

            val downloads = dataNode["downloads"]
            val captions = dataNode["captions"]

            var hasAnyLinks = false

            if (downloads != null && downloads.isArray) {
                for (download in downloads) {
                    val streamUrl = download["url"]?.asText()?.takeIf { it.isNotBlank() } ?: continue
                    val resolution = download["resolution"]?.asInt()
                    val quality = when (resolution) {
                        2160 -> Qualities.P2160.value
                        1440 -> Qualities.P1440.value
                        1080 -> Qualities.P1080.value
                        720 -> Qualities.P720.value
                        480 -> Qualities.P480.value
                        360 -> Qualities.P360.value
                        240 -> Qualities.P240.value
                        else -> resolution ?: Qualities.Unknown.value
                    }

                    val linkType = when {
                        streamUrl.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
                        streamUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                        streamUrl.substringAfterLast('.', "").equals("torrent", ignoreCase = true) -> ExtractorLinkType.TORRENT
                        streamUrl.substringAfterLast('.', "").equals("m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                        streamUrl.contains(".mp4", ignoreCase = true) || streamUrl.contains(".mkv", ignoreCase = true) -> ExtractorLinkType.VIDEO
                        else -> INFER_TYPE
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ${resolution ?: ""}".trim(),
                            url = streamUrl,
                            type = linkType,
                        ) {
                            this.quality = quality
                            this.headers = mapOf("Referer" to "https://videodownloader.site/")
                        }
                    )
                    hasAnyLinks = true
                }
            }

            if (captions != null && captions.isArray) {
                for (caption in captions) {
                    val captionUrl = caption["url"]?.asText()?.takeIf { it.isNotBlank() } ?: continue
                    val lang = caption["lanName"]?.asText()
                        ?: caption["lan"]?.asText()
                        ?: "Unknown"
                    subtitleCallback.invoke(newSubtitleFile(url = captionUrl, lang = lang))
                }
            }

            hasAnyLinks
        } catch (_: Exception) {
            false
        }
    }
}

fun getHighestQuality(input: String): Int? {
    val qualities = listOf(
        "2160" to Qualities.P2160.value,
        "1440" to Qualities.P1440.value,
        "1080" to Qualities.P1080.value,
        "720"  to Qualities.P720.value,
        "480"  to Qualities.P480.value,
        "360"  to Qualities.P360.value,
        "240"  to Qualities.P240.value
    )

    for ((label, mappedValue) in qualities) {
        if (input.contains(label, ignoreCase = true)) {
            return mappedValue
        }
    }
    return null
}

private suspend fun identifyID(
    title: String,
    year: Int?,
    imdbRatingValue: Double?
): Pair<Int?, String?> {
    val normTitle = normalize(title)

    // try multi -> tv -> movie (with year)
    val tryOrder = listOf("multi", "tv", "movie")
    for (type in tryOrder) {
        val res = searchAndPick(normTitle, year, imdbRatingValue)
        if (res.first != null) return res
    }

    // retry without year (often helpful for dubbed/localized titles)
    if (year != null) {
        for (type in tryOrder) {
            val res = searchAndPick(normTitle, null, imdbRatingValue)
            if (res.first != null) return res
        }
    }

    val stripped = normTitle
        .replace("\\b(hindi|tamil|telugu|dub|dubbed|dubbed audio|dual audio|dubbed version)\\b".toRegex(RegexOption.IGNORE_CASE), " ")
        .replace("\\s+".toRegex(), " ")
        .trim()
    if (stripped.isNotBlank() && stripped != normTitle) {
        for (type in tryOrder) {
            val res = searchAndPick(stripped, year, imdbRatingValue)
            if (res.first != null) return res
        }
        if (year != null) {
            for (type in tryOrder) {
                val res = searchAndPick(stripped, null, imdbRatingValue)
                if (res.first != null) return res
            }
        }
    }

    return Pair(null, null)
}

private suspend fun searchAndPick(
    normTitle: String,
    year: Int?,
    imdbRatingValue: Double?,
): Pair<Int?, String?> {

    suspend fun doSearch(endpoint: String, extraParams: String = ""): org.json.JSONArray? {
        val url = buildString {
            append("https://api.themoviedb.org/3/").append(endpoint)
            append("?api_key=").append("1865f43a0549ca50d341dd9ab8b29f49")
            append(extraParams)
            append("&include_adult=false&page=1")
        }
        val text = app.get(url).text
        return JSONObject(text).optJSONArray("results")
    }

    val multiResults = doSearch("search/multi", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&year=$year" else ""))
    val searchQueues: List<Pair<String, org.json.JSONArray?>> = listOf(
        "multi" to multiResults,
        "tv" to doSearch("search/tv", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&first_air_date_year=$year" else "")),
        "movie" to doSearch("search/movie", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&year=$year" else ""))
    )

    var bestId: Int? = null
    var bestScore = -1.0
    var bestIsTv = false

    for ((sourceType, results) in searchQueues) {
        if (results == null) continue
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)

            val mediaType = when (sourceType) {
                "multi" -> o.optString("media_type", "")
                "tv" -> "tv"
                else -> "movie"
            }

            val candidateId = o.optInt("id", -1)
            if (candidateId == -1) continue

            val candTitle = when (mediaType) {
                "tv" -> listOf(o.optString("name", ""), o.optString("original_name", "")).firstOrNull { it.isNotBlank() }?.lowercase() ?: ""
                "movie" -> listOf(o.optString("title", ""), o.optString("original_title", "")).firstOrNull { it.isNotBlank() }?.lowercase() ?: ""
                else -> listOf(o.optString("title", ""), o.optString("name", ""), o.optString("original_title", ""), o.optString("original_name", "")).firstOrNull { it.isNotBlank() }?.lowercase() ?: ""
            }

            val candDate = when (mediaType) {
                "tv" -> o.optString("first_air_date", "")
                else -> o.optString("release_date", "")
            }
            val candYear = candDate.take(4).toIntOrNull()
            val candRating = o.optDouble("vote_average", Double.NaN)

            // scoring
            var score = 0.0
            if (tokenEquals(candTitle, normTitle)) score += 50.0
            else if (candTitle.contains(normTitle) || normTitle.contains(candTitle)) score += 15.0

            if (candYear != null && year != null && candYear == year) score += 35.0

            if (imdbRatingValue != null && !candRating.isNaN()) {
                val diff = kotlin.math.abs(candRating - imdbRatingValue)
                if (diff <= 0.5) score += 10.0 else if (diff <= 1.0) score += 5.0
            }

            if (o.has("popularity")) score += (o.optDouble("popularity", 0.0) / 100.0).coerceAtMost(5.0)

            if (score > bestScore) {
                bestScore = score
                bestId = candidateId
                bestIsTv = (mediaType == "tv")
            }
        }

        if (bestScore >= 45) break
    }

    if (bestId == null || bestScore < 40.0) return Pair(null, null)

    // fetch details for external_ids
    val detailKind = if (bestIsTv) "tv" else "movie"
    val detailUrl = "https://api.themoviedb.org/3/$detailKind/$bestId?api_key=1865f43a0549ca50d341dd9ab8b29f49&append_to_response=external_ids"
    val detailText = app.get(detailUrl).text
    val detailJson = JSONObject(detailText)
    val imdbId = detailJson.optJSONObject("external_ids")?.optString("imdb_id")

    return Pair(bestId, imdbId)
}

private fun tokenEquals(a: String, b: String): Boolean {
    val sa = a.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    val sb = b.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    if (sa.isEmpty() || sb.isEmpty()) return false
    val inter = sa.intersect(sb).size
    return inter >= max(1, minOf(sa.size, sb.size) * 3 / 4)
}

private fun normalize(s: String): String {
    val t = s.replace("\\[.*?]".toRegex(), " ")
        .replace("\\(.*?\\)".toRegex(), " ")
        .replace("(?i)\\b(dub|dubbed|hd|4k|hindi|tamil|telugu|dual audio)\\b".toRegex(), " ")
        .trim()
        .lowercase()
        .replace(":", " ")
        .replace("\\p{Punct}".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
    return t
}

private suspend fun fetchMetaData(imdbId: String?, type: TvType): JsonNode? {
    if (imdbId.isNullOrBlank()) return null

    val metaType = if (type == TvType.TvSeries) "series" else "movie"
    val url = "https://aiometadata.elfhosted.com/stremio/b7cb164b-074b-41d5-b458-b3a834e197bb/meta/$metaType/$imdbId.json"

    return try {
        val resp = app.get(url).text
        mapper.readTree(resp)["meta"]
    } catch (_: Exception) {
        null
    }
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null

    val appLang = appLangCode?.substringBefore("-")?.lowercase()
    val url = if (type == TvType.Movie) {
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    } else {
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"
    }

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    fun logoUrlAt(i: Int): String = "https://image.tmdb.org/t/p/w500${logos.getJSONObject(i).optString("file_path")}"
    fun isSvg(i: Int): Boolean = logos.getJSONObject(i).optString("file_path").endsWith(".svg", ignoreCase = true)

    if (!appLang.isNullOrBlank()) {
        var svgFallback: String? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (logo.optString("iso_639_1") == appLang) {
                if (isSvg(i)) {
                    if (svgFallback == null) svgFallback = logoUrlAt(i)
                } else {
                    return logoUrlAt(i)
                }
            }
        }
        if (svgFallback != null) return svgFallback
    }

    var enSvgFallback: String? = null
    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (logo.optString("iso_639_1") == "en") {
            if (isSvg(i)) {
                if (enSvgFallback == null) enSvgFallback = logoUrlAt(i)
            } else {
                return logoUrlAt(i)
            }
        }
    }
    if (enSvgFallback != null) return enSvgFallback

    for (i in 0 until logos.length()) {
        if (!isSvg(i)) return logoUrlAt(i)
    }

    return logoUrlAt(0)
}
