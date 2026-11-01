
package com.onlykdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "" to "नए रिलीज़",
        "/genres/koren-drama/" to "कोरियन ड्रामा",
        "/genres/hindi-dubbed-drama/" to "हिंदी डब्ड ड्रामा",
        "/genres/ongoing/" to "जारी ड्रामा"
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
        val response = app.get(
            url,
            headers = headers
        )

        val html = response.text.lowercase()

        if (
            html.contains("cf-chl-") ||
            html.contains("challenge-platform") ||
            html.contains("just a moment") ||
            html.contains("verify you are human")
        ) {
            throw ErrorLoadingException(
                "OnlyKDrama requires browser verification."
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
        var current: Element = link

        repeat(7) {
            val parent = current.parent() ?: return current

            if (
                parent.selectFirst("img") != null &&
                parent.select("a[href*=/drama/]").isNotEmpty()
            ) {
                current = parent
            } else {
                return current
            }
        }

        return current
    }

    private fun getTitle(
        link: Element,
        card: Element
    ): String {

        val title = link.text().trim()
            .ifBlank {
                link.attr("title").trim()
            }
            .ifBlank {
                link.attr("aria-label").trim()
            }
            .ifBlank {
                card.selectFirst(
                    "h1, h2, h3, h4, .entry-title, .title"
                )?.text()?.trim().orEmpty()
            }
            .ifBlank {
                card.selectFirst("img")
                    ?.attr("alt")?.trim().orEmpty()
            }

        return title
    }

    private fun parseHome(
        document: Document
    ): List<SearchResponse> {

        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (link in document.select("a[href*=/drama/]")) {

            val url = absolute(link.attr("href")) ?: continue

            if (!url.startsWith("$mainUrl/")) continue

            val path = url.removePrefix(mainUrl)

            if (!path.startsWith("/drama/")) continue

            val card = getCard(link)
            val title = getTitle(link, card)

            if (title.isBlank()) continue

            if (!seen.add(url)) continue

            val poster = getImage(card)
                ?: getImage(link)

            val type = if (
                title.contains("movie", true) ||
                title.contains("film", true)
            ) {
                TvType.Movie
            } else {
                TvType.TvSeries
            }

            results.add(
                if (type == TvType.Movie) {
                    newMovieSearchResponse(
                        title,
                        url,
                        type
                    ) {
                        posterUrl = poster
                    }
                } else {
                    newTvSeriesSearchResponse(
                        title,
                        url,
                        type
                    ) {
                        posterUrl = poster
                    }
                }
            )
        }

        return results
    }

    private fun getPageUrl(
        base: String,
        page: Int
    ): String {

        if (page <= 1) return base

        val cleanBase = base.trimEnd('/')

        return "$cleanBase/page/$page/"
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
            absolute(request.data)
                ?: "$mainUrl/"
        }

        val url = getPageUrl(
            base,
            pageNumber
        )

        val document = getDocument(url)

        val results = parseHome(document)

        if (results.isEmpty()) {
            throw ErrorLoadingException(
                "No drama titles found. Website layout or request may have changed."
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
