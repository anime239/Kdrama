
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
        TvType.Movie,
        TvType.AsianDrama
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "" to "New Releases",
        "/genres/koren-drama/" to "Korean Drama",
        "/genres/hindi-dubbed-drama/" to "Hindi Dubbed Drama",
        "/genres/ongoing/" to "Ongoing Dramas"
    )

    private fun absolute(url: String?): String? {
        val value = url?.trim()
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?: return null

        if (value.isBlank() ||
            value.startsWith("data:") ||
            value.startsWith("javascript:")
        ) return null

        return fixUrl(value)
    }

    private suspend fun getDocument(url: String): Document {
        val response = app.get(url, headers = headers)
        val document = response.document
        val html = response.text.lowercase()

        if (
            html.contains("cf-chl-") ||
            html.contains("challenge-platform") ||
            html.contains("just a moment") ||
            html.contains("verify you are human")
        ) {
            throw ErrorLoadingException(
                "OnlyKDrama is asking for browser verification. Try again later or open the site in a browser."
            )
        }

        return document
    }

    private fun imageFrom(element: Element?): String? {
        if (element == null) return null

        val image = element.selectFirst("img") ?: return null

        val candidates = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-srcset"),
            image.attr("srcset"),
            image.attr("data-lazy"),
            image.attr("src"),
            image.attr("data-image")
        )

        for (candidate in candidates) {
            if (candidate.isBlank()) continue

            val chosen = if (candidate.contains(",")) {
                candidate.split(",").last()
                    .trim()
                    .substringBefore(" ")
            } else {
                candidate.trim().substringBefore(" ")
            }

            absolute(chosen)?.let { return it }
        }

        return null
    }

    private fun isContentUrl(rawUrl: String): Boolean {
        val url = absolute(rawUrl) ?: return false

        if (!url.startsWith(mainUrl)) return false

        val path = url.removePrefix(mainUrl)
            .substringBefore("?")
            .substringBefore("#")

        if (path.isBlank() || path == "/") return false

        if (
            path.contains("/genres/") ||
            path.contains("/category/") ||
            path.contains("/tag/") ||
            path.contains("/page/") ||
            path.contains("/wp-") ||
            path.endsWith(".xml")
        ) return false

        return true
    }

    private fun cardTitle(card: Element, link: Element): String {
        return link.text().trim()
            .ifBlank { link.attr("title").trim() }
            .ifBlank {
                card.selectFirst("h1, h2, h3, h4, .title, .entry-title")
                    ?.text()?.trim().orEmpty()
            }
            .ifBlank {
                card.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val output = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val containers = document.select(
            "article, .post, .item, .movie, .result-item, .grid-item, .entry"
        )

        val roots = if (containers.isNotEmpty()) {
            containers
        } else {
            document.select("a[href]:has(img)")
        }

        for (root in roots) {
            val anchors = if (root.tagName() == "a") {
                listOf(root)
            } else {
                root.select("a[href]")
            }

            val link = anchors.firstOrNull {
                isContentUrl(it.attr("href"))
            } ?: continue

            val url = absolute(link.attr("href")) ?: continue

            if (!seen.add(url)) continue

            val title = cardTitle(root, link)
            if (title.isBlank()) continue

            val poster = imageFrom(root) ?: imageFrom(link)

            val type = if (title.contains("movie", true)) {
                TvType.Movie
            } else {
                TvType.TvSeries
            }

            output.add(
                if (type == TvType.Movie) {
                    newMovieSearchResponse(title, url, type) {
                        posterUrl = poster
                    }
                } else {
                    newTvSeriesSearchResponse(title, url, type) {
                        posterUrl = poster
                    }
                }
            )
        }

        return output
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base

        return if (base.contains("?")) {
            "$base&paged=$page"
        } else {
            "${base.trimEnd('/')}/page/$page/"
        }
    }

    private fun hasNext(document: Document, page: Int): Boolean {
        return document.select("a[href]").any { anchor ->
            val href = anchor.attr("href")

            val nextPage = Regex(
                "(?:/page/|[?&]paged=)(\\d+)",
                RegexOption.IGNORE_CASE
            ).find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            (nextPage != null && nextPage > page) ||
                anchor.attr("rel").equals("next", true) ||
                anchor.text().trim().equals("next", true) ||
                anchor.text().trim() == "›" ||
                anchor.text().trim() == "»"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = if (request.data.isBlank()) {
            "$mainUrl/"
        } else {
            absolute(request.data) ?: "$mainUrl/"
        }

        val document = getDocument(
            pageUrl(base, page.coerceAtLeast(1))
        )

        val results = parseCards(document)

        if (results.isEmpty()) {
            throw ErrorLoadingException(
                "No titles found on OnlyKDrama. The site layout may have changed or the request was blocked."
            )
        }

        return newHomePageResponse(
            HomePageList(
                request.name,
                results,
                isHorizontalImages = false
            ),
            hasNext = hasNext(document, page)
        )
    }

    private suspend fun searchResults(
        query: String,
        page: Int
    ): Pair<List<SearchResponse>, Boolean> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        val url = "$mainUrl/?s=$encoded" +
            if (page > 1) "&paged=$page" else ""

        val document = getDocument(url)

        return parseCards(document) to hasNext(document, page)
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        return searchResults(query, 1).first
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val (results, next) = searchResults(
            query,
            page.coerceAtLeast(1)
        )

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

    private fun sectionText(
        document: Document,
        headingText: String
    ): String? {
        val heading = document.select("h1, h2, h3, h4, strong, b")
            .firstOrNull {
                it.text().trim().equals(headingText, true)
            } ?: return null

        val next = heading.nextElementSibling()
            ?: heading.parent()?.nextElementSibling()

        return next?.text()?.trim()?.takeIf {
            it.isNotBlank()
        }
    }

    private fun getPlot(document: Document): String? {
        return sectionText(document, "Synopsis")
            ?: document.selectFirst("meta[property=og:description]")
                ?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")
                ?.attr("content")?.trim()
    }

    private fun getPoster(document: Document): String? {
        return document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.let { absolute(it) }
            ?: imageFrom(
                document.selectFirst(
                    ".poster, .entry-content, article, .post, main"
                )
            )
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = absolute(url)
            ?: throw ErrorLoadingException("Invalid OnlyKDrama URL")

        val document = getDocument(pageUrl)

        val title = document.selectFirst(
            "h1.entry-title, h1"
        )?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")?.trim()
            ?: throw ErrorLoadingException(
                "Could not read the title from OnlyKDrama"
            )

        val plot = getPlot(document)

        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(title)?.value?.toIntOrNull()
            ?: Regex("\\b(19|20)\\d{2}\\b")
                .find(document.text())?.value?.toIntOrNull()

        val tags = document.select(
            "a[rel=tag], .genres a, a[href*='/genres/']"
        )
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()

        val type = if (title.contains("movie", true)) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        val episode = newEpisode(pageUrl) {
            name = "Available Episodes / Links"
            season = 1
            episode = 1
        }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(
                title,
                pageUrl,
                TvType.Movie,
                pageUrl
            ) {
                posterUrl = getPoster(document)
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(
                title,
                pageUrl,
                TvType.TvSeries,
                listOf(episode)
            ) {
                posterUrl = getPoster(document)
                this.plot = plot
                this.year = year
                this.tags = tags
            }
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
            "drive.google",
            "gofile",
            "mega.nz",
            "pixeldrain",
            "terabox",
            "gdflix",
            "hubdrive",
            "filepress",
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

        val content = document.select(
            ".entry-content, .post-content, article, main"
        ).firstOrNull() ?: document

        val links = content.select("a[href]")
            .mapNotNull {
                absolute(it.attr("href"))
            }
            .filter { isDownloadLink(it) }
            .distinct()

        if (links.isEmpty()) {
            throw ErrorLoadingException(
                "No supported episode/download links were found on this title page."
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
