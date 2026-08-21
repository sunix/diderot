package org.sunix.diderot.commands;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.sunix.diderot.core.Workspace;
import org.sunix.diderot.git.GitCli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "update", mixinStandardHelpOptions = true,
        description = "Resolve the version constraints in diderot.yaml, fetch the skills, and (re)write diderot.lock. "
                + "The Helm equivalent is `helm dependency update`.")
public class UpdateCommand implements Callable<Integer> {

    @Option(names = { "-C", "--directory" }, defaultValue = ".",
            description = "Project root containing diderot.yaml (default: current directory).")
    Path directory;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        try {
            new Workspace(directory.toAbsolutePath().normalize(), new GitCli(GitCli.defaultCacheRoot()), out)
                    .update();
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}
