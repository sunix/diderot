package org.sunix.diderot.commands;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "update", mixinStandardHelpOptions = true,
        description = "Resolve the version constraints in diderot.yaml, fetch the skills, and (re)write diderot.lock. "
                + "The Helm equivalent is `helm dependency update`.")
public class UpdateCommand implements Callable<Integer> {

    @Option(names = { "-f", "--manifest" }, defaultValue = "diderot.yaml",
            description = "Path to the manifest (default: ${DEFAULT-VALUE}).")
    Path manifest;

    @Override
    public Integer call() {
        System.err.println("diderot update: not implemented yet (milestone M1 — git sources first).");
        return 1;
    }
}
