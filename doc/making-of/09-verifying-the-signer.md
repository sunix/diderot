# Checking who signed it, and refusing when it was someone else

*Part nine of [diderot's making-of](../../MAKING-OF.md): storing a signature is not the same as
verifying it. This part pins the expected signer, checks every signature against that identity, and
fails safely — when verification fails, nothing is written.*

> In [part eight](08-storing-a-signature.md), we stored the signature with the artifact so it could attest to the skill's content.
>
> This part completes that work. It answers the question at the end of [part seven](07-signing-foundations.md): **“Do I trust this signer?”**

## The goal: reproduce the attack from part seven

Part seven described an attack that all of diderot's existing checks would have allowed: an attacker gets hold of a registry token, republishes a skill with one sentence changed, and runs `update`. The command reports `ok` because the files on disk match the files recorded in the lockfile.

The solution proposed there was to pin the expected signer. Here's what that looks like in a real project: add a `signer` section to the skill entry in `diderot.yaml`.

```yaml
skills:
  - name: making-of
    source: oci://ghcr.io/sunix/skills/making-of
    version: "^1.0.0"
    signer:
      identity: https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
      issuer: https://token.actions.githubusercontent.com
```

We need both values. The identity alone is not enough: someone could create a workflow with the same path in their own repository, or use a token from a different issuer. The issuer identifies who issued the token. Together, the identity and issuer identify the publisher we trust.

With this configuration, a successful `update` has only a small change in its output. The more interesting case is when the signature is valid, but belongs to someone else. Here's the actual error produced by the test:

```console
$ diderot update
error: Skill 'making-of': 127.0.0.1:32773/skills/wrong-signer@sha256:da3dd2ab277d… is signed,
       but not by the expected signer.
       expected  https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
       found     untrusted-sa@sigstore-conformance.iam.gserviceaccount.com
       Nothing was written.
```

The `expected` line shows the identity configured in `diderot.yaml`, exactly as written. The `found` line shows who actually signed the artifact — in this case, a sigstore test account used to simulate an attacker.

Two details matter here.

First, the error shows **both identities**, not just the result of the check. Simply saying “not the expected signer” would be just as safe: nothing would be installed either way. But it would leave the user with no clear next step.

The `expected` identity is already available in `diderot.yaml`. The `found` identity is harder to discover. diderot doesn't yet have a command to show who signed a skill ([issue #40](https://github.com/sunix/diderot/issues/40)). Without this information, the user would have to pull the artifact and inspect the signature bundle manually.

With both identities in the error message, the next step is much clearer. If the publisher simply renamed a workflow file, updating the pinned identity may be all that's needed. If the identity is unfamiliar, the user can investigate further before trusting it.

Second, the final line is essential: `diderot.lock` remains unchanged. The project can keep using the version it already had, rather than accidentally accepting the new one.

## The API: removing the call that checked too little

### What was there before, and what it accepted

Part seven restored `Signing` from the parked branch with this verification method — quoted from
`main`, since this step is what deletes it:

```java
public void verifyDigest(String digest, String bundleJson) throws IOException {
    …
    verifier.verify(rawDigestBytes(digest), bundle, VerificationOptions.builder().build());
}
```

Two arguments: the digest, and the bundle. `VerificationOptions.builder().build()` is an **empty
policy** — a list of certificate matchers with nothing in it. The verification is real: the
certificate chains to Fulcio, the Rekor entry is genuine, the signature covers that digest. The one
question it never asks is *whose certificate this is*.

That is easier to show than to argue, so it is a test. It signs with the conformance identity, runs
the old empty-policy check, and then asks the same bundle the question this chapter is about:

```java
@Test
void anEmptyPolicyAcceptsASignatureFromAnybody() throws Exception {
    String digest = sha256Of("what the old two-argument call verified");
    String bundle = signing.signDigest(digest);

    KeylessVerifier verifier = KeylessVerifier.builder().sigstoreStagingDefaults().build();
    verifier.verify(rawDigestBytes(digest), Bundle.from(new StringReader(bundle)),
            VerificationOptions.builder().build()); // no exception: nobody asked who signed

    assertThrows(IOException.class,
            () -> signing.verifyDigest(digest, bundle, PUBLISHER, ISSUER),
            "the same bundle, once the project says whose signature it expects");
}
```

The first `verify` returns normally. Nobody said which signer was expected, so a signature from a
sigstore test account is as acceptable as one from your release workflow — which is exactly the
scenario from the start of this chapter, passing.

### What changed

Rather than add the identity as an optional parameter, the two-argument method is **gone**, replaced
by one that cannot be called without saying who is expected:

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

Three things are worth pointing out in it.

**The identity and issuer are required, and checked before anything else.** Passing null or blank
fails immediately, so there is no accidental path back to an empty policy.

**There is no two-argument overload left.** This is the part that is about API design rather than
cryptography: a shorter method that still compiles is the one future code reaches for, including
code written months from now by someone who has not read this chapter. Keeping it would have left
two methods answering two different questions with nearly the same name:

- `verifyDigest(digest, bundle)` → "this signature is valid"
- `verifyDigest(digest, bundle, identity, issuer)` → "this signature was made by the workflow this
  project trusts"

Part seven spent a section on why those are not the same statement. Deleting the first one is how the
code stops offering the weaker answer.

**The comparison itself is sigstore's, not ours.** `VerificationOptions` takes certificate matchers,
and `CertificateMatcher.fulcio()` builds one that reads the certificate's Subject Alternative Name
and its issuer extension — the two fields part seven printed with `openssl`. The strings from
`diderot.yaml` go in; no X.509 parsing comes out.

Where it is called from is the subject of the next section: `update` and `install` both reach it
through `Workspace.verifySigner`, which is also the only place that decides what happens when it
throws.

### Proof

The test above is the proof for this section, and it carries both halves: the old call accepting a
stranger's signature, and the new one refusing the same bundle.

```console
$ ./mvnw test -Dtest=SigningTest
[INFO] Running org.sunix.diderot.oci.SigningTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 19.63 s
[INFO] BUILD SUCCESS
```

It stays in the suite rather than being deleted with the method it describes, because it is the
reason the method is gone. If somebody adds an unpinned path back one day, this test still says what
that buys.

## The check: one place, and fail safely

The policy is implemented in `Workspace`, where skills are resolved:

```java
private Signer verifySigner(String name, Signer expected, String repository, String digest, Path content)
        throws IOException {
    // Nothing pinned: resolve the skill exactly as it was resolved before signing existed.
    if (expected == null) {
        return null;
    }
    // Half a pin is a configuration mistake, not a request for a weaker check.
    if (expected.identity == null || expected.issuer == null) {
        throw new IOException("Skill '" + name + "': signer needs both an identity and an issuer");
    }

    // The bundle travels in the artifact's manifest. A skill carrying none cannot satisfy a pin,
    // so this is a refusal and not a fallback to "unsigned is fine".
    String bundle = oci.fetchSignature(repository, digest)
            .orElseThrow(() -> new IOException("Skill '" + name + "': " + repository + "@" + digest
                    + " carries no signature, but diderot.yaml pins a signer.\n"
                    + "       expected  " + expected.identity + "\n"
                    + "       Nothing was written."));
    try {
        // Hash the directory that was just pulled: the check binds to the bytes about to be
        // installed, never to a digest the registry or the lockfile supplied.
        signing.verifyDigest(ContentDigest.sha256Of(content), bundle, expected.identity, expected.issuer);
    } catch (IOException e) {
        // Name both identities. A refusal has to tell a human what to do next, not only that
        // something was wrong — and this throws before Yaml.write, so nothing is recorded.
        throw new IOException("Skill '" + name + "': " + repository + "@" + digest
                + " is signed, but not by the expected signer.\n"
                + "       expected  " + expected.identity + "\n"
                + "       found     " + Signing.signerOf(bundle) + "\n"
                + "       Nothing was written.", e);
    }
    // Handed back to the caller for the lockfile: it records who was verified, not just what.
    return new Signer(expected.identity, expected.issuer);
}
```

There are four important decisions in this method.

**1. Verify the content that will actually be installed.**  
We calculate the digest from the directory we just pulled. We don't trust the digest from the lockfile or registry as a substitute. This ties verification to the exact content we're about to install, so a registry can't serve different content under the same tag and bypass the check.

**2. No configured signer means no signature check.**  
If a skill has no `signer:` entry, diderot resolves it as before. Requiring signatures for every skill from day one would make the tool difficult to adopt, because most skills in the ecosystem aren't signed yet.

**3. Any verification failure stops the operation.**  
We don't print a warning and carry on. A warning in a CI log can easily be missed, which would defeat the purpose of the check. The exception is raised before `Yaml.write`, so “Nothing was written” describes what the code actually does.

**4. Record the verified signer in the lockfile.**  
`diderot.lock` now records who was verified, not just what was installed.

The check runs in two places. `update` verifies the signer while resolving the skill. `install` verifies it again, because a lockfile might come from a teammate or a CI cache rather than from the current machine. Trusting that whoever created the lockfile had already checked the signature would leave a gap precisely where the check is needed.

### What does a consumer need at runtime?

Publishing requires Fulcio and Rekor. Signature verification, however, doesn't need to contact either service directly. That's important: a package manager should be able to verify a signature without needing a network connection every time.

`KeylessVerifier.verify` doesn't use an HTTP client. It checks the certificate chain against Fulcio's root, compares the identity with the configured matchers, verifies the signature against the digest, and checks the transparency-log entry against Rekor's public key. These inputs are available in the bundle or in the trust root.

The trust root is where things get less straightforward. I ran the same verification twice with the same bundle and a warm cache. The second run took place in a network namespace with no connectivity:

```console
$ ./mvnw -o test -Dtest=… -Dphase=verify
VERIFY_RESULT ok in 1750ms

$ unshare -rn ./mvnw -o test -Dtest=… -Dphase=verify
VERIFY_RESULT failed: IOException: Could not build a sigstore verifier: TUF repo failed to update
  caused by SigstoreConfigurationException: TUF repo failed to update
  caused by UnknownHostException: tuf-repo-cdn.sigstage.dev
```

So, does a user need access to sigstore every time, or only once? **In principle, once; in practice, every time.**

`sigstore-java` refreshes its TUF metadata when the last refresh was more than a day ago. But it stores the refresh timestamp in memory. A CLI starts with a fresh process each time, so that timestamp is lost. As a result, every `diderot update` with a pinned signer tries to reach the CDN.

It shouldn't need to do that while the cached metadata is still valid. TUF metadata has an expiry date, which allows clients to use cached metadata safely until it expires. On my machine, the cached `timestamp.json` was valid for about a week after it was fetched.

The rule should follow TUF's expiry mechanism: use the cached trust root while it is still valid, and refuse to verify once it has expired. We shouldn't refuse simply because the CDN is unreachable. That's the problem tracked in [issue #44](https://github.com/sunix/diderot/issues/44). It matters because installing from a warm content cache should otherwise work offline, but pinning a signer currently prevents that.

## The tests: five scenarios, including two refusals

These tests use a real registry running in a container, real signatures created against sigstore's staging instance, and real certificates. There are no mocks in this test class.

The key test is the one where the skill was signed by the wrong identity:

```java
@Test
void updateFailsClosedWhenSomebodyElseSignedIt() throws Exception {
    String repository = publishSigned("wrong-signer");
    Path project = project("wrong-signer-consumer", repository, PUBLISHER, ISSUER);

    IOException refusal = assertThrows(IOException.class,
            () -> workspace(project, new StringWriter()).update());

    assertTrue(refusal.getMessage().contains("not by the expected signer"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains(PUBLISHER), "the refusal names what was expected");
    assertTrue(refusal.getMessage().contains(IDENTITY), "and who actually signed it");
    assertFalse(Files.exists(project.resolve("diderot.lock")),
            "nothing was written: the project keeps whatever it had");
}
```

The test signs with the conformance identity, then has the project pin the publisher it actually trusts — an identity nothing in the test can sign as. The signature is genuine; the signer is not the one the project expects. That is the leaked-token scenario from part seven, with the attacker played by the only identity a test is allowed to use.

The final assertion matters: it checks that `diderot.lock` was not created. Without it, a test could confirm that an error was reported while missing the more important bug — writing the lockfile anyway.

The other four tests cover the remaining cases:

- A correctly pinned signer is accepted and recorded in the lockfile, with `signer ok` in the output.
- A skill with no signature is rejected when a signer is pinned.
- A skill without a pinned signer is resolved without signature verification.
- `install` repeats the check for a lockfile it didn't create.

Here's the test run:

```console
$ ./mvnw test -Dtest=SignatureVerificationTest
[INFO] Running org.sunix.diderot.core.SignatureVerificationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 16.50 s
[INFO] BUILD SUCCESS
```

The tests took 16 seconds, most of which was network time. Five skills were pushed, three of them signed against Fulcio and Rekor for real. Each signature involved issuing a certificate and writing an entry to the transparency log.

## What's still missing?

**Preventing downgrades.**  
[Issue #25](https://github.com/sunix/diderot/issues/25) defines a rule: if a skill was previously signed but is now unsigned, that should be treated as a downgrade, not as if no signature had ever existed.

Right now, the configuration controls the check: a pinned signer means we verify; no pin means we don't. The lockfile now records the signer that was verified, which gives us part of what we need. But diderot doesn't yet compare that recorded signer with a skill that no longer has a pin.

**Discovering a signer instead of asking users to type one.**  
In [part six](06-add-and-remove.md), I explained that `add` should show users an identity they can review and accept, rather than making them copy an identity from somewhere else. `diderot add` has access to the signature at exactly the right moment. Supporting that workflow is the next step, and it should make signer pinning easier to use for people who don't already know which string to enter.

**Signing a real skill in CI.**  
Everything described above runs against sigstore's staging instance with a published test token. For production, the workflow needs a GitHub-issued token. GitHub only provides that token when the workflow requests it.

The [ai-skills publishing workflow](https://github.com/sunix/ai-skills/blob/main/.github/workflows/push-skill-to-oci.yml) currently declares `contents: read` and `packages: write`, but not `id-token: write`. Adding that permission is the missing step before a skill can be signed in CI and verified by consumers.

**Supporting skills installed from Git.**  
For OCI artifacts, the signature is stored in an OCI manifest annotation. A skill installed directly from a Git repository doesn't have that manifest, so there's nowhere to store the signature in the same way.

The signature attests to the content digest, independently of how the content is transported. That means the same signature bundle could work for Git-sourced skills too, as long as we agree on where to put it. A separate file alongside the skill, excluded from the content digest, looks like a reasonable option — but that design still needs to be worked out.
