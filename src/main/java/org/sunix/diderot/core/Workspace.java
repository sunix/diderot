package org.sunix.diderot.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sunix.diderot.core.LockFile.LockedSkill;
import org.sunix.diderot.core.Manifest.ManifestSkill;
import org.sunix.diderot.git.GitCli;
import org.sunix.diderot.oci.OrasClient;
import org.sunix.diderot.oci.Signing;

/**
 * The update/install/status engine, operating on a project root containing diderot.yaml.
 * Kept free of picocli so tests drive it directly.
 */
public class Workspace {

    /**
     * What to do with a signed skill whose signer {@code diderot.yaml} does not pin yet.
     * A pinned signer is always enforced; this only decides about trusting a new one.
     */
    public enum UnpinnedSigner {
        /** Resolve as if signing did not exist: `add --no-pin-signer`, and `resolve` by default. */
        IGNORE,
        /** Show the signer and offer to pin it; no or nobody to ask leaves it unpinned (`update`). */
        OFFER,
        /** The signer has to be confirmed, or nothing is resolved (`add`). */
        REQUIRE
    }

    private final Path root;
    private final GitCli git;
    private final OrasClient oci;
    private final Signing signing;
    private final PrintWriter out;
    private Confirm confirm = Confirm.console();

    public Workspace(Path root, GitCli git, OrasClient oci, PrintWriter out) {
        this(root, git, oci, Signing.production(), out);
    }

    /** Tests substitute a {@link Signing} bound to sigstore's staging instance. */
    public Workspace(Path root, GitCli git, OrasClient oci, Signing signing, PrintWriter out) {
        this.root = root;
        this.git = git;
        this.oci = oci;
        this.signing = signing;
        this.out = out;
    }

    /** Who is asked before a signer is trusted: the terminal by default, {@code --yes}, or a test. */
    public Workspace confirmingWith(Confirm confirm) {
        this.confirm = confirm;
        return this;
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
        // Signers confirmed during this run. Written to diderot.yaml only once every skill has
        // resolved, so a refusal halfway through leaves both files as they were.
        Map<String, Signer> newPins = new LinkedHashMap<>();
        for (ManifestSkill skill : manifest.skills) {
            requireName(skill);
            SourceRef ref = SourceRef.parse(skill.source);
            Signer before = skill.signer;
            LockedSkill locked = switch (ref.kind()) {
                case GIT -> lockGitSkill(skill, ref);
                case OCI -> lockOciSkill(skill, ref, UnpinnedSigner.OFFER);
            };
            if (skill.signer != before) {
                newPins.put(skill.name, skill.signer);
            }
            lock.skills.add(locked);
            reportLocked(ref, locked);
        }
        if (!newPins.isEmpty()) {
            ManifestEditor editor = ManifestEditor.of(Files.readString(manifestPath()));
            newPins.forEach(editor::pinSigner);
            Files.writeString(manifestPath(), editor.text());
            newPins.keySet().forEach(name -> out.printf("pinned %-20s signer in %s%n",
                    name, manifestPath().getFileName()));
        }
        lock.skills.sort(Comparator.comparing(s -> s.name));
        Yaml.write(lockPath(), lock);
        out.println("wrote " + lockPath().getFileName());
        return lock;
    }

    /** Install exactly what the lockfile pins, verifying content digests after extraction. */
    public void install(List<String> targetOverrides) throws IOException {
        LockFile lock = readLock();
        Manifest manifest = readManifest();
        List<TargetLayout> targets = resolveTargets(targetOverrides);
        for (LockedSkill skill : lock.skills) {
            SourceRef ref = SourceRef.parse(skill.source);
            // A lockfile can arrive from a teammate, so install re-runs the check rather than
            // trusting that whoever wrote the lock ran it. The pull is cached, so asking for the
            // content here costs nothing the loop below was not going to pay anyway.
            if (ref.kind() == SourceRef.Kind.OCI) {
                enforcePinnedSigner(skill.name, pinnedSigner(manifest, skill.name), ref.url(), skill.resolved,
                        oci.cachedPull(ref.url(), skill.resolved));
            }
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

    private LockedSkill lockOciSkill(ManifestSkill skill, SourceRef ref, UnpinnedSigner unpinned)
            throws IOException {
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
        locked.signer = trustSigner(skill, unpinned, ref.url(), digest, content);
        return locked;
    }

    /**
     * Decides which signer, if any, this skill is held to — and asks before trusting one the
     * project has not trusted yet. The rule is the whole of issue #63: a signer already pinned is
     * enforced without a question, because it was confirmed when it was pinned; a signer that would
     * be pinned for the first time, or that replaces the pinned one, is shown and has to be accepted.
     *
     * <p>Whatever is offered has first been verified against its own identity, so a person is never
     * asked to trust a bundle that does not even cover these bytes. Accepting sets
     * {@code skill.signer}; the caller writes that to diderot.yaml.
     */
    private Signer trustSigner(ManifestSkill skill, UnpinnedSigner unpinned, String repository, String digest,
            Path content) throws IOException {
        if (skill.signer != null) {
            try {
                return enforcePinnedSigner(skill.name, skill.signer, repository, digest, content);
            } catch (SignerMismatch mismatch) {
                Signer found = selfConsistentSigner(mismatch.bundle, content);
                if (found == null) {
                    throw mismatch;
                }
                out.printf("  %s is now signed by somebody other than the signer diderot.yaml pins%n", skill.name);
                out.printf("  pinned      %s (%s)%n", skill.signer.identity, skill.signer.issuer);
                printSigner(found);
                Optional<Boolean> answer = confirm.ask("  Trust the new signer for " + skill.name + "?");
                if (answer.orElse(false)) {
                    skill.signer = found;
                    return found;
                }
                throw new IOException(mismatch.getMessage() + (answer.isEmpty()
                        ? "\n       No terminal to confirm the new signer on: run in a terminal, or pass"
                                + " --yes once you have read the identity above."
                        : ""), mismatch);
            }
        }
        if (unpinned == UnpinnedSigner.IGNORE) {
            return null;
        }
        String bundle = oci.fetchSignature(repository, digest).orElse(null);
        if (bundle == null) {
            // Most of the ecosystem is unsigned; a line about it on every resolution is noise.
            return null;
        }
        Signer found = selfConsistentSigner(bundle, content);
        if (found == null) {
            String problem = "Skill '" + skill.name + "': " + repository + "@" + digest
                    + " carries a signature that does not verify against its own content.";
            if (unpinned == UnpinnedSigner.REQUIRE) {
                throw new IOException(problem + " Nothing was written.");
            }
            out.println("  warning: " + problem + " Not offered for pinning.");
            return null;
        }
        printSigner(found);
        boolean required = unpinned == UnpinnedSigner.REQUIRE;
        Optional<Boolean> answer = confirm.ask(required
                ? "  Trust this signer for " + skill.name + "?"
                : "  Pin this signer for " + skill.name + " in diderot.yaml?");
        if (answer.orElse(false)) {
            skill.signer = found;
            return found;
        }
        if (required) {
            throw new IOException(answer.isEmpty()
                    ? "Skill '" + skill.name + "' is signed, and there is no terminal to confirm the"
                            + " signer on. Read the identity above, then pass --yes to trust it, or"
                            + " --no-pin-signer to declare the skill without a signer. Nothing was written."
                    : "Skill '" + skill.name + "': signer not trusted, so it was not added."
                            + " --no-pin-signer declares it without a signer.");
        }
        out.println(answer.isEmpty()
                ? "  not pinned  no terminal to ask on — `diderot update --yes` pins it"
                : "  not pinned");
        return null;
    }

    /** Who signed {@code bundle}, only when that signature really covers {@code content}; else null. */
    private Signer selfConsistentSigner(String bundle, Path content) {
        Signer found = Signing.identityOf(bundle);
        if (found == null || found.identity == null || found.issuer == null) {
            return null;
        }
        try {
            signing.verifySignature(ContentDigest.sha256Of(content), bundle, found.identity, found.issuer);
            return found;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void printSigner(Signer signer) {
        out.printf("  signed by   %s%n", signer.identity);
        out.printf("  issuer      %s%n", signer.issuer);
        out.flush();
    }

    /** A signature that verifies, by somebody other than the pinned signer. Carries the bundle. */
    private static final class SignerMismatch extends IOException {
        final transient String bundle;

        SignerMismatch(String message, String bundle, Throwable cause) {
            super(message, cause);
            this.bundle = bundle;
        }
    }

    /** The signer pinned for {@code name} in the manifest, or null when the skill pins none. */
    private static Signer pinnedSigner(Manifest manifest, String name) {
        return manifest.skills.stream()
                .filter(s -> name.equals(s.name))
                .map(s -> s.signer)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Applies the project's policy for one skill: when {@code diderot.yaml} pins a signer, the
     * signature has to exist and has to be that signer's, and what was verified is returned for the
     * lock. No pinned signer means no check and nothing recorded, so unsigned skills stay usable —
     * the only way this can be adopted gradually.
     *
     * <p>This is the policy half; the cryptographic half is {@link Signing#verifySignature}, which
     * this method calls once and which has no opinion about what should happen when it throws.
     *
     * <p>Everything that can go wrong here fails closed, because the alternative — carrying on with
     * a warning — is indistinguishable from the attack it is supposed to stop. A caller that throws
     * before the lockfile is written leaves the project on the version it already had.
     *
     * <p>What gets verified is {@code content}, recomputed here rather than taken from anywhere:
     * the signature covers the skill's bytes, so the check binds to what is about to be installed
     * and not to a document the registry served.
     */
    private Signer enforcePinnedSigner(String name, Signer expected, String repository, String digest, Path content)
            throws IOException {
        // Nothing pinned: resolve the skill exactly as it was resolved before signing existed.
        if (expected == null) {
            return null;
        }
        // Half a pin is a configuration mistake, not a request for a weaker check.
        if (expected.identity == null || expected.issuer == null) {
            throw new IOException("Skill '" + name + "': signer needs both an identity and an issuer");
        }

        // The bundle travels in the artifact's manifest. A skill carrying none cannot satisfy a pin,
        // so this is a refusal and not a fallback to "unsigned is fine".
        String bundle = oci.fetchSignature(repository, digest)
                .orElseThrow(() -> new IOException("Skill '" + name + "': " + repository + "@" + digest
                        + " carries no signature, but diderot.yaml pins a signer.\n"
                        + "       expected  " + expected.identity + "\n"
                        + "       Nothing was written."));
        try {
            // Hash the directory that was just pulled: the check binds to the bytes about to be
            // installed, never to a digest the registry or the lockfile supplied.
            signing.verifySignature(ContentDigest.sha256Of(content), bundle, expected.identity, expected.issuer);
        } catch (IOException e) {
            // Name both identities. A refusal has to tell a human what to do next, not only that
            // something was wrong — and this throws before Yaml.write, so nothing is recorded.
            throw new SignerMismatch("Skill '" + name + "': " + repository + "@" + digest
                    + " is signed, but not by the expected signer.\n"
                    + "       expected  " + expected.identity + "\n"
                    + "       found     " + Signing.signerOf(bundle) + "\n"
                    + "       Nothing was written.", bundle, e);
        }
        // Handed back to the caller for the lockfile: it records who was verified, not just what.
        return new Signer(expected.identity, expected.issuer);
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

    /**
     * Resolves one manifest entry and nothing else. `add` uses this rather than a full
     * {@link #update()}: re-resolving the whole manifest to declare one new skill would quietly move
     * every floating constraint already in the lock, which is not what the user asked for.
     */
    public LockedSkill resolve(ManifestSkill skill) throws IOException {
        return resolve(skill, UnpinnedSigner.IGNORE);
    }

    /**
     * As above, deciding what happens when the skill is signed but pins nobody yet. `add` passes
     * {@link UnpinnedSigner#REQUIRE}: the signer is confirmed here, and set on {@code skill}.
     */
    public LockedSkill resolve(ManifestSkill skill, UnpinnedSigner unpinned) throws IOException {
        requireName(skill);
        SourceRef ref = SourceRef.parse(skill.source);
        LockedSkill locked = switch (ref.kind()) {
            case GIT -> lockGitSkill(skill, ref);
            case OCI -> lockOciSkill(skill, ref, unpinned);
        };
        reportLocked(ref, locked);
        return locked;
    }

    /**
     * The one line a resolution prints. `update` and `add` both end here, so a signature that was
     * checked says so whichever command checked it - the first version of `add` verified the
     * signer and then printed a line that never mentioned it, which reads exactly like a project
     * where nothing was verified at all.
     */
    private void reportLocked(SourceRef ref, LockedSkill locked) {
        // With ranges in play, the repository alone no longer says what was chosen.
        String reference = locked.tag == null ? ref.url() : ref.url() + ":" + locked.tag;
        out.printf("locked %-20s %s@%s (%s%s)%n",
                locked.name, reference, shortSha(locked.resolved), locked.digest,
                locked.signer == null ? "" : ", signer ok");
    }

    /** Adds or replaces one entry in the lockfile, leaving every other pin exactly as it was. */
    public void putLockEntry(LockedSkill locked) throws IOException {
        LockFile lock = lockOrEmpty();
        lock.skills.removeIf(s -> locked.name.equals(s.name));
        lock.skills.add(locked);
        lock.skills.sort(Comparator.comparing(s -> s.name));
        Yaml.write(lockPath(), lock);
    }

    /** Drops one entry from the lockfile; false when it was not pinned in the first place. */
    public boolean dropLockEntry(String name) throws IOException {
        LockFile lock = lockOrEmpty();
        if (!lock.skills.removeIf(s -> name.equals(s.name))) {
            return false;
        }
        Yaml.write(lockPath(), lock);
        return true;
    }

    /** Deletes the installed copies of a skill and returns the directories that were removed. */
    public List<Path> uninstall(String name, List<String> targetOverrides) throws IOException {
        List<Path> removed = new ArrayList<>();
        for (TargetLayout target : resolveTargets(targetOverrides)) {
            Path dest = target.skillsDir(root).resolve(name);
            if (Files.isDirectory(dest)) {
                deleteRecursively(dest);
                removed.add(dest);
            }
        }
        return removed;
    }

    /** Manifest entries with no pin in the lockfile — the ones `install` would silently skip. */
    public List<String> unlockedSkills() throws IOException {
        List<String> pinned = lockOrEmpty().skills.stream().map(s -> s.name).toList();
        List<String> missing = new ArrayList<>();
        for (ManifestSkill skill : readManifest().skills) {
            if (skill.name != null && !pinned.contains(skill.name)) {
                missing.add(skill.name);
            }
        }
        return missing;
    }

    private LockFile lockOrEmpty() throws IOException {
        return Files.isRegularFile(lockPath()) ? readLock() : new LockFile();
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
