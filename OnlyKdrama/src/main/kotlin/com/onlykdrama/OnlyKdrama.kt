
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
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "/genres/koren-drama/" to "Korean Drama",
        "/genres/hindi-dubbed-drama/" to "Hindi Dubbed Drama",
        "/genres/ongoing/" to "Ongoing Dramas"
    )

    private fun absolute(url: String?): String? {
        val value = url?.trim()
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?: return null

        if (
            value.isBlank() ||
            value.startsWith("data:") ||
            value.startsWith("javascript:")
        ) return null

        return fixUrl(value)
    }

    private suspend fun getDocument(url: String): Document {
        val requestHeaders = OnlyKdramaSession.headers(headers)

        val response = app.get(
            url,
            headers = requestHeaders
        )

        val lower = response.text.lowercase()

        if (
            lower.contains("cf-chl-") ||
            lower.contains("challenge-platform") ||
            lower.contains("just a moment") ||
            lower.contains("verify you are human")
        ) {
            throw ErrorLoadingException(
                "OnlyKDrama verification is required. " +
                    "Open OnlyKDrama settings, complete website verification, " +
                    "press SAVE SESSION and retry."
            )
        }

        return response.document
    }

    private fun getImage(element: Element?): String? {
        if (element == null) return null

        val image = element.selectFirst("img") ?: return null

        val candidates = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-image"),
            image.attr("data-lazy"),
            image.attr("data-srcset"),
            image.attr("srcset"),
            image.attr("src")
        )

        for (candidate in candidates) {
            if (candidate.isBlank()) continue

            val selected = if (candidate.contains(",")) {
                candidate.split(",")
                    .last()
                    .trim()
                    .substringBefore(" ")
            } else {
                candidate.trim().substringBefore(" ")
            }

            if (selected.startsWith("data:")) continue

            absolute(selected)?.let {
                return it
            }
        }

        return null
    }

    private fun getCard(link: Element): Element {
        var current = link
        var parent = link.parent()

        repeat(7) {
            if (parent == null) return current

            if (
                parent.selectFirst("img") != null &&
                parent.select("a[href*=/drama/]").isNotEmpty()
            ) {
                current = parent
            } else {
                return current
            }

            parent = parent.parent()
        }

        return current
    }

    private fun getTitle(
        link: Element,
        card: Element
    ): String {
        return link.text().trim()
            .ifBlank { link.attr("title").trim() }
            .ifBlank { link.attr("aria-label").trim() }
            .ifBlank {
                card.selectFirst(
                    "h1, h2, h3, h4, .entry-title, .title"
                )?.text()?.trim().orEmpty()
            }
            .ifBlank {
                card.selectFirst("img")
                    ?.attr("alt")?.trim().orEmpty()
            }
    }

    private fun createSearchResponse(
        title: String,
        url: String,
        poster: String?
    ): SearchResponse {
        return if (
            url.contains("/movies/") ||
            title.contains("movie", true) ||
            title.contains("film", true)
        ) {
            newMovieSearchResponse(
                title,
                url,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                url,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }
    }

    private fun parseHome(
        document: Document
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (link in document.select("a[href*=/drama/], a[href*=/movies/]")) {
            val url = absolute(link.attr("href")) ?: continue

            if (!url.startsWith("$mainUrl/")) continue

            val path = url.removePrefix(mainUrl)
                .substringBefore("?")
                .substringBefore("#")

            if (
                !path.startsWith("/drama/") &&
                !path.startsWith("/movies/")
            ) continue

            val card = getCard(link)
            val title = getTitle(link, card)

            if (title.isBlank() || !seen.add(url)) continue

            val poster = getImage(card) ?: getImage(link)

            results.add(
                createSearchResponse(title, url, poster)
            )
        }

        return results
    }

    private fun parseSearch(
        document: Document
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (card in document.select(".search-page .result-item")) {
            val link = card.selectFirst(
                ".details .title a[href], .image a[href]"
            ) ?: continue

            val url = absolute(link.attr("href")) ?: continue

            if (!url.startsWith("$mainUrl/")) continue

            val path = url.removePrefix(mainUrl)
                .substringBefore("?")
                .substringBefore("#")

            if (
                !path.startsWith("/drama/") &&
                !path.startsWith("/movies/")
            ) continue

            val title = card.selectFirst(
                ".details .title a"
            )?.text()?.trim()
                .orEmpty()
                .ifBlank {
                    card.selectFirst(".image img")
                        ?.attr("alt")?.trim().orEmpty()
                }

            if (title.isBlank() || !seen.add(url)) continue

            val poster = getImage(
                card.selectFirst(".image")
            )

            results.add(
                createSearchResponse(title, url, poster)
            )
        }

        return results
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        return search(query, 1).results
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val pageNumber = page.coerceAtLeast(1)
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

        val url = if (pageNumber == 1) {
            "$mainUrl/?s=$encodedQuery"
        } else {
            "$mainUrl/page/$pageNumber/?s=$encodedQuery"
        }

        val document = getDocument(url)
        val results = parseSearch(document)

        return newSearchResponseList(
            results,
            hasNext = results.isNotEmpty()
        )
    }

    private fun getPageUrl(
        base: String,
        page: Int
    ): String {
        if (page <= 1) return base

        return "${base.trimEnd('/')}/page/$page/"
    }

    private fun hasNextPage(
        document: Document,
        page: Int
    ): Boolean {
        return document.select("a[href]").any { link ->
            val href = link.attr("href")

            val nextPage = Regex(
                """/page/(\d+)/?""",
                RegexOption.IGNORE_CASE
            ).find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            (nextPage != null && nextPage > page) ||
                link.attr("rel").equals("next", true) ||
                link.text().trim().equals("next", true) ||
                link.text().trim() == "»" ||
                link.text().trim() == "›"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageNumber = page.coerceAtLeast(1)

        val base = if (request.data.isBlank()) {
            "$mainUrl/"
        } else {
            absolute(request.data) ?: "$mainUrl/"
        }

        val url = getPageUrl(
            base,
            pageNumber
        )

        val document = getDocument(url)
        val results = parseHome(document)

        if (results.isEmpty()) {
            throw ErrorLoadingException(
                "No drama titles found on OnlyKDrama."
            )
        }

        return newHomePageResponse(
            HomePageList(
                request.name,
                results,
                isHorizontalImages = false
            ),
            hasNext = hasNextPage(
                document,
                pageNumber
            )
        )
    }
}
