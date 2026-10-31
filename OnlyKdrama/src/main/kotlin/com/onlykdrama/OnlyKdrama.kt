
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
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
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

    private fun isCloudflareChallenge(html: String): Boolean {
        val page = html.lowercase()

        return page.contains("cf-chl-") ||
            page.contains("challenge-platform") ||
            page.contains("performing security verification") ||
            page.contains("just a moment...") ||
            page.contains("cf-browser-verification")
    }

    private fun getPoster(element: Element): String? {
        val image = element.selectFirst(".poster img") ?: return null

        val attributes = listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-echo",
            "src",
            "srcset"
        )

        for (attribute in attributes) {
            val value = image.attr(attribute).trim()

            if (value.isBlank() || value.startsWith("data:")) {
                continue
            }

            val imageUrl = if (attribute == "srcset") {
                value.substringBefore(",").trim().substringBefore(" ")
            } else {
                value
            }

            if (imageUrl.isNotBlank()) {
                return absoluteUrl(imageUrl)
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

            if (!url.startsWith("$mainUrl/") || !seen.add(url)) {
                continue
            }

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

    private suspend fun fetchDocument(url: String): Document {
        val response = app.get(
            url,
            headers = headers
        )

        val html = response.text

        if (isCloudflareChallenge(html)) {
            throw ErrorLoadingException(
                "OnlyKDrama: Cloudflare verification blocked the HTTP request. " +
                    "The website browser and CloudStream HTTP client use separate sessions."
            )
        }

        val document = response.document

        if (document.select("article.item").isEmpty()) {
            throw ErrorLoadingException(
                "OnlyKDrama: No drama cards were found in the HTTP response."
            )
        }

        return document
    }

    private fun hasNextPage(
        document: Document,
        currentPage: Int,
        parameter: String
    ): Boolean {
        return document.select("a[href]").any { link ->
            val href = link.attr("href")

            val nextPage = when (parameter) {
                "page" -> Regex("""/page/(\d+)/?""")
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                else -> Regex("""[?&]paged=(\d+)""")
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }

            nextPage != null && nextPage > currentPage
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

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = parseVideos(document),
                isHorizontalImages = true
            ),
            hasNext = hasNextPage(
                document,
                pageNumber,
                "page"
            )
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

        val url = buildString {
            append("$mainUrl/advance-search/")
            append("?q=$encodedQuery")
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
            hasNextPage(
                document,
                pageNumber,
                "paged"
            )
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
