package org.sunix.diderot.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.sunix.diderot.core.GitTreeHasher;
import org.sunix.diderot.core.LockFile;
import org.sunix.diderot.core.Workspace;
import org.sunix.diderot.git.GitCli;
import org.sunix.diderot.testutil.Git;

import dev.sigstore.oidc.client.OidcClients;
import dev.sigstore.oidc.client.TokenStringOidcClient;
import org.sunix.diderot.testutil.SigstoreConformanceToken;

/**
 * Full OCI cycle against a real registry (zot in a container — a CNCF, OCI 1.1-native registry
 * with the Referrers API enabled by default; docker/distribution's registry:2, and even registry:3
 * with its default config, don't set the OCI-Subject response header attachArtifact relies on) AND
 * real sigstore infrastructure (the staging instance, signed with sigstore's own published
 * conformance-testing token — see {@link Signing#staging}): push a skill directory (signed),
 * resolve + lock it from a manifest (verified), install it, tamper with it, repair it. Skipped when
 * no container runtime or no network path to the test token is available.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OciRoundTripTest {

    static String containerId;
    static String registryHostPort;
    static Signing signing;

    @TempDir
    Path tmp;

    @BeforeAll
    void startRegistry() throws Exception {
        try {
            Git.run(Path.of("."), "docker", "version");
        } catch (Exception e) {
            assumeTrue(false, "docker not available: " + e.getMessage());
        }
        containerId = Git.run(Path.of("."), "docker", "run", "-d", "--rm",
                "-p", "127.0.0.1:0:5000", "ghcr.io/project-zot/zot:latest").trim();
        String portLine = Git.run(Path.of("."), "docker", "port", containerId, "5000/tcp").trim();
        registryHostPort = portLine.lines().findFirst().orElseThrow(); // e.g. 127.0.0.1:32768
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest ping = HttpRequest.newBuilder(URI.create("http://" + registryHostPort + "/v2/")).build();
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            try {
                http.send(ping, HttpResponse.BodyHandlers.discarding());
                break;
            } catch (Exception e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("zot did not become ready", e);
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
    void pushUpdateInstallDriftRepair() throws Exception {
        // The skill to publish.
        Path skillDir = tmp.resolve("making-of");
        Files.createDirectories(skillDir.resolve("templates"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: making-of\n---\nInstructions.\n");
        Files.writeString(skillDir.resolve("templates/MAKING-OF.md"), "# template\n");
        String sourceTreeDigest = "tree:" + GitTreeHasher.treeSha(skillDir);

        OrasClient oras = new OrasClient(tmp.resolve("oci-cache"), signing);
        String repository = registryHostPort + "/skills/making-of";
        String pushedDigest = oras.push(skillDir, repository + ":v1");
        assertTrue(pushedDigest.startsWith("sha256:"));

        // A consumer project declaring that OCI source.
        Path project = tmp.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("diderot.yaml"), """
                skills:
                  - name: making-of
                    source: oci://%s
                    version: v1
                targets: [claude]
                """.formatted(repository));
        StringWriter output = new StringWriter();
        Workspace workspace = new Workspace(project, new GitCli(tmp.resolve("git-cache")),
                oras, new PrintWriter(output, true));

        LockFile lock = workspace.update();
        assertEquals(pushedDigest, lock.skills.get(0).resolved,
                "the lock pins the exact manifest digest the push produced");
        assertEquals(sourceTreeDigest, lock.skills.get(0).digest,
                "the locked content digest equals the source directory's tree hash: "
                        + "what was pushed is byte-for-byte what got locked");

        workspace.install(null);
        Path installed = project.resolve(".claude/skills/making-of");
        assertTrue(Files.isRegularFile(installed.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(installed.resolve("templates/MAKING-OF.md")));
        assertEquals(0, workspace.status(null));

        Files.writeString(installed.resolve("SKILL.md"), "tampered\n");
        assertEquals(1, workspace.status(null), "drift in an OCI-sourced skill is detected");
        workspace.install(null);
        assertEquals(0, workspace.status(null), "reinstall from the digest-pinned cache repairs it");
    }

    @Test
    void updateRefusesAnArtifactPushedWithoutTheOrasClientSigningStep() throws Exception {
        // A hand-crafted push that skips diderot's own signing step entirely — the shape a
        // compromised or unrelated publisher's artifact would have. Uses the raw ORAS registry
        // client directly, bypassing OrasClient.push() on purpose.
        Path skillDir = tmp.resolve("unsigned-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: unsigned\n---\n");
        String repository = registryHostPort + "/skills/unsigned";
        land.oras.Registry registry = land.oras.Registry.builder().insecure().build();
        registry.pushArtifact(land.oras.ContainerRef.parse(repository + ":v1"),
                land.oras.LocalPath.of(skillDir));

        Path project = tmp.resolve("project-unsigned");
        Files.createDirectories(project);
        Files.writeString(project.resolve("diderot.yaml"), """
                skills:
                  - name: unsigned
                    source: oci://%s
                    version: v1
                """.formatted(repository));
        Workspace workspace = new Workspace(project, new GitCli(tmp.resolve("git-cache-unsigned")),
                new OrasClient(tmp.resolve("oci-cache-unsigned"), signing),
                new PrintWriter(new StringWriter(), true));

        var e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, workspace::update);
        assertTrue(e.getMessage().contains("No sigstore signature attached"),
                "an artifact with no attached signature must be refused, not silently trusted");
    }

    @Test
    void updateRefusesAnArtifactWithoutSkillMd() throws Exception {
        Path notASkill = tmp.resolve("not-a-skill");
        Files.createDirectories(notASkill);
        Files.writeString(notASkill.resolve("README.md"), "no SKILL.md here\n");
        OrasClient oras = new OrasClient(tmp.resolve("oci-cache-2"), signing);
        String repository = registryHostPort + "/skills/not-a-skill";
        oras.push(notASkill, repository + ":v1");

        Path project = tmp.resolve("project-2");
        Files.createDirectories(project);
        Files.writeString(project.resolve("diderot.yaml"), """
                skills:
                  - name: not-a-skill
                    source: oci://%s
                    version: v1
                """.formatted(repository));
        Workspace workspace = new Workspace(project, new GitCli(tmp.resolve("git-cache-2")),
                oras, new PrintWriter(new StringWriter(), true));

        var e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, workspace::update);
        assertTrue(e.getMessage().contains("SKILL.md"));
    }
}
