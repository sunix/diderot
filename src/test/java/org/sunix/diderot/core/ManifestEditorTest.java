package org.sunix.diderot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestEditorTest {

    @TempDir
    Path tmp;

    /** A hand-written manifest: comments, an aligned trailing comment, a flow sequence, blank lines. */
    private static final String AUTHORED = """
            # Skills this project depends on.
            # Keep making-of first: the others reference its style rules.
            skills:
              - name: making-of          # the journal skill
                source: oci://ghcr.io/sunix/skills/making-of
                version: "^1.0.0"

            targets: [claude]
            """;

    @Test
    void addingASkillKeepsEveryOtherByte() {
        ManifestEditor editor = ManifestEditor.of(AUTHORED);
        editor.add("release-please", "oci://ghcr.io/sunix/skills/release-please", "^1.2.0");

        assertEquals("""
                # Skills this project depends on.
                # Keep making-of first: the others reference its style rules.
                skills:
                  - name: making-of          # the journal skill
                    source: oci://ghcr.io/sunix/skills/making-of
                    version: "^1.0.0"
                  - name: release-please
                    source: oci://ghcr.io/sunix/skills/release-please
                    version: "^1.2.0"

                targets: [claude]
                """, editor.text(),
                "both comments, the alignment, the blank line and `targets: [claude]` survive verbatim");
    }

    @Test
    void removingASkillTakesOnlyItsOwnLines() {
        ManifestEditor editor = ManifestEditor.of(AUTHORED);
        editor.add("release-please", "oci://ghcr.io/sunix/skills/release-please", "^1.2.0");
        assertTrue(editor.remove("making-of"));

        assertEquals("""
                # Skills this project depends on.
                # Keep making-of first: the others reference its style rules.
                skills:
                  - name: release-please
                    source: oci://ghcr.io/sunix/skills/release-please
                    version: "^1.2.0"

                targets: [claude]
                """, editor.text());
    }

    /** The edited file still has to be YAML, and still has to mean what it looks like it means. */
    @Test
    void theResultParsesToTheExpectedModel() throws Exception {
        ManifestEditor editor = ManifestEditor.of(AUTHORED);
        editor.add("release-please", "oci://ghcr.io/sunix/skills/release-please", ">=1.0.0 <2");
        Path file = tmp.resolve("diderot.yaml");
        Files.writeString(file, editor.text());

        Manifest manifest = Yaml.read(file, Manifest.class);
        assertEquals(2, manifest.skills.size());
        assertEquals("making-of", manifest.skills.get(0).name);
        assertEquals("release-please", manifest.skills.get(1).name);
        assertEquals("oci://ghcr.io/sunix/skills/release-please", manifest.skills.get(1).source);
        assertEquals(">=1.0.0 <2", manifest.skills.get(1).version,
                "a range opening on `>` would start a folded block scalar unquoted, so it gets quoted");
        assertEquals(java.util.List.of("claude"), manifest.targets);
    }

    @Test
    void aVersionIsQuotedOnlyWhenYamlNeedsIt() {
        ManifestEditor editor = ManifestEditor.of("skills:\n");
        editor.add("a", "oci://x/a", "^1.0.0");
        editor.add("b", "oci://x/b", "*");
        editor.add("c", "oci://x/c", "main");
        assertTrue(editor.text().contains("version: ^1.0.0"), "`^` is not a YAML indicator");
        assertTrue(editor.text().contains("version: \"*\""), "`*` opens an alias, so it must be quoted");
        assertTrue(editor.text().contains("version: main"), "a branch name needs nothing");
    }

    @Test
    void fourSpaceIndentationIsMatchedRatherThanNormalised() {
        ManifestEditor editor = ManifestEditor.of("""
                skills:
                    - name: making-of
                      source: oci://ghcr.io/sunix/skills/making-of
                      version: latest
                """);
        editor.add("release-please", "oci://x/release-please", "^1.0.0");
        assertTrue(editor.text().contains("\n    - name: release-please\n"),
                "the new entry adopts the file's own indentation:\n" + editor.text());
    }

    @Test
    void aManifestWithoutASkillsKeyGetsOne() {
        ManifestEditor editor = ManifestEditor.of("targets: [claude]\n");
        editor.add("making-of", "oci://ghcr.io/sunix/skills/making-of", "^1.0.0");
        assertEquals("""
                targets: [claude]

                skills:
                  - name: making-of
                    source: oci://ghcr.io/sunix/skills/making-of
                    version: ^1.0.0
                """, editor.text());
    }

    @Test
    void anEmptyInlineListIsConvertedRatherThanRefused() {
        ManifestEditor editor = ManifestEditor.of("skills: []\ntargets: [claude]\n");
        editor.add("making-of", "oci://ghcr.io/sunix/skills/making-of", "^1.0.0");
        assertEquals("""
                skills:
                  - name: making-of
                    source: oci://ghcr.io/sunix/skills/making-of
                    version: ^1.0.0
                targets: [claude]
                """, editor.text(),
                "`skills: []` holds nothing an author could lose, unlike a populated inline list");
    }

    @Test
    void anInlineSkillsListIsRefusedRatherThanMangled() {
        ManifestEditor editor = ManifestEditor.of("skills: [{name: a}]\ntargets: [claude]\n");
        var e = assertThrows(IllegalStateException.class,
                () -> editor.add("making-of", "oci://x/making-of", "^1.0.0"));
        assertTrue(e.getMessage().contains("block list"), e.getMessage());
    }

    @Test
    void readingBackWhatIsDeclared() {
        ManifestEditor editor = ManifestEditor.of(AUTHORED);
        assertTrue(editor.declares("making-of"));
        assertFalse(editor.declares("release-please"));
        assertEquals("oci://ghcr.io/sunix/skills/making-of", editor.sourceOf("making-of").orElseThrow(),
                "the trailing comment on that line is not part of the value");
        assertTrue(editor.sourceOf("nope").isEmpty());
    }

    @Test
    void removingSomethingAbsentSaysSoInsteadOfChangingTheFile() {
        ManifestEditor editor = ManifestEditor.of(AUTHORED);
        assertFalse(editor.remove("nope"));
        assertEquals(AUTHORED, editor.text());
    }
}
