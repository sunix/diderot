package org.sunix.diderot.core;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.semver4j.Semver;

/**
 * Decides what a manifest's {@code version:} means for a registry source, and resolves the range
 * case against the tags a repository advertises.
 *
 * <p>Most constraints are literal tags: {@code version: 1.2.0} resolves the tag named
 * {@code 1.2.0}, exactly as it did before ranges existed. A constraint becomes a <em>range</em>
 * only when it is written like one — see {@link #isRange(String)}. That distinction is
 * deliberately syntactic rather than "whatever the range parser accepts", because npm-style range
 * parsers are permissive to a fault: semver4j reads {@code latest} as the range {@code >=0.0.0},
 * so asking it to validate would quietly turn a pin on a moving tag into "give me the newest
 * release".
 */
public final class VersionConstraint {

    /**
     * Characters that appear only in a range: the comparators, the caret and tilde shorthands, the
     * union operator, the wildcard, and the space that separates an intersection such as
     * {@code >=1.0.0 <2}.
     */
    private static final Pattern RANGE_SYNTAX = Pattern.compile("[\\^~><=|*\\s]");

    /** X-ranges ({@code 1.x}, {@code 1.2.X}) contain no operator, so they need a rule of their own. */
    private static final Pattern X_RANGE = Pattern.compile("v?\\d+(\\.\\d+)?\\.[xX]");

    private VersionConstraint() {
    }

    /** Whether this constraint should be resolved against the tag list rather than used as a tag. */
    public static boolean isRange(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String v = version.trim();
        return RANGE_SYNTAX.matcher(v).find() || X_RANGE.matcher(v).matches();
    }

    /**
     * The highest tag satisfying {@code range}, or empty when none does. Tags that are not semver
     * are skipped in silence: a registry legitimately holds {@code latest}, {@code main}, date
     * stamps and other junk beside its releases, and a range must not fail because of them.
     *
     * <p>Both {@code 1.2.3} and {@code v1.2.3} are recognised, and the tag comes back as written so
     * the caller resolves a reference that actually exists. When two tags name the same version the
     * shorter-sorting one wins, which makes the choice deterministic instead of dependent on the
     * order the registry happened to list them in. Pre-releases are excluded unless the range asks
     * for them, following npm's rule.
     */
    public static Optional<String> select(String range, List<String> tags) {
        Semver best = null;
        String bestTag = null;
        for (String tag : tags) {
            Semver candidate = parseOrNull(tag);
            if (candidate == null || !satisfiesOrFalse(candidate, range)) {
                continue;
            }
            int cmp = bestTag == null ? 1 : candidate.compareTo(best);
            if (cmp > 0 || (cmp == 0 && tag.compareTo(bestTag) < 0)) {
                best = candidate;
                bestTag = tag;
            }
        }
        return Optional.ofNullable(bestTag);
    }

    /** The tags a range could ever pick from, highest version first — for use in error messages. */
    public static List<String> semverTags(List<String> tags) {
        return tags.stream()
                .filter(t -> parseOrNull(t) != null)
                .sorted(Comparator.comparing(VersionConstraint::parseOrNull,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
    }

    /**
     * Tag lists come from the outside world, so neither a parse nor a comparison is allowed to take
     * the whole resolution down: anything unreadable is simply not a candidate.
     */
    private static Semver parseOrNull(String tag) {
        try {
            return Semver.parse(tag);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean satisfiesOrFalse(Semver candidate, String range) {
        try {
            return candidate.satisfies(range);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
