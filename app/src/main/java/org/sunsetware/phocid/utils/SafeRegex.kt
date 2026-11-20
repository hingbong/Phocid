package org.sunsetware.phocid.utils

import com.google.re2j.Pattern as Re2Pattern
import java.util.regex.Pattern as JavaPattern

/**
 * Bounded matcher for user-supplied indexing rules.
 *
 * Rules are compiled with RE2/J, a linear-time automaton engine: matching cost grows with the
 * subject length only, so a hostile or accidental pattern such as "(a|aa)+$" can no longer stall
 * the indexing thread the way java.util.regex's backtracking matcher can.
 *
 * RE2/J rejects a few constructs the backtracking engine accepts — notably backreferences and
 * lookaround. Legacy stored rules rely on those (for example a whitelist that uses a negative
 * lookahead to exclude tracks), and silently dropping them emptied users' libraries. Such rules
 * fall back to java.util.regex, bounded by [MAX_SUBJECT_LENGTH] truncation and a per-match deadline
 * ([MATCH_TIMEOUT_MS]) so the fallback cannot reintroduce unbounded backtracking. A rule that times
 * out reports no match rather than stalling or crashing the indexing thread.
 *
 * Two size bounds remain as defence in depth: patterns are capped at [MAX_PATTERN_LENGTH], and
 * subjects are truncated to [MAX_SUBJECT_LENGTH] before matching. The nested-quantifier shape check
 * is kept so patterns that were already rejected stay rejected.
 */
object SafeRegex {
    /** Long enough for real rules, short enough to bound the compiled program size. */
    const val MAX_PATTERN_LENGTH = 512

    /** Filesystem paths are short; truncating preserves the previous matching behaviour. */
    const val MAX_SUBJECT_LENGTH = 1024

    /** Per-match wall-clock budget for the java.util.regex fallback. */
    const val MATCH_TIMEOUT_MS = 200L

    // Matches a quantified group that is itself quantified, e.g. (a+)+ , (.*)* , (a|b)+?+
    private val NESTED_QUANTIFIER = Re2Pattern.compile("""\([^()]*[+*][^()]*\)\s*[+*{]""")

    /** Returns true when [pattern] is accepted as a bounded user rule. */
    fun isValid(pattern: String): Boolean = compile(pattern) != null

    /**
     * Compiles an accepted rule, or null when it is invalid. RE2/J is preferred; a rule it rejects
     * but java.util.regex accepts (lookaround / backreferences) uses the deadline-bounded fallback.
     */
    fun compile(pattern: String): SafePattern? {
        if (pattern.length > MAX_PATTERN_LENGTH) return null
        if (NESTED_QUANTIFIER.matcher(pattern).find()) return null
        val re2 =
            try {
                Re2Pattern.compile(pattern, Re2Pattern.CASE_INSENSITIVE)
            } catch (_: Exception) {
                null
            }
        if (re2 != null) return SafePattern(re2, null)
        val java =
            try {
                JavaPattern.compile(
                    pattern,
                    JavaPattern.CASE_INSENSITIVE or JavaPattern.UNICODE_CASE,
                )
            } catch (_: Exception) {
                null
            }
        return if (java != null) SafePattern(null, java) else null
    }
}

/**
 * A compiled indexing rule. [re2] is non-null for the linear-time engine; otherwise the rule uses a
 * deadline-bounded java.util.regex fallback so legacy lookaround/backreference rules keep working.
 */
class SafePattern internal constructor(val re2: Re2Pattern?, private val java: JavaPattern?) {
    /** Runs a match against a length-bounded subject, honouring the fallback deadline. */
    fun matchesBounded(subject: String): Boolean {
        val bounded =
            if (subject.length > SafeRegex.MAX_SUBJECT_LENGTH)
                subject.take(SafeRegex.MAX_SUBJECT_LENGTH)
            else subject
        if (re2 != null) return re2.matcher(bounded).find()
        val deadline = System.nanoTime() + SafeRegex.MATCH_TIMEOUT_MS * 1_000_000L
        return try {
            java!!.matcher(DeadlineCharSequence(bounded, deadline)).find()
        } catch (_: MatchTimeoutException) {
            false
        }
    }
}

private class MatchTimeoutException : RuntimeException()

/**
 * CharSequence that aborts a backtracking match once the deadline passes: java.util.regex reads the
 * subject through [get] on every step, so a pathological pattern is interrupted instead of running
 * away. [subSequence] preserves the deadline for captured groups.
 */
private class DeadlineCharSequence(
    private val delegate: CharSequence,
    private val deadlineNanos: Long,
) : CharSequence {
    override val length: Int
        get() = delegate.length

    override fun get(index: Int): Char {
        if (System.nanoTime() > deadlineNanos) throw MatchTimeoutException()
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(delegate.subSequence(startIndex, endIndex), deadlineNanos)

    override fun toString(): String = delegate.toString()
}
