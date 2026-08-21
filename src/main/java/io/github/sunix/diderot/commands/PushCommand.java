package io.github.sunix.diderot.commands;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "push", mixinStandardHelpOptions = true,
        description = "Package a skill directory as an OCI artifact and push it to a registry "
                + "(e.g. oci://ghcr.io/owner/skills/my-skill:1.0.0). The Helm equivalent is `helm push`.")
public class PushCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Skill directory (must contain SKILL.md).")
    Path skillDir;

    @Parameters(index = "1", description = "Destination OCI reference (oci://registry/repository:tag).")
    String reference;

    @Override
    public Integer call() {
        System.err.println("diderot push: not implemented yet (milestone M2 — OCI via oras-java + cosign).");
        return 1;
    }
}
