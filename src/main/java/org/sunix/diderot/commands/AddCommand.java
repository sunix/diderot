package org.sunix.diderot.commands;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.sunix.diderot.core.Confirm;
import org.sunix.diderot.core.LockFile.LockedSkill;
import org.sunix.diderot.core.Manifest;
import org.sunix.diderot.core.Manifest.ManifestSkill;
import org.sunix.diderot.core.ManifestEditor;
import org.sunix.diderot.core.SourceRef;
import org.sunix.diderot.core.Workspace;
import org.sunix.diderot.core.Workspace.UnpinnedSigner;
import org.sunix.diderot.git.GitCli;
import org.sunix.diderot.oci.OrasClient;

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

    /**
     * Says out loud what already happens, so a script can state its intent. Not declared as
     * picocli's {@code negatable = true}: with a default of true, picocli swaps the two spellings,
     * and {@code --pin-signer} would have turned pinning <em>off</em>. A flag that does the
     * opposite of what it reads is bad anywhere and indefensible on a signature check.
     */
    @Option(names = "--pin-signer",
            description = "Record the signer of a signed skill in diderot.yaml. This is already "
                    + "what happens once you confirm it; the flag is here for scripts that would "
                    + "rather say so.")
    boolean pinSigner;

    @Option(names = { "-y", "--yes" },
            description = "Trust the signer that is printed without being asked. Without a terminal "
                    + "(agents, CI), adding a signed skill needs this or --no-pin-signer.")
    boolean yesOption;

    @Option(names = "--no-pin-signer",
            description = "Declare the skill without recording its signer, so later resolutions "
                    + "accept whoever signed it. By default you are shown who signed a signed "
                    + "skill and asked to trust them; once trusted, every later resolution has to "
                    + "find that same signer.")
    boolean noPinSigner;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        Path root = directory.toAbsolutePath().normalize();
        Path manifestPath = root.resolve("diderot.yaml");
        String original = null;
        try {
            if (pinSigner && noPinSigner) {
                throw new IllegalArgumentException(
                        "--pin-signer and --no-pin-signer contradict each other.");
            }
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

            Workspace workspace = new Workspace(root, new GitCli(GitCli.defaultCacheRoot()),
                    new OrasClient(OrasClient.defaultCacheRoot()), out)
                    .confirmingWith(yesOption ? Confirm.yes() : Confirm.console());

            // Resolve before writing anything: whether this skill carries a signature is part of
            // what the entry should say, and it can only be known once the artifact is in hand.
            // A signed skill's signer is shown and has to be confirmed here (#63); once accepted it
            // is set on `skill`, already verified against the bytes that were just pulled.
            LockedSkill locked = workspace.resolve(skill,
                    noPinSigner ? UnpinnedSigner.IGNORE : UnpinnedSigner.REQUIRE);

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
            out.printf("added %-20s %s (%s%s)%n", skillName, source, skill.version,
                    skill.signer == null ? "" : ", signer pinned");
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
