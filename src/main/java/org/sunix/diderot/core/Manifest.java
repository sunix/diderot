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
        /** For git sources: a branch, tag, or commit SHA. Defaults to the remote default branch resolution of "HEAD". */
        public String version = "HEAD";
    }
}
