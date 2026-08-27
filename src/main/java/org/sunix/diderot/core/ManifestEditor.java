package org.sunix.diderot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adds and removes skill entries in {@code diderot.yaml} by splicing lines, never by
 * serialising a parsed model back out.
 *
 * <p>The distinction matters because the manifest is <em>authored</em>: it holds comments, a chosen
 * key order, an indentation style, and flow sequences like {@code targets: [claude]}. Round-tripping
 * it through the same Jackson mapper that writes {@code diderot.lock} — which is generated and
 * therefore safe to rewrite wholesale — deletes every one of those. A tool that eats your comments
 * the first time you run it does not get run a second time.
 *
 * <p>So this class reads the file as text, changes only the lines belonging to one list item, and
 * leaves every other byte exactly as it found it. It understands one shape, the block sequence under
 * a {@code skills:} key, and refuses anything else rather than guessing.
 */
public final class ManifestEditor {

    /** YAML characters that cannot open a plain scalar, so a version starting with one needs quotes. */
    private static final String INDICATORS = "-?:,[]{}#&*!|>'\"%@`";

    private final List<String> lines;
    private final boolean endsWithNewline;

    private ManifestEditor(List<String> lines, boolean endsWithNewline) {
        this.lines = lines;
        this.endsWithNewline = endsWithNewline;
    }

    public static ManifestEditor of(String text) {
        boolean trailing = text.isEmpty() || text.endsWith("\n");
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        // split with -1 leaves a trailing empty element for a newline-terminated file; drop it so
        // the line list is exactly the file's lines.
        if (trailing && !lines.isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return new ManifestEditor(lines, trailing);
    }

    public String text() {
        String joined = String.join("\n", lines);
        return endsWithNewline && !joined.isEmpty() ? joined + "\n" : joined;
    }

    /** The source declared for {@code name}, or empty when the manifest doesn't mention it. */
    public Optional<String> sourceOf(String name) {
        for (int[] item : items()) {
            if (name.equals(field(item, "name"))) {
                return Optional.ofNullable(field(item, "source"));
            }
        }
        return Optional.empty();
    }

    public boolean declares(String name) {
        for (int[] item : items()) {
            if (name.equals(field(item, "name"))) {
                return true;
            }
        }
        return false;
    }

    /** Appends one skill after the last existing entry, creating the {@code skills:} key if absent. */
    public void add(String name, String source, String version) {
        int header = skillsHeader();
        if (header < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("skills:");
            header = lines.size() - 1;
        }
        if (lines.get(header).substring("skills:".length()).trim().equals("[]")) {
            lines.set(header, "skills:");
        }
        String indent = listIndent(header);
        List<String> entry = List.of(
                indent + "- name: " + name,
                indent + "  source: " + source,
                indent + "  version: " + scalar(version, quotesVersions()));
        lines.addAll(insertionPoint(header), entry);
    }

    /** Removes the entry named {@code name}; returns false when there was nothing to remove. */
    public boolean remove(String name) {
        for (int[] item : items()) {
            if (!name.equals(field(item, "name"))) {
                continue;
            }
            int end = item[1];
            // Leave the blank line that separated this entry from the next one alone.
            while (end - 1 > item[0] && lines.get(end - 1).isBlank()) {
                end--;
            }
            lines.subList(item[0], end).clear();
            return true;
        }
        return false;
    }

    // --- the line arithmetic -------------------------------------------------------------------

    /** Index of the {@code skills:} line, or -1. A flow sequence is refused rather than guessed at. */
    private int skillsHeader() {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("skills:")) {
                continue;
            }
            String rest = line.substring("skills:".length()).trim();
            // `skills: []` holds nothing an author could lose, so it is workable: reads see no
            // entries and `add` converts the line to a block. A *populated* inline list is refused,
            // because pretending it is empty would have `remove` report nothing to remove for a
            // skill that is plainly declared.
            if (!rest.isEmpty() && !rest.startsWith("#") && !rest.equals("[]")) {
                throw new IllegalStateException("diderot.yaml declares skills inline (`" + line.trim()
                        + "`). Rewrite it as a block list, one `- name:` per line, and try again.");
            }
            return i;
        }
        return -1;
    }

    /** The line after the last entry of the skills block — where a new entry goes. */
    private int insertionPoint(int header) {
        int end = blockEnd(header);
        while (end - 1 > header && lines.get(end - 1).isBlank()) {
            end--;
        }
        return end;
    }

    /** First line after the skills block: the next top-level key, or end of file. */
    private int blockEnd(int header) {
        for (int i = header + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                return i;
            }
        }
        return lines.size();
    }

    /** Half-open [start, end) line ranges, one per entry in the skills block. */
    private List<int[]> items() {
        int header = skillsHeader();
        if (header < 0) {
            return List.of();
        }
        int end = blockEnd(header);
        List<Integer> starts = new ArrayList<>();
        for (int i = header + 1; i < end; i++) {
            if (lines.get(i).stripLeading().startsWith("- ")) {
                starts.add(i);
            }
        }
        List<int[]> items = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            items.add(new int[] { starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : end });
        }
        return items;
    }

    /** Reads a scalar field of one entry, tolerating a trailing comment and surrounding quotes. */
    private String field(int[] item, String key) {
        String raw = rawField(item, key);
        return raw == null ? null : unquote(raw);
    }

    /** The field exactly as written, quotes included — what the quoting style is read from. */
    private String rawField(int[] item, String key) {
        for (int i = item[0]; i < item[1]; i++) {
            String line = lines.get(i).stripLeading();
            if (line.startsWith("- ")) {
                line = line.substring(2);
            }
            if (!line.startsWith(key + ":")) {
                continue;
            }
            String value = line.substring(key.length() + 1).trim();
            int comment = value.indexOf(" #");
            if (comment >= 0) {
                value = value.substring(0, comment).trim();
            }
            return value;
        }
        return null;
    }

    /** Matches the existing entries' indentation, so an edited file keeps one style throughout. */
    private String listIndent(int header) {
        int end = blockEnd(header);
        for (int i = header + 1; i < end; i++) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("- ")) {
                return line.substring(0, line.indexOf('-'));
            }
        }
        return "  ";
    }

    /**
     * Whether the file already quotes its versions. Following that is the same courtesy as matching
     * its indentation: a manifest where a human wrote {@code version: "^1.0.0"} should not end up
     * with a bare {@code version: ^1.2.0} on the next line.
     */
    private boolean quotesVersions() {
        for (int[] item : items()) {
            String raw = rawField(item, "version");
            if (raw == null || !(raw.startsWith("\"") || raw.startsWith("'"))) {
                continue;
            }
            // Quotes YAML forced (`"*"`, `">=1 <2"`) say nothing about what the author prefers;
            // only quotes around a value that would have been fine bare are a style choice.
            if (scalar(unquote(raw), false).equals(unquote(raw))) {
                return true;
            }
        }
        return false;
    }

    private static String scalar(String value, boolean preferQuotes) {
        boolean unsafe = value.isEmpty()
                || INDICATORS.indexOf(value.charAt(0)) >= 0
                || value.contains(" #")
                || value.contains(": ");
        return unsafe || preferQuotes ? "\"" + value.replace("\"", "\\\"") + "\"" : value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
