package com.kidsafe.secure.services

import java.util.Locale

object UrlBlockerHelper {

    // A comprehensive list of adult/explicit domains and generic trigger keywords.
    // Categorized to block explicit sites and harmful text searches on Google Chrome.
    private val BLOCKED_KEYWORDS = listOf(
        // Major Tube Sites & Brands
        "pornhub", "xvideos", "redtube", "youporn", "xnxx", "xhamster", "spankbang", "eporner",
        "chaturbate", "onlyfans", "brazzers", "naughtyamerica", "playboy", "bangbros", "realitykings",
        
        // Localized Philippine Adult Brands
        "pinayflix", "sarapbabe", "asianpinay", "javhdporn", "kantot", "tikjak",
        
        // Universal Explicit Keywords (Will block any URL containing these)
        "porn", "sex", "xxx", "nude", "nsfw", "camgirl", "stripchat", "bonga",
        "rule34", "hentai", "jav", "milf", "bdsm", "fetish", "voyeur", "escort",
        
        // Specific Adult Top-Level Domains (TLDs)
        ".xxx", ".porn", ".adult", ".sex", ".cam",

        // HARMFUL TEXT SEARCH PHRASES
        // 1. Explicit / Sexual Content Searches
        "porn videos", "sex scenes", "nude photos", "xxx sites",

        // 2. Violence and Gore
        "how to kill someone", "real fight videos", "gore videos", "how to make a bomb",

        // 3. Self-harm / Dangerous Behavior
        "how to hurt myself", "ways to commit suicide", "how to overdose", "how to kill", "how to die", "how to commit suicide",

        // 4. Drugs and Illegal Activities
        "how to make drugs", "buy illegal substances", "how to hack accounts",

        // 6. Scams / Unsafe Interaction
        "free robux generator", "hack game accounts", "chat with strangers online",

        // 7. Age-Inappropriate Content
        "gambling", "dating apps"
    )

    // Words that frequently appear as innocent substrings (Scunthorpe problem).
    private val EXACT_WORD_KEYWORDS = setOf(
        "porn", "sex", "xxx", "nude", "jav", "cam", "escort", "milf", "bdsm", "voyeur", "fetish", "nsfw"
    )

    // Precompile regexes for exact word matching to save CPU cycles in the Accessibility Service
    private val EXACT_WORD_REGEXES = EXACT_WORD_KEYWORDS.map {
        Regex("\\b$it\\b")
    }

    // Remaining keywords for fast substring matching
    private val SUBSTRING_KEYWORDS = BLOCKED_KEYWORDS.filterNot { it in EXACT_WORD_KEYWORDS }

    /**
     * Checks if the extracted URL contains any explicit keywords or exactly matches bad sites.
     * @param url The URL text extracted from the browser AccessibilityNode.
     * @return true if the URL is deemed inappropriate and should be blocked.
     */
    fun isUrlBlocked(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        
        // Lowercase the text
        val urlLower = url.lowercase(Locale.ROOT)
        
        // Normalize the URL string by converting '+' and '%20' (URL encoded space) to actual spaces.
        // This ensures the multi-word phrases added above will match whether typed in a search bar or as a raw URL.
        val normalizedUrl = urlLower.replace("%20", " ").replace("+", " ")

        // 1. Check exact word matches (using precompiled regex boundaries)
        for (regex in EXACT_WORD_REGEXES) {
            if (regex.containsMatchIn(normalizedUrl)) {
                return true
            }
        }
        
        // 2. Check general substring matches for domains and explicit multi-word phrases
        for (keyword in SUBSTRING_KEYWORDS) {
            if (normalizedUrl.contains(keyword)) {
                return true
            }
        }
        
        return false
    }
}
