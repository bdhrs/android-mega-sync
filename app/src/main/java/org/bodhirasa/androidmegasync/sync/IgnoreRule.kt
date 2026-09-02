package org.bodhirasa.androidmegasync.sync

fun interface IgnoreRule {
    fun isIgnored(path: String): Boolean

    companion object {
        val NONE = IgnoreRule { false }
    }
}

class PathListIgnoreRule(paths: List<String>) : IgnoreRule {

    private val excluded = paths.toSet()

    override fun isIgnored(path: String): Boolean =
        path in excluded || excluded.any { path.startsWith("$it/") }
}

class GlobIgnoreRule(globs: List<String>) : IgnoreRule {

    private val regexes = globs.map { it.globToRegex() }

    override fun isIgnored(path: String): Boolean = regexes.any { it.matches(path) }

    private fun String.globToRegex(): Regex {
        val sb = StringBuilder()
        for (c in this) {
            when (c) {
                '*' -> sb.append("[^/]*")
                '?' -> sb.append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                    sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return Regex(sb.toString())
    }
}
