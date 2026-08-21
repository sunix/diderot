package io.github.sunix.diderot.commands;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "install", mixinStandardHelpOptions = true,
        description = "Install skills exactly as pinned (by content digest) in diderot.lock. Reproducible: no resolution "
                + "happens here. The Helm equivalent is `helm dependency build`.")
public class InstallCommand implements Callable<Integer> {

    @Option(names = { "-f", "--manifest" }, defaultValue = "diderot.yaml",
            description = "Path to the manifest (default: ${DEFAULT-VALUE}).")
    Path manifest;

    @Option(names = "--target",
            description = "Agent layout(s) to install into (e.g. claude). Repeatable. Defaults to the manifest's "
                    + "targets. v1 supports the standard Agent Skills layout; more layouts are planned.")
    List<String> targets;

    @Override
    public Integer call() {
        System.err.println("diderot install: not implemented yet (milestone M1 — git sources first).");
        return 1;
    }
}
