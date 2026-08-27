package org.sunix.diderot.commands;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.sunix.diderot.core.ManifestEditor;
import org.sunix.diderot.core.Workspace;
import org.sunix.diderot.git.GitCli;
import org.sunix.diderot.oci.OrasClient;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "remove", aliases = "rm", mixinStandardHelpOptions = true,
        description = "Undeclare a skill: drop it from diderot.yaml, unpin it from diderot.lock, and "
                + "delete the copies installed in the agent directories.")
public class RemoveCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<name>", description = "Skill name as declared in diderot.yaml.")
    String name;

    @Option(names = "--keep-installed",
            description = "Leave the installed directories on disk; only undeclare and unpin.")
    boolean keepInstalled;

    @Option(names = { "-C", "--directory" }, defaultValue = ".",
            description = "Project root containing diderot.yaml (default: current directory).")
    Path directory;

    @Option(names = "--target",
            description = "Agent layout(s) to delete from (claude, agents). Defaults to the manifest's targets.")
    List<String> targets;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        Path root = directory.toAbsolutePath().normalize();
        Path manifestPath = root.resolve("diderot.yaml");
        try {
            // The installed directories are read from the manifest's targets, so work them out
            // before the declaration is gone.
            Workspace workspace = new Workspace(root, new GitCli(GitCli.defaultCacheRoot()),
                    new OrasClient(OrasClient.defaultCacheRoot()), out);
            List<Path> installed = keepInstalled ? List.of() : workspace.uninstall(name, targets);

            boolean declared = false;
            if (Files.isRegularFile(manifestPath)) {
                ManifestEditor editor = ManifestEditor.of(Files.readString(manifestPath));
                declared = editor.remove(name);
                if (declared) {
                    Files.writeString(manifestPath, editor.text());
                }
            }
            boolean pinned = workspace.dropLockEntry(name);

            if (!declared && !pinned && installed.isEmpty()) {
                throw new IllegalStateException("Nothing to remove: '" + name
                        + "' is not in diderot.yaml, not in diderot.lock, and not installed.");
            }
            List<String> touched = new ArrayList<>();
            if (declared) {
                touched.add("diderot.yaml");
            }
            if (pinned) {
                touched.add("diderot.lock");
            }
            if (!installed.isEmpty()) {
                touched.add(installed.size() + (installed.size() == 1
                        ? " installed directory" : " installed directories"));
            }
            out.printf("removed %-20s %s%n", name, String.join(", ", touched));
            for (Path dir : installed) {
                out.println("  deleted " + root.relativize(dir));
            }
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}
