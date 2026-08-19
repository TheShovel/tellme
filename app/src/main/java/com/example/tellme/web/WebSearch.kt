package com.example.tellme.web

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Key-free web search + content extraction, tuned for a daily news briefing.
 *
 * Strategy: Run multiple **topic-specific Google News RSS** queries in parallel to get real
 * article headlines, then enrich via **DDG** (find actual article URL) + **Jina AI Reader**
 * (extract readable text). Generic/vague headlines are filtered out.
 */
object WebSearch {

    private const val TAG = "TellMe.WebSearch"
    private const val DDG_LITE = "https://lite.duckduckgo.com/lite/"
    private const val JINA_READER = "https://r.jina.ai/"
    private const val NEWS_RSS = "https://news.google.com/rss/search?q="
    private const val WIKI = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit="

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private val aggregatorDomains = listOf(
        "news.google.com", "cnn.com", "bbc.com", "nbcnews.com",
        "foxnews.com", "msnbc.com", "reuters.com",
    )

    /** Fallback topic buckets for broad daily coverage when the user prompt is too generic. */
    private val fallbackTopics = listOf(
        "breaking world news today",
        "technology news today",
        "politics news today",
        "business finance news today",
        "science health news today",
    )

    data class SearchResult(val title: String, val url: String, val snippet: String)

    /**
     * Search across DuckDuckGo (primary, ranked higher) and Google News RSS (secondary)
     * in parallel, combine results, filter generic headlines, and enrich the top results
     * with real article content via Jina AI Reader.
     *
     * DuckDuckGo is used as the primary source because it covers both news and general
     * information, while Google News RSS provides additional news-specific coverage.
     */
    suspend fun search(query: String, max: Int = 5): List<SearchResult> = withContext(Dispatchers.IO) {
        // 1. Run both sources in parallel
        val (ddgResults, rssResults) = coroutineScope {
            val ddgDeferred = async { searchDuckDuckGo(query, max) }
            val rssDeferred = async { searchGoogleNews(query, max) }
            ddgDeferred.await() to rssDeferred.await()
        }
        Log.i(TAG, "DDG returned ${ddgResults.size}, RSS returned ${rssResults.size} for \"$query\"")

        // 2. DDG ranks higher — put it first, then RSS for news coverage
        val combined = ddgResults + rssResults

        // 3. If combined results are thin, supplement with RSS fallback topics
        val allResults = if (combined.size >= 3) {
            combined
        } else {
            Log.i(TAG, "Thin results (${combined.size}); supplementing with topic queries")
            val topicResults = coroutineScope {
                fallbackTopics.map { q ->
                    async { searchGoogleNews(q, 3) }
                }.awaitAll().flatten()
            }
            combined + topicResults
        }
        Log.i(TAG, "Combined total: ${allResults.size} results")

        if (allResults.isNotEmpty()) {
            val filtered = filterGenericHeadlines(allResults)
            Log.i(TAG, "after filter: ${filtered.size} results (dropped ${allResults.size - filtered.size} generic)")
            // Deduplicate by title similarity, take top N
            val deduped = deduplicate(filtered)
            Log.i(TAG, "after dedup: ${deduped.size} results")
            val top = deduped.take(max)
            // Enrich via Jina AI Reader for real article content
            val enriched = enrichWithJina(top)
            return@withContext enriched
        }

        // 4. Wikipedia last resort
        searchWikipedia(query, max)
    }

    // ---- Deduplication --------------------------------------------------------------------------

    /** Remove near-duplicate headlines (same event reported by different outlets). */
    private fun deduplicate(results: List<SearchResult>): List<SearchResult> {
        val seen = mutableListOf<String>()
        return results.filter { r ->
            val key = r.title.lowercase()
                .replace(Regex("[^a-z0-9 ]"), "")
                .split(" ")
                .filter { it.length > 3 }
                .take(5)
                .joinToString(" ")
            val isDupe = seen.any { existing ->
                // Simple word-overlap check: if >60% of significant words overlap, it's a dupe
                val words = key.split(" ").toSet()
                val existingWords = existing.split(" ").toSet()
                if (words.isEmpty() || existingWords.isEmpty()) return@any false
                val overlap = words.intersect(existingWords).size
                overlap.toFloat() / maxOf(words.size, existingWords.size) > 0.6f
            }
            if (!isDupe) seen.add(key)
            !isDupe
        }
    }

    // ---- Headline quality filter ----------------------------------------------------------------

    private fun filterGenericHeadlines(results: List<SearchResult>): List<SearchResult> {
        val genericPatterns = listOf(
            Regex("(?i)breaking updates?.*&\\s*live coverage"),
            Regex("(?i)top news stories? in .+ for"),
            Regex("(?i)latest news (bulletin|update|coverage)"),
            Regex("(?i)news today:.*breaking"),
            Regex("(?i)top \\d+ (news|websites|stories)"),
            Regex("(?i)best (news|sources|websites) for"),
            Regex("(?i)how to (get|find|read|follow) (the )?news"),
            Regex("(?i)news (sites?|sources?|websites?|channels?)"),
            Regex("(?i)video\\.\\s*latest"),
        )
        return results.filter { r ->
            val dominated = genericPatterns.any { it.containsMatchIn(r.title) }
            if (dominated) Log.i(TAG, "  filtered generic: '${r.title.take(80)}'")
            !dominated
        }
    }

    // ---- DuckDuckGo Lite -----------------------------------------------------------------------

    private fun searchDuckDuckGo(query: String, max: Int): List<SearchResult> = runCatching {
        val doc = Jsoup.connect(DDG_LITE)
            .userAgent(UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .timeout(20_000)
            .data("q", query)
            .post()
        val links = doc.select("a.result-link")
        val snippets = doc.select("td.result-snippet")
        val out = mutableListOf<SearchResult>()
        for (i in links.indices) {
            val a = links[i]
            val realUrl = decodeDdg(a.attr("href")) ?: continue
            val title = a.text().trim()
            val snippet = snippets.getOrNull(i)?.text()?.trim().orEmpty()
            if (title.isNotEmpty()) out.add(SearchResult(title, realUrl, snippet))
            if (out.size >= max) break
        }
        Log.i(TAG, "DDG Lite returned ${out.size} results for \"$query\"")
        out
    }.onFailure { Log.w(TAG, "DDG search failed: ${it.message}") }.getOrDefault(emptyList())

    private fun decodeDdg(href: String): String? {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val idx = href.indexOf("uddg=")
        if (idx < 0) return null
        val start = idx + 5
        val end = href.indexOf('&', start).let { if (it < 0) href.length else it }
        return runCatching { URLDecoder.decode(href.substring(start, end), "UTF-8") }.getOrDefault(null)
    }

    // ---- Enrich headlines via DDG + Jina -------------------------------------------------------

    private suspend fun enrichViaDdg(headlines: List<SearchResult>): List<SearchResult> = coroutineScope {
        headlines.mapIndexed { i, h ->
            async {
                if (i >= 5) return@async h
                val headlineText = h.title.substringBefore(" — ").trim()
                if (headlineText.isBlank()) return@async h

                val ddgResults = searchDuckDuckGo(headlineText, 2)
                if (ddgResults.isEmpty()) return@async h

                val article = ddgResults.firstOrNull { r ->
                    aggregatorDomains.none { domain -> r.url.contains(domain) } && r.snippet.length > 20
                } ?: ddgResults.first()

                Log.i(TAG, "enrichViaDdg[$i] found: ${article.url.take(60)} snippet(${article.snippet.length})")

                val jinaText = fetchWithJina(article.url)
                if (jinaText.isNotBlank()) {
                    h.copy(snippet = jinaText)
                } else {
                    h.copy(snippet = article.snippet)
                }
            }
        }.awaitAll()
    }

    // ---- Jina AI Reader -------------------------------------------------------------------------

    private suspend fun enrichWithJina(results: List<SearchResult>): List<SearchResult> = coroutineScope {
        results.mapIndexed { i, r ->
            async {
                if (i >= 5 || r.url.isBlank()) return@async r
                val text = fetchWithJina(r.url)
                if (text.isNotBlank()) r.copy(snippet = text) else r
            }
        }.awaitAll()
    }

    private fun fetchWithJina(url: String): String = runCatching {
        // Try decoded publisher URL first, then fall back to original URL
        val candidates = mutableListOf(resolveRedirect(url))
        if (candidates[0] == url || candidates[0].contains("consent.google")) {
            candidates.add(url) // Try original Google News URL
        }
        for (candidate in candidates) {
            val jinaUrl = "$JINA_READER$candidate"
            val doc = Jsoup.connect(jinaUrl)
                .userAgent("TellMe/0.1")
                .header("Accept", "text/plain")
                .timeout(12_000)
                .ignoreContentType(true)
                .get()
            val text = doc.text().take(800).trim()
            // Skip if Jina returned a consent page or empty
            if (text.isNotBlank() && !text.contains("consent.google") && !text.contains("Cookie")) {
                return text
            }
        }
        ""
    }.getOrDefault("")

    private fun resolveRedirect(url: String): String = runCatching {
        if (url.contains("news.google.com/rss/articles/")) {
            val encoded = url.substringAfter("/articles/").substringBefore("?")
            if (encoded.startsWith("CBMi")) {
                val b64 = encoded.substring(4)
                val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                val decoded = android.util.Base64.decode(
                    padded.replace('-', '+').replace('_', '/'),
                    android.util.Base64.DEFAULT
                )
                val realUrl = String(decoded, Charsets.UTF_8)
                if (realUrl.startsWith("http") && !realUrl.contains("consent.google")) return realUrl
            }
        }
        url
    }.getOrDefault(url)

    // ---- Google News RSS -----------------------------------------------------------------------

    private fun searchGoogleNews(query: String, max: Int): List<SearchResult> = runCatching {
        val url = NEWS_RSS + URLEncoder.encode(query, "UTF-8") + "&hl=en-US&gl=US&ceid=US:en"
        val xml = Jsoup.connect(url).ignoreContentType(true).timeout(20_000).execute().body()
        val items = "<item>(.*?)</item>".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(xml)
        val out = mutableListOf<SearchResult>()
        for (m in items) {
            val block = m.groupValues[1]
            val title = unescape(stripHtml(findTag(block, "title"))).trim()
            val source = unescape(stripHtml(findTag(block, "source"))).trim()
            val link = findTag(block, "link").trim()
            @Suppress("SimpleDateFormat") val date = try {
                val d = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                    .parse(findTag(block, "pubDate").trim())
                java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(d!!)
            } catch (_: Exception) { "" }
            if (title.isBlank() || link.isBlank()) continue
            val suffix = buildList {
                if (source.isNotBlank()) add(source)
                if (date.isNotBlank()) add("($date)")
            }
            val displayTitle = if (suffix.isNotEmpty()) "$title — ${suffix.joinToString(" ")}" else title
            out.add(SearchResult(displayTitle, link, ""))
            if (out.size >= max) break
        }
        out
    }.onFailure { Log.w(TAG, "Google News RSS failed: ${it.message}") }.getOrDefault(emptyList())

    // ---- Wikipedia (last resort) ---------------------------------------------------------------

    private fun searchWikipedia(query: String, max: Int): List<SearchResult> = runCatching {
        val url = WIKI + max + "&srsearch=" + URLEncoder.encode(query, "UTF-8")
        val json = Jsoup.connect(url).ignoreContentType(true).timeout(20_000).execute().body()
        val titles = "\"title\":\"(.*?)\"".toRegex().findAll(json).map { unescape(it.groupValues[1]) }
        val snippets = "\"snippet\":\"(.*?)\"".toRegex().findAll(json)
            .map { unescape(stripHtml(it.groupValues[1])) }
        titles.mapIndexed { i, t ->
            SearchResult(t, "https://en.wikipedia.org/wiki/" + t.replace(' ', '_'), snippets.elementAtOrNull(i).orEmpty())
        }.take(max).toList()
    }.onFailure { Log.w(TAG, "Wikipedia search failed: ${it.message}") }.getOrDefault(emptyList())

    // ---- helpers -------------------------------------------------------------------------------

    private fun findTag(block: String, name: String): String =
        "<$name>(.*?)</$name>".toRegex(RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim() ?: ""

    private fun stripHtml(s: String): String = s.replace(Regex("<[^>]+>"), "")
    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\").replace("&quot;", "\"").replace("&amp;", "&")
}
