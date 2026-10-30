package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.ui.views.statistics.tagCandidateIndex
import org.sunsetware.phocid.ui.views.statistics.uniqueTagCandidateOwner

class TagCandidateIndexTest {
    @Test
    fun uniqueCandidatesMapToTheirOwningTrack() {
        val index =
            tagCandidateIndex(
                listOf(
                    1L to listOf("a", "b"),
                    2L to listOf("c"),
                )
            )

        assertThat(index)
            .containsExactlyEntriesOf(mapOf("a" to setOf(1L), "b" to setOf(1L), "c" to setOf(2L)))
    }

    @Test
    fun candidateClaimedByTwoTracksKeepsBothOwners() {
        val index =
            tagCandidateIndex(
                listOf(
                    1L to listOf("shared", "a"),
                    2L to listOf("shared", "b"),
                )
            )

        assertThat(index).containsEntry("shared", setOf(1L, 2L))
        assertThat(index).containsEntry("a", setOf(1L)).containsEntry("b", setOf(2L))
    }

    @Test
    fun candidateClaimedByThreeTracksKeepsEveryOwner() {
        val index =
            tagCandidateIndex(
                listOf(
                    1L to listOf("shared"),
                    2L to listOf("shared"),
                    3L to listOf("shared"),
                )
            )

        assertThat(index).containsEntry("shared", setOf(1L, 2L, 3L))
    }

    @Test
    fun repeatedCandidateFromTheSameTrackIsUnique() {
        assertThat(tagCandidateIndex(listOf(1L to listOf("a", "a"))))
            .containsExactlyEntriesOf(mapOf("a" to setOf(1L)))
    }

    @Test
    fun candidateKeysOwnedByDifferentTracksDoNotResolveToTheFirstCandidate() {
        val index =
            tagCandidateIndex(
                listOf(
                    1L to listOf("90"),
                    2L to listOf("89"),
                )
            )

        assertThat(uniqueTagCandidateOwner(listOf("90", "89"), index)).isNull()
        assertThat(uniqueTagCandidateOwner(listOf("89", "90"), index)).isNull()
    }

    @Test
    fun sharedCandidateIsAmbiguousEvenWhenItIsTheOnlyCandidate() {
        val index =
            tagCandidateIndex(
                listOf(
                    1L to listOf("shared"),
                    2L to listOf("shared"),
                )
            )

        assertThat(uniqueTagCandidateOwner(listOf("shared"), index)).isNull()
    }

    @Test
    fun uniqueCandidateResolvesToItsOwner() {
        val index =
            tagCandidateIndex(
                listOf(
                    1L to listOf("a", "b"),
                    2L to listOf("c"),
                )
            )

        assertThat(uniqueTagCandidateOwner(listOf("a"), index)).isEqualTo(1L)
        assertThat(uniqueTagCandidateOwner(listOf("c"), index)).isEqualTo(2L)
    }

    @Test
    fun candidateUnionResolvesOnlyWhenEveryMatchingKeySharesTheSameOwner() {
        val index =
            tagCandidateIndex(
                listOf(
                    1L to listOf("a", "b"),
                    2L to listOf("c"),
                )
            )

        assertThat(uniqueTagCandidateOwner(listOf("a", "b"), index)).isEqualTo(1L)
        assertThat(uniqueTagCandidateOwner(listOf("a", "c"), index)).isNull()
    }
}
