
package com.onlykdrama

import android.util.Log
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

        Log.d(
            "OnlyKDrama",
            "Requesting $url; savedCookies=${OnlyKdramaSession.savedCookieNamesForLog()}"
        )

        val response = app.get(
            url,
            headers = requestHeaders
        )

        val html = response.text
        val lower = html.lowercase()
        val document = response.document

        val hasDramaCards =
            document.select("article.item").isNotEmpty()

        val hasCfChallengeToken =
            lower.contains("cf-chl-") ||
                lower.contains("__cf_chl_tk")

        val hasVerificationText =
            lower.contains("just a moment") ||
                lower.contains("verify you are human")

        val hasChallengePlatform =
            lower.contains("challenge-platform")

        val isChallenge =
            hasCfChallengeToken ||
                hasVerificationText ||
                (hasChallengePlatform && !hasDramaCards)

        Log.d(
            "OnlyKDrama",
            "Response diagnostics: url=$url, htmlLength=${html.length}, dramaCards=$hasDramaCards, cfChallengeToken=$hasCfChallengeToken, verificationText=$hasVerificationText, challengePlatform=$hasChallengePlatform, isChallenge=$isChallenge"
        )

        if (isChallenge) {
            Log.e(
                "OnlyKDrama",
                "Cloudflare challenge detected. URL=$url; cookieNames=${OnlyKdramaSession.savedCookieNamesForLog()}; cf_clearance=${OnlyKdramaSession.hasCloudflareClearanceForLog()}"
            )

            throw ErrorLoadingException(
                "OnlyKDrama returned a Cloudflare verification page."
            )
        }

        return document
    }

    private fun getPoster(article: Element): String? {
        val image = article.selectFirst(".poster img") ?: return null

        val attributes = listOf(
            "src",
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-image",
            "data-lazy"
        )

        for (attribute in attributes) {
            val value = image.attr(attribute).trim()

            if (value.isBlank() || value.startsWith("data:")) {
                continue
            }

            return absolute(value)
        }

        return null
    }

    private fun getDramaUrl(article: Element): String? {
        val href = article.selectFirst(
            ".poster a[href]"
        )?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?: article.selectFirst(
                ".data h3 a[href]"
            )?.attr("href")

        val url = absolute(href) ?: return null

        return url.takeIf {
            it.startsWith("$mainUrl/drama/")
        }
    }

    private fun getDramaTitle(article: Element): String {
        return article.selectFirst(
            ".data h3 a"
        )?.text()?.trim()
            ?.ifBlank { null }
            ?: article.selectFirst(
                ".poster img"
            )?.attr("alt")?.trim()
                ?.ifBlank { null }
            ?: ""
    }

    private fun parseHome(
        document: Document
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (article in document.select("article.item")) {
            val url = getDramaUrl(article) ?: continue
            val title = getDramaTitle(article)

            if (title.isBlank()) continue
            if (!seen.add(url)) continue

            val poster = getPoster(article)

            val isMovie =
                article.hasClass("movies") ||
                    title.contains("movie", true) ||
                    title.contains("film", true)

            val response = if (isMovie) {
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

            results.add(response)
        }

        Log.d(
            "OnlyKDrama",
            "Homepage parsing completed: ${results.size} titles found"
        )

        return results
    }

    private fun getSearchPoster(card: Element): String? {
        val image = card.selectFirst(".image img") ?: return null

        val attributes = listOf(
            "data-lazy-src",
            "data-src",
            "data-original",
            "data-image",
            "data-lazy",
            "src"
        )

        for (attribute in attributes) {
            val value = image.attr(attribute).trim()

            if (value.isBlank() || value.startsWith("data:")) {
                continue
            }

            return absolute(value)
        }

        return null
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

            val isMovie = path.startsWith("/movies/")
            val isDrama = path.startsWith("/drama/")

            if (!isMovie && !isDrama) continue
            if (!seen.add(url)) continue

            val title = card.selectFirst(
                ".details .title a"
            )?.text()?.trim()
                ?.ifBlank { null }
                ?: card.selectFirst(".image img")
                    ?.attr("alt")?.trim()
                    ?.ifBlank { null }
                ?: continue

            val poster = getSearchPoster(card)

            val response = if (isMovie) {
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

            results.add(response)
        }

        Log.d(
            "OnlyKDrama",
            "Search completed: ${results.size} results found"
        )

        return results
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        val url = "$mainUrl/?s=$encodedQuery"
        val document = getDocument(url)

        return parseSearch(document)
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

        val url = getPageUrl(base, pageNumber)
        val document = getDocument(url)
        val results = parseHome(document)

        if (results.isEmpty()) {
            throw ErrorLoadingException(
                "No drama cards found on OnlyKDrama. Check the response diagnostics."
            )
        }

        return newHomePageResponse(
            HomePageList(
                request.name,
                results,
                isHorizontalImages = false
            ),
            hasNext = hasNextPage(document, pageNumber)
        )
    }
}
