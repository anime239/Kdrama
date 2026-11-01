
package com.onlykdrama

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

        val hasHomeCards =
            document.select("article.item").isNotEmpty()

        val hasSearchCards =
            document.select(".search-page .result-item").isNotEmpty()

        val hasDetailContent =
            document.selectFirst("#single .sheader .data h1") != null

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
                (
                    hasChallengePlatform &&
                        !hasHomeCards &&
                        !hasSearchCards &&
                        !hasDetailContent
                    )

        Log.d(
            "OnlyKDrama",
            "Response diagnostics: url=$url, htmlLength=${html.length}, homeCards=$hasHomeCards, searchCards=$hasSearchCards, detailContent=$hasDetailContent, cfChallengeToken=$hasCfChallengeToken, verificationText=$hasVerificationText, challengePlatform=$hasChallengePlatform, isChallenge=$isChallenge"
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
            ".poster a[href], .data h3 a[href]"
        )?.attr("href")

        val url = absolute(href) ?: return null

        return url.takeIf {
            it.startsWith("$mainUrl/drama/") ||
                it.startsWith("$mainUrl/movies/")
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

            if (title.isBlank() || !seen.add(url)) continue

            val poster = getPoster(article)
            val isMovie = url.contains("/movies/") ||
                article.hasClass("movies") ||
                title.contains("movie", true) ||
                title.contains("film", true)

            val response = if (isMovie) {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                }
            }

            results.add(response)
        }

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
            val isMovie = url.contains("/movies/")
            val isDrama = url.contains("/drama/")

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
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                }
            }

            results.add(response)
        }

        if (results.isEmpty()) {
            results.addAll(parseHome(document))
        }

        Log.d(
            "OnlyKDrama",
            "Advanced search completed: ${results.size} results found"
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

        val url = "$mainUrl/advance-search/?q=$encodedQuery&type=all&lang=&genre=&year=&min_rating=0&sort=latest"

        Log.d("OnlyKDrama", "Advanced search URL: $url")

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

    private fun getDetailPoster(document: Document): String? {
        val image = document.selectFirst(
            "#single .sheader .poster img"
        ) ?: return null

        val attributes = listOf(
            "src",
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-image"
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

    private fun getSynopsis(document: Document): String? {
        val info = document.selectFirst("#info") ?: return null

        val synopsisBox = info.select(".sbox").firstOrNull {
            it.selectFirst("h2")?.text()
                ?.trim()
                ?.equals("Synopsis", true) == true
        } ?: return null

        return synopsisBox.selectFirst(".wp-content")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
    }

    private fun getTags(document: Document): List<String> {
        return document.select(
            "#single .sheader .data .sgeneros a"
        ).map {
            it.text().trim()
        }.filter {
            it.isNotBlank()
        }.distinct()
    }

    private fun getYear(document: Document): Int? {
        val date = document.selectFirst(
            "#single .sheader .data .extra .date"
        )?.text().orEmpty()

        return Regex("[0-9]{4}")
            .find(date)
            ?.value
            ?.toIntOrNull()
    }

    private fun getRating(document: Document): Int? {
        return document.selectFirst(
            "#single .sheader .data .dt_rating_vgs"
        )?.text()
            ?.trim()
            ?.toFloatOrNull()
            ?.toInt()
    }

    private fun parseEpisodes(
        document: Document,
        poster: String?,
        plot: String?
        ): List<Episode> {
        val results = mutableListOf<Episode>()

        val contentRoot = document.selectFirst("#info .wp-content")
            ?: return results

        val allAccordions = contentRoot.select(
            ".wp-block-ub-content-toggle-accordion"
        )

        val episodeAccordions = allAccordions.filter { accordion ->
            val content = accordion.selectFirst(
                ".wp-block-ub-content-toggle-accordion-content-wrap"
            )

            content?.selectFirst(
                ".wp-block-ub-content-toggle-accordion"
            ) != null
        }

        val entries = if (episodeAccordions.isNotEmpty()) {
            episodeAccordions.mapIndexedNotNull { index, accordion ->
                val title = accordion.selectFirst(
                    ".wp-block-ub-content-toggle-accordion-title"
                )?.text()?.trim()?.ifBlank { null }
                    ?: "Episode ${index + 1}"

                val content = accordion.selectFirst(
                    ".wp-block-ub-content-toggle-accordion-content-wrap"
                ) ?: return@mapIndexedNotNull null

                Triple(title, content, index + 1)
            }
        } else {
            listOf(Triple("Episode 1", contentRoot, 1))
        }

        for ((episodeTitle, episodeContent, index) in entries) {
            val providerAccordions = episodeContent.select(
                ".wp-block-ub-content-toggle-accordion"
            )

            val links = JSONArray()
            val seenUrls = mutableSetOf<String>()

            val providerEntries = if (providerAccordions.isNotEmpty()) {
                providerAccordions.mapNotNull { accordion ->
                    val title = accordion.selectFirst(
                        ".wp-block-ub-content-toggle-accordion-title"
                    )?.text()?.trim()
                        ?.replace("Download Links", "")?.trim()
                        ?.ifBlank { null } ?: "Download"

                    val content = accordion.selectFirst(
                        ".wp-block-ub-content-toggle-accordion-content-wrap"
                    ) ?: return@mapNotNull null

                    title to content
                }
            } else {
                listOf("Download" to episodeContent)
            }

            for ((provider, providerContent) in providerEntries) {
                for (element in providerContent.select(
                    "a[href], [data-href], [data-url]"
                )) {
                    val rawUrl = listOf(
                        element.attr("href"),
                        element.attr("data-href"),
                        element.attr("data-url")
                    ).firstOrNull { it.isNotBlank() } ?: continue

                    val link = absolute(rawUrl) ?: continue

                    if (!link.startsWith("http", true)) continue
                    if (!seenUrls.add(link)) continue

                    val linkName = element.text().trim()
                        .ifBlank { element.attr("title").trim() }
                        .ifBlank { element.attr("aria-label").trim() }
                        .ifBlank { element.parent()?.text()?.trim().orEmpty() }
                        .ifBlank { provider }

                    links.put(
                        JSONObject()
                            .put("name", linkName)
                            .put("url", link)
                            .put("provider", provider)
                    )
                }
            }

            Log.d(
                "OnlyKDrama",
                "$episodeTitle: ${links.length()} download links collected"
            )

            if (links.length() == 0) continue

            val episodeNumber = Regex("[0-9]+")
                .find(episodeTitle)?.value?.toIntOrNull() ?: index

            val episodeData = JSONObject()
                .put("title", episodeTitle)
                .put("links", links)
                .toString()

            results.add(newEpisode(episodeData) {
                name = episodeTitle
                season = 1
                episode = episodeNumber
                posterUrl = poster
                description = plot
            })
        }

        Log.d(
            "OnlyKDrama",
            "Total playable episode entries: ${results.size}"
        )

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)

        val title = document.selectFirst(
            "#single .sheader .data h1"
        )?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = getDetailPoster(document)
        val plot = getSynopsis(document)
        val tags = getTags(document)
        val year = getYear(document)
        val rating = getRating(document)

        val isMovie = url.contains("/movies/")

        if (isMovie) {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
            }
        }

        val episodes = parseEpisodes(
            document,
            poster,
            plot
        )

        if (episodes.isEmpty()) {
            throw ErrorLoadingException(
                "No episode download links found on this drama page."
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = rating?.let { Score.from10(it) }
        }
    }

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
    ): Boolean {
    val links = runCatching {
        JSONObject(data).getJSONArray("links")
    }.getOrNull() ?: return false

    val items = (0 until links.length()).mapNotNull { index ->
        links.optJSONObject(index)?.let { item ->
            Triple(
                item.optString("url"),
                item.optString("provider"),
                item.optString("name")
            )
        }
    }.filter { it.first.isNotBlank() }
        .distinctBy { it.first }

    if (items.isEmpty()) return false

    val semaphore = Semaphore(10)
    val callbackLock = Any()
    val startTime = System.currentTimeMillis()

    val results = coroutineScope {
        items.map { (url, provider, fileName) ->
            async {
                semaphore.withPermit {
                    var extracted = false

                    try {
                        Log.d(
                            "OnlyKDrama",
                            "Starting extraction: $provider; $fileName"
                        )

                        loadExtractor(
                            url,
                            mainUrl,
                            { subtitle ->
                                synchronized(callbackLock) {
                                    subtitleCallback(subtitle)
                                }
                            }
                        ) { link ->
                            extracted = true

                            synchronized(callbackLock) {
                                callback(link)
                            }
                        }

                        Log.d(
                            "OnlyKDrama",
                            "Extraction completed: $provider; success=$extracted"
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(
                            "OnlyKDrama",
                            "Extraction failed: $provider; $fileName; ${e.message}"
                        )
                    }

                    extracted
                }
            }
        }.awaitAll()
    }

    val successful = results.count { it }

    Log.d(
        "OnlyKDrama",
        "Extraction finished: $successful/${items.size} providers in ${System.currentTimeMillis() - startTime}ms"
    )

    return successful > 0
}

}
