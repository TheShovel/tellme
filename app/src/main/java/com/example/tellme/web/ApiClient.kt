package com.example.tellme.web

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Unified API client with 15+ named tools the LLM can request.
 * All APIs are free, no keys required.
 */
object ApiClient {

    private const val TAG = "TellMe.ApiClient"

    /** Call a named API with arguments extracted by the LLM. */
    suspend fun call(apiName: String, args: Map<String, String>): String {
        return when (apiName) {
            "get_weather" -> fetchWeather(args["city"] ?: "")
            "get_forecast" -> fetchForecast(args["city"] ?: "", args["days"] ?: "3")
            "get_crypto_prices" -> fetchCryptoPrices(args["coins"] ?: "bitcoin,ethereum")
            "get_exchange_rate" -> fetchExchangeRate(args["from"] ?: "USD", args["to"] ?: "EUR")
            "get_definition" -> fetchDefinition(args["word"] ?: "")
            "get_nasa_apod" -> fetchNasaApod()
            "get_hackernews_top" -> fetchHackerNewsTop(args["count"] ?: "5")
            "get_holidays" -> fetchHolidays(args["country"] ?: "US")
            "get_tv_show" -> fetchTvShow(args["query"] ?: "")
            "get_book" -> fetchBook(args["query"] ?: "")
            "get_random_joke" -> fetchRandomJoke()
            "get_wikipedia" -> fetchWikipedia(args["topic"] ?: "")
            "get_ip_info" -> fetchIpInfo()
            "get_quote" -> fetchQuote()
            else -> ""
        }
    }

    /** Parse the LLM's tool-call output: [tool_name key="val" ...] */
    fun parseToolCalls(llmOutput: String): List<Pair<String, Map<String, String>>> {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val blockPattern = Regex("""\[(\w+)(.*?)\]""")
        for (match in blockPattern.findAll(llmOutput)) {
            val apiName = match.groupValues[1]
            val argsStr = match.groupValues[2]
            val args = mutableMapOf<String, String>()
            val kvPattern = Regex("""(\w+)="([^"]*)"""")
            for (kv in kvPattern.findAll(argsStr)) {
                args[kv.groupValues[1]] = kv.groupValues[2]
            }
            calls.add(apiName to args)
        }
        return calls
    }

    // ── Weather (Open-Meteo) ──────────────────────────────────────────────

    private suspend fun fetchWeather(city: String): String = withContext(Dispatchers.IO) {
        try {
            if (city.isBlank()) return@withContext ""
            val (lat, lon, name) = geocode(city) ?: return@withContext ""
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code" +
                    "&timezone=auto&forecast_days=1"
            val j = fetchJson(url)
            val cur = j.getAsJsonObject("current")
            val temp = cur.get("temperature_2m")?.asDouble
            val feels = cur.get("apparent_temperature")?.asDouble
            val hum = cur.get("relative_humidity_2m")?.asInt
            val wind = cur.get("wind_speed_10m")?.asDouble
            val code = cur.get("weather_code")?.asInt
            val desc = wxCode(code ?: 0)
            val daily = j.getAsJsonObject("daily")
            val hi = daily.getAsJsonArray("temperature_2m_max")?.get(0)?.asDouble
            val lo = daily.getAsJsonArray("temperature_2m_min")?.get(0)?.asDouble
            val precip = daily.getAsJsonArray("precipitation_sum")?.get(0)?.asDouble
            buildString {
                append("$name now: $desc, ${temp?.toInt()}°C (feels ${feels?.toInt()}°C), humidity ${hum}%, wind ${wind?.toInt()} km/h.")
                if (hi != null && lo != null) append(" Today high ${hi.toInt()}°C, low ${lo.toInt()}°C.")
                if (precip != null && precip > 0) append(" Precipitation ${precip}mm.")
            }
        } catch (e: Exception) { Log.e(TAG, "weather failed", e); "" }
    }

    private suspend fun fetchForecast(city: String, days: String): String = withContext(Dispatchers.IO) {
        try {
            if (city.isBlank()) return@withContext ""
            val (lat, lon, name) = geocode(city) ?: return@withContext ""
            val n = days.toIntOrNull()?.coerceIn(1, 16) ?: 3
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_sum" +
                    "&timezone=auto&forecast_days=$n"
            val j = fetchJson(url)
            val daily = j.getAsJsonArray("time")
            val maxT = j.getAsJsonObject("daily").getAsJsonArray("temperature_2m_max")
            val minT = j.getAsJsonObject("daily").getAsJsonArray("temperature_2m_min")
            val codes = j.getAsJsonObject("daily").getAsJsonArray("weather_code")
            val parts = mutableListOf<String>()
            for (i in 0 until minOf(daily.size(), n)) {
                val date = daily.get(i)?.asString?.substringAfterLast("-") ?: "?"
                val hi = maxT?.get(i)?.asDouble?.toInt() ?: "?"
                val lo = minT?.get(i)?.asDouble?.toInt() ?: "?"
                val desc = wxCode(codes?.get(i)?.asInt ?: 0)
                parts.add("$date: $desc $lo-$hi°C")
            }
            "$name ${n}-day forecast: ${parts.joinToString("; ")}"
        } catch (e: Exception) { Log.e(TAG, "forecast failed", e); "" }
    }

    // ── Crypto (CoinGecko Keyless) ────────────────────────────────────────

    private suspend fun fetchCryptoPrices(coins: String): String = withContext(Dispatchers.IO) {
        try {
            val ids = coins.trim().lowercase().replace(" ", "")
            val url = "https://api.coingecko.com/api/v3/simple/price?ids=$ids&vs_currencies=usd&include_24hr_change=true&include_market_cap=true"
            val j = fetchJson(url)
            val parts = ids.split(",").mapNotNull { coin ->
                val obj = j.getAsJsonObject(coin.trim()) ?: return@mapNotNull null
                val price = obj.get("usd")?.asDouble ?: return@mapNotNull null
                val change = obj.get("usd_24h_change")?.asDouble
                val mcap = obj.get("usd_market_cap")?.asLong
                val name = coin.trim().replaceFirstChar { it.uppercase() }
                val chg = if (change != null) " (${if (change >= 0) "+" else ""}${"%.1f".format(change)}% 24h)" else ""
                val cap = if (mcap != null) ", market cap $${"%,.0f".format(mcap.toDouble() / 1_000_000_000)}B" else ""
                "$name: $${"%,.2f".format(price)}$chg$cap"
            }
            if (parts.isEmpty()) "" else "Crypto: ${parts.joinToString("; ")}"
        } catch (e: Exception) { Log.e(TAG, "crypto failed", e); "" }
    }



    // ── Exchange Rates ─────────────────────────────────────────────────────

    private suspend fun fetchExchangeRate(from: String, to: String): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://open.er-api.com/v6/latest/${from.uppercase()}"
            val j = fetchJson(url)
            val rates = j.getAsJsonObject("rates")
            val rate = rates?.get(to.uppercase())?.asDouble ?: return@withContext ""
            "1 ${from.uppercase()} = ${"%.4f".format(rate)} ${to.uppercase()}"
        } catch (e: Exception) { Log.e(TAG, "exchange failed", e); "" }
    }

    // ── Dictionary ─────────────────────────────────────────────────────────

    private suspend fun fetchDefinition(word: String): String = withContext(Dispatchers.IO) {
        try {
            if (word.isBlank()) return@withContext ""
            val url = "https://api.dictionaryapi.dev/api/v2/entries/en/${URLEncoder.encode(word, "UTF-8")}"
            val j = fetchJsonArray(url)
            if (j == null || j.size() == 0) return@withContext ""
            val entry = j[0].asJsonObject
            val phonetic = entry.get("phonetic")?.asString ?: ""
            val meanings = entry.getAsJsonArray("meanings")
            val defs = mutableListOf<String>()
            if (meanings != null) {
                for (m in 0 until minOf(meanings.size(), 2)) {
                    val meaning = meanings[m].asJsonObject
                    val pos = meaning.get("partOfSpeech")?.asString ?: ""
                    val dList = meaning.getAsJsonArray("definitions")
                    if (dList != null && dList.size() > 0) {
                        val d = dList[0].asJsonObject.get("definition")?.asString ?: ""
                        defs.add("($pos) $d")
                    }
                }
            }
            "$word${if (phonetic.isNotBlank()) " $phonetic" else ""}: ${defs.joinToString("; ")}"
        } catch (e: Exception) { Log.e(TAG, "definition failed", e); "" }
    }

    // ── NASA Astronomy Picture of the Day ──────────────────────────────────

    private suspend fun fetchNasaApod(): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY"
            val j = fetchJson(url)
            val title = j.get("title")?.asString ?: ""
            val explanation = j.get("explanation")?.asString?.take(300) ?: ""
            val date = j.get("date")?.asString ?: ""
            "NASA APOD ($date): $title. $explanation"
        } catch (e: Exception) { Log.e(TAG, "nasa failed", e); "" }
    }

    // ── HackerNews Top Stories ─────────────────────────────────────────────

    private suspend fun fetchHackerNewsTop(count: String): String = withContext(Dispatchers.IO) {
        try {
            val n = count.toIntOrNull()?.coerceIn(1, 10) ?: 5
            val topUrl = "https://hacker-news.firebaseio.com/v0/topstories.json"
            val topBody = fetchRaw(topUrl)
            val ids = JsonParser.parseString(topBody).asJsonArray
            val stories = mutableListOf<String>()
            for (i in 0 until minOf(ids.size(), n)) {
                val storyId = ids[i].asLong
                val storyUrl = "https://hacker-news.firebaseio.com/v0/item/$storyId.json"
                val story = fetchJson(storyUrl)
                val title = story.get("title")?.asString ?: continue
                val score = story.get("score")?.asInt ?: 0
                stories.add("$title (score: $score)")
            }
            "HackerNews top: ${stories.joinToString("; ")}"
        } catch (e: Exception) { Log.e(TAG, "hn failed", e); "" }
    }



    // ── Public Holidays (Nager.Date) ──────────────────────────────────────

    private suspend fun fetchHolidays(country: String): String = withContext(Dispatchers.IO) {
        try {
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val cc = when (country.lowercase().trim()) {
                "us", "usa", "united states" -> "US"; "uk", "britain", "england" -> "GB"
                "romania", "ro" -> "RO"; "germany", "de" -> "DE"; "france", "fr" -> "FR"
                else -> country.uppercase().take(2)
            }
            val url = "https://date.nager.at/api/v3/PublicHolidays/$year/$cc"
            val j = fetchJsonArray(url)
            if (j == null || j.size() == 0) return@withContext ""
            val holidays = mutableListOf<String>()
            for (i in 0 until minOf(j.size(), 8)) {
                val h = j[i].asJsonObject
                val name = h.get("localName")?.asString ?: h.get("name")?.asString ?: ""
                val date = h.get("date")?.asString ?: ""
                holidays.add("$name ($date)")
            }
            "$country $year holidays: ${holidays.joinToString("; ")}"
        } catch (e: Exception) { Log.e(TAG, "holidays failed", e); "" }
    }

    // ── TV Shows (TVMaze) ─────────────────────────────────────────────────

    private suspend fun fetchTvShow(query: String): String = withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) return@withContext ""
            val url = "http://api.tvmaze.com/search/shows?q=${URLEncoder.encode(query, "UTF-8")}"
            val j = fetchJsonArray(url)
            if (j == null || j.size() == 0) return@withContext ""
            val shows = mutableListOf<String>()
            for (i in 0 until minOf(j.size(), 3)) {
                val show = j[i].asJsonObject.getAsJsonObject("show")
                val name = show.get("name")?.asString ?: continue
                val rating = show.getAsJsonObject("rating")?.get("average")?.asDouble
                val status = show.get("status")?.asString ?: ""
                val premiered = show.get("premiered")?.asString?.take(4) ?: ""
                val ratingStr = if (rating != null) "rating: $rating" else ""
                shows.add("$name ($premiered, $status, $ratingStr)")
            }
            "TV shows for \"$query\": ${shows.joinToString("; ")}"
        } catch (e: Exception) { Log.e(TAG, "tv failed", e); "" }
    }

    // ── Books (Open Library) ──────────────────────────────────────────────

    private suspend fun fetchBook(query: String): String = withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) return@withContext ""
            val url = "https://openlibrary.org/search.json?q=${URLEncoder.encode(query, "UTF-8")}&limit=3"
            val j = fetchJson(url)
            val docs = j.getAsJsonArray("docs") ?: return@withContext ""
            val books = mutableListOf<String>()
            for (i in 0 until minOf(docs.size(), 3)) {
                val b = docs[i].asJsonObject
                val title = b.get("title")?.asString ?: continue
                val authors = b.getAsJsonArray("author_name")?.get(0)?.asString ?: "?"
                val year = b.getAsJsonArray("first_publish_year")?.get(0)?.asInt
                val yearStr = if (year != null) "($year)" else ""
                books.add("$title by $authors $yearStr")
            }
            "Books for \"$query\": ${books.joinToString("; ")}"
        } catch (e: Exception) { Log.e(TAG, "book failed", e); "" }
    }

    // ── Random Joke ────────────────────────────────────────────────────────

    private suspend fun fetchRandomJoke(): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://official-joke-api.appspot.com/random_joke"
            val j = fetchJson(url)
            val setup = j.get("setup")?.asString ?: ""
            val punchline = j.get("punchline")?.asString ?: ""
            "Joke: $setup $punchline"
        } catch (e: Exception) { "" }
    }

    // ── Wikipedia ──────────────────────────────────────────────────────────

    private suspend fun fetchWikipedia(topic: String): String = withContext(Dispatchers.IO) {
        try {
            if (topic.isBlank()) return@withContext ""
            val url = "https://en.wikipedia.org/api/rest_v1/page/summary/${URLEncoder.encode(topic, "UTF-8")}"
            val j = fetchJson(url)
            val extract = j.get("extract")?.asString ?: return@withContext ""
            if (extract.length < 20) "" else "Wikipedia: ${extract.take(500)}"
        } catch (e: Exception) { "" }
    }

    // ── IP Info ────────────────────────────────────────────────────────────

    private suspend fun fetchIpInfo(): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://ipinfo.io/json"
            val j = fetchJson(url)
            val ip = j.get("ip")?.asString ?: ""
            val city = j.get("city")?.asString ?: ""
            val country = j.get("country")?.asString ?: ""
            val org = j.get("org")?.asString ?: ""
            "Your IP: $ip, location: $city, $country, org: $org"
        } catch (e: Exception) { "" }
    }

    // ── Inspirational Quote ────────────────────────────────────────────────

    private suspend fun fetchQuote(): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://zenquotes.io/api/random"
            val j = fetchJsonArray(url)
            if (j == null || j.size() == 0) return@withContext ""
            val q = j[0].asJsonObject
            val text = q.get("q")?.asString ?: ""
            val author = q.get("a")?.asString ?: ""
            "\"$text\" — $author"
        } catch (e: Exception) { "" }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private data class GeoResult(val lat: Double, val lon: Double, val name: String)

    private fun geocode(city: String): GeoResult? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=1&language=en"
        val j = fetchJson(url)
        val results = j.getAsJsonArray("results") ?: return null
        if (results.size() == 0) return null
        val r = results[0].asJsonObject
        return GeoResult(r.get("latitude").asDouble, r.get("longitude").asDouble, r.get("name")?.asString ?: city)
    }

    private fun wxCode(code: Int): String = when (code) {
        0 -> "Clear sky"; 1, 2, 3 -> "Partly cloudy"; 45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"; 61, 63, 65 -> "Rain"; 66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"; 80, 81, 82 -> "Rain showers"; 95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"; else -> "Mixed"
    }

    private fun fetchJson(url: String): com.google.gson.JsonObject {
        val body = fetchRaw(url)
        return JsonParser.parseString(body).asJsonObject
    }

    private fun fetchJsonArray(url: String): com.google.gson.JsonArray? {
        val body = fetchRaw(url)
        val parsed = JsonParser.parseString(body)
        return if (parsed.isJsonArray) parsed.asJsonArray else null
    }

    private fun fetchRaw(url: String, userAgent: String = "TellMe/0.1"): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent)
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return conn.inputStream.bufferedReader().readText()
    }
}
