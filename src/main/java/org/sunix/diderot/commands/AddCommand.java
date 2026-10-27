package org.sunix.diderot.commands;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.sunix.diderot.core.LockFile.LockedSkill;
import org.sunix.diderot.core.Manifest;
import org.sunix.diderot.core.Manifest.ManifestSkill;
import org.sunix.diderot.core.ManifestEditor;
import org.sunix.diderot.core.Signer;
import org.sunix.diderot.core.SourceRef;
import org.sunix.diderot.core.Workspace;
import org.sunix.diderot.git.GitCli;
import org.sunix.diderot.oci.OrasClient;
import org.sunix.diderot.oci.Signing;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "add", mixinStandardHelpOptions = true,
        description = "Declare a skill in diderot.yaml and pin it in diderot.lock, without touching "
                + "the constraints already there. Run `diderot install` to put it on disk.")
public class AddCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<source>",
            description = "oci://<registry>/<repository> or git+<url>[#path-inside-repo].")
    String source;

    @Option(names = "--name",
            description = "Skill name. Defaults to the last segment of the source.")
    String name;

    @Option(names = "--version",
            description = "Tag, semver range (oci), or branch/tag/commit (git). Defaults to the "
                    + "source's own default: `latest` for a registry, `HEAD` for git.")
    String version;

    @Option(names = { "-C", "--directory" }, defaultValue = ".",
            description = "Project root containing diderot.yaml (default: current directory).")
    Path directory;

    @Option(names = "--pin-signer", negatable = true,
            description = "Pin the signer of a signed skill without asking (or refuse to, with "
                    + "--no-pin-signer). Without either, you are asked when there is a terminal to "
                    + "ask on, and nothing is pinned when there is not.")
    Boolean pinSigner;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        Path root = directory.toAbsolutePath().normalize();
        Path manifestPath = root.resolve("diderot.yaml");
        String original = null;
        try {
            SourceRef ref = SourceRef.parse(source);
            String skillName = name != null && !name.isBlank() ? name : inferName(ref);

            original = Files.isRegularFile(manifestPath) ? Files.readString(manifestPath) : "";
            ManifestEditor editor = ManifestEditor.of(original);
            String existing = editor.sourceOf(skillName).orElse(null);
            if (existing != null) {
                throw new IllegalStateException("Skill '" + skillName + "' is already declared, from "
                        + existing + ". Change its version in diderot.yaml, or remove it first.");
            }

            ManifestSkill skill = new ManifestSkill();
            skill.name = skillName;
            skill.source = source;
            // With no --version, write the default this kind of source actually uses. `HEAD` in an
            // oci:// entry resolves correctly - the resolver maps it to `latest` - and reads as git
            // vocabulary in a registry reference, so the manifest would mean one thing and say
            // another.
            skill.version = version != null && !version.isBlank()
                    ? version
                    : ref.kind() == SourceRef.Kind.OCI ? "latest" : "HEAD";

            OrasClient oras = new OrasClient(OrasClient.defaultCacheRoot());
            Workspace workspace = new Workspace(root, new GitCli(GitCli.defaultCacheRoot()), oras, out);

            // Resolve before writing anything: whether this skill carries a signature is part of
            // what the entry should say, and it can only be known once the artifact is in hand.
            LockedSkill locked = workspace.resolve(skill);
            skill.signer = offerToPin(ref, locked, oras, skillName, out);

            // Then write the manifest, and put the file back exactly as it was if anything after
            // this fails rather than leaving a declaration nothing can resolve.
            editor.add(skillName, source, skill.version, skill.signer);
            String text = editor.text();
            if (original.isEmpty()) {
                // A manifest this command authored from nothing should say where skills go, rather
                // than working by a default that is only visible in the code. Taken from the model
                // so the written line and the assumed one cannot drift apart.
                text += "\ntargets: [" + String.join(", ", new Manifest().targets) + "]\n";
            }
            Files.writeString(manifestPath, text);
            out.printf("added %-20s %s (%s)%n", skillName, source, skill.version);

            // With a signer pinned, resolve once more: the second pass is the one that verifies,
            // so the signature is checked here rather than at the next command somebody runs.
            if (skill.signer != null) {
                locked = workspace.resolve(skill);
            }
            workspace.putLockEntry(locked);

            List<String> unlocked = workspace.unlockedSkills();
            if (!unlocked.isEmpty()) {
                out.println("note: not pinned in diderot.lock yet: " + String.join(", ", unlocked)
                        + " — run `diderot update`.");
            }
            out.println("run `diderot install` to put it on disk.");
            return 0;
        } catch (Exception e) {
            restore(manifestPath, original);
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }


    /**
     * Shows who signed the skill that was just resolved, and decides whether to pin them.
     *
     * <p>This is the moment part six of the making-of was arguing for: an identity can be accepted
     * here because it is in front of the person, rather
     * than copied from a web page — and a string copied from a web page is a string an attacker can
     * supply. An unsigned skill says nothing at all: most of the ecosystem is unsigned, and a
     * warning on every add is a warning nobody reads.
     */
    private Signer offerToPin(SourceRef ref, LockedSkill locked, OrasClient oras, String skillName,
            PrintWriter out) {
        if (ref.kind() != SourceRef.Kind.OCI || Boolean.FALSE.equals(pinSigner)) {
            return null;
        }
        Signer signer = oras.fetchSignature(ref.url(), locked.resolved)
                .map(Signing::identityOf)
                .orElse(null);
        if (signer == null) {
            return null;
        }
        out.printf("  signed by   %s%n", signer.identity);
        out.printf("  issuer      %s%n", signer.issuer);
        if (Boolean.TRUE.equals(pinSigner)) {
            return signer;
        }
        if (System.console() == null) {
            // No terminal: an agent or a CI job. Say what was found and what was not done with it,
            // because silence here would leave an unverified project looking like a verified one.
            out.printf("  not pinned (no terminal to ask on) — add --pin-signer to trust this "
                    + "signer for %s%n", skillName);
            return null;
        }
        String answer = System.console().readLine("  Trust this signer for %s? [y/N] ", skillName);
        return answer != null && answer.trim().toLowerCase().startsWith("y") ? signer : null;
    }

    /** `oci://ghcr.io/sunix/skills/making-of` and `git+https://…#skills/doc/making-of` both give `making-of`. */
    private static String inferName(SourceRef ref) {
        String path = ref.path().isEmpty() ? ref.url() : ref.path();
        String last = path.substring(path.lastIndexOf('/') + 1);
        int colon = last.indexOf(':');
        if (colon > 0) {
            last = last.substring(0, colon);
        }
        if (last.isBlank()) {
            throw new IllegalArgumentException("Cannot work out a skill name from " + ref.url()
                    + " — pass --name.");
        }
        return last;
    }

    private void restore(Path manifestPath, String original) {
        if (original == null) {
            return;
        }
        try {
            if (original.isEmpty()) {
                Files.deleteIfExists(manifestPath);
            } else {
                Files.writeString(manifestPath, original);
            }
        } catch (Exception ignored) {
            spec.commandLine().getErr().println(
                    "warning: could not restore " + manifestPath + " — check it by hand.");
        }
    }
}
