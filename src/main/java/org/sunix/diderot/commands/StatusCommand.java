package org.sunix.diderot.commands;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

@Command(name = "status", mixinStandardHelpOptions = true,
        description = "Compare the skills installed in the agent directories against diderot.lock and report drift.")
public class StatusCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("diderot status: not implemented yet (milestone M1).");
        return 1;
    }
}
