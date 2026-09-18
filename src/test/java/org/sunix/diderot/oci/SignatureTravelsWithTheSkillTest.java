package org.sunix.diderot.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.sunix.diderot.core.ContentDigest;
import org.sunix.diderot.testutil.Git;
import org.sunix.diderot.testutil.SigstoreConformanceToken;

import dev.sigstore.oidc.client.OidcClients;
import dev.sigstore.oidc.client.TokenStringOidcClient;

/**
 * A signature over content, carried by the artifact it belongs to: signed once, published under two
 * tags, and readable back from either — which is what signing the content rather than the manifest
 * buys. Real registry in a container, real staging signature; skipped without either.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignatureTravelsWithTheSkillTest {

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
    void oneSignatureCoversTheSameContentUnderEveryTag() throws Exception {
        Path skillDir = skillDirectory("two-tags");
        String repository = registryHostPort + "/skills/two-tags";
        OrasClient oras = new OrasClient(tmp.resolve("cache-two-tags"));

        // Signed once, over the content — then published twice, as a release and as a floating tag.
        String bundle = signing.signDigest(ContentDigest.sha256Of(skillDir));
        String pinnedDigest = oras.push(skillDir, repository + ":1.0.0", bundle);
        Thread.sleep(1100); // the created annotation the SDK stamps has second granularity
        String floatingDigest = oras.push(skillDir, repository + ":latest", bundle);

        assertNotEquals(pinnedDigest, floatingDigest,
                "identical content still produces two manifests, which is issue #21 — the SDK "
                        + "stamps a fresh created timestamp on every push");
        assertEquals(Optional.of(bundle), oras.fetchSignature(repository, pinnedDigest));
        assertEquals(Optional.of(bundle), oras.fetchSignature(repository, floatingDigest),
                "the same signature came back through the other tag: what it attests is the "
                        + "content, which both manifests carry");
    }

    @Test
    void anUnsignedPushCarriesNothing() throws Exception {
        Path skillDir = skillDirectory("unsigned");
        String repository = registryHostPort + "/skills/unsigned";
        OrasClient oras = new OrasClient(tmp.resolve("cache-unsigned"));

        String digest = oras.push(skillDir, repository + ":v1");

        assertEquals(Optional.empty(), oras.fetchSignature(repository, digest),
                "a missing signature is an empty answer, not an error");
    }

    private Path skillDirectory(String name) throws Exception {
        Path skillDir = tmp.resolve(name).resolve("making-of");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: making-of\n---\nInstructions.\n");
        return skillDir;
    }
}
