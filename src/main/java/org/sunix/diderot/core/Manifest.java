package org.sunix.diderot.core;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** The user-facing manifest, {@code diderot.yaml}. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Manifest {

    public List<ManifestSkill> skills = new ArrayList<>();

    /** Agent layouts to install into, e.g. ["claude"]. */
    public List<String> targets = new ArrayList<>(List.of("claude"));

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManifestSkill {
        public String name;
        /** e.g. git+https://github.com/sunix/ai-skills#skills/documentation/making-of */
        public String source;
        /**
         * For git sources: a branch, tag, or commit SHA. For {@code oci://} sources: a tag, or a
         * semver range such as {@code ^1.0.0}, {@code ~1.2.0} or {@code >=1.0.0 <2}, resolved
         * against the tags the repository publishes. Defaults to the remote default branch for git
         * and to the {@code latest} tag for a registry.
         */
        public String version = "HEAD";
    }
}
