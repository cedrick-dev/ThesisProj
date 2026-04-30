package com.kidsafe.secure.services

import java.net.URI
import java.util.Locale

object UrlBlockerHelper {

    // ==============================
    // 🔒 KEYWORD DATASETS (CATEGORIZED)
    // ==============================

    private val ADULT_KEYWORDS = listOf(
        // Major Sites
        "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
        "spankbang", "eporner", "beeg", "tube8", "tnaflix",

        // Premium Brands
        "brazzers", "bangbros", "realitykings", "naughtyamerica",

        // Live / Cam
        "chaturbate", "stripchat", "livejasmin", "bongacams", "cam4",

        // Subscription Platforms
        "onlyfans", "fansly", "manyvids",

        // Hentai / Anime
        "hentai", "nhentai", "hanime", "rule34",

        // Local PH Terms
        "pinaysex", "pinayporn", "kantot", "kantutan", "jakol",
        "pinay scandal", "scandal video",

        // Generic
        "porn", "sex", "xxx", "nude", "nsfw", "lewd",
        "milf", "bdsm", "fetish", "voyeur", "escort"
    )

    private val VIOLENCE_KEYWORDS = listOf(
        "how to kill", "how to make a bomb", "gore",
        "beheading", "قتل"
    )

    private val SELF_HARM_KEYWORDS = listOf(
        "how to commit suicide", "how to die",
        "self harm", "cutting myself", "overdose"
    )

    private val ILLEGAL_KEYWORDS = listOf(
        "buy drugs", "how to make meth",
        "how to hack", "hack account"
    )

    private val SCAM_KEYWORDS = listOf(
        "free robux", "generator no verification",
        "talk to strangers", "random chat"
    )

    private val OTHER_KEYWORDS = listOf(
        "dating app", "hookup", "one night stand", "gambling",
        ".xxx", ".porn", ".adult", ".sex", ".cam"
    )

    private val PROFANITY_KEYWORDS = listOf(
        // 🇵🇭 Filipino profanity — multi-word / long-form (safe for substring match)
        "putangina", "putragis", "tarantado",
        "kantutan", "jakulan", "iyotero",
        "malibog", "motherfucker", "bullshit",
        "asshole", "bastard",
        "fucking", "biatch", "azzhole",

        // 🇵🇭 Variations / spaced / censored
        "p i", "p.i",

        // 🟠 Harmful phrases
        "kill yourself", "go die", "drop dead"

        // ℹ️ Removed from blocklist (too many false positives):
        //   "mf", "dumb", "noob", "lmao", "lmfao", "leche", "wtf", "idiot", "stupid"
        //   → moved to EXACT_WORD_KEYWORDS below so they only match on word boundaries
        // ℹ️ Short ambiguous Filipino words (puta, gago, tanga, etc.) moved to
        //   EXACT_WORD_KEYWORDS to prevent false positives on partial matches.
    )

    // Merge all
    private val BLOCKED_KEYWORDS: Set<String> = (
        ADULT_KEYWORDS +
        VIOLENCE_KEYWORDS +
        SELF_HARM_KEYWORDS +
        ILLEGAL_KEYWORDS +
        SCAM_KEYWORDS +
        OTHER_KEYWORDS +
        PROFANITY_KEYWORDS
    ).toSet()

    // ==============================
    // 🎯 EXACT WORD MATCHING (ANTI FALSE POSITIVE)
    // These are matched with \W word boundaries so short/common words
    // don't cause false positives on legitimate sites/content.
    // ==============================

    private val EXACT_WORD_KEYWORDS = setOf(
        // Adult
        "porn", "sex", "xxx", "nude", "jav", "cam",
        "escort", "milf", "bdsm", "voyeur", "fetish", "nsfw",
        // Strong profanity (single words — risk false positives as substrings)
        "fuck", "fuk", "shit", "dick", "cock", "pussy", "cum", "ass",
        "slut", "whore", "jizz", "bitch",
        // 🇵🇭 Short Filipino profanity — word-boundary only to avoid false positives
        "puta", "pota", "potek", "gago", "tanga", "bobo", "ulol",
        "bwisit", "buset", "hayop", "punyeta", "hindot", "kantot",
        "jakol", "libog", "burat", "tite", "pepe", "iyot",
        // Mild / ambiguous — only block if standalone word
        "mf", "wtf", "kys",
        // Insults — only block if standalone
        "idiot", "stupid", "dumb", "moron", "retard",
        "loser", "trash", "noob"
    )

    private val EXACT_WORD_REGEXES = EXACT_WORD_KEYWORDS.map {
        Regex("(^|\\W)${Regex.escape(it)}(\\W|$)")
    }

    // SUBSTRING_KEYWORDS excludes EXACT_WORD_KEYWORDS to avoid double-matching
    private val SUBSTRING_KEYWORDS = BLOCKED_KEYWORDS - EXACT_WORD_KEYWORDS

    // ==============================
    // 🌐 HOST EXTRACTION
    // ==============================

    private fun extractHost(url: String): String {
        return try {
            val cleanUrl = if (!url.startsWith("http")) "http://$url" else url
            URI(cleanUrl).host?.lowercase(Locale.ROOT) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ==============================
    // 🔍 MAIN CHECK FUNCTION
    // ==============================

    fun isUrlBlocked(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        // ✅ Extract host from RAW url BEFORE normalization
        // (normalization replaces digits/symbols which would corrupt domain names)
        val host = extractHost(url)

        // Normalize for search query / path matching
        val normalized = normalize(url)

        // 1️⃣ Domain-based blocking — ONLY on known-bad domains (full host match against long/unambiguous keywords)
        //    Short keywords (< 6 chars) use word-boundary regex on the host to avoid false positives
        for (keyword in SUBSTRING_KEYWORDS) {
            if (keyword.length >= 6) {
                if (host.contains(keyword)) return true
            } else {
                // Use word boundary for short keywords on the host
                val r = Regex("(^|[^a-z0-9])${Regex.escape(keyword)}([^a-z0-9]|$)")
                if (r.containsMatchIn(host)) return true
            }
        }

        // 2️⃣ Exact word match on normalized full URL (safe boundary check)
        for (regex in EXACT_WORD_REGEXES) {
            if (regex.containsMatchIn(normalized)) return true
        }

        // 3️⃣ Substring match — ONLY on the search query portion of the URL
        //    (prevents partial matches on domain segments or unrelated path tokens)
        val searchQuery = extractSearchQuery(url)
        if (searchQuery.isNotBlank()) {
            val normalizedQuery = normalize(searchQuery)
            for (keyword in SUBSTRING_KEYWORDS) {
                if (normalizedQuery.contains(keyword)) return true
            }
        }

        return false
    }

    // ==============================
    // 🔎 SEARCH QUERY EXTRACTION
    // Extracts the value of common search query params (q, query, search, s, p)
    // so substring keyword matching is scoped only to what the user actually searched.
    // ==============================

    private fun extractSearchQuery(url: String): String {
        return try {
            val cleanUrl = if (!url.startsWith("http")) "http://$url" else url
            val uri = URI(cleanUrl)
            val query = uri.query ?: return ""
            val searchParams = listOf("q", "query", "search", "s", "p", "text", "keyword")
            query.split("&").forEach { param ->
                val (key, value) = param.split("=", limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else return@forEach
                }
                if (key.lowercase() in searchParams) {
                    return value.replace("+", " ").replace("%20", " ")
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    // ==============================
    // 🧠 NORMALIZATION (ANTI BYPASS)
    // Converts obfuscated text to readable form before keyword matching.
    // e.g., "sh1t" → "shit", "a$$hole" → "asshole", "put@ngina" → "putangina"
    // ==============================

    private fun normalize(input: String): String {
        return input.lowercase(Locale.ROOT)
            .replace("%20", " ")
            .replace("+", " ")
            // Leet-speak digit substitutions
            .replace("0", "o")
            .replace("3", "e")
            .replace("1", "i")
            .replace("5", "s")
            .replace("4", "a")
            // Symbol substitutions (covers censored variants like @, !, $)
            .replace("@", "a")
            .replace("!", "i")
            .replace("$", "s")
    }
}
