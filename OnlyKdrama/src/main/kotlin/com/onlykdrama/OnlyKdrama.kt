package com.onlykdrama

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale
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

    private val tmdbApiUrl = "https://api.themoviedb.org/3"
    private val tmdbImageBase = "https://image.tmdb.org/t/p"
    private val tmdbToken: String
        get() = BuildConfig.TMDB_TOKEN.trim()

    private val tmdbHeaders: Map<String, String>
        get() = mapOf(
            "Authorization" to "Bearer $tmdbToken",
            "accept" to "application/json"
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

    private data class TmdbCastMember(
        val name: String,
        val role: String?,
        val image: String?
    )

    private data class TmdbMetadata(
        val tmdbId: Int,
        val mediaType: String,
        val title: String?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val logoUrl: String?,
        val synopsis: String?,
        val rating: Double?,
        val year: Int?,
        val tags: List<String>,
        val trailerUrl: String?,
        val cast: List<TmdbCastMember>,
        val status: ShowStatus?,
        val duration: Int?
    )

    private data class TmdbEpisodeInfo(
        val name: String?,
        val overview: String?,
        val stillUrl: String?
    )

    private data class ParsedTitleInfo(
        val cleanTitle: String,
        val searchQueries: List<String>,
        val normalizedPrimary: String,
        val yearHint: Int?,
        val seasonHint: Int?,
        val originCountryHint: String?
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

        return Regex("""([^\s,]+)\s+(\d+(?:\.\d+)?[wx])""", RegexOption.IGNORE_CASE)
            .findAll(srcset)
            .mapNotNull { match ->
                val rawUrl = match.groupValues[1].takeIf {
                    it.isNotBlank() && !it.startsWith("data:")
                } ?: return@mapNotNull null

                val descriptor = match.groupValues[2].lowercase()
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

    private fun cleanCardTitle(rawTitle: String): String {
        val parsed = parseOnlyKdramaTitle(rawTitle, null)
        return parsed.cleanTitle.ifBlank { rawTitle.trim() }
    }

    private fun parseHome(
        document: Document
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (article in document.select("article.item")) {
            val url = getDramaUrl(article) ?: continue
            val rawTitle = getDramaTitle(article)

            if (rawTitle.isBlank() || !seen.add(url)) continue

            val title = cleanCardTitle(rawTitle)
            val poster = getPoster(article)
            val isMovie = url.contains("/movies/") ||
                article.hasClass("movies") ||
                rawTitle.contains("movie", true) ||
                rawTitle.contains("film", true)

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

            val rawTitle = card.selectFirst(
                ".details .title a"
            )?.text()?.trim()
                ?.ifBlank { null }
                ?: card.selectFirst(".image img")
                    ?.attr("alt")?.trim()
                    ?.ifBlank { null }
                ?: continue

            val title = cleanCardTitle(rawTitle)
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

    // ==================== TMDB MATCHING & METADATA ====================

    private fun normalizeForComparison(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun parseOnlyKdramaTitle(rawTitle: String, pageYear: Int?): ParsedTitleInfo {
        val lower = rawTitle.lowercase(Locale.ROOT)

        val originCountryHint = when {
            Regex("""\b(?:korean|k-?drama|k-?movie)\b""").containsMatchIn(lower) -> "KR"
            Regex("""\b(?:chinese|c-?drama|c-?movie)\b""").containsMatchIn(lower) -> "CN"
            Regex("""\b(?:japanese|j-?drama|j-?movie)\b""").containsMatchIn(lower) -> "JP"
            Regex("""\b(?:thai|t-?drama|t-?movie)\b""").containsMatchIn(lower) -> "TH"
            Regex("""\b(?:taiwanese|tw-?drama)\b""").containsMatchIn(lower) -> "TW"
            Regex("""\b(?:filipino|pinoy|philippine)\b""").containsMatchIn(lower) -> "PH"
            Regex("""\b(?:indonesian)\b""").containsMatchIn(lower) -> "ID"
            Regex("""\b(?:vietnamese)\b""").containsMatchIn(lower) -> "VN"
            Regex("""\b(?:malaysian)\b""").containsMatchIn(lower) -> "MY"
            Regex("""\b(?:turkish)\b""").containsMatchIn(lower) -> "TR"
            else -> null
        }

        val seasonMatch = Regex("""(?i)\b(?:season|part)\s*0*(\d+)\b|\bS0*(\d+)\b""")
            .find(rawTitle)
        val seasonHint = seasonMatch?.let { match ->
            (match.groupValues[1].ifBlank { match.groupValues[2] }).toIntOrNull()
        }

        val titleYear = if ((seasonHint ?: 1) > 1) {
            null
        } else {
            Regex("""[\(\[\s]((?:19|20)\d{2})[\)\]\s]?""")
                .find(rawTitle)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: pageYear
        }

        val altBracketTitle = Regex("""\(([^()]{3,60})\)""")
            .findAll(rawTitle)
            .map { it.groupValues[1].trim() }
            .firstOrNull { inside ->
                !Regex("""^(?:19|20)\d{2}$""").matches(inside) &&
                    !Regex("""(?i)^(?:english|hindi|korean|chinese|japanese|thai|indonesian|dubbed|subbed|dual audio|season \d+|s\d+|ep\.?\s*\d+|complete).*$""").matches(inside)
            }

        var cleaned = rawTitle
            .replace(Regex("""[\(\[]\s*(?:19|20)\d{2}\s*[\)\]]"""), " ")
            .replace(
                Regex("""(?i)[\(\[]\s*(?:english|hindi|korean|chinese|japanese|thai|indonesian|vietnamese|malaysian|turkish|dual\s*audio|multi\s*audio|eng\s*sub|hindi\s*sub|dubbed|subbed|uncut|complete|full\s*movie|s\d+|season\s*\d+|ep\.?\s*\d+)[^)\]]*[\)\]]"""),
                " "
            )
            .replace(
                Regex("""(?i)\b(?:season|part)\s*\d+\b|\bS0*\d+(?:\s*E0*\d+)?\b|\b(?:episodes?|eps?\.?)\s*\d+(?:\s*(?:-|to|&)\s*\d+)?(?:\s*(?:added|complete|end))?\b|\bE0*\d+\b"""),
                " "
            )
            .replace(
                Regex("""(?i)\b(?:english|hindi|urdu|tamil|telugu|dual\s*audio|multi\s*audio|eng\s*sub|hindi\s*sub|dubbed|subbed|in\s+hindi|in\s+english)\b"""),
                " "
            )
            .replace(
                Regex("""(?i)\b(?:korean|chinese|japanese|thai|taiwanese|filipino|pinoy|indonesian|vietnamese|malaysian|turkish|asian|k|c|j|t)?\s*-?\s*(?:drama|series|movie|film|web\s*series)\b"""),
                " "
            )
            .replace(Regex("""(?i)\b(?:complete|all\s*episodes|full\s*movie|uncut)\b"""), " ")
            .replace(Regex("""\b(?:19|20)\d{2}\b\s*$"""), " ")
            .replace(Regex("""[\|\-\–\—:/]+\s*$"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (cleaned.isBlank()) {
            cleaned = rawTitle.substringBefore("(").trim()
        }

        val candidates = linkedSetOf<String>()
        if (cleaned.isNotBlank()) candidates.add(cleaned)

        cleaned.split(Regex("""\s*[/|]\s*"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .forEach { candidates.add(it) }

        val beforeColon = cleaned.substringBefore(":").trim()
        if (beforeColon.length >= 3 && !beforeColon.equals(cleaned, true)) {
            candidates.add(beforeColon)
        }

        val beforeDash = cleaned.substringBefore(" - ").trim()
        if (beforeDash.length >= 3 && !beforeDash.equals(cleaned, true)) {
            candidates.add(beforeDash)
        }

        if (!altBracketTitle.isNullOrBlank()) {
            candidates.add(altBracketTitle)
        }

        return ParsedTitleInfo(
            cleanTitle = cleaned,
            searchQueries = candidates.toList(),
            normalizedPrimary = normalizeForComparison(cleaned),
            yearHint = titleYear,
            seasonHint = seasonHint,
            originCountryHint = originCountryHint
        )
    }

    private fun scoreTmdbCandidate(
        item: JSONObject,
        mediaType: String,
        parsed: ParsedTitleInfo
    ): Double {
        val title = if (mediaType == "movie") {
            item.optString("title")
        } else {
            item.optString("name")
        }.trim()

        val originalTitle = if (mediaType == "movie") {
            item.optString("original_title")
        } else {
            item.optString("original_name")
        }.trim()

        val normTitle = normalizeForComparison(title)
        val normOriginal = normalizeForComparison(originalTitle)
        val target = parsed.normalizedPrimary

        if (normTitle.isBlank() && normOriginal.isBlank()) return -100.0

        var score = 0.0

        val bestTitleScore = listOf(normTitle, normOriginal)
            .filter { it.isNotBlank() }
            .maxOfOrNull { candidate ->
                when {
                    candidate == target -> 100.0
                    candidate.startsWith(target) || target.startsWith(candidate) -> 70.0
                    candidate.contains(target) || target.contains(candidate) -> 55.0
                    else -> {
                        val targetWords = target.split(" ").filter { it.length > 1 }.toSet()
                        val candWords = candidate.split(" ").filter { it.length > 1 }.toSet()
                        if (targetWords.isEmpty() || candWords.isEmpty()) 0.0
                        else {
                            val overlap = targetWords.intersect(candWords).size.toDouble()
                            (overlap / maxOf(targetWords.size, candWords.size)) * 60.0
                        }
                    }
                }
            } ?: 0.0

        score += bestTitleScore

        val dateStr = if (mediaType == "movie") {
            item.optString("release_date")
        } else {
            item.optString("first_air_date")
        }
        val itemYear = dateStr.take(4).toIntOrNull()

        if (parsed.yearHint != null && itemYear != null) {
            val diff = kotlin.math.abs(parsed.yearHint - itemYear)
            score += when {
                diff == 0 -> 55.0
                diff == 1 -> 25.0
                diff >= 4 -> -25.0
                else -> 0.0
            }
        }

        val origins = mutableSetOf<String>()
        val originArray = item.optJSONArray("origin_country")
        if (originArray != null) {
            for (i in 0 until originArray.length()) {
                origins.add(originArray.optString(i).uppercase(Locale.ROOT))
            }
        }
        val origLang = item.optString("original_language").lowercase(Locale.ROOT)
        when (origLang) {
            "ko" -> origins.add("KR")
            "zh" -> origins.add("CN")
            "ja" -> origins.add("JP")
            "th" -> origins.add("TH")
            "id" -> origins.add("ID")
            "vi" -> origins.add("VN")
            "ms" -> origins.add("MY")
            "tl" -> origins.add("PH")
            "tr" -> origins.add("TR")
        }

        val asianCountries = setOf("KR", "CN", "JP", "TH", "TW", "PH", "HK", "ID", "VN", "MY")

        if (parsed.originCountryHint != null) {
            if (origins.contains(parsed.originCountryHint)) {
                score += 55.0
            } else if (origins.any { it in asianCountries }) {
                score += 15.0
            }
        } else if (origins.any { it in asianCountries }) {
            score += 20.0
        }

        val popularity = item.optDouble("popularity", 0.0)
        score += minOf(popularity / 25.0, 10.0)

        return score
    }

    private suspend fun searchBestTmdbMatch(
        parsed: ParsedTitleInfo,
        isMovie: Boolean
    ): Pair<Int, String>? {
        val primaryType = if (isMovie) "movie" else "tv"
        val fallbackType = if (isMovie) "tv" else "movie"

        var bestId: Int? = null
        var bestType: String = primaryType
        var bestScore = 35.0

        for (query in parsed.searchQueries.take(3)) {
            val encoded = URLEncoder.encode(query, "UTF-8")

            for (mediaType in listOf(primaryType, fallbackType)) {
                val searchUrl =
                    "$tmdbApiUrl/search/$mediaType?query=$encoded&include_adult=false&language=en-US&page=1"

                val json = runCatching {
                    JSONObject(app.get(searchUrl, headers = tmdbHeaders).text)
                }.getOrNull() ?: continue

                val results = json.optJSONArray("results") ?: continue
                for (i in 0 until minOf(results.length(), 15)) {
                    val item = results.optJSONObject(i) ?: continue
                    val id = item.optInt("id", 0)
                    if (id <= 0) continue

                    var candidateScore = scoreTmdbCandidate(item, mediaType, parsed)
                    if (mediaType == primaryType) candidateScore += 10.0

                    if (candidateScore > bestScore) {
                        bestScore = candidateScore
                        bestId = id
                        bestType = mediaType
                    }
                }

                if (bestId != null && bestType == primaryType && bestScore >= 135.0) {
                    return bestId to bestType
                }
            }
        }

        return bestId?.let { it to bestType }
    }

    private suspend fun fetchTmdbMetadata(
        parsed: ParsedTitleInfo,
        isMovie: Boolean
    ): TmdbMetadata? {
        if (tmdbToken.isBlank()) {
            Log.w("OnlyKDrama", "TMDB_TOKEN is empty; skipping TMDB metadata fetch.")
            return null
        }

        return try {
            val match = searchBestTmdbMatch(parsed, isMovie) ?: return null
            val (tmdbId, mediaType) = match

            val detailUrl =
                "$tmdbApiUrl/$mediaType/$tmdbId?language=en-US&append_to_response=credits,videos,images&include_image_language=en,null"

            val data = JSONObject(app.get(detailUrl, headers = tmdbHeaders).text)

            val tmdbTitle = if (mediaType == "movie") {
                data.optString("title")
            } else {
                data.optString("name")
            }.trim().takeIf { it.isNotBlank() && it != "null" }

            val posterPath = data.optString("poster_path").takeIf {
                it.isNotBlank() && it != "null"
            }
            val backdropPath = data.optString("backdrop_path").takeIf {
                it.isNotBlank() && it != "null"
            }

            val logos = data.optJSONObject("images")?.optJSONArray("logos")
            var bestLogoPath: String? = null
            var bestLogoScore = -1.0
            if (logos != null) {
                for (i in 0 until logos.length()) {
                    val logo = logos.optJSONObject(i) ?: continue
                    val path = logo.optString("file_path").takeIf {
                        it.isNotBlank() && it != "null"
                    } ?: continue

                    val lang = logo.optString("iso_639_1")
                    val voteAvg = logo.optDouble("vote_average", 0.0)
                    val width = logo.optInt("width", 0)

                    val logoScore = (if (lang == "en") 100.0 else if (lang == "null" || lang.isBlank()) 50.0 else 10.0) +
                        voteAvg +
                        (if (width >= 300) 10.0 else 0.0)

                    if (logoScore > bestLogoScore) {
                        bestLogoScore = logoScore
                        bestLogoPath = path
                    }
                }
            }

            val synopsis = data.optString("overview").trim().takeIf {
                it.isNotBlank() && it != "null"
            }

            val rating = data.optDouble("vote_average", 0.0).takeIf { it > 0.0 }

            val dateStr = if (mediaType == "movie") {
                data.optString("release_date")
            } else {
                data.optString("first_air_date")
            }
            val year = dateStr.take(4).toIntOrNull()

            val tags = mutableListOf<String>()
            val genresArray = data.optJSONArray("genres")
            if (genresArray != null) {
                for (i in 0 until genresArray.length()) {
                    val gName = genresArray.optJSONObject(i)?.optString("name")?.trim()
                    if (!gName.isNullOrBlank()) tags.add(gName)
                }
            }

            val castList = mutableListOf<TmdbCastMember>()
            val castArray = data.optJSONObject("credits")?.optJSONArray("cast")
            if (castArray != null) {
                for (i in 0 until minOf(castArray.length(), 20)) {
                    val person = castArray.optJSONObject(i) ?: continue
                    val actorName = person.optString("name").trim()
                        .ifBlank { person.optString("original_name").trim() }
                    if (actorName.isBlank()) continue

                    val character = person.optString("character").trim().takeIf {
                        it.isNotBlank() && it != "null"
                    }
                    val profilePath = person.optString("profile_path").takeIf {
                        it.isNotBlank() && it != "null"
                    }

                    castList.add(
                        TmdbCastMember(
                            name = actorName,
                            role = character,
                            image = profilePath?.let { "$tmdbImageBase/w300$it" }
                        )
                    )
                }
            }

            var trailerUrl: String? = null
            var bestTrailerScore = -1
            val videosArray = data.optJSONObject("videos")?.optJSONArray("results")
            if (videosArray != null) {
                for (i in 0 until videosArray.length()) {
                    val video = videosArray.optJSONObject(i) ?: continue
                    val site = video.optString("site")
                    val key = video.optString("key").trim()
                    if (!site.equals("YouTube", true) || key.isBlank()) continue

                    val type = video.optString("type")
                    val official = video.optBoolean("official", false)

                    val videoScore = when {
                        type.equals("Trailer", true) && official -> 100
                        type.equals("Trailer", true) -> 80
                        type.equals("Teaser", true) && official -> 60
                        type.equals("Teaser", true) -> 40
                        else -> 10
                    }

                    if (videoScore > bestTrailerScore) {
                        bestTrailerScore = videoScore
                        trailerUrl = "https://www.youtube.com/watch?v=$key"
                    }
                }
            }

            val status = when (data.optString("status").lowercase(Locale.ROOT)) {
                "returning series", "in production", "planned" -> ShowStatus.Ongoing
                "ended", "canceled" -> ShowStatus.Completed
                else -> null
            }

            val duration = if (mediaType == "movie") {
                data.optInt("runtime", 0).takeIf { it > 0 }
            } else {
                data.optJSONArray("episode_run_time")
                    ?.optInt(0, 0)
                    ?.takeIf { it > 0 }
            }

            TmdbMetadata(
                tmdbId = tmdbId,
                mediaType = mediaType,
                title = tmdbTitle,
                posterUrl = posterPath?.let { "$tmdbImageBase/w780$it" },
                backdropUrl = backdropPath?.let { "$tmdbImageBase/w1280$it" },
                logoUrl = bestLogoPath?.let { "$tmdbImageBase/w500$it" },
                synopsis = synopsis,
                rating = rating,
                year = year,
                tags = tags.distinct(),
                trailerUrl = trailerUrl,
                cast = castList,
                status = status,
                duration = duration
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("OnlyKDrama", "TMDB metadata fetch failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchTmdbSeasonEpisodes(
        tmdbId: Int,
        seasons: Set<Int>
    ): Map<Pair<Int, Int>, TmdbEpisodeInfo> {
        if (tmdbToken.isBlank() || seasons.isEmpty()) return emptyMap()

        return coroutineScope {
            seasons.map { seasonNum ->
                async {
                    try {
                        val seasonUrl = "$tmdbApiUrl/tv/$tmdbId/season/$seasonNum?language=en-US"
                        val data = JSONObject(app.get(seasonUrl, headers = tmdbHeaders).text)
                        val epArray = data.optJSONArray("episodes") ?: return@async emptyList()

                        val list = mutableListOf<Pair<Pair<Int, Int>, TmdbEpisodeInfo>>()
                        for (i in 0 until epArray.length()) {
                            val ep = epArray.optJSONObject(i) ?: continue
                            val epNum = ep.optInt("episode_number", 0)
                            if (epNum <= 0) continue

                            val epName = ep.optString("name").trim().takeIf {
                                it.isNotBlank() && it != "null"
                            }
                            val overview = ep.optString("overview").trim().takeIf {
                                it.isNotBlank() && it != "null"
                            }
                            val stillPath = ep.optString("still_path").trim().takeIf {
                                it.isNotBlank() && it != "null"
                            }

                            list.add(
                                (seasonNum to epNum) to TmdbEpisodeInfo(
                                    name = epName,
                                    overview = overview,
                                    stillUrl = stillPath?.let { "$tmdbImageBase/w780$it" }
                                )
                            )
                        }
                        list
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("OnlyKDrama", "Failed fetching TMDB season $seasonNum for id=$tmdbId: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten().toMap()
        }
    }

    // ==================== LINKS & EPISODE PARSING ====================

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
        defaultSeason: Int,
        fallbackPoster: String?,
        fallbackPlot: String?
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

        val shortFallbackPlot = fallbackPlot?.take(180)?.let {
            if (fallbackPlot.length > 180) {
                "${it.substringBeforeLast(" ")}..."
            } else {
                it
            }
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
                    ?.toIntOrNull() ?: defaultSeason

                val episodeData = JSONObject()
                    .put("title", episodeTitle)
                    .put("links", links)
                    .toString()

                results.add(newEpisode(episodeData) {
                    name = episodeTitle
                    season = seasonNumber
                    episode = episodeNumber
                    posterUrl = fallbackPoster
                    description = shortFallbackPlot
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

                    val season = match.groupValues[1].toIntOrNull() ?: defaultSeason
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
                    posterUrl = fallbackPoster
                    description = shortFallbackPlot
                })
            }
        }

        return results.sortedWith(
            compareBy<Episode> { it.season ?: defaultSeason }
                .thenBy { it.episode ?: 0 }
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)

        val rawTitle = document.selectFirst(
            "#single .sheader .data h1"
        )?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val sitePoster = getDetailPoster(document)
        val sitePlot = getSynopsis(document)
        val siteTags = getTags(document)
        val siteYear = getYear(document)
        val siteRating = getRating(document)

        val isMovie = url.contains("/movies/")
        val parsedTitle = parseOnlyKdramaTitle(rawTitle, siteYear)
        val tmdb = fetchTmdbMetadata(parsedTitle, isMovie)

        val finalTitle = tmdb?.title
            ?: parsedTitle.cleanTitle.ifBlank { rawTitle }
        val finalPoster = tmdb?.posterUrl ?: sitePoster
        val finalBackdrop = tmdb?.backdropUrl ?: finalPoster
        val finalPlot = tmdb?.synopsis ?: sitePlot
        val finalYear = tmdb?.year ?: siteYear
        val finalTags = (tmdb?.tags.orEmpty() + siteTags).distinct()
        val finalScore = tmdb?.rating?.let { Score.from10(it) }
            ?: siteRating?.let { Score.from10(it) }
        val finalActors = tmdb?.cast?.map { member ->
            Actor(member.name, member.image) to member.role
        }.orEmpty()

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
                finalTitle,
                url,
                TvType.Movie,
                movieData
            ) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.logoUrl = tmdb?.logoUrl
                this.plot = finalPlot
                this.year = finalYear
                this.tags = finalTags
                this.score = finalScore
                this.duration = tmdb?.duration
                if (finalActors.isNotEmpty()) addActors(finalActors)
                tmdb?.trailerUrl?.let { addTrailer(it) }
            }
        }

        val episodes = parseEpisodes(
            document = document,
            defaultSeason = parsedTitle.seasonHint ?: 1,
            fallbackPoster = finalBackdrop,
            fallbackPlot = finalPlot
        )

        if (episodes.isEmpty()) {
            throw ErrorLoadingException(
                "No episode download links found on this drama page."
            )
        }

        if (tmdb != null && tmdb.mediaType == "tv") {
            val seasonNumbers = episodes.mapNotNull { it.season }.toSet()
            val tmdbEpisodes = fetchTmdbSeasonEpisodes(tmdb.tmdbId, seasonNumbers)

            if (tmdbEpisodes.isNotEmpty()) {
                for (ep in episodes) {
                    val s = ep.season ?: continue
                    val e = ep.episode ?: continue
                    val info = tmdbEpisodes[s to e] ?: continue

                    if (!info.name.isNullOrBlank()) {
                        ep.name = info.name
                    }
                    if (!info.stillUrl.isNullOrBlank()) {
                        ep.posterUrl = info.stillUrl
                    }
                    if (!info.overview.isNullOrBlank()) {
                        ep.description = info.overview
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(
            finalTitle,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = finalBackdrop
            this.logoUrl = tmdb?.logoUrl
            this.plot = finalPlot
            this.year = finalYear
            this.tags = finalTags
            this.score = finalScore
            this.showStatus = tmdb?.status
            this.duration = tmdb?.duration
            if (finalActors.isNotEmpty()) addActors(finalActors)
            tmdb?.trailerUrl?.let { addTrailer(it) }
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
