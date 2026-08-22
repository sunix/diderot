package org.sunix.diderot.commands;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.sunix.diderot.core.Workspace;
import org.sunix.diderot.git.GitCli;
import org.sunix.diderot.oci.OrasClient;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "status", mixinStandardHelpOptions = true,
        description = "Compare the skills installed in the agent directories against diderot.lock and report drift.")
public class StatusCommand implements Callable<Integer> {

    @Option(names = { "-C", "--directory" }, defaultValue = ".",
            description = "Project root containing diderot.lock (default: current directory).")
    Path directory;

    @Option(names = "--target",
            description = "Agent layout(s) to check (claude, agents). Repeatable. Defaults to the manifest's targets.")
    List<String> targets;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        try {
            int problems = new Workspace(directory.toAbsolutePath().normalize(),
                    new GitCli(GitCli.defaultCacheRoot()),
                    new OrasClient(OrasClient.defaultCacheRoot()), out).status(targets);
            return problems == 0 ? 0 : 1;
        } catch (Exception e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}
