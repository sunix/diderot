package org.sunix.diderot.oci;

import java.io.IOException;
import java.io.StringReader;
import java.util.HexFormat;

import dev.sigstore.KeylessSigner;
import dev.sigstore.KeylessSignerException;
import dev.sigstore.KeylessVerificationException;
import dev.sigstore.KeylessVerifier;
import dev.sigstore.VerificationOptions;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.bundle.BundleParseException;
import dev.sigstore.oidc.client.OidcClients;

/**
 * The sigstore boundary: the third external system diderot talks to, alongside git (GitCli) and
 * registries (OrasClient). Keyless signing/verification only — sigstore-java's stable public API
 * is {@link KeylessSigner}/{@link KeylessVerifier}, no long-lived key pairs to manage. A skill's
 * manifest digest is what gets signed, not its file bytes, so the signature ties to exactly what a
 * registry consumer resolves.
 */
public class Signing {

    private final boolean staging;
    private final OidcClients oidcOverride;

    private Signing(boolean staging, OidcClients oidcOverride) {
        this.staging = staging;
        this.oidcOverride = oidcOverride;
    }

    /** The real sigstore public-good instance (Fulcio + Rekor production), what `diderot push` uses. */
    public static Signing production() {
        return new Signing(false, null);
    }

    /**
     * The sigstore staging instance — test-only, never used by real signing. {@code oidcOverride}
     * lets tests supply a non-interactive test identity (e.g. sigstore's own published
     * "untrusted testing token" for conformance suites) instead of the interactive browser flow.
     */
    public static Signing staging(OidcClients oidcOverride) {
        return new Signing(true, oidcOverride);
    }

    /** Signs an OCI manifest digest ({@code sha256:<hex>}) and returns the sigstore bundle as JSON. */
    public String signDigest(String digest) throws IOException {
        KeylessSigner.Builder builder = KeylessSigner.builder();
        if (staging) {
            builder.sigstoreStagingDefaults();
        } else {
            builder.sigstorePublicDefaults();
        }
        if (oidcOverride != null) {
            builder.oidcClients(oidcOverride);
        }
        try (KeylessSigner signer = builder.build()) {
            Bundle bundle = signer.sign(rawDigestBytes(digest));
            return bundle.toJson();
        } catch (KeylessSignerException e) {
            throw new IOException("Signing failed for " + digest + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("Could not build a sigstore signer: " + e.getMessage(), e);
        }
    }

    /** Verifies a sigstore bundle (JSON) against the OCI manifest digest it should attest to. */
    public void verifyDigest(String digest, String bundleJson) throws IOException {
        KeylessVerifier.Builder builder = KeylessVerifier.builder();
        if (staging) {
            builder.sigstoreStagingDefaults();
        } else {
            builder.sigstorePublicDefaults();
        }
        try {
            KeylessVerifier verifier = builder.build();
            Bundle bundle = Bundle.from(new StringReader(bundleJson));
            verifier.verify(rawDigestBytes(digest), bundle, VerificationOptions.builder().build());
        } catch (KeylessVerificationException e) {
            throw new IOException("Signature verification failed for " + digest + ": " + e.getMessage(), e);
        } catch (BundleParseException e) {
            throw new IOException("Could not parse the sigstore bundle for " + digest + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("Could not build a sigstore verifier: " + e.getMessage(), e);
        }
    }

    private static byte[] rawDigestBytes(String digest) {
        if (!digest.startsWith("sha256:")) {
            throw new IllegalArgumentException("Only sha256 OCI digests are supported: " + digest);
        }
        try {
            return HexFormat.of().parseHex(digest.substring("sha256:".length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed digest: " + digest, e);
        }
    }
}
