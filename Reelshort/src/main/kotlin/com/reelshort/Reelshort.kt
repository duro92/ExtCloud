package com.reelshort

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class Reelshort : MainAPI() {
    override var mainUrl = "https://api.sansekai.my.id"
    override var name = "Reelshort💗"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/api/reelshort/foryou" to "Untukmu",
        "/api/reelshort/homepage" to "Beranda",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>())

        val items = fetchItems(request.data)
            .distinctBy { it.id }
            .mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val fromSearch = fetchItems("/api/reelshort/search?query=$encoded")
        val items = if (fromSearch.isNotEmpty()) {
            fromSearch
        } else {
            listOf(
                "/api/reelshort/foryou",
                "/api/reelshort/homepage",
            ).flatMap { fetchItems(it) }
                .distinctBy { it.id }
                .filter {
                    it.title.contains(q, true) || it.description.orEmpty().contains(q, true)
                }
        }

        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = extractBookId(url)
        val detail = fetchDetail(bookId)
            ?: throw ErrorLoadingException("Detail Reelshort tidak ditemukan")

        val episodes = detail.chapters
            .sortedBy { it.episodeNumber }
            .map { chapter ->
                newEpisode(
                    LoadData(
                        bookId = detail.bookId,
                        chapterId = chapter.chapterId,
                        episodeNumber = chapter.episodeNumber
                    ).toJson()
                ) {
                    name = chapter.title.ifBlank { "EP ${chapter.episodeNumber}" }
                    episode = chapter.episodeNumber
                    posterUrl = detail.cover
                }
            }

        return newTvSeriesLoadResponse(
            name = detail.title,
            url = "$mainUrl/book/${detail.bookId}",
            type = TvType.AsianDrama,
            episodes = episodes
        ) {
            posterUrl = detail.cover
            plot = detail.description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = parseJson<LoadData>(data)
        val bookId = payload.bookId ?: return false
        val episodeNumber = payload.episodeNumber ?: return false

        val episodeJson = runCatching {
            app.get("$mainUrl/api/reelshort/episode?bookId=$bookId&episodeNumber=$episodeNumber").text
        }.getOrNull() ?: return false

        val root = runCatching { JSONObject(episodeJson) }.getOrNull() ?: return false
        if (!root.optBoolean("success")) return false
        if (root.optBoolean("isLocked")) return false

        val videos = root.optJSONArray("videoList")
        if (videos == null || videos.length() == 0) return false

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
        )

        val links = mutableListOf<Pair<String, String>>()
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            val streamUrl = video.optStringSafe("url") ?: continue
            val encode = video.optStringSafe("encode") ?: "Auto"
            val quality = video.optInt("quality").takeIf { it > 0 }?.toString() ?: "Auto"
            val label = "$encode $quality"
            links.add(label to streamUrl)
        }

        links.distinctBy { it.second }.forEach { (label, streamUrl) ->
            if (streamUrl.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(
                    source = "$name $label",
                    streamUrl = streamUrl,
                    referer = mainUrl,
                    headers = headers
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name $label",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.headers = headers
                    }
                )
            }
        }

        return links.isNotEmpty()
    }

    private suspend fun fetchItems(path: String): List<BookItem> {
        val body = runCatching { app.get("$mainUrl$path").text }.getOrNull() ?: return emptyList()
        return runCatching { parseItems(body) }.getOrDefault(emptyList())
    }

    private fun parseItems(body: String): List<BookItem> {
        val root = JSONObject(body)
        val results = mutableListOf<BookItem>()

        val searchResults = root.optJSONArray("results")
        if (searchResults != null) {
            for (i in 0 until searchResults.length()) {
                val obj = searchResults.optJSONObject(i) ?: continue
                parseBook(obj)?.let(results::add)
            }
            return results
        }

        val data = root.optJSONObject("data") ?: return emptyList()
        val lists = data.optJSONArray("lists") ?: return emptyList()
        for (i in 0 until lists.length()) {
            val entry = lists.optJSONObject(i) ?: continue

            // foryou format (langsung item buku)
            if (entry.has("book_id")) {
                parseBook(entry)?.let(results::add)
            }

            // homepage format (nested books)
            appendBooks(results, entry.optJSONArray("books"))
            appendBooks(results, entry.optJSONArray("book_list"))
            appendBooks(results, entry.optJSONArray("lists"))
        }
        return results
    }

    private fun appendBooks(out: MutableList<BookItem>, books: JSONArray?) {
        if (books == null) return
        for (i in 0 until books.length()) {
            val obj = books.optJSONObject(i) ?: continue
            parseBook(obj)?.let(out::add)
        }
    }

    private fun parseBook(obj: JSONObject): BookItem? {
        val id = obj.optStringSafe("book_id")
            ?: obj.optStringSafe("bookId")
            ?: obj.optStringSafe("id")
            ?: return null
        val title = obj.optStringSafe("book_title")
            ?: obj.optStringSafe("title")
            ?: obj.optStringSafe("name")
            ?: return null
        if (title.isBlank()) return null

        val tags = mutableListOf<String>()
        tags.addAll(obj.optStringArray("theme"))
        tags.addAll(obj.optTagList("tag_list"))
        tags.addAll(obj.optStringArray("tag"))

        return BookItem(
            id = id,
            title = title,
            cover = obj.optStringSafe("book_pic") ?: obj.optStringSafe("cover"),
            description = obj.optStringSafe("special_desc") ?: obj.optStringSafe("description"),
            chapterCount = obj.optInt("chapter_count").takeIf { it > 0 }
                ?: obj.optInt("chapterCount").takeIf { it > 0 },
            tags = tags.distinct()
        )
    }

    private suspend fun fetchDetail(bookId: String): DetailData? {
        val body = runCatching {
            app.get("$mainUrl/api/reelshort/detail?bookId=$bookId").text
        }.getOrNull() ?: return null

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!root.optBoolean("success")) return null

        val title = root.optStringSafe("title") ?: return null
        val chapters = mutableListOf<ChapterData>()
        val arr = root.optJSONArray("chapters")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val episodeNumber = obj.optInt("index").takeIf { it > 0 } ?: continue
                chapters.add(
                    ChapterData(
                        chapterId = obj.optStringSafe("chapterId"),
                        title = obj.optStringSafe("title").orEmpty(),
                        episodeNumber = episodeNumber
                    )
                )
            }
        }

        return DetailData(
            bookId = root.optStringSafe("bookId") ?: bookId,
            title = title,
            cover = root.optStringSafe("cover"),
            description = root.optStringSafe("description"),
            chapters = chapters
        )
    }

    private fun extractBookId(url: String): String {
        val fromQuery = Regex("[?&](?:bookId|id)=([^&]+)").find(url)?.groupValues?.getOrNull(1)
        if (!fromQuery.isNullOrBlank()) return fromQuery
        return url.substringAfterLast("/").substringBefore("?")
    }

    private fun JSONObject.optStringSafe(key: String): String? {
        val value = optString(key).trim()
        return value.takeIf { it.isNotBlank() && !it.equals("null", true) }
    }

    private fun JSONObject.optStringArray(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val value = arr.optString(i).trim()
            if (value.isNotBlank() && !value.equals("null", true)) out.add(value)
        }
        return out
    }

    private fun JSONObject.optTagList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val tag = arr.optJSONObject(i)
            val value = tag?.optStringSafe("tag_name") ?: arr.optString(i).trim()
            if (value.isNotBlank() && !value.equals("null", true)) out.add(value)
        }
        return out
    }

    private fun BookItem.toSearchResponse(): SearchResponse? {
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(
            title,
            "$mainUrl/book/$id",
            TvType.AsianDrama
        ) {
            posterUrl = cover
        }
    }

    data class LoadData(
        val bookId: String? = null,
        val chapterId: String? = null,
        val episodeNumber: Int? = null,
    )

    data class BookItem(
        val id: String,
        val title: String,
        val cover: String?,
        val description: String?,
        val chapterCount: Int?,
        val tags: List<String>,
    )

    data class DetailData(
        val bookId: String,
        val title: String,
        val cover: String?,
        val description: String?,
        val chapters: List<ChapterData>,
    )

    data class ChapterData(
        val chapterId: String?,
        val title: String,
        val episodeNumber: Int,
    )
}
