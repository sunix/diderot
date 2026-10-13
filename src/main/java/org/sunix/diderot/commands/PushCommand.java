package org.sunix.diderot.commands;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.sunix.diderot.core.ContentDigest;
import org.sunix.diderot.oci.OrasClient;
import org.sunix.diderot.oci.Signing;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "push", mixinStandardHelpOptions = true,
        description = "Package a skill directory as an OCI artifact and push it to a registry "
                + "(e.g. oci://ghcr.io/owner/skills/my-skill:1.0.0). The Helm equivalent is `helm push`.")
public class PushCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Skill directory (must contain SKILL.md).")
    Path skillDir;

    @Parameters(index = "1", description = "Destination OCI reference (oci://registry/repository:tag).")
    String reference;

    @Option(names = "--sign", description = "Sign the pushed manifest digest with sigstore keyless signing.")
    boolean sign;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        try {
            if (!Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
                throw new IllegalArgumentException("Not a skill directory (no SKILL.md): " + skillDir);
            }
            String ref = reference.startsWith("oci://") ? reference.substring("oci://".length()) : reference;
            Path dir = skillDir.toAbsolutePath().normalize();
            OrasClient oras = new OrasClient(OrasClient.defaultCacheRoot());
            // Signing comes first when asked for, because the bundle travels inside the artifact:
            // it is the content that gets signed, so there is nothing to wait for the push to learn.
            String bundle = null;
            if (sign) {
                String contentDigest = ContentDigest.sha256Of(dir);
                bundle = Signing.production().signDigest(contentDigest);
                out.printf("signed %s (%d byte sigstore bundle)%n", contentDigest, bundle.length());
            }
            String digest = oras.push(dir, ref, bundle);
            out.printf("pushed %s -> %s@%s%n", skillDir, ref, digest);
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}
