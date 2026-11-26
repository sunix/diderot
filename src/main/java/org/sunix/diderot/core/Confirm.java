package org.sunix.diderot.core;

import java.io.Console;
import java.lang.reflect.Method;
import java.util.Optional;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Asks a person a yes/no question, or says that nobody can be asked.
 *
 * <p>The three answers are kept apart on purpose. "No" is a decision; "nobody to ask" is the absence
 * of one, and a caller trusting a signer must not read it as either answer: where a confirmation is
 * required, no terminal means a refusal that says how to proceed ({@code --yes}), never a silent
 * skip that leaves an unverified project looking like a verified one.
 */
// Console is registered so the native binary can find isTerminal() reflectively, as the JVM does.
@RegisterForReflection(targets = Console.class, methods = true, fields = false)
@FunctionalInterface
public interface Confirm {

    /** {@code true} for yes, {@code false} for no, empty when there is no one to ask. */
    Optional<Boolean> ask(String question);

    /** For {@code --yes}: the person already read what was printed and accepts it. */
    static Confirm alwaysYes() {
        return question -> Optional.of(true);
    }

    /** Agents and CI: nobody is there to answer. */
    static Confirm nobody() {
        return question -> Optional.empty();
    }

    /** The real terminal, when there is one; otherwise behaves like {@link #nobody()}. */
    static Confirm console() {
        return question -> {
            Console console = terminal();
            if (console == null) {
                return Optional.empty();
            }
            String answer = console.readLine("%s [y/N] ", question);
            return Optional.of(answer != null && answer.trim().toLowerCase().startsWith("y"));
        };
    }

    /**
     * The console, only when it really is an interactive terminal. On JDK 22 to 24,
     * {@code System.console()} returns a console even with stdin piped (seen on 23; 21 and 25 return
     * null), so a null check alone would ask a question of a pipe and read its empty answer as "no".
     * {@code Console.isTerminal()} says which it is, but only exists from 22; diderot compiles for
     * 21, so it is looked up.
     */
    private static Console terminal() {
        Console console = System.console();
        if (console == null) {
            return null;
        }
        try {
            Method isTerminal = Console.class.getMethod("isTerminal");
            return Boolean.TRUE.equals(isTerminal.invoke(console)) ? console : null;
        } catch (ReflectiveOperationException e) {
            return console; // JDK 21: a non-null console is a terminal.
        }
    }
}
