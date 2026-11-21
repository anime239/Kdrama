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
import java.util.concurrent.atomic.AtomicBoolean

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
        "user-agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "accept-language" to "en-US,en;q=0.9"
    )

    private val requestSemaphore = Semaphore(2)

    override val mainPage = mainPageOf(
        "" to "New Releases",
        "/drama/" to "TV Shows",
        "/movies/" to "Movies",
        "/genres/ongoing/" to "Ongoing",
        "/genres/action/" to "Action",
        "/genres/romance/" to "Romance",
        "/genres/youth/" to "Youth",
        "/genres/comedy/" to "Comedy",
        "/genres/thriller/" to "Thriller",
        "/genres/mystery/" to "Mystery",
        "/genres/crime/" to "Crime",
        "/genres/horror/" to "Horror",
        "/genres/adventure/" to "Adventure",
        "/genres/sci-fi/" to "Sci-Fi"
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

    private suspend fun getDocument(
        url: String,
        allowVerificationRetry: Boolean = true
    ): Document {
        val versionBeforeRequest = OnlyKdramaSession.getSessionVersion()
        val requestHeaders = OnlyKdramaSession.headers(headers)
        val requestCookies = OnlyKdramaSession.cookieMap()

        Log.d(
            "OnlyKDrama",
            "Requesting $url; version=$versionBeforeRequest; cookies=${requestCookies.keys}"
        )

        val response = requestSemaphore.withPermit {
            app.get(
                url = url,
                headers = requestHeaders,
                referer = "$mainUrl/",
                cookies = requestCookies
            )
        }

        val html = response.text
        val lower = html.lowercase()
        val document = response.document

        val hasHomeCards =
            document.select("article.item").isNotEmpty()

        val hasSearchCards =
            document.select(".search-page .result-item").isNotEmpty()

        val hasDetailContent =
            document.selectFirst("#single .sheader .data h1") != null

        val hasSiteShell =
            document.selectFirst(
                "#contenedor, header#header, .search-page, .head-main-nav, footer.main, #searchform, .module, .no-result, form[action*='advance-search']"
            ) != null

        val pageTitle = document.title().lowercase()

        val hasChallengeTitle =
            pageTitle.contains("just a moment") ||
                pageTitle.contains("attention required") ||
                pageTitle.contains("verify you are human")

        val hasValidPage =
            hasHomeCards ||
                hasSearchCards ||
                hasDetailContent ||
                (response.code == 200 && hasSiteShell && !hasChallengeTitle)

        val cfMitigated =
            response.headers["cf-mitigated"]?.contains("challenge", true) == true

        val hasCfChallengeToken =
            lower.contains("cf-chl-") ||
                lower.contains("__cf_chl_tk")

        val hasVerificationText =
            lower.contains("just a moment") ||
                lower.contains("verify you are human") ||
                lower.contains("checking your browser")

        val hasChallengePlatform =
            lower.contains("challenge-platform")

        val isChallenge =
            !hasValidPage && (
                cfMitigated ||
                    hasChallengeTitle ||
                    hasCfChallengeToken ||
                    hasVerificationText ||
                    response.code == 403 ||
                    response.code == 503 ||
                    (hasChallengePlatform && response.code != 200)
                )

        Log.d(
            "OnlyKDrama",
            "Response diagnostics: url=$url, code=${response.code}, htmlLength=${html.length}, validPage=$hasValidPage, siteShell=$hasSiteShell, cfMitigated=$cfMitigated, isChallenge=$isChallenge"
        )

        if (isChallenge) {
            Log.e(
                "OnlyKDrama",
                "Cloudflare challenge detected: $url"
            )

            if (allowVerificationRetry) {
                val verified = OnlyKdramaVerification.awaitVerification(
                    failedSessionVersion = versionBeforeRequest
                )
                if (verified) {
                    return getDocument(url, allowVerificationRetry = false)
                }
            }

            throw ErrorLoadingException(
                "Cloudflare verification required. Complete verification in the OnlyKDrama popup and retry."
            )
        }

        if (response.cookies.isNotEmpty()) {
            OnlyKdramaSession.mergeCookies(response.cookies)
        } else {
            OnlyKdramaSession.recordBackendSuccess(requestHeaders["user-agent"])
        }

        return document
    }

    private fun handleExtractorChallenge(error: Throwable) {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()

        Log.e("OnlyKDrama", "Extractor error: $message")
    }

    private fun parseBestSrcsetUrl(srcset: String): String? {
        if (srcset.isBlank()) return null

        return srcset
            .split(Regex("""(?<=\s\d+(?:\.\d+)?[wx]),\s*"""))
            .mapNotNull { entry ->
                val parts = entry.trim().split(Regex("""\s+"""))
                val rawUrl = parts.firstOrNull()?.takeIf {
                    it.isNotBlank() && !it.startsWith("data:")
                } ?: return@mapNotNull null

                val descriptor = parts.getOrNull(1).orEmpty().lowercase()
                val score = when {
                    descriptor.endsWith("w") ->
                        descriptor.removeSuffix("w").toFloatOrNull() ?: 0f
                    descriptor.endsWith("x") ->
                        (descriptor.removeSuffix("x").toFloatOrNull() ?: 0f) * 1000f
                    else -> 0f
                }

                rawUrl to score
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun upgradePosterUrl(url: String): String {
        var upgraded = url

        upgraded = upgraded.replace(
            Regex(
                """-\d{2,4}x\d{2,4}(?=\.(?:jpe?g|png|webp|avif)(?:\?.*)?$)""",
                RegexOption.IGNORE_CASE
            ),
            ""
        )

        upgraded = upgraded.replace(
            Regex("""/t/p/w(?:92|154|185|300|342)/"""),
            "/t/p/w780/"
        )

        if (upgraded.contains(".wp.com/")) {
            upgraded = upgraded
                .replace(Regex("""([?&])(?:resize|fit|w|h)=[^&]*"""), "$1")
                .replace(Regex("""\?&+"""), "?")
                .replace(Regex("""&{2,}"""), "&")
                .trimEnd('?', '&')
        }

        return upgraded
    }

    private fun extractBestPoster(image: Element?): String? {
        if (image == null) return null

        val srcsetAttributes = listOf(
            "data-lazy-srcset",
            "data-srcset",
            "srcset"
        )
        for (attr in srcsetAttributes) {
            val bestFromSrcset = parseBestSrcsetUrl(image.attr(attr))
            val resolved = absolute(bestFromSrcset)
            if (resolved != null) {
                return upgradePosterUrl(resolved)
            }
        }

        val imageAttributes = listOf(
            "data-lazy-src",
            "data-src",
            "data-original",
            "data-image",
            "data-lazy",
            "src"
        )
        for (attr in imageAttributes) {
            val value = image.attr(attr).trim()
            if (value.isBlank() || value.startsWith("data:")) continue

            val resolved = absolute(value)
            if (resolved != null) {
                return upgradePosterUrl(resolved)
            }
        }

        return null
    }

    private fun getPoster(article: Element): String? {
        return extractBestPoster(article.selectFirst(".poster img"))
            ?: extractBestPoster(article.selectFirst(".poster noscript img"))
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
        return extractBestPoster(card.selectFirst(".image img"))
            ?: extractBestPoster(card.selectFirst(".image noscript img"))
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
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(
            cleanQuery,
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
        return extractBestPoster(document.selectFirst("#single .sheader .poster img"))
            ?: extractBestPoster(document.selectFirst("#single .sheader .poster noscript img"))
    }

    private fun getSynopsis(document: Document): String? {
        val info = document.selectFirst("#info") ?: return null

        val synopsisBox = info.select(".sbox").firstOrNull {
            it.selectFirst("h2")?.text()
                ?.trim()
                ?.equals("Synopsis", true) == true
        } ?: return null

        val content = synopsisBox.selectFirst(".wp-content") ?: return null

        val synopsis = content.select("p")
            .map { it.text().trim() }
            .firstOrNull { it.isNotBlank() }
            ?: content.ownText().trim()

        return synopsis
            .replace(Regex("\\s+"), " ")
            .ifBlank { null }
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

    private fun parseMovieLinks(document: Document): String {
        val root = document.selectFirst(
            "#info .wp-content > .wp-block-ub-content-toggle-block"
        ) ?: return JSONObject().put("links", JSONArray()).toString()

        val links = JSONArray()
        val seenUrls = mutableSetOf<String>()

        val providerAccordions = root.children().filter {
            it.hasClass("wp-block-ub-content-toggle-accordion")
        }

        for (accordion in providerAccordions) {
            val provider = accordion.selectFirst(
                ".wp-block-ub-content-toggle-accordion-title"
            )?.text()
                ?.trim()
                ?.replace(Regex("""(?i)\s*download\s*links\s*"""), "")
                ?.trim()
                ?.ifBlank { null }
                ?: "Download"

            val content = accordion.selectFirst(
                ".wp-block-ub-content-toggle-accordion-content-wrap"
            ) ?: continue

            for (element in content.select(
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

                val fileName = element.text()
                    .trim()
                    .ifBlank { element.attr("title").trim() }
                    .ifBlank { element.attr("aria-label").trim() }
                    .ifBlank { provider }

                links.put(
                    JSONObject()
                        .put("name", fileName)
                        .put("url", link)
                        .put("provider", provider)
                )
            }
        }

        return JSONObject()
            .put("links", links)
            .toString()
    }

    private fun parseEpisodes(
        document: Document,
        poster: String?,
        plot: String?
    ): List<Episode> {
        val contentRoot = document.selectFirst("#info .wp-content")
            ?: return emptyList()

        val accordions = contentRoot.select(
            ".wp-block-ub-content-toggle-accordion"
        ).filter { accordion ->
            accordion.parents().none {
                it.hasClass("wp-block-ub-content-toggle-accordion")
            }
        }

        if (accordions.isEmpty()) return emptyList()

        val nestedLayout = accordions.any { accordion ->
            accordion.selectFirst(
                ".wp-block-ub-content-toggle-accordion-content-wrap " +
                    ".wp-block-ub-content-toggle-accordion"
            ) != null
        }

        val results = mutableListOf<Episode>()

        if (nestedLayout) {
            val episodeAccordions = accordions.filter { accordion ->
                accordion.selectFirst(
                    ".wp-block-ub-content-toggle-accordion-content-wrap " +
                        ".wp-block-ub-content-toggle-accordion"
                ) != null
            }

            val entries = if (episodeAccordions.isNotEmpty()) {
                episodeAccordions.mapIndexedNotNull { index, accordion ->
                    val title = accordion.selectFirst(
                        ".wp-block-ub-content-toggle-accordion-title"
                    )?.text()?.trim()
                        ?.ifBlank { null }
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

                val providers = if (providerAccordions.isNotEmpty()) {
                    providerAccordions.mapNotNull { accordion ->
                        val title = accordion.selectFirst(
                            ".wp-block-ub-content-toggle-accordion-title"
                        )?.text()?.trim()
                            ?.replace("Download Links", "")
                            ?.trim()
                            ?.ifBlank { null }
                            ?: "Download"

                        val content = accordion.selectFirst(
                            ".wp-block-ub-content-toggle-accordion-content-wrap"
                        ) ?: return@mapNotNull null

                        title to content
                    }
                } else {
                    listOf("Download" to episodeContent)
                }

                for ((provider, providerContent) in providers) {
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

                if (links.length() == 0) continue

                val episodeNumber = Regex("[0-9]+")
                    .find(episodeTitle)
                    ?.value
                    ?.toIntOrNull() ?: index

                val seasonNumber = Regex(
                    """(?i)\bS(?:eason)?\s*0*(\d+)"""
                ).find(episodeTitle)
                    ?.groupValues?.getOrNull(1)
                    ?.toIntOrNull() ?: 1

                val episodeData = JSONObject()
                    .put("title", episodeTitle)
                    .put("links", links)
                    .toString()

                results.add(newEpisode(episodeData) {
                    name = episodeTitle
                    season = seasonNumber
                    episode = episodeNumber
                    posterUrl = poster
                    description = plot?.take(180)?.let {
                        if (plot.length > 180) {
                            "${it.substringBeforeLast(" ")}..."
                        } else {
                            it
                        }
                    }
                })
            }
        } else {
            data class EpisodeGroup(
                val season: Int,
                val episode: Int,
                val links: JSONArray = JSONArray(),
                val seen: MutableSet<String> = mutableSetOf()
            )

            val episodeGroups = linkedMapOf<Pair<Int, Int>, EpisodeGroup>()

            for (accordion in accordions) {
                val provider = accordion.selectFirst(
                    ".wp-block-ub-content-toggle-accordion-title"
                )?.text()?.trim()
                    ?.ifBlank { null }
                    ?: "Download"

                val content = accordion.selectFirst(
                    ".wp-block-ub-content-toggle-accordion-content-wrap"
                ) ?: continue

                for (element in content.select(
                    "a[href], [data-href], [data-url]"
                )) {
                    val rawUrl = listOf(
                        element.attr("href"),
                        element.attr("data-href"),
                        element.attr("data-url")
                    ).firstOrNull { it.isNotBlank() } ?: continue

                    val link = absolute(rawUrl) ?: continue
                    if (!link.startsWith("http", true)) continue

                    val fileName = element.text().trim()
                        .ifBlank { element.attr("title").trim() }
                        .ifBlank { element.attr("aria-label").trim() }

                    val sourceText = "$fileName $provider"

                    val match = Regex(
                        """(?i)\bS\s*0*(\d{1,2})\s*E\s*0*(\d{1,3})\b"""
                    ).find(sourceText) ?: continue

                    val season = match.groupValues[1].toIntOrNull() ?: 1
                    val episode = match.groupValues[2].toIntOrNull() ?: continue

                    val key = season to episode
                    val group = episodeGroups.getOrPut(key) {
                        EpisodeGroup(season, episode)
                    }

                    if (!group.seen.add(link)) continue

                    group.links.put(
                        JSONObject()
                            .put("name", fileName.ifBlank { provider })
                            .put("url", link)
                            .put("provider", provider)
                    )
                }
            }

            for ((_, group) in episodeGroups) {
                if (group.links.length() == 0) continue

                val episodeTitle = "Episode ${group.episode}"

                val episodeData = JSONObject()
                    .put("title", episodeTitle)
                    .put("links", group.links)
                    .toString()

                results.add(newEpisode(episodeData) {
                    name = episodeTitle
                    season = group.season
                    episode = group.episode
                    posterUrl = poster
                    description = plot?.take(180)?.let {
                        if (plot.length > 180) {
                            "${it.substringBeforeLast(" ")}..."
                        } else {
                            it
                        }
                    }
                })
            }
        }

        return results.sortedWith(
            compareBy<Episode> { it.season ?: 1 }
                .thenBy { it.episode ?: 0 }
        )
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
            val movieData = parseMovieLinks(document)

            val movieLinks = runCatching {
                JSONObject(movieData).getJSONArray("links")
            }.getOrNull()

            if (movieLinks == null || movieLinks.length() == 0) {
                throw ErrorLoadingException(
                    "No movie download links found on OnlyKDrama."
                )
            }

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                movieData
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

        val tasks = mutableListOf<Triple<String, String, String>>()
        val seenUrls = mutableSetOf<String>()

        for (index in 0 until links.length()) {
            val item = links.optJSONObject(index) ?: continue

            val url = item.optString("url").trim()
            val provider = item.optString("provider")
            val fileName = item.optString("name")

            if (url.isBlank() || !seenUrls.add(url)) continue

            tasks.add(Triple(url, provider, fileName))
        }

        if (tasks.isEmpty()) return false

        val semaphore = Semaphore(7)
        val callbackLock = Any()
        val loaded = AtomicBoolean(false)

        coroutineScope {
            tasks.map { (url, provider, fileName) ->
                async {
                    semaphore.withPermit {
                        try {
                            Log.d(
                                "OnlyKDrama",
                                "Extracting provider=$provider; file=$fileName"
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
                                synchronized(callbackLock) {
                                    loaded.set(true)
                                    callback(link)
                                }
                            }

                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            handleExtractorChallenge(e)

                            Log.e(
                                "OnlyKDrama",
                                "Extractor failed: provider=$provider; file=$fileName; ${e.message}"
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        Log.d(
            "OnlyKDrama",
            "Extraction completed: ${tasks.size} provider links processed; success=${loaded.get()}"
        )

        return loaded.get()
    }
}
