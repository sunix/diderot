package org.sunix.diderot.core;

/**
 * A parsed skill source. Supported schemes:
 *
 * <pre>
 * git+https://github.com/sunix/ai-skills#skills/documentation/making-of
 * git+ssh://git@github.com/owner/repo#path/inside/repo
 * git+file:///local/repo#path            (used by tests)
 * oci://ghcr.io/owner/skills/name        (milestone M2 — parsed, not yet resolvable)
 * </pre>
 */
public record SourceRef(Kind kind, String url, String path) {

    public enum Kind {
        GIT, OCI
    }

    public static SourceRef parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Skill source is missing.");
        }
        if (source.startsWith("git+")) {
            String rest = source.substring("git+".length());
            int hash = rest.indexOf('#');
            String url = hash < 0 ? rest : rest.substring(0, hash);
            String path = hash < 0 ? "" : rest.substring(hash + 1);
            path = trimSlashes(path);
            if (url.isBlank()) {
                throw new IllegalArgumentException("Invalid git source (empty URL): " + source);
            }
            return new SourceRef(Kind.GIT, url, path);
        }
        if (source.startsWith("oci://")) {
            return new SourceRef(Kind.OCI, source.substring("oci://".length()), "");
        }
        throw new IllegalArgumentException(
                "Unsupported source scheme: " + source + " (expected git+<url>[#path] or oci://<ref>)");
    }

    private static String trimSlashes(String p) {
        String r = p;
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }
}
