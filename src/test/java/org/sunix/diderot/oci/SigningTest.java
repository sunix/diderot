package org.sunix.diderot.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
        signing.verifyDigest(digest, bundle, IDENTITY, ISSUER); // throws on failure; no exception = pass
    }

    @Test
    void verificationFailsClosedWhenTheBundleIsForADifferentDigest() throws Exception {
        String signedDigest = sha256Of("what was actually signed");
        String bundle = signing.signDigest(signedDigest);

        String substitutedDigest = sha256Of("what an attacker wants installed instead");
        assertThrows(IOException.class,
                () -> signing.verifyDigest(substitutedDigest, bundle, IDENTITY, ISSUER),
                "a valid signature for one digest must not verify a different one");
    }

    @Test
    void verificationFailsClosedWhenSomebodyElseSignedIt() throws Exception {
        String digest = sha256Of("signed by the conformance identity");
        String bundle = signing.signDigest(digest);

        assertThrows(IOException.class,
                () -> signing.verifyDigest(digest, bundle,
                        "https://github.com/attacker/tools/.github/workflows/release.yml@refs/heads/main",
                        ISSUER),
                "a genuine signature from the wrong identity is still a refusal");
        assertEquals(IDENTITY, Signing.signerOf(bundle),
                "and the refusal can name who did sign it");
    }

    private static String sha256Of(String content) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "sha256:" + HexFormat.of().formatHex(hash);
    }
}
