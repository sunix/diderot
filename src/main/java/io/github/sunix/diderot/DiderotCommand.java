package io.github.sunix.diderot;

import io.github.sunix.diderot.commands.InstallCommand;
import io.github.sunix.diderot.commands.PushCommand;
import io.github.sunix.diderot.commands.StatusCommand;
import io.github.sunix.diderot.commands.UpdateCommand;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@TopCommand
@Command(name = "diderot", mixinStandardHelpOptions = true, version = "diderot 0.1.0-SNAPSHOT",
        description = "Package manager for AI agent skills — Helm-style manifests and lockfiles over git and OCI registry sources.",
        subcommands = { UpdateCommand.class, InstallCommand.class, StatusCommand.class, PushCommand.class })
public class DiderotCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        // No subcommand given: print usage rather than failing silently.
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
