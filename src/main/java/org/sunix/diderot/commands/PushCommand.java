package org.sunix.diderot.commands;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

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
            String digest = new OrasClient(OrasClient.defaultCacheRoot())
                    .push(skillDir.toAbsolutePath().normalize(), ref);
            out.printf("pushed %s -> %s@%s%n", skillDir, ref, digest);
            if (sign) {
                // Signing only, for now: the bundle has nowhere to go until the transport lands,
                // because ghcr.io does not implement the Referrers API the parked branch used.
                String bundle = Signing.production().signDigest(digest);
                out.printf("signed %s (%d byte sigstore bundle, not yet stored)%n",
                        digest, bundle.length());
            }
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}
