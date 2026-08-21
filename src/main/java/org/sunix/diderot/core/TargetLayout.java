package org.sunix.diderot.core;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Where skills get installed for a given agent tool. v1 ships the standard Agent Skills layout
 * used by Claude Code; other tools mostly follow the same convention or symlink to it, and
 * dedicated layouts can be added here without touching the resolver.
 */
public enum TargetLayout {

    CLAUDE(".claude/skills"),
    /** The tool-neutral directory used by several agents (e.g. `npx skills` installs there too). */
    AGENTS(".agents/skills");

    private final String dir;

    TargetLayout(String dir) {
        this.dir = dir;
    }

    public Path skillsDir(Path projectRoot) {
        return projectRoot.resolve(dir);
    }

    public static TargetLayout of(String name) {
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown target '" + name + "'. Known targets: claude, agents.");
        }
    }
}
