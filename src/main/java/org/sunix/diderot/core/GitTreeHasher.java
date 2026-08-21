package org.sunix.diderot.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Computes the git tree SHA-1 of a directory on disk, in pure Java, without a .git directory.
 *
 * <p>This is what lets {@code install} verify extracted content against the lockfile and
 * {@code status} detect drift in installed skills: the hash of the bytes on disk must equal the
 * {@code tree:<sha>} recorded at lock time, reproducing git's own object hashing — blobs are
 * {@code sha1("blob <size>\0" + content)}, trees are sorted entries of
 * {@code "<mode> <name>\0" + <20 raw sha bytes>} hashed as {@code sha1("tree <size>\0" + entries)}.
 * Directories sort as {@code name + "/"}, git's rule.
 */
public final class GitTreeHasher {

    private GitTreeHasher() {
    }

    public static String treeSha(Path dir) throws IOException {
        return hex(hashTree(dir));
    }

    private static byte[] hashTree(Path dir) throws IOException {
        record Entry(String mode, String name, byte[] sha, boolean isDir) {
        }
        List<Entry> entries = new ArrayList<>();
        try (var children = Files.list(dir)) {
            for (Path child : children.sorted().toList()) {
                String name = child.getFileName().toString();
                if (name.equals(".git")) {
                    continue;
                }
                if (Files.isSymbolicLink(child)) {
                    byte[] target = Files.readSymbolicLink(child).toString().getBytes(StandardCharsets.UTF_8);
                    entries.add(new Entry("120000", name, hashObject("blob", target), false));
                } else if (Files.isDirectory(child)) {
                    entries.add(new Entry("40000", name, hashTree(child), true));
                } else {
                    String mode = Files.isExecutable(child) ? "100755" : "100644";
                    entries.add(new Entry(mode, name, hashObject("blob", Files.readAllBytes(child)), false));
                }
            }
        }
        // git sorts tree entries as if directory names had a trailing '/'
        entries.sort(Comparator.comparing(e -> e.isDir() ? e.name() + "/" : e.name()));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Entry e : entries) {
            body.writeBytes((e.mode() + " " + e.name() + "\0").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(e.sha());
        }
        return hashObject("tree", body.toByteArray());
    }

    private static byte[] hashObject(String type, byte[] content) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update((type + " " + content.length + "\0").getBytes(StandardCharsets.UTF_8));
            return sha1.digest(content);
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
