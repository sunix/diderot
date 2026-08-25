package org.sunix.diderot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class VersionConstraintTest {

    /** What a registry really looks like: releases, a moving tag, a branch name, and junk. */
    private static final List<String> TAGS =
            List.of("1.0.0", "1.1.0", "1.2.3", "2.0.0", "1.3.0-rc.1", "latest", "main", "1h", "1.1");

    @Test
    void rangesAreRecognisedByTheirSyntax() {
        assertTrue(VersionConstraint.isRange("^1.0.0"));
        assertTrue(VersionConstraint.isRange("~1.2.0"));
        assertTrue(VersionConstraint.isRange(">=1.0.0 <2"));
        assertTrue(VersionConstraint.isRange("1.2.x"));
        assertTrue(VersionConstraint.isRange("*"));
        assertTrue(VersionConstraint.isRange("1.0.0 || 2.0.0"));
    }

    @Test
    void everythingElseStaysALiteralTag() {
        assertFalse(VersionConstraint.isRange("1.2.3"), "an exact version pins that tag, as before");
        assertFalse(VersionConstraint.isRange("v1"));
        assertFalse(VersionConstraint.isRange("1.0.0-rc.1"), "a pre-release tag is a tag, not a range");
        assertFalse(VersionConstraint.isRange("main"));
        assertFalse(VersionConstraint.isRange("HEAD"));
        assertFalse(VersionConstraint.isRange(null));
        assertFalse(VersionConstraint.isRange("  "));
    }

    /**
     * The reason {@link VersionConstraint#isRange} is syntactic instead of "whatever the range
     * parser accepts": semver4j happily reads {@code latest} as the range {@code >=0.0.0}. Treating
     * it as one would turn a pin on a moving tag into "give me the newest release" - silently
     * resolving {@code latest} to {@code 2.0.0} instead of fetching the tag the user named.
     */
    @Test
    void latestIsATagEvenThoughARangeParserWouldAcceptIt() {
        assertFalse(VersionConstraint.isRange("latest"));
    }

    @Test
    void aRangePicksTheHighestSatisfyingTagAndIgnoresTheJunk() {
        assertEquals(Optional.of("1.2.3"), VersionConstraint.select("^1.0.0", TAGS));
        assertEquals(Optional.of("1.1.0"), VersionConstraint.select("~1.1.0", TAGS));
        assertEquals(Optional.of("1.2.3"), VersionConstraint.select(">=1.0.0 <2", TAGS));
        assertEquals(Optional.of("2.0.0"), VersionConstraint.select("*", TAGS));
        assertEquals(Optional.of("1.2.3"), VersionConstraint.select("1.2.x", TAGS));
    }

    @Test
    void preReleasesAreNotCandidatesUnlessAskedFor() {
        assertEquals(Optional.of("1.2.3"), VersionConstraint.select("^1.0.0", TAGS),
                "1.3.0-rc.1 is higher than 1.2.3 but a caret range must not pick a pre-release");
    }

    @Test
    void aVPrefixedTagResolvesAndComesBackAsWritten() {
        assertEquals(Optional.of("v1.4.0"),
                VersionConstraint.select("^1.0.0", List.of("v1.2.0", "v1.4.0", "latest")),
                "the tag must be returned verbatim, since that is the reference the registry has");
    }

    @Test
    void twoTagsForOneVersionResolveDeterministically() {
        assertEquals(Optional.of("1.2.3"), VersionConstraint.select("^1.0.0", List.of("v1.2.3", "1.2.3")));
        assertEquals(Optional.of("1.2.3"), VersionConstraint.select("^1.0.0", List.of("1.2.3", "v1.2.3")),
                "the choice must not depend on the order the registry listed the tags in");
    }

    @Test
    void nothingSatisfyingMeansEmptyRatherThanAnException() {
        assertEquals(Optional.empty(), VersionConstraint.select("^3.0.0", TAGS));
        assertEquals(Optional.empty(), VersionConstraint.select("^1.0.0", List.of("latest", "main")));
        assertEquals(Optional.empty(), VersionConstraint.select("^1.0.0", List.of()));
    }

    @Test
    void semverTagsExplainsWhatARangeCouldHavePicked() {
        assertEquals(List.of("2.0.0", "1.3.0-rc.1", "1.2.3", "1.1.0", "1.0.0"),
                VersionConstraint.semverTags(TAGS),
                "newest first, with latest/main/1h/1.1 dropped as unresolvable");
    }
}
