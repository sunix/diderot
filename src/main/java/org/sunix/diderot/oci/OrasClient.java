package org.sunix.diderot.oci;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import org.sunix.diderot.core.GitTreeHasher;

import land.oras.Annotations;
import land.oras.ArtifactType;
import land.oras.ContainerRef;
import land.oras.Manifest;
import land.oras.Registry;

/**
 * The OCI counterpart of GitCli: the only class that talks to container registries, built on the
 * ORAS Java SDK. Skills are pushed as OCI artifacts (one tar+gzip layer, auto-unpacked on pull) and
 * pulled into a local content cache keyed by manifest digest — a digest-addressed directory can
 * never go stale.
 */
public class OrasClient {

    /** The artifactType identifying a diderot skill in a registry. */
    public static final String SKILL_ARTIFACT_TYPE = "application/vnd.diderot.skill.v1";

    /** Manifest annotation carrying the git-tree digest of the pushed directory. */
    public static final String TREE_DIGEST_ANNOTATION = "org.sunix.diderot.tree-digest";

    private final Path cacheRoot;

    public OrasClient(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public static Path defaultCacheRoot() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("diderot").resolve("oci");
    }

    /** Resolves a tag reference (e.g. {@code ghcr.io/owner/skill:v1}) to its manifest digest. */
    public String resolveDigest(String reference) {
        return registryFor(reference).getDescriptor(ContainerRef.parse(reference)).getDigest();
    }

    /**
     * Ensures the artifact pinned by {@code digest} is present in the local cache and returns the
     * directory holding the skill content. Pulls at most once per digest; cached content is
     * immutable by construction (digest-addressed).
     */
    public Path cachedPull(String repository, String digest) throws IOException {
        Path slot = cacheRoot.resolve(digest.replace(':', '-'));
        Path content = slot.resolve("content");
        if (!Files.isDirectory(content)) {
            Path pulling = slot.resolve("pulling");
            deleteRecursively(pulling);
            Files.createDirectories(pulling);
            String ref = repository + "@" + digest;
            registryFor(ref).pullArtifact(ContainerRef.parse(ref), pulling, true);
            Files.move(pulling, content, StandardCopyOption.ATOMIC_MOVE);
        }
        return contentRoot(content);
    }

    /**
     * Pushes a skill directory as an OCI artifact and returns the manifest digest. The directory
     * travels as one tar+gzip layer (the SDK sets the unpack annotation); the manifest carries the
     * diderot artifactType and the git-tree digest of the directory for provenance.
     */
    public String push(Path skillDir, String reference) throws IOException {
        String treeDigest = "tree:" + GitTreeHasher.treeSha(skillDir);
        Manifest manifest = registryFor(reference).pushArtifact(
                ContainerRef.parse(reference),
                ArtifactType.from(SKILL_ARTIFACT_TYPE),
                Annotations.ofManifest(Map.of(TREE_DIGEST_ANNOTATION, treeDigest)),
                land.oras.LocalPath.of(skillDir));
        return manifest.getDescriptor().getDigest();
    }

    /**
     * A pulled directory artifact extracts as {@code <dest>/<original-dir-name>/…}; the skill
     * content root is that single child directory. Fall back to the destination itself when the
     * layout differs.
     */
    private static Path contentRoot(Path pulled) throws IOException {
        try (var children = Files.list(pulled)) {
            var entries = children.toList();
            if (entries.size() == 1 && Files.isDirectory(entries.get(0))) {
                return entries.get(0);
            }
        }
        return pulled;
    }

    /** Plain HTTP for local registries (tests, localhost:5000-style); TLS + docker-config auth otherwise. */
    private static Registry registryFor(String reference) {
        String host = reference.split("/", 2)[0];
        if (host.startsWith("localhost") || host.startsWith("127.")) {
            return Registry.builder().insecure().build();
        }
        return Registry.builder().defaults().build();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
