package io.github.yuroyami.archivekodec.util

/**
 * Escaped name for a bounded `toString`: quotes, backslashes and controls are
 * escaped, lone surrogates are shown as `\uXXXX`, and output stops at 96 characters.
 */
internal fun describeName(name: String): String = buildString {
    var at = 0
    while (at < name.length) {
        val char = name[at]
        var width = 1
        val text = when (char) {
            '\\' -> "\\\\"
            '"' -> "\\\""
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> when {
                char.code in 0xD800..0xDBFF && at + 1 < name.length && name[at + 1].code in 0xDC00..0xDFFF -> {
                    width = 2
                    name.substring(at, at + 2)
                }
                char.code < 0x20 || char.code == 0x7F || char.code in 0xD800..0xDFFF ->
                    "\\u${char.code.toString(16).padStart(4, '0')}"
                else -> char.toString()
            }
        }
        if (length + text.length > 96) {
            append('…')
            break
        }
        append(text)
        at += width
    }
}
