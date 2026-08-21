package org.sunix.diderot.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Runs real git commands in tests (fixtures and cross-verification). */
public final class Git {

    private Git() {
    }

    public static String run(Path dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.environment().put("GIT_AUTHOR_NAME", "test");
        pb.environment().put("GIT_AUTHOR_EMAIL", "test@example.com");
        pb.environment().put("GIT_COMMITTER_NAME", "test");
        pb.environment().put("GIT_COMMITTER_EMAIL", "test@example.com");
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        p.getErrorStream().transferTo(err);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("git failed (" + code + "): " + String.join(" ", cmd) + "\n" + err);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
