package org.sunix.diderot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.sunix.diderot.git.GitCli;
import org.sunix.diderot.oci.OrasClient;
import org.sunix.diderot.oci.Signing;
import org.sunix.diderot.testutil.Git;
import org.sunix.diderot.testutil.SigstoreConformanceToken;

import dev.sigstore.oidc.client.OidcClients;
import dev.sigstore.oidc.client.TokenStringOidcClient;

/**
 * What a pinned signer is worth, end to end: a skill pushed to a real registry, signed for real
 * against sigstore's staging instance, and resolved by a project that pins who may sign it. The
 * cases that matter are the refusals — wrong signer, and no signature at all — and what they leave
 * behind, which must be nothing. Skipped without a container runtime or a path to the test token.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignatureVerificationTest {

    /** Who sigstore's published testing token authenticates as. */
    private static final String IDENTITY = "untrusted-sa@sigstore-conformance.iam.gserviceaccount.com";
    private static final String ISSUER = "https://accounts.google.com";
    private static final String ATTACKER =
            "https://github.com/attacker/tools/.github/workflows/release.yml@refs/heads/main";

    static String containerId;
    static String registryHostPort;
    static Signing signing;

    @TempDir
    Path tmp;

    @BeforeAll
    void startRegistryAndSigner() throws Exception {
        try {
            Git.run(Path.of("."), "docker", "version");
        } catch (Exception e) {
            assumeTrue(false, "docker not available: " + e.getMessage());
        }
        containerId = Git.run(Path.of("."), "docker", "run", "-d", "--rm",
                "-p", "127.0.0.1:0:5000", "registry:2").trim();
        registryHostPort = Git.run(Path.of("."), "docker", "port", containerId, "5000/tcp")
                .trim().lines().findFirst().orElseThrow();
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest ping = HttpRequest.newBuilder(URI.create("http://" + registryHostPort + "/v2/")).build();
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            try {
                http.send(ping, HttpResponse.BodyHandlers.discarding());
                break;
            } catch (Exception e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("registry:2 did not become ready", e);
                }
                Thread.sleep(300);
            }
        }
        try {
            SigstoreConformanceToken.fetch();
        } catch (Exception e) {
            assumeTrue(false, "sigstore conformance-testing token unreachable: " + e.getMessage());
        }
        signing = Signing.staging(
                OidcClients.of(TokenStringOidcClient.from(SigstoreConformanceToken.provider())));
    }

    @AfterAll
    void stopRegistry() throws Exception {
        if (containerId != null) {
            Git.run(Path.of("."), "docker", "rm", "-f", containerId);
        }
    }

    @Test
    void updateVerifiesAndRecordsTheSignerItPinned() throws Exception {
        String repository = publishSigned("trusted");
        Path project = project("trusted-consumer", repository, IDENTITY, ISSUER);
        StringWriter output = new StringWriter();

        LockFile lock = workspace(project, output).update();

        assertEquals(IDENTITY, lock.skills.get(0).signer.identity,
                "the lock records who was verified, so a human can read it back");
        assertEquals(ISSUER, lock.skills.get(0).signer.issuer);
        assertTrue(output.toString().contains("signer ok"), output.toString());
    }

    @Test
    void updateFailsClosedWhenSomebodyElseSignedIt() throws Exception {
        String repository = publishSigned("wrong-signer");
        Path project = project("wrong-signer-consumer", repository, ATTACKER, ISSUER);

        IOException refusal = assertThrows(IOException.class, () -> workspace(project, new StringWriter()).update());

        assertTrue(refusal.getMessage().contains("not by the expected signer"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(ATTACKER), "the refusal names what was expected");
        assertTrue(refusal.getMessage().contains(IDENTITY), "and who actually signed it");
        assertFalse(Files.exists(project.resolve("diderot.lock")),
                "nothing was written: the project keeps whatever it had");
    }

    @Test
    void updateFailsClosedWhenNothingIsSignedAtAll() throws Exception {
        String repository = publishUnsigned("unsigned");
        Path project = project("unsigned-consumer", repository, IDENTITY, ISSUER);

        IOException refusal = assertThrows(IOException.class, () -> workspace(project, new StringWriter()).update());

        assertTrue(refusal.getMessage().contains("carries no signature"), refusal.getMessage());
        assertFalse(Files.exists(project.resolve("diderot.lock")));
    }

    @Test
    void installReChecksInsteadOfTrustingTheLock() throws Exception {
        String repository = publishSigned("shared-lock");
        Path project = project("shared-lock-consumer", repository, IDENTITY, ISSUER);
        workspace(project, new StringWriter()).update();
        workspace(project, new StringWriter()).install(null);

        // The lockfile stays exactly as a teammate produced it; only the pin changes, which is what
        // a project would edit after learning its publisher's identity was not what it thought.
        Files.writeString(project.resolve("diderot.yaml"),
                manifestYaml(repository, ATTACKER, ISSUER));

        IOException refusal = assertThrows(IOException.class,
                () -> workspace(project, new StringWriter()).install(null));
        assertTrue(refusal.getMessage().contains("not by the expected signer"), refusal.getMessage());
    }

    @Test
    void anUnpinnedSkillIsInstalledWithoutAnyCheck() throws Exception {
        String repository = publishUnsigned("no-pin");
        Path project = project("no-pin-consumer", repository, null, null);
        StringWriter output = new StringWriter();

        LockFile lock = workspace(project, output).update();

        assertEquals(null, lock.skills.get(0).signer, "nothing pinned, nothing recorded");
        assertFalse(output.toString().contains("signer ok"));
    }

    /** Publishes a skill with a real staging signature over its content, riding in the manifest. */
    private String publishSigned(String name) throws Exception {
        Path skillDir = skillDirectory(name);
        String repository = registryHostPort + "/skills/" + name;
        String bundle = signing.signDigest(ContentDigest.sha256Of(skillDir));
        new OrasClient(tmp.resolve("cache-" + name)).push(skillDir, repository + ":v1", bundle);
        return repository;
    }

    private String publishUnsigned(String name) throws Exception {
        String repository = registryHostPort + "/skills/" + name;
        new OrasClient(tmp.resolve("cache-" + name)).push(skillDirectory(name), repository + ":v1");
        return repository;
    }

    private Path skillDirectory(String name) throws Exception {
        Path skillDir = tmp.resolve("skills").resolve(name).resolve("making-of");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: making-of\n---\nInstructions.\n");
        return skillDir;
    }

    private Path project(String name, String repository, String identity, String issuer) throws Exception {
        Path project = tmp.resolve(name);
        Files.createDirectories(project);
        Files.writeString(project.resolve("diderot.yaml"), manifestYaml(repository, identity, issuer));
        return project;
    }

    private static String manifestYaml(String repository, String identity, String issuer) {
        String pin = identity == null ? "" : """
                    signer:
                      identity: %s
                      issuer: %s
                """.formatted(identity, issuer);
        return """
                skills:
                  - name: making-of
                    source: oci://%s
                    version: v1
                %stargets: [claude]
                """.formatted(repository, pin);
    }

    private Workspace workspace(Path project, StringWriter output) {
        return new Workspace(project, new GitCli(tmp.resolve("git-cache")),
                new OrasClient(tmp.resolve("oci-cache")), signing, new PrintWriter(output, true));
    }
}
