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
    override val hasQuickSearch = true
    override val usesWebView = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "" to "New Release"
    )

    private fun absoluteUrl(value: String): String {
        val cleaned = value.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return fixUrl(cleaned)
    }

    private fun isChallenge(html: String): Boolean {
        val content = html.lowercase()

        return content.contains("cf-chl-") ||
            content.contains("challenge-platform") ||
            content.contains("cf-browser-verification") ||
            content.contains("performing security verification") ||
            content.contains("just a moment...")
    }

    private fun imageUrl(card: Element): String? {
        val image = card.selectFirst(".poster img") ?: return null

        val candidates = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-echo"),
            image.attr("data-lazy"),
            image.attr("src"),
            image.attr("srcset")
        )

        for (candidate in candidates) {
            if (candidate.isBlank() || candidate.startsWith("data:")) {
                continue
            }

            val selected = if (candidate.contains(",")) {
                candidate.substringBefore(",")
                    .trim()
                    .substringBefore(" ")
            } else {
                candidate.trim()
            }

            if (selected.isNotBlank() && !selected.startsWith("data:")) {
                return absoluteUrl(selected)
            }
        }

        return null
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

            if (!url.startsWith("$mainUrl/")) continue
            if (!seen.add(url)) continue

            val title = link.text().trim()
                .ifBlank { link.attr("aria-label").trim() }
                .ifBlank {
                    card.selectFirst(".poster img")
                        ?.attr("alt")
                        ?.trim()
                        .orEmpty()
                }

            if (title.isBlank()) continue

            val poster = imageUrl(card)

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

    private suspend fun fetchDocument(url: String): Document {
        val response = app.get(
            url,
            headers = requestHeaders
        )

        val html = response.text

        if (isChallenge(html)) {
            throw ErrorLoadingException(
                "OnlyKDrama: The website returned a Cloudflare verification page. " +
                    "The browser session is separate from CloudStream's HTTP session."
            )
        }

        val document = response.document

        if (document.select("article.item").isEmpty()) {
            throw ErrorLoadingException(
                "OnlyKDrama: No drama cards were returned. " +
                    "The website may have changed its response or blocked this request."
            )
        }

        return document
    }

    private fun hasNextPage(
        document: Document,
        page: Int,
        search: Boolean
    ): Boolean {
        val selector = if (search) {
            "a[href*='paged=']"
        } else {
            "a[href*='/page/']"
        }

        return document.select(selector).any { element ->

            val href = element.attr("href")

            val next = if (search) {
                Regex("""[?&]paged=(\d+)""")
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            } else {
                Regex("""/page/(\d+)/?""")
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }

            next != null && next > page
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val pageNumber = page.coerceAtLeast(1)

        val url = if (pageNumber == 1) {
            "$mainUrl/"
        } else {
            "$mainUrl/page/$pageNumber/"
        }

        val document = fetchDocument(url)

        val results = parseVideos(document)

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = results,
                isHorizontalImages = true
            ),
            hasNext = hasNextPage(
                document,
                pageNumber,
                false
            )
        )
    }

    private suspend fun searchPage(
        query: String,
        page: Int
    ): Pair<List<SearchResponse>, Boolean> {

        val pageNumber = page.coerceAtLeast(1)

        val encoded = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        val url = buildString {
            append("$mainUrl/advance-search/")
            append("?q=$encoded")
            append("&type=all")
            append("&lang=")
            append("&genre=")
            append("&year=")
            append("&min_rating=0")
            append("&sort=latest")

            if (pageNumber > 1) {
                append("&paged=$pageNumber")
            }
        }

        val document = fetchDocument(url)

        return Pair(
            parseVideos(document),
            hasNextPage(document, pageNumber, true)
        )
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

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return searchPage(query, 1).first
    }
}