package org.sunix.diderot.commands;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.sunix.diderot.core.Confirm;
import org.sunix.diderot.core.Workspace;
import org.sunix.diderot.git.GitCli;
import org.sunix.diderot.oci.OrasClient;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "update", mixinStandardHelpOptions = true,
        description = "Resolve the version constraints in diderot.yaml, fetch the skills, and (re)write diderot.lock. "
                + "A signer not trusted yet is shown and has to be confirmed. "
                + "The Helm equivalent is `helm dependency update`.")
public class UpdateCommand implements Callable<Integer> {

    @Option(names = { "-C", "--directory" }, defaultValue = ".",
            description = "Project root containing diderot.yaml (default: current directory).")
    Path directory;

    @Option(names = { "-y", "--yes" },
            description = "Trust the signers that are printed without being asked: a signed skill "
                    + "that pins nobody yet gets pinned, and a pinned skill now signed by somebody "
                    + "else gets re-pinned. Only for a script that has read those identities.")
    boolean yes;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        try {
            new Workspace(directory.toAbsolutePath().normalize(), new GitCli(GitCli.defaultCacheRoot()),
                    new OrasClient(OrasClient.defaultCacheRoot()), out)
                    .confirmingWith(yes ? Confirm.yes() : Confirm.console())
                    .update();
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}
