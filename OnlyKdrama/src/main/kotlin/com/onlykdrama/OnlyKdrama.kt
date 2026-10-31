
package com.onlykdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class OnlyKdrama : MainAPI() {

    override var mainUrl = "https://onlykdrama.shop"
    override var name = "OnlyKDrama"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "" to "New Release"
    )

    private fun absoluteUrl(url: String): String {
        return fixUrl(
            url.trim()
                .replace("\\/", "/")
                .replace("&amp;", "&")
        )
    }

    private fun getPoster(element: Element?): String? {
        if (element == null) return null

        val image = if (element.tagName() == "img") {
            element
        } else {
            element.selectFirst("img")
        }

        val poster = listOf(
            image?.attr("data-src"),
            image?.attr("data-lazy-src"),
            image?.attr("data-original"),
            image?.attr("src"),
            element.attr("data-bg")
        ).firstOrNull {
            !it.isNullOrBlank() && !it.startsWith("data:")
        } ?: return null

        return absoluteUrl(poster)
    }

    private fun parseVideos(document: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (card in document.select("article.item")) {
            val link = card.selectFirst(".data h3 a[href]")
                ?: card.selectFirst(".poster a[href]")
                ?: continue

            val href = link.attr("href").trim()
            if (href.isBlank()) continue

            val url = absoluteUrl(href)
            if (!url.startsWith("$mainUrl/") || !seen.add(url)) continue

            val title = link.text().trim()
                .ifBlank { link.attr("aria-label").trim() }

            if (title.isBlank()) continue

            val poster = getPoster(card.selectFirst(".poster img"))

            results.add(
                newTvSeriesSearchResponse(
                    title,
                    url,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            )
        }

        return results
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            mainUrl
        } else {
            "$mainUrl/page/$page/"
        }

        val document = app.get(
            url,
            headers = headers
        ).document

        return newHomePageResponse(
            request.name,
            parseVideos(document),
            hasNext = false
        )
    }

    private suspend fun searchPage(
        query: String,
        page: Int
        ): Pair<List<SearchResponse>, Boolean> {
        val encodedQuery = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        val pageNumber = page.coerceAtLeast(1)

        val url = "$mainUrl/advance-search/?q=$encodedQuery&type=all&lang=&genre=&year=&min_rating=0&sort=latest" +
            if (pageNumber > 1) "&paged=$pageNumber" else ""

        val document = app.get(
            url,
            headers = headers
        ).document

        val hasNext = document.select(
            "a[href*='paged=']"
        ).any { element ->
            val nextPage = Regex("""paged=(\d+)""")
                .find(element.attr("href"))
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            nextPage != null && nextPage > pageNumber
        }

        return Pair(parseVideos(document), hasNext)
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        return searchPage(query, 1).first
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {
        val (results, hasNext) = searchPage(query, page)

        return newSearchResponseList(
            results,
            hasNext = hasNext
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {
        val document = app.get(
            url,
            headers = headers
        ).document

        val title = document.selectFirst(
            "h1"
        )?.text()?.trim()
            ?: document.title().substringBefore("|").trim()

        if (title.isBlank()) return null

        val poster = document.selectFirst(
            "meta[property=og:image]"
        )?.attr("content")?.takeIf {
            it.isNotBlank()
        } ?: getPoster(
            document.selectFirst("img")
        )

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = poster?.let { absoluteUrl(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers = headers
        ).document

        val videoUrl = document.selectFirst(
            "video source[src], video[src]"
        )?.attr("src")?.takeIf {
            it.isNotBlank()
        } ?: return false

        callback(
            newExtractorLink(
                name,
                name,
                absoluteUrl(videoUrl),
                ExtractorLinkType.VIDEO
            ) {
                this.referer = data
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )

        return true
    }
}
