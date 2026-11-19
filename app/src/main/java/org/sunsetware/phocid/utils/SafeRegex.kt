package org.sunsetware.phocid.utils

/**
 * Bounded matcher for user-supplied indexing rules.
 *
 * Kotlin's [Regex] is backed by java.util.regex's backtracking NFA. A short pattern such as
 * "(a+)+$" can take exponential time on a path of only a few dozen characters, so compiling a
 * pattern successfully is not enough to make it safe to run against every track path. Until the
 * project migrates to a linear-time engine (RE2/J), we bound both the pattern and the subject and
 * reject the classic nested-quantifier shape that causes catastrophic backtracking.
 *
 * This is defence in depth, not a proof of linear time: callers must still treat these rules as
 * untrusted input.
 */
object SafeRegex {
    /** Long enough for real rules, short enough to keep pathological patterns small. */
    const val MAX_PATTERN_LENGTH = 512

    /** Filesystem paths are short; truncating bounds the input to the backtracking matcher. */
    const val MAX_SUBJECT_LENGTH = 1024

    // Matches a quantified group that is itself quantified, e.g. (a+)+ , (.*)* , (a|b)+?+
    private val NESTED_QUANTIFIER =
        Regex("""([^()]*[+*][^()]*)s*[+*{]""")

    /** Returns true when [pattern] is accepted as a bounded user rule. */
    fun isValid(pattern: String): Boolean {
        if (pattern.length > MAX_PATTERN_LENGTH) return false
        if (NESTED_QUANTIFIER.containsMatchIn(pattern)) return false
        return try {
            Regex(pattern, RegexOption.IGNORE_CASE)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Compiles an accepted rule, or null when it is invalid. Callers should use [isValid] first so
     * invalid rules are not silently dropped without feedback.
     */
    fun compile(pattern: String): Regex? =
        if (!isValid(pattern)) null
        else
            try {
                Regex(pattern, RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                null
            }
}

/** Runs a match against a length-bounded subject. */
fun Regex.matchesBounded(subject: String): Boolean =
    containsMatchIn(
        if (subject.length > SafeRegex.MAX_SUBJECT_LENGTH) subject.take(SafeRegex.MAX_SUBJECT_LENGTH)
        else subject
    )
