
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
        val response = app.get(url, headers = headers)
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

    private fun imageFrom(element: Element?): String? {
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

            absolute(selected)?.let { return it }
        }

        return null
    }

    private fun cardTitle(
        link: Element,
        card: Element
    ): String {
        return link.text().trim()
            .ifBlank { link.attr("title").trim() }
            .ifBlank { link.attr("aria-label").trim() }
            .ifBlank {
                card.selectFirst(
                    "h1, h2, h3, h4, .title, .entry-title"
                )?.text()?.trim().orEmpty()
            }
            .ifBlank {
                card.selectFirst("img")
                    ?.attr("alt")?.trim().orEmpty()
            }
    }

    private fun parseCards(
        document: Document
    ): List<SearchResponse> {

        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (link in document.select("a[href*=/drama/]")) {
            val url = absolute(link.attr("href")) ?: continue

            if (!url.startsWith("$mainUrl/")) continue
            if (!seen.add(url)) continue

            var card = link
            var parent = link.parent()

            var depth = 0
            while (parent != null && depth < 6) {
                if (parent.selectFirst("img") != null) {
                    card = parent
                    break
                }

                parent = parent.parent()
                depth++
            }

            val title = cardTitle(link, card)
            if (title.isBlank()) continue

            val poster = imageFrom(card) ?: imageFrom(link)

            val isMovie =
                title.contains("movie", true) ||
                Regex(
                    """\bMovie\b""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(card.text())

            results.add(
                if (isMovie) {
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
            )
        }

        return results
    }

    private fun hasNext(
        document: Document,
        page: Int
    ): Boolean {
        return document.select("a[href]").any { anchor ->

            val href = anchor.attr("href")

            val nextPage = Regex(
                """(?:/page/|[?&]paged=)(\d+)""",
                RegexOption.IGNORE_CASE
            ).find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            (nextPage != null && nextPage > page) ||
                anchor.attr("rel").equals("next", true) ||
                anchor.text().trim().equals("next", true) ||
                anchor.text().trim() == "»" ||
                anchor.text().trim() == "›"
        }
    }

    private fun standardSearchUrl(
        query: String,
        page: Int
    ): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        return if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }
    }

    private fun advancedSearchUrl(
        query: String,
        page: Int
    ): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        return buildString {
            append("$mainUrl/advance-search/?q=$encoded")
            append("&type=all")
            append("&lang=")
            append("&genre=")
            append("&year=")
            append("&min_rating=0")
            append("&sort=latest")

            if (page > 1) {
                append("&paged=$page")
            }
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

        val url = if (base.contains("?")) {
            if (pageNumber == 1) base
            else "$base&paged=$pageNumber"
        } else {
            if (pageNumber == 1) base
            else "${base.trimEnd('/')}/page/$pageNumber/"
        }

        val document = getDocument(url)
        val results = parseCards(document)

        if (results.isEmpty()) {
            throw ErrorLoadingException(
                "No titles found on OnlyKDrama."
            )
        }

        return newHomePageResponse(
            HomePageList(
                request.name,
                results,
                isHorizontalImages = false
            ),
            hasNext = hasNext(document, pageNumber)
        )
    }

    private suspend fun searchResults(
        query: String,
        page: Int
    ): Pair<List<SearchResponse>, Boolean> {

        val pageNumber = page.coerceAtLeast(1)

        var standardDocument: Document? = null

        try {
            standardDocument = getDocument(
                standardSearchUrl(query, pageNumber)
            )

            val results = parseCards(standardDocument)

            if (results.isNotEmpty()) {
                return results to hasNext(
                    standardDocument,
                    pageNumber
                )
            }
        } catch (_: Exception) {
        }

        try {
            val advancedDocument = getDocument(
                advancedSearchUrl(query, pageNumber)
            )

            val results = parseCards(advancedDocument)

            return results to hasNext(
                advancedDocument,
                pageNumber
            )
        } catch (e: Exception) {
            if (standardDocument != null) {
                return emptyList<SearchResponse>() to false
            }

            throw ErrorLoadingException(
                "OnlyKDrama search failed: ${e.message}"
            )
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        return searchResults(query, 1).first
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {

        val (results, next) = searchResults(query, page)

        return newSearchResponseList(
            results,
            hasNext = next
        )
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return searchResults(query, 1).first
    }

    private fun getPoster(
        document: Document
    ): String? {
        return document.selectFirst(
            "meta[property=og:image]"
        )?.attr("content")
            ?.let { absolute(it) }
            ?: imageFrom(
                document.selectFirst(
                    ".poster, .entry-content, article, main"
                )
            )
    }

    private fun getPlot(
        document: Document
    ): String? {

        val heading = document.select(
            "h1, h2, h3, h4"
        ).firstOrNull {
            it.text().trim().equals(
                "Synopsis",
                ignoreCase = true
            )
        }

        val synopsisText = heading
            ?.nextElementSibling()
            ?.text()
            ?.trim()

        return synopsisText?.takeIf {
            it.isNotBlank()
        } ?: document.selectFirst(
            "meta[property=og:description]"
        )?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(
                "meta[name=description]"
            )?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val pageUrl = absolute(url)
            ?: throw ErrorLoadingException("Invalid URL")

        val document = getDocument(pageUrl)

        val title = document.selectFirst(
            "h1.entry-title, h1"
        )?.text()?.trim()
            ?: document.selectFirst(
                "meta[property=og:title]"
            )?.attr("content")?.trim()
            ?: throw ErrorLoadingException(
                "Title not found."
            )

        val plot = getPlot(document)
        val poster = getPoster(document)

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""")
                .find(document.text())?.value?.toIntOrNull()

        val tags = document.select(
            "a[rel=tag], a[href*='/genres/']"
        )
            .map { it.text().trim() }
            .filter {
                it.isNotBlank() && it.length < 40
            }
            .distinct()

        val isMovie = title.contains(
            "movie",
            ignoreCase = true
        )

        if (isMovie) {
            return newMovieLoadResponse(
                title,
                pageUrl,
                TvType.Movie,
                pageUrl
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val episode = newEpisode(pageUrl) {
            name = "Available Episodes"
            season = 1
            episode = 1
        }

        return newTvSeriesLoadResponse(
            title,
            pageUrl,
            TvType.TvSeries,
            listOf(episode)
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private fun isDownloadLink(url: String): Boolean {

        val host = url.substringAfter("//")
            .substringBefore("/")
            .substringBefore(":")
            .lowercase()

        return listOf(
            "hubcloud",
            "xcloud",
            "gdflix",
            "gdrive",
            "drive.google",
            "gofile",
            "mega.nz",
            "pixeldrain",
            "terabox",
            "filepress",
            "hubdrive",
            "streamtape",
            "mixdrop",
            "streamwish"
        ).any { host.contains(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl = absolute(data) ?: return false

        val document = getDocument(pageUrl)

        val content = document.selectFirst(
            ".entry-content, .post-content, article, main"
        ) ?: document

        val links = content.select("a[href]")
            .mapNotNull {
                absolute(it.attr("href"))
            }
            .filter {
                isDownloadLink(it)
            }
            .distinct()

        if (links.isEmpty()) {
            throw ErrorLoadingException(
                "No supported download links found."
            )
        }

        var extracted = false

        for (link in links) {
            try {
                loadExtractor(
                    link,
                    pageUrl,
                    subtitleCallback,
                    callback
                )
                extracted = true
            } catch (_: Exception) {
            }
        }

        return extracted
    }
}
