package com.kidsafe.secure.services

import java.util.Locale

object UrlBlockerHelper {

    // A comprehensive list of adult/explicit domains and generic trigger keywords.
    // Since we use .contains(), if any of these words appear anywhere in the URL, it will block the site.
    private val BLOCKED_KEYWORDS = listOf(
        // Major Tube Sites & Brands
        "pornhub", "xvideos", "redtube", "youporn", "xnxx", "xhamster", "spankbang", "eporner",
        "chaturbate", "onlyfans", "brazzers", "naughtyamerica", "playboy", "bangbros", "realitykings",
        
        // Universal Explicit Keywords (Will block any URL containing these)
        "porn", "sex", "xxx", "nude", "nsfw", "camgirl", "stripchat", "bonga",
        "rule34", "hentai", "jav", "milf", "bdsm", "fetish", "voyeur", "escort",
        
        // Specific Adult Top-Level Domains (TLDs)
        ".xxx", ".porn", ".adult", ".sex", ".cam"
    )

    /**
     * Checks if the extracted URL contains any explicit keywords or exactly matches bad sites.
     * @param url The URL text extracted from the browser AccessibilityNode.
     * @return true if the URL is deemed inappropriate and should be blocked.
     */
    fun isUrlBlocked(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        
        val urlLower = url.lowercase(Locale.ROOT)
        
        for (keyword in BLOCKED_KEYWORDS) {
            // Check if the URL contains the exact keyword.
            // We use simple 'contains' for keywords but be careful about false positives 
            // from words like "corn" vs "porn" if matched strictly.
            if (urlLower.contains(keyword)) {
                return true
            }
        }
        return false
    }
}
