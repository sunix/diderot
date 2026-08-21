package org.sunix.diderot.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Thin wrapper around the {@code git} binary. diderot keeps one bare clone per repository URL under
 * a cache directory and resolves refs, tree SHAs, and archives from it — the same approach as Go
 * modules in their git-backed days: no protocol reimplementation, git does git.
 */
public class GitCli {

    private final Path cacheRoot;

    public GitCli(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public static Path defaultCacheRoot() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("diderot").resolve("git");
    }

    /** Clones (bare) on first use, fetches branches and tags afterwards. Returns the cache repo path. */
    public Path ensureFresh(String url) throws IOException {
        Path repo = cacheRoot.resolve(cacheKey(url));
        if (!Files.isDirectory(repo)) {
            Files.createDirectories(cacheRoot);
            run(null, "git", "clone", "--bare", "--quiet", url, repo.toString());
        } else {
            run(repo, "git", "fetch", "--quiet", "--prune", "origin",
                    "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*");
        }
        return repo;
    }

    /** Resolves a branch, tag, or commit-ish to a full commit SHA. */
    public String resolveCommit(Path repo, String ref) throws IOException {
        return run(repo, "git", "rev-parse", "--verify", "--quiet", ref + "^{commit}").trim();
    }

    /** Returns the tree SHA of {@code path} at {@code commit} (of the whole tree when path is empty). */
    public String treeSha(Path repo, String commit, String path) throws IOException {
        String spec = path.isEmpty() ? commit + "^{tree}" : commit + ":" + path;
        return run(repo, "git", "rev-parse", "--verify", "--quiet", spec).trim();
    }

    /** True when {@code path} exists as a blob at {@code commit}. */
    public boolean blobExists(Path repo, String commit, String path) {
        try {
            run(repo, "git", "cat-file", "-e", commit + ":" + path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Extracts the directory {@code path} at {@code commit} into {@code dest} (created if needed). */
    public void extract(Path repo, String commit, String path, Path dest) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git", "archive", "--format=tar"));
        cmd.add(path.isEmpty() ? commit : commit + ":" + path);
        Files.createDirectories(dest);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(p.getInputStream())) {
            untar(tar, dest);
        }
        waitFor(p, cmd);
    }

    private static void untar(TarArchiveInputStream tar, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        TarArchiveEntry entry;
        while ((entry = tar.getNextEntry()) != null) {
            Path out = root.resolve(entry.getName()).normalize();
            if (!out.startsWith(root)) {
                throw new IOException("Archive entry escapes destination: " + entry.getName());
            }
            if (entry.isDirectory()) {
                Files.createDirectories(out);
            } else if (entry.isSymbolicLink()) {
                Files.createDirectories(out.getParent());
                Files.deleteIfExists(out);
                Files.createSymbolicLink(out, Path.of(entry.getLinkName()));
            } else {
                Files.createDirectories(out.getParent());
                Files.copy(tar, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if ((entry.getMode() & 0100) != 0) {
                    out.toFile().setExecutable(true);
                }
            }
        }
    }

    private static String cacheKey(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String hex = new BigInteger(1, md.digest(url.getBytes(StandardCharsets.UTF_8))).toString(16);
            return hex.substring(0, Math.min(16, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String run(Path workDir, String... cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        Process p = pb.start();
        String out = readAll(p.getInputStream());
        waitFor(p, List.of(cmd));
        return out;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static void waitFor(Process p, List<String> cmd) throws IOException {
        try {
            int code = p.waitFor();
            if (code != 0) {
                String err = readAll(p.getErrorStream()).trim();
                throw new IOException("Command failed (" + code + "): " + String.join(" ", cmd)
                        + (err.isEmpty() ? "" : "\n" + err));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running: " + String.join(" ", cmd), e);
        }
    }
}
