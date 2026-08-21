package org.sunix.diderot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SourceRefTest {

    @Test
    void parsesGitHttpsWithPath() {
        SourceRef ref = SourceRef.parse("git+https://github.com/sunix/ai-skills#skills/documentation/making-of");
        assertEquals(SourceRef.Kind.GIT, ref.kind());
        assertEquals("https://github.com/sunix/ai-skills", ref.url());
        assertEquals("skills/documentation/making-of", ref.path());
    }

    @Test
    void parsesGitWithoutPathAndTrimsSlashes() {
        assertEquals("", SourceRef.parse("git+https://example.com/repo").path());
        assertEquals("a/b", SourceRef.parse("git+https://example.com/repo#/a/b/").path());
    }

    @Test
    void parsesOci() {
        SourceRef ref = SourceRef.parse("oci://ghcr.io/sunix/skills/making-of");
        assertEquals(SourceRef.Kind.OCI, ref.kind());
        assertEquals("ghcr.io/sunix/skills/making-of", ref.url());
    }

    @Test
    void rejectsUnknownSchemes() {
        assertThrows(IllegalArgumentException.class, () -> SourceRef.parse("http://example.com"));
        assertThrows(IllegalArgumentException.class, () -> SourceRef.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SourceRef.parse(null));
    }
}
