package org.sunix.diderot.oci;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sunix.diderot.core.GitTreeHasher;

import land.oras.Annotations;
import land.oras.ArtifactType;
import land.oras.ContainerRef;
import land.oras.Manifest;
import land.oras.Registry;
import land.oras.Tags;

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

    /**
     * Manifest annotation carrying the sigstore bundle, base64-encoded. The signature travels with
     * the artifact rather than beside it — see {@link #push(Path, String, String)}.
     */
    public static final String SIGNATURE_ANNOTATION = "org.sunix.diderot.signature";

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
     * Every tag the repository advertises, in registry order. The SDK's single-argument
     * {@code getTags} already follows the pagination links and accumulates the pages (with a guard
     * against a registry that keeps pointing at itself), so a long tag list needs no paging here.
     */
    public List<String> listTags(String repository) {
        Tags tags = registryFor(repository).getTags(ContainerRef.parse(repository));
        return tags.tags() == null ? List.of() : List.copyOf(tags.tags());
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
        return push(skillDir, reference, null);
    }

    /**
     * Pushes a skill directory, carrying {@code bundleJson} with it when one is given.
     *
     * <p>The signature rides <em>inside</em> the artifact, as a manifest annotation, which is only
     * possible because what it attests is the content rather than this manifest — a signature over
     * the manifest digest would change the digest it attests and has to live somewhere else, which
     * is the whole reason the OCI referrers machinery exists. Helm does the same thing with its
     * provenance file, as a layer; an annotation is used here because
     * {@link #cachedPull(String, String)} extracts every layer, and a bundle landing in the skill
     * directory would change the content digest it is attesting.
     */
    public String push(Path skillDir, String reference, String bundleJson) throws IOException {
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(TREE_DIGEST_ANNOTATION, "tree:" + GitTreeHasher.treeSha(skillDir));
        if (bundleJson != null) {
            annotations.put(SIGNATURE_ANNOTATION, Base64.getEncoder()
                    .encodeToString(bundleJson.getBytes(StandardCharsets.UTF_8)));
        }
        Manifest manifest = registryFor(reference).pushArtifact(
                ContainerRef.parse(reference),
                ArtifactType.from(SKILL_ARTIFACT_TYPE),
                Annotations.ofManifest(annotations),
                land.oras.LocalPath.of(skillDir));
        return manifest.getDescriptor().getDigest();
    }

    /**
     * The signature carried by the artifact at {@code digest}, or empty when it carries none.
     * One manifest fetch, no discovery: the bundle is where the artifact is.
     */
    public Optional<String> fetchSignature(String repository, String digest) {
        Manifest manifest = registryFor(repository)
                .getManifest(ContainerRef.parse(repository).withDigest(digest));
        Map<String, String> annotations = manifest.getAnnotations();
        if (annotations == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(annotations.get(SIGNATURE_ANNOTATION))
                .map(encoded -> new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
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
