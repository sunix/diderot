package org.sunix.diderot.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.sigstore.KeylessVerifier;
import dev.sigstore.VerificationOptions;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.oidc.client.OidcClients;
import dev.sigstore.oidc.client.TokenStringOidcClient;
import org.sunix.diderot.testutil.SigstoreConformanceToken;

/**
 * Real keyless sign + verify against the sigstore staging instance, isolated from any registry —
 * exercises the actual cryptography and the actual Rekor transparency log, not a stub.
 */
class SigningTest {

    /** Who sigstore's published testing token authenticates as, and who vouched for it. */
    static final String IDENTITY = "untrusted-sa@sigstore-conformance.iam.gserviceaccount.com";
    static final String ISSUER = "https://accounts.google.com";

    /** The publisher a project would pin — an identity nothing in this test can sign as. */
    static final String PUBLISHER =
            "https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main";

    static Signing signing;

    @BeforeAll
    static void setUp() {
        try {
            SigstoreConformanceToken.fetch();
        } catch (Exception e) {
            assumeTrue(false, "sigstore conformance-testing token unreachable: " + e.getMessage());
        }
        signing = Signing.staging(
                OidcClients.of(TokenStringOidcClient.from(SigstoreConformanceToken.provider())));
    }

    @Test
    void signedDigestVerifiesAgainstItself() throws Exception {
        String digest = sha256Of("diderot signing test — happy path");
        String bundle = signing.signDigest(digest);
        signing.verifySignature(digest, bundle, IDENTITY, ISSUER); // throws on failure; no exception = pass
    }

    @Test
    void verificationFailsClosedWhenTheBundleIsForADifferentDigest() throws Exception {
        String signedDigest = sha256Of("what was actually signed");
        String bundle = signing.signDigest(signedDigest);

        String substitutedDigest = sha256Of("what an attacker wants installed instead");
        assertThrows(IOException.class,
                () -> signing.verifySignature(substitutedDigest, bundle, IDENTITY, ISSUER),
                "a valid signature for one digest must not verify a different one");
    }

    @Test
    void verificationFailsClosedWhenSomebodyElseSignedIt() throws Exception {
        String digest = sha256Of("signed by the conformance identity");
        String bundle = signing.signDigest(digest);

        assertThrows(IOException.class,
                () -> signing.verifySignature(digest, bundle, PUBLISHER, ISSUER),
                "a genuine signature from the wrong identity is still a refusal");
        assertEquals(IDENTITY, Signing.signerOf(bundle),
                "and the refusal can name who did sign it");
    }

    /**
     * What the two-argument {@code verifySignature(digest, bundle)} removed in this step actually did,
     * kept as a test so the reason it is gone stays provable rather than asserted: an empty
     * {@link VerificationOptions} accepts a signature from an identity nobody asked for.
     */
    @Test
    void anEmptyPolicyAcceptsASignatureFromAnybody() throws Exception {
        String digest = sha256Of("what the old two-argument call verified");
        String bundle = signing.signDigest(digest);

        KeylessVerifier verifier = KeylessVerifier.builder().sigstoreStagingDefaults().build();
        verifier.verify(rawDigestBytes(digest), Bundle.from(new StringReader(bundle)),
                VerificationOptions.builder().build()); // no exception: nobody asked who signed

        assertThrows(IOException.class,
                () -> signing.verifySignature(digest, bundle, PUBLISHER, ISSUER),
                "the same bundle, once the project says whose signature it expects");
    }

    private static byte[] rawDigestBytes(String digest) {
        return HexFormat.of().parseHex(digest.substring("sha256:".length()));
    }

    private static String sha256Of(String content) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        return "sha256:" + HexFormat.of().formatHex(hash);
    }
}
