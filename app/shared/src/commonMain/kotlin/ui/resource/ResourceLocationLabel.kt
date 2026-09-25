package me.him188.ani.app.ui.resource

import io.ktor.http.decodeURLPart

/** Display-only decoding; opaque document IDs are never interpreted as filesystem paths. */
internal fun resourceLocationLabel(location: String): String {
    fun decoded(value: String) = runCatching { value.decodeURLPart() }.getOrDefault(value)
    val scheme = location.substringBefore(':', "").lowercase()
    if (scheme != "file" && scheme != "content") return location
    val rest = location.substringAfter(':')
    val authority = if (rest.startsWith("//")) rest.removePrefix("//").substringBefore('/') else ""
    val path = if (rest.startsWith("//")) rest.removePrefix("//").substringAfter('/', "") else rest
    if (scheme == "file") {
        val filePath = decoded(if (rest.startsWith("//")) "/$path" else path)
        if (authority.isNotEmpty() && !authority.equals("localhost", ignoreCase = true)) return "//${decoded(authority)}$filePath"
        return if (Regex("^/[A-Za-z]:/").containsMatchIn(filePath)) filePath.drop(1) else filePath
    }
    if (authority.isEmpty()) return location
    val segments = path.substringBefore('?').substringBefore('#').split('/')
    val marker = segments.indexOfLast { it == "document" }.takeIf { it >= 0 }
        ?: segments.indexOfLast { it == "tree" }.takeIf { it >= 0 }
    val identifier = decoded(if (marker == null) segments.joinToString("/") else segments.drop(marker + 1).joinToString("/"))
    if (authority == "com.android.externalstorage.documents") {
        val volume = identifier.substringBefore(':', "")
        if (volume.isNotEmpty()) return "$volume/${identifier.substringAfter(':').trimStart('/')}"
    }
    if (authority == "com.android.providers.downloads.documents" && identifier.startsWith("raw:")) {
        return identifier.removePrefix("raw:")
    }
    return "${decoded(authority)} / $identifier"
}
