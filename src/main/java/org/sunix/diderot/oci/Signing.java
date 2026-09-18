package org.sunix.diderot.oci;

import java.io.IOException;
import java.io.StringReader;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

import dev.sigstore.KeylessSigner;
import dev.sigstore.KeylessSignerException;
import dev.sigstore.KeylessVerificationException;
import dev.sigstore.KeylessVerifier;
import dev.sigstore.VerificationOptions;
import dev.sigstore.VerificationOptions.CertificateMatcher;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.bundle.BundleParseException;
import dev.sigstore.oidc.client.OidcClients;
import dev.sigstore.strings.StringMatcher;

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

    /**
     * Verifies a sigstore bundle (JSON) against the OCI manifest digest it should attest to
     * <em>and</em> against the signer it must come from. Both identity arguments are required: an
     * unpinned verification answers "this was signed", which is a different and much weaker
     * statement than "this was signed by the workflow this project trusts".
     *
     * @param expectedIdentity the certificate's subject alternative name, e.g. a workflow ref
     * @param expectedIssuer the OIDC issuer that minted the identity token, e.g.
     *        {@code https://token.actions.githubusercontent.com}
     */
    public void verifyDigest(String digest, String bundleJson, String expectedIdentity, String expectedIssuer)
            throws IOException {
        if (expectedIdentity == null || expectedIdentity.isBlank()
                || expectedIssuer == null || expectedIssuer.isBlank()) {
            throw new IllegalArgumentException("Verification needs both an expected identity and issuer");
        }
        KeylessVerifier.Builder builder = KeylessVerifier.builder();
        if (staging) {
            builder.sigstoreStagingDefaults();
        } else {
            builder.sigstorePublicDefaults();
        }
        VerificationOptions options = VerificationOptions.builder()
                .addCertificateMatchers(CertificateMatcher.fulcio()
                        .subjectAlternativeName(StringMatcher.string(expectedIdentity))
                        .issuer(StringMatcher.string(expectedIssuer))
                        .build())
                .build();
        try {
            KeylessVerifier verifier = builder.build();
            Bundle bundle = Bundle.from(new StringReader(bundleJson));
            verifier.verify(rawDigestBytes(digest), bundle, options);
        } catch (KeylessVerificationException e) {
            throw new IOException("Signature verification failed for " + digest + ": " + e.getMessage(), e);
        } catch (BundleParseException e) {
            throw new IOException("Could not parse the sigstore bundle for " + digest + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("Could not build a sigstore verifier: " + e.getMessage(), e);
        }
    }

    /**
     * The identity a bundle actually carries, for error messages: a refusal that says only "does not
     * match" leaves a human unable to tell an attack from a renamed workflow. Best-effort — a bundle
     * that cannot be read at all still has to produce something printable.
     */
    public static String signerOf(String bundleJson) {
        try {
            Bundle bundle = Bundle.from(new StringReader(bundleJson));
            List<? extends Certificate> certificates = bundle.getCertPath().getCertificates();
            if (certificates.isEmpty() || !(certificates.get(0) instanceof X509Certificate certificate)) {
                return "unknown";
            }
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names == null) {
                return "unknown";
            }
            for (List<?> name : names) {
                if (name.size() == 2 && name.get(1) instanceof String value) {
                    return value;
                }
            }
            return "unknown";
        } catch (Exception e) {
            return "unreadable bundle (" + e.getMessage() + ")";
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
