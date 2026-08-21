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
         * Content digest of the skill directory: {@code tree:<git tree SHA-1>}. This is what install
         * verifies and status compares against — a tag can be re-pushed, a tree SHA cannot lie.
         */
        public String digest;
    }
}
