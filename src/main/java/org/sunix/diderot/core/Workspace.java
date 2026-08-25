package org.sunix.diderot.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.sunix.diderot.core.LockFile.LockedSkill;
import org.sunix.diderot.core.Manifest.ManifestSkill;
import org.sunix.diderot.git.GitCli;
import org.sunix.diderot.oci.OrasClient;

/**
 * The update/install/status engine, operating on a project root containing diderot.yaml.
 * Kept free of picocli so tests drive it directly.
 */
public class Workspace {

    private final Path root;
    private final GitCli git;
    private final OrasClient oci;
    private final PrintWriter out;

    public Workspace(Path root, GitCli git, OrasClient oci, PrintWriter out) {
        this.root = root;
        this.git = git;
        this.oci = oci;
        this.out = out;
    }

    public Path manifestPath() {
        return root.resolve("diderot.yaml");
    }

    public Path lockPath() {
        return root.resolve("diderot.lock");
    }

    /** Resolve every manifest constraint to a commit + content digest and (re)write the lockfile. */
    public LockFile update() throws IOException {
        Manifest manifest = readManifest();
        LockFile lock = new LockFile();
        for (ManifestSkill skill : manifest.skills) {
            requireName(skill);
            SourceRef ref = SourceRef.parse(skill.source);
            LockedSkill locked = switch (ref.kind()) {
                case GIT -> lockGitSkill(skill, ref);
                case OCI -> lockOciSkill(skill, ref);
            };
            lock.skills.add(locked);
            // With ranges in play, the repository alone no longer says what was chosen.
            String reference = locked.tag == null ? ref.url() : ref.url() + ":" + locked.tag;
            out.printf("locked %-20s %s@%s (%s)%n",
                    skill.name, reference, shortSha(locked.resolved), locked.digest);
        }
        lock.skills.sort(Comparator.comparing(s -> s.name));
        Yaml.write(lockPath(), lock);
        out.println("wrote " + lockPath().getFileName());
        return lock;
    }

    /** Install exactly what the lockfile pins, verifying content digests after extraction. */
    public void install(List<String> targetOverrides) throws IOException {
        LockFile lock = readLock();
        List<TargetLayout> targets = resolveTargets(targetOverrides);
        for (LockedSkill skill : lock.skills) {
            SourceRef ref = SourceRef.parse(skill.source);
            for (TargetLayout target : targets) {
                Path dest = target.skillsDir(root).resolve(skill.name);
                deleteRecursively(dest);
                switch (ref.kind()) {
                    case GIT -> {
                        Path repo = git.ensureFresh(ref.url());
                        git.extract(repo, skill.resolved, ref.path(), dest);
                    }
                    case OCI -> copyRecursively(oci.cachedPull(ref.url(), skill.resolved), dest);
                }
                String actual = "tree:" + GitTreeHasher.treeSha(dest);
                if (!actual.equals(skill.digest)) {
                    throw new IOException("Digest mismatch for '" + skill.name + "' in " + dest
                            + ": expected " + skill.digest + ", got " + actual);
                }
                out.printf("installed %-20s -> %s (%s verified)%n",
                        skill.name, root.relativize(dest), skill.digest);
            }
        }
    }

    private LockedSkill lockGitSkill(ManifestSkill skill, SourceRef ref) throws IOException {
        Path repo = git.ensureFresh(ref.url());
        String commit = git.resolveCommit(repo, skill.version);
        String skillMd = ref.path().isEmpty() ? "SKILL.md" : ref.path() + "/SKILL.md";
        if (!git.blobExists(repo, commit, skillMd)) {
            throw new IOException("Skill '" + skill.name + "': no SKILL.md at " + skillMd
                    + " in " + ref.url() + "@" + shortSha(commit));
        }
        LockedSkill locked = new LockedSkill();
        locked.name = skill.name;
        locked.source = skill.source;
        locked.resolved = commit;
        locked.digest = "tree:" + git.treeSha(repo, commit, ref.path());
        return locked;
    }

    private LockedSkill lockOciSkill(ManifestSkill skill, SourceRef ref) throws IOException {
        String tag = resolveTag(skill, ref);
        String digest;
        try {
            digest = oci.resolveDigest(ref.url() + ":" + tag);
        } catch (RuntimeException e) {
            // Bare registry errors name neither the reference that failed nor what does exist.
            throw new IOException("Skill '" + skill.name + "': cannot resolve " + ref.url() + ":" + tag
                    + " (" + e.getMessage() + "). " + publishedTags(ref), e);
        }
        Path content = oci.cachedPull(ref.url(), digest);
        if (!Files.isRegularFile(content.resolve("SKILL.md"))) {
            throw new IOException("Skill '" + skill.name + "': no SKILL.md in " + ref.url() + ":" + tag);
        }
        LockedSkill locked = new LockedSkill();
        locked.name = skill.name;
        locked.source = skill.source;
        locked.resolved = digest;
        locked.tag = tag;
        locked.digest = "tree:" + GitTreeHasher.treeSha(content);
        return locked;
    }

    /**
     * The registry tag a manifest constraint points at. A literal tag is used exactly as written, so
     * a pin stays a pin; only something written like a range gets resolved against the tag list.
     */
    private String resolveTag(ManifestSkill skill, SourceRef ref) throws IOException {
        // "HEAD" is the git-flavored default; for a registry the moving default tag is "latest".
        if (skill.version == null || skill.version.isBlank() || "HEAD".equals(skill.version)) {
            return "latest";
        }
        if (!VersionConstraint.isRange(skill.version)) {
            return skill.version;
        }
        List<String> tags = listTags(skill, ref);
        Optional<String> chosen = VersionConstraint.select(skill.version, tags);
        if (chosen.isEmpty()) {
            throw new IOException("Skill '" + skill.name + "': no tag in " + ref.url()
                    + " satisfies " + skill.version + ". " + describeTags(tags));
        }
        return chosen.get();
    }

    private List<String> listTags(ManifestSkill skill, SourceRef ref) throws IOException {
        try {
            return oci.listTags(ref.url());
        } catch (RuntimeException e) {
            throw new IOException("Skill '" + skill.name + "': cannot list the tags of " + ref.url()
                    + ", which " + skill.version + " needs in order to resolve (" + e.getMessage() + ").", e);
        }
    }

    private static String describeTags(List<String> tags) {
        List<String> semver = VersionConstraint.semverTags(tags);
        if (semver.isEmpty()) {
            return tags.isEmpty()
                    ? "The repository advertises no tags at all."
                    : "None of its tags are semver: " + String.join(", ", tags) + ".";
        }
        return "Published versions, newest first: " + String.join(", ", semver) + ".";
    }

    /** What the repository does publish - the context a bare 404 leaves out. */
    private String publishedTags(SourceRef ref) {
        try {
            return describeTags(oci.listTags(ref.url()));
        } catch (RuntimeException e) {
            return "Its tag list could not be read either (" + e.getMessage() + ").";
        }
    }

    /** Compare installed skills against the lockfile; returns the number of problems found. */
    public int status(List<String> targetOverrides) throws IOException {
        LockFile lock = readLock();
        List<TargetLayout> targets = resolveTargets(targetOverrides);
        int problems = 0;
        for (LockedSkill skill : lock.skills) {
            for (TargetLayout target : targets) {
                Path dest = target.skillsDir(root).resolve(skill.name);
                String state;
                if (!Files.isDirectory(dest)) {
                    state = "MISSING";
                    problems++;
                } else if (("tree:" + GitTreeHasher.treeSha(dest)).equals(skill.digest)) {
                    state = "ok";
                } else {
                    state = "DRIFTED";
                    problems++;
                }
                out.printf("%-8s %-20s %s%n", state, skill.name, root.relativize(dest));
            }
        }
        return problems;
    }

    private Manifest readManifest() throws IOException {
        if (!Files.isRegularFile(manifestPath())) {
            throw new IOException("No diderot.yaml found in " + root + ".");
        }
        return Yaml.read(manifestPath(), Manifest.class);
    }

    private LockFile readLock() throws IOException {
        if (!Files.isRegularFile(lockPath())) {
            throw new IOException("No diderot.lock found in " + root + ". Run `diderot update` first.");
        }
        return Yaml.read(lockPath(), LockFile.class);
    }

    private List<TargetLayout> resolveTargets(List<String> overrides) throws IOException {
        List<String> names = overrides != null && !overrides.isEmpty() ? overrides : readManifest().targets;
        List<TargetLayout> targets = new ArrayList<>();
        for (String name : names) {
            targets.add(TargetLayout.of(name));
        }
        return targets;
    }

    private static void requireName(ManifestSkill skill) throws IOException {
        if (skill.name == null || skill.name.isBlank()) {
            throw new IOException("Every skill in diderot.yaml needs a name.");
        }
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path src : walk.toList()) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static String shortSha(String sha) {
        if (sha.startsWith("sha256:")) {
            return sha.substring(0, Math.min(sha.length(), "sha256:".length() + 12));
        }
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }
}
