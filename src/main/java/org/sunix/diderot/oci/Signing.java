package org.sunix.diderot.oci;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
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
import org.sunix.diderot.core.Signer;

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
     * Verifies a sigstore bundle (JSON) against the digest it should attest to <em>and</em> against
     * the signer it must come from — which is why this is not called {@code verifyDigest} any more:
     * the two-argument method of that name answered "this was signed", a different and much weaker
     * statement than "this was signed by the workflow this project trusts".
     *
     * <p>Both identity arguments are required. Deciding what to do when this throws belongs to the
     * caller; {@code Workspace.enforcePinnedSigner} is the one that has that opinion.
     *
     * @param expectedIdentity the certificate's subject alternative name, e.g. a workflow ref
     * @param expectedIssuer the OIDC issuer that minted the identity token, e.g.
     *        {@code https://token.actions.githubusercontent.com}
     */
    public void verifySignature(String digest, String bundleJson, String expectedIdentity, String expectedIssuer)
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
    /**
     * The identity a bundle carries, as a pin a manifest could hold: the certificate's subject
     * alternative name, and the OIDC issuer that vouched for it. Returns null when the bundle
     * cannot be read or carries neither — a caller offering to pin a signer has nothing to offer.
     *
     * <p>The issuer lives in an extension rather than a field. sigstore writes two: the original
     * {@code 1.3.6.1.4.1.57264.1.1}, whose value is the bare string, and
     * {@code 1.3.6.1.4.1.57264.1.8}, which wraps the same string in a DER UTF8String. The first is
     * deprecated and still emitted; both are read here, the plain one first.
     */
    public static Signer identityOf(String bundleJson) {
        try {
            Bundle bundle = Bundle.from(new StringReader(bundleJson));
            List<? extends Certificate> certificates = bundle.getCertPath().getCertificates();
            if (certificates.isEmpty() || !(certificates.get(0) instanceof X509Certificate certificate)) {
                return null;
            }
            String identity = subjectAlternativeName(certificate);
            String issuer = extensionString(certificate, "1.3.6.1.4.1.57264.1.1");
            if (issuer == null) {
                issuer = extensionString(certificate, "1.3.6.1.4.1.57264.1.8");
            }
            return identity == null || issuer == null ? null : new Signer(identity, issuer);
        } catch (Exception e) {
            return null;
        }
    }

    private static String subjectAlternativeName(X509Certificate certificate) throws Exception {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        if (names == null) {
            return null;
        }
        for (List<?> name : names) {
            if (name.size() == 2 && name.get(1) instanceof String value) {
                return value;
            }
        }
        return null;
    }

    /**
     * The string inside an X.509 extension. {@code getExtensionValue} hands back the DER octet
     * string that wraps the value, so the wrapper is stepped over — and then the value itself may
     * be another DER string, which is stepped over too. Anything that does not come out looking
     * like a URL is treated as unreadable rather than guessed at.
     */
    private static String extensionString(X509Certificate certificate, String oid) {
        byte[] raw = certificate.getExtensionValue(oid);
        if (raw == null || raw.length < 3) {
            return null;
        }
        String value = new String(raw, 2, raw.length - 2, StandardCharsets.UTF_8);
        if (!value.startsWith("http") && value.length() > 2) {
            value = value.substring(2);
        }
        return value.startsWith("http") ? value : null;
    }

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
