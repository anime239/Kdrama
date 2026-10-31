
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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "" to "New Release"
    )

    // ---------------- URL HANDLING ----------------

    private fun absoluteUrl(url: String): String {
        return fixUrl(
            url.trim()
                .replace("\\/", "/")
                .replace("&amp;", "&")
        )
    }

    private fun isValidDramaUrl(url: String): Boolean {
        return url.startsWith("$mainUrl/")
    }

    // ---------------- CLOUDFLARE DETECTION ----------------

    private fun isCloudflareChallenge(html: String): Boolean {
        val page = html.lowercase()

        return page.contains("cf-chl-") ||
            page.contains("challenge-platform") ||
            page.contains("performing security verification") ||
            page.contains("just a moment...") ||
            page.contains("cf-browser-verification") ||
            page.contains("checking your browser") ||
            page.contains("verify you are human")
    }

    private suspend fun fetchDocument(url: String): Document {

        val response = app.get(
            url,
            headers = headers
        )

        val html = response.text

        if (isCloudflareChallenge(html)) {
            throw ErrorLoadingException(
                "OnlyKDrama is showing a security verification page. " +
                    "Open OnlyKDrama from plugin settings, complete the verification, " +
                    "then retry the request."
            )
        }

        return response.document
    }

    // ---------------- POSTER EXTRACTION ----------------

    private fun getPoster(card: Element): String? {

        val image = card.selectFirst(".poster img")
            ?: card.selectFirst("img")

        if (image == null) return null

        val possibleSources = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-echo"),
            image.attr("src")
        )

        for (source in possibleSources) {
            val value = source.trim()

            if (
                value.isNotBlank() &&
                !value.startsWith("data:") &&
                !value.startsWith("blob:")
            ) {
                return absoluteUrl(value)
            }
        }

        val srcSet = image.attr("srcset")

        if (srcSet.isNotBlank()) {
            val firstImage = srcSet
                .split(",")
                .firstOrNull()
                ?.trim()
                ?.substringBefore(" ")
                ?.trim()

            if (
                !firstImage.isNullOrBlank() &&
                !firstImage.startsWith("data:")
            ) {
                return absoluteUrl(firstImage)
            }
        }

        return null
    }

    // ---------------- DRAMA PARSING ----------------

    private fun parseVideos(
        document: Document
    ): List<SearchResponse> {

        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val cards = document.select(
            "article.item, .items article.item"
        )

        for (card in cards) {

            val link = card.selectFirst(
                ".data h3 a[href]"
            ) ?: card.selectFirst(
                ".poster a[href]"
            ) ?: continue

            val href = link.attr("href").trim()

            if (href.isBlank()) continue

            val url = absoluteUrl(href)

            if (!isValidDramaUrl(url)) continue

            if (!seen.add(url)) continue

            val title = link.text().trim()
                .ifBlank {
                    link.attr("aria-label").trim()
                }
                .ifBlank {
                    card.selectFirst(".poster img")
                        ?.attr("alt")
                        ?.trim()
                        .orEmpty()
                }

            if (title.isBlank()) continue

            val poster = getPoster(card)

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

    // ---------------- PAGINATION ----------------

    private fun hasNextPage(
        document: Document,
        currentPage: Int,
        searchMode: Boolean
    ): Boolean {

        val pattern = if (searchMode) {
            Regex("""[?&]paged=(\d+)""")
        } else {
            Regex("""/page/(\d+)/?""")
        }

        for (link in document.select("a[href]")) {

            val href = link.attr("href")

            val nextPage = pattern
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            if (nextPage != null && nextPage > currentPage) {
                return true
            }
        }

        return false
    }

    // ---------------- HOMEPAGE ----------------

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

        val hasNext = hasNextPage(
            document,
            pageNumber,
            searchMode = false
        )

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = results,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    // ---------------- SEARCH ----------------

    private suspend fun searchPage(
        query: String,
        page: Int
    ): Pair<List<SearchResponse>, Boolean> {

        val cleanQuery = query.trim()

        if (cleanQuery.isBlank()) {
            return Pair(emptyList(), false)
        }

        val encodedQuery = URLEncoder.encode(
            cleanQuery,
            "UTF-8"
        )

        val pageNumber = page.coerceAtLeast(1)

        val url =
            "$mainUrl/advance-search/?q=$encodedQuery&type=all&lang=&genre=&year=&min_rating=0&sort=latest" +
                if (pageNumber > 1) {
                    "&paged=$pageNumber"
                } else {
                    ""
                }

        val document = fetchDocument(url)

        val results = parseVideos(document)

        val hasNext = hasNextPage(
            document,
            pageNumber,
            searchMode = true
        )

        return Pair(results, hasNext)
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

        val (results, hasNext) = searchPage(
            query,
            page
        )

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
