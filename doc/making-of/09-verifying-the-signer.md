# Checking who signed it, and refusing when it is somebody else

*Part nine of [diderot's making-of](../../MAKING-OF.md): a stored signature is not a checked one.
Pinning a signer, verifying against it, and failing closed — with nothing written when it fails.*

> The step after [part eight](08-storing-a-signature.md), where the signature ended up riding inside
> the artifact and attesting the skill's content. This one is the reason all of it exists: answering
> row 4 of the table [part seven](07-signing-foundations.md) opened with, *do I accept that signer?*

## The goal: the Tuesday from part seven, for real this time

Part seven described a compromise that every check diderot had would wave through: a leaked registry
token, a skill republished with one sentence added, and an `update` that reports `ok` because the
bytes on disk match the bytes that were locked. The answer sketched there was a pinned signer. Here
it is as a project actually writes it — three lines added to a skill in `diderot.yaml`:

```yaml
skills:
  - name: making-of
    source: oci://ghcr.io/sunix/skills/making-of
    version: "^1.0.0"
    signer:
      identity: https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
      issuer: https://token.actions.githubusercontent.com
```

Both halves are required, and the pair is the point. An identity on its own is a string anyone can
put in a certificate: nothing stops somebody running a workflow at the same path in their own
repository, or minting a token from an issuer of their own. The issuer says who is entitled to
assert that identity, and only the two together name a publisher.

With that in place the ordinary update gains four characters, and the interesting run is the other
one. This is the real message, from the test that produces it:

```console
$ diderot update
error: Skill 'making-of': 127.0.0.1:32779/skills/wrong-signer@sha256:04b1542e717d… is signed,
       but not by the expected signer.
       expected  https://github.com/attacker/tools/.github/workflows/release.yml@refs/heads/main
       found     untrusted-sa@sigstore-conformance.iam.gserviceaccount.com
       Nothing was written.
```

Two properties of that output are deliberate. It names **both** identities, because a refusal that
says only *"does not match"* leaves a human unable to tell an attack from a publisher who renamed a
workflow file. And the last line is the whole design: `diderot.lock` is untouched, so `install` keeps
serving the version the project already had.

## The code: an API that cannot be called unpinned

Part seven left `verifyDigest` with an empty policy and called it the actual gap:
`VerificationOptions.builder().build()` checks that a signature is genuine and never asks whose it
is. The fix is not a new parameter with a default — it is a signature that cannot express the old
behaviour:

```java
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
```

There is no two-argument overload, which is the part worth deciding on purpose. Had one been left
for convenience, it would have been the shortest call available and every future caller would have
reached for it. The narrow API is what makes "this was signed" impossible to confuse with "this was
signed by the workflow this project trusts" — the two statements part seven spent a section
separating.

Underneath, the matcher is the pair from the manifest, with `CertificateMatcher.fulcio()` reading the
certificate's subject alternative name and the issuer extension that part seven printed with
`openssl`. Nothing in this code decodes X.509 by hand.

## The check: one place, and it fails closed

The policy lives in `Workspace`, where resolution happens:

```java
private Signer verifySigner(String name, Signer expected, String repository, String digest, Path content)
        throws IOException {
    if (expected == null) {
        return null;
    }
    if (expected.identity == null || expected.issuer == null) {
        throw new IOException("Skill '" + name + "': signer needs both an identity and an issuer");
    }
    String bundle = oci.fetchSignature(repository, digest)
            .orElseThrow(() -> new IOException("Skill '" + name + "': " + repository + "@" + digest
                    + " carries no signature, but diderot.yaml pins a signer.\n"
                    + "       expected  " + expected.identity + "\n"
                    + "       Nothing was written."));
    try {
        signing.verifyDigest(ContentDigest.sha256Of(content), bundle, expected.identity, expected.issuer);
    } catch (IOException e) {
        throw new IOException("Skill '" + name + "': " + repository + "@" + digest
                + " is signed, but not by the expected signer.\n"
                + "       expected  " + expected.identity + "\n"
                + "       found     " + Signing.signerOf(bundle) + "\n"
                + "       Nothing was written.", e);
    }
    return new Signer(expected.identity, expected.issuer);
}
```

Four decisions are in that method. **What gets verified is the content**, recomputed from the
directory that was just pulled rather than taken from the lock or from the registry — so the check
binds to the bytes about to be installed, and a registry that serves a different manifest for the
same tag cannot slip past it. **No pin means no check**: a skill with no `signer:` is resolved
exactly as before, because a tool that required signatures on day one would simply not be adopted —
almost nothing in the ecosystem is signed yet. **Every failure throws**, rather than warning and
continuing: a warning in a CI log is indistinguishable from the attack it is meant to stop, and the
throw happens before `Yaml.write`, which is what makes *nothing was written* true rather than
aspirational. And **the signer is recorded in the lock**, so `diderot.lock` says who was verified
instead of only what was installed.

It runs in two places, and the second one is the one I nearly forgot. `update` checks at resolution
time, which covers the project doing the updating. `install` checks again, because a lockfile arrives
from a teammate or a CI cache as often as it is produced locally — trusting that whoever wrote the
lock ran the check would make the whole thing optional in exactly the situation where it matters.

### What a consumer needs at run time, which is less than it looks

Publishing needs Fulcio and Rekor. Verifying, it turns out, needs neither — and that is worth showing
rather than asserting, because it is the property that decides whether a package manager can check
signatures at all. `KeylessVerifier.verify` has no HTTP client in it: the certificate chain is
checked against Fulcio's root, the identity against the matchers, the signature against the digest,
and the log entry against Rekor's public key. Every input to that is either in the bundle or in the
trust root.

Which leaves the trust root, and there the measurement is less flattering. Same bundle, same warm
cache, twice — the second run inside a network namespace with no connectivity at all:

```console
$ ./mvnw -o test -Dtest=… -Dphase=verify
VERIFY_RESULT ok in 1750ms

$ unshare -rn ./mvnw -o test -Dtest=… -Dphase=verify
VERIFY_RESULT failed: IOException: Could not build a sigstore verifier: TUF repo failed to update
  caused by SigstoreConfigurationException: TUF repo failed to update
  caused by UnknownHostException: tuf-repo-cdn.sigstage.dev
```

So the answer to *"does a user need access to sigstore, or only once?"* is: **once in principle,
every time in practice.** sigstore-java refreshes the TUF metadata when its last refresh is more
than a day old, but it keeps that timestamp in memory — and a CLI process starts with no memory of
anything, so every `diderot update` with a pinned signer reaches for the CDN.

It should not have to. TUF metadata carries its own expiry, which is precisely what makes offline use
safe rather than a shortcut; the cached `timestamp.json` on this machine was good for about a week
from the day it was fetched. The rule to implement is TUF's own — use the cached trust root while it
has not expired, refuse when it has — rather than "refuse whenever the CDN is unreachable". That is
[#44](https://github.com/sunix/diderot/issues/44), and it matters because `install` from a warm
content cache is otherwise an offline operation, which a pinned signer currently takes away.

## Proof: five ways to arrive, two of them refusals

Real registry in a container, real signatures against sigstore's staging instance, real certificates
— no mocks anywhere in this file. The one that carries the argument is the wrong-signer case:

```java
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
```

It signs with the conformance identity and pins a different one, so everything about the signature is
genuine and only the *who* is wrong — which is precisely the leaked-token scenario, reproduced. The
last assertion is the one that would catch a regression nobody else would notice: `diderot.lock` must
not exist afterwards.

The other four cover the shape around it: a correct pin verifies and lands in the lock with
`signer ok` in the output, a skill with no signature at all is refused with a different message, an
unpinned skill is resolved without any check, and `install` re-runs the check on a lockfile it did
not write.

```console
$ ./mvnw test -Dtest=SignatureVerificationTest
[INFO] Running org.sunix.diderot.core.SignatureVerificationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 16.50 s
[INFO] BUILD SUCCESS
```

Sixteen seconds, most of it network: four skills pushed, three of them signed for real against
Fulcio and Rekor, each signature a certificate issued and a transparency-log entry written.

## What this leaves open

**Never-regress.** The rule from [#25](https://github.com/sunix/diderot/issues/25) says a skill that
was signed and turns up unsigned is a downgrade rather than an absence. Today the pin drives
everything: pinned means checked, unpinned means not. The lock now records the signer it verified,
which is the piece that rule needs, but nothing yet compares an unpinned skill against what the lock
remembers.

**Discovering a signer instead of typing one.** [Part six](06-add-and-remove.md) argued that `add`
exists so an identity can be shown and accepted rather than copied from somewhere — `diderot add`
holds the signature at exactly the right moment. That is the next step, and it is what makes this
feature usable by someone who does not already know the string to paste.

**Signing a real skill, in CI.** Everything above runs against sigstore's staging instance with a
published test token. The production path needs the ambient GitHub token, which only exists when a
workflow asks for it: ai-skills'
[publish workflow](https://github.com/sunix/ai-skills/blob/main/.github/workflows/push-skill-to-oci.yml)
declares `contents: read` and `packages: write` and not `id-token: write`, so that one line of YAML
is what stands between this and a skill anyone can verify.

**And git-sourced skills are still unsigned territory.** The signature rides in an OCI manifest
annotation; a skill installed from a git repository has no manifest to ride in. The content digest
it attests is transport-independent — that was the point — so the same bundle would verify, given a
convention for where it travels. A file beside the skill, excluded from the digest, is the obvious
candidate and is not designed yet.
