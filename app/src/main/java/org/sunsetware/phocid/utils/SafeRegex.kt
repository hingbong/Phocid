package org.sunsetware.phocid.utils

import com.google.re2j.Pattern

/**
 * Bounded matcher for user-supplied indexing rules.
 *
 * Rules are compiled with RE2/J, a linear-time automaton engine: matching cost grows with the
 * subject length only, so a hostile or accidental pattern such as "(a|aa)+$" can no longer stall
 * the indexing thread the way java.util.regex's backtracking matcher can. RE2/J rejects a few
 * constructs that the backtracking engine accepts — notably backreferences and lookaround — and
 * [isValid] reports those as invalid so the editing dialog shows feedback instead of the rule
 * being silently dropped at compile time.
 *
 * Two size bounds remain as defence in depth: patterns are capped at [MAX_PATTERN_LENGTH], and
 * subjects are truncated to [MAX_SUBJECT_LENGTH] before matching (which also preserves the
 * previous matching behaviour for absurdly long paths). The nested-quantifier shape check is
 * kept so patterns that were already rejected stay rejected.
 */
object SafeRegex {
    /** Long enough for real rules, short enough to bound the compiled program size. */
    const val MAX_PATTERN_LENGTH = 512

    /** Filesystem paths are short; truncating preserves the previous matching behaviour. */
    const val MAX_SUBJECT_LENGTH = 1024

    // Matches a quantified group that is itself quantified, e.g. (a+)+ , (.*)* , (a|b)+?+
    private val NESTED_QUANTIFIER = Pattern.compile("""\([^()]*[+*][^()]*\)\s*[+*{]""")

    /** Returns true when [pattern] is accepted as a bounded user rule. */
    fun isValid(pattern: String): Boolean {
        if (pattern.length > MAX_PATTERN_LENGTH) return false
        if (NESTED_QUANTIFIER.matcher(pattern).find()) return false
        return try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Compiles an accepted rule, or null when it is invalid. Callers should use [isValid] first
     * so invalid rules are not silently dropped without feedback.
     */
    fun compile(pattern: String): Pattern? =
        if (!isValid(pattern)) null
        else
            try {
                Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
            } catch (_: Exception) {
                null
            }
}

/** Runs a match against a length-bounded subject. */
fun Pattern.matchesBounded(subject: String): Boolean =
    matcher(
            if (subject.length > SafeRegex.MAX_SUBJECT_LENGTH) subject.take(SafeRegex.MAX_SUBJECT_LENGTH)
            else subject
        )
        .find()
