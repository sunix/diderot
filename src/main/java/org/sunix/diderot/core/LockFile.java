package org.sunix.diderot.core;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** The generated lockfile, {@code diderot.lock}. Never edited by hand. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LockFile {

    public int lockfileVersion = 1;

    public List<LockedSkill> skills = new ArrayList<>();

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LockedSkill {
        public String name;
        /** The source as declared in the manifest. */
        public String source;
        /** The commit SHA the version constraint resolved to at lock time. */
        public String resolved;
        /**
         * For registry sources, the tag the constraint resolved to - informational only, and
         * omitted for git sources where {@link #resolved} is already a readable commit. It exists
         * because a range leaves the answer invisible otherwise: {@code ^1.0.0} against an opaque
         * {@code sha256:...} tells a human nothing about which release they are on. Read it as
         * "at lock time, this tag pointed at that digest" - the digest stays the authority, since a
         * tag can be re-pushed and this field cannot notice.
         */
        public String tag;
        /**
         * Content digest of the skill directory: {@code tree:<git tree SHA-1>}. This is what install
         * verifies and status compares against — a tag can be re-pushed, a tree SHA cannot lie.
         */
        public String digest;
    }
}
