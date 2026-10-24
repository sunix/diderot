package org.sunix.diderot.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A sha256 content identity for a skill directory: what a signature attests to.
 *
 * <p>{@link GitTreeHasher} already gives a directory an identity, and {@code install} and
 * {@code status} use it to detect drift — but it is git's, which means SHA-1, whose collision
 * resistance is broken (a chosen-prefix collision cost around $45k of GPU time in 2020 and less
 * since). Git itself hashes with sha1dc and refuses the known attack patterns; this project's pure
 * Java reimplementation cannot. Second preimages remain out of reach, so a leaked registry token
 * still cannot match an existing digest — but a publisher able to craft two colliding directories
 * could have the harmless one signed and serve the other, and nothing downstream would notice.
 * A signature should not inherit that, so it binds to this digest instead.
 *
 * <p>The listing is deliberately flat and readable: a version line, then one line per file, sorted
 * by path as raw UTF-8 bytes so the order cannot depend on a locale.
 *
 * <pre>
 * diderot-content-v1
 * 100644 &lt;sha256 of the file&gt; SKILL.md
 * 100755 &lt;sha256 of the file&gt; bin/run.sh
 * 120000 &lt;sha256 of the target path&gt; latest
 * </pre>
 *
 * <p>Inclusion rules mirror the tree hasher exactly — {@code .git} skipped, symlinks hashed by
 * their target rather than followed, the executable bit the only mode distinction — so both digests
 * always describe the same set of files. Empty directories are invisible to both, as they are to
 * git.
 */
public final class ContentDigest {

    /** Prefixes the listing so this digest can never be confused with a hash of something else. */
    static final String FORMAT = "diderot-content-v1\n";

    private ContentDigest() {
    }

    /** The content digest of {@code dir}, as {@code sha256:<hex>}. */
    public static String sha256Of(Path dir) throws IOException {
        List<String> lines = new ArrayList<>();
        collect(dir, "", lines);
        lines.sort((a, b) -> Arrays.compareUnsigned(
                pathOf(a).getBytes(StandardCharsets.UTF_8), pathOf(b).getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream listing = new ByteArrayOutputStream();
        listing.writeBytes(FORMAT.getBytes(StandardCharsets.UTF_8));
        for (String line : lines) {
            listing.writeBytes(line.getBytes(StandardCharsets.UTF_8));
        }
        return "sha256:" + hex(sha256(listing.toByteArray()));
    }

    private static void collect(Path dir, String prefix, List<String> lines) throws IOException {
        try (var children = Files.list(dir)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.equals(".git")) {
                    continue;
                }
                String path = prefix + name;
                if (Files.isSymbolicLink(child)) {
                    byte[] target = Files.readSymbolicLink(child).toString().getBytes(StandardCharsets.UTF_8);
                    lines.add(line("120000", sha256(target), path));
                } else if (Files.isDirectory(child)) {
                    collect(child, path + "/", lines);
                } else {
                    String mode = Files.isExecutable(child) ? "100755" : "100644";
                    lines.add(line(mode, sha256(Files.readAllBytes(child)), path));
                }
            }
        }
    }

    private static String line(String mode, byte[] sha, String path) {
        return mode + " " + hex(sha) + " " + path + "\n";
    }

    /** The path a listing line ends with, used for sorting before the lines are concatenated. */
    private static String pathOf(String line) {
        return line.substring(line.indexOf(' ', line.indexOf(' ') + 1) + 1, line.length() - 1);
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
