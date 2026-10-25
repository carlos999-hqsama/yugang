package com.personaldashboard.app

import java.util.Locale

/**
 * Pure Content-Type classification for `native.fetch(responseType = auto)`.
 *
 * Keep this independent from HTTP clients so the MIME policy can be tested without networking.
 */
internal object PluginMime {
    fun isText(contentType: String?): Boolean {
        if (contentType == null) return false

        val mediaType =
            contentType
                .substringBefore(';')
                .trim()
                .lowercase(Locale.ROOT)
        val slash = mediaType.indexOf('/')
        if (slash <= 0 || slash != mediaType.lastIndexOf('/') || slash == mediaType.length - 1) {
            return false
        }

        val type = mediaType.substring(0, slash)
        val subtype = mediaType.substring(slash + 1)
        if (subtype.isEmpty()) return false

        if (type == "text") return true
        if (type != "application") return false

        return subtype in
            setOf(
                "json",
                "xml",
                "javascript",
                "x-www-form-urlencoded",
                "graphql",
            ) || hasStructuredSuffix(subtype, "json") || hasStructuredSuffix(subtype, "xml")
    }

    private fun hasStructuredSuffix(
        subtype: String,
        suffix: String,
    ): Boolean = subtype.length > suffix.length + 1 && subtype.endsWith("+$suffix")
}
