package app.manyuenext.cloud.common

object CloudLogRedactor {
    fun redact(value: String): String = value
        .replace(Regex("(?i)(authorization\\s*:\\s*)(?:bearer|token)?\\s*[^\\s,;]+"), "$1<redacted>")
        .replace(Regex("(?i)(cookie\\s*:\\s*)[^\\r\\n]+"), "$1<redacted>")
        .replace(Regex("(?i)(\\b(?:bearer|token|avs|jwt|password|secret|api[-_ ]?key)\\b\\s*[=:]\\s*)[^\\s,;]+"), "$1<redacted>")
        .replace(Regex("(?i)(\\b(?:bearer|token)\\b\\s+)[^\\s,;]+"), "$1<redacted>")
}
