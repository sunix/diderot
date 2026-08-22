# Sign at push, verify at pull

*Part three of [diderot's making-of](../../MAKING-OF.md): keyless signing lands on top of
M2's OCI backend — every `push` signs, every first pull verifies, no flag to skip either.*

## The goal: an unsigned artifact must not install, silently or otherwise

The target for this chapter, concretely: someone publishes a tampered skill — or just a
skill I never signed — to a registry, and a consumer points `diderot.yaml` at it.

```yaml
skills:
  - name: whatever
    source: oci://ghcr.io/someone/skills/whatever
    version: v1
```

`diderot update` on that manifest must **refuse**, loudly, before the content ever
touches disk or a lockfile:

```console
$ diderot update
error: No sigstore signature attached to ghcr.io/someone/skills/whatever@sha256:... —
refusing to trust unsigned OCI content.
```

And symmetrically, `diderot push` from me must produce something a consumer's `update`
accepts without any extra flag — signing has to be the default, not an opt-in, or the
refusal above means nothing.

## What landed

One new class, `oci/Signing.java` — the third external system diderot talks to, after
git (`GitCli`) and registries (`OrasClient`). sigstore-java's only stable public API is
**keyless** signing (`KeylessSigner`/`KeylessVerifier`): no private key file to generate,
back up, or leak — an OIDC identity gets a short-lived certificate from Fulcio, the
signature gets logged to Rekor's transparency log, and that's the proof. It's genuinely
the same default `cosign` moved to years ago, not a diderot invention.

`OrasClient.push()` grew two lines after the artifact push: sign the manifest digest,
attach the bundle as a referrer.

```java
String bundleJson = signing.signDigest(digest);
registry.attachArtifact(
        ContainerRef.parse(repository + "@" + digest),
        ArtifactType.from(SIGSTORE_BUNDLE_ARTIFACT_TYPE),
        LocalPath.of(bundleFile));
```

And `OrasClient.cachedPull()` — the one place both `update`'s resolution and `install`'s
materialization go through — grew one line *before* the pull:

```java
if (!Files.isDirectory(content)) {
    verifySignature(repository, digest);   // throws before a single byte is trusted
    ...pull...
}
```

That placement is the whole design: verification happens exactly once per digest, right
before it's first trusted, for *either* caller. A digest already in the local cache
doesn't get re-verified on every subsequent `install` — it doesn't need to, the cache is
content-addressed and was checked the moment it was born. But a fresh throwaway workspace
has a cold cache, so for the audience M1 called out — a remote agent spinning up with
nothing but a clone — `install` really does verify, every time it matters.

`verifySignature` itself is the referrer dance: ask the registry for anything of the
sigstore bundle type attached to this digest, refuse if there's none, pull the one bundle
found (it's a tiny JSON file, fetched the same way a skill directory is), hand it to
`Signing.verifyDigest`.

```java
Referrers referrers = registry.getReferrers(ref, ArtifactType.from(SIGSTORE_BUNDLE_ARTIFACT_TYPE));
if (referrers.getManifests().isEmpty()) {
    throw new IOException("No sigstore signature attached to " + repository + "@" + digest
            + " — refusing to trust unsigned OCI content.");
}
```

`Signing` itself signs and verifies **digests**, not files — `KeylessSigner.sign(byte[]
artifactDigest)` takes the raw sha256 bytes, which is exactly the manifest digest ORAS
already gives us, decoded from its `sha256:<hex>` form. The signature ties to the digest
a consumer resolves, not to some local file that could differ.

## The registry that quietly didn't support what it needed to

First real snag: the OCI round-trip test, still on `registry:2` from M2, threw this on
the very first signed push:

```
land.oras.exception.OrasException: Subject was set on manifest but not OCI subject
header was returned. Legacy flow not implemented
```

`registry:2` resolves to `docker/distribution` 2.8.3 — a project that predates the OCI
1.1 Referrers API entirely, the mechanism `attachArtifact` relies on to link a signature
to what it signs. I tried the obvious next step, `registry:3` (the CNCF successor), and
it failed the exact same way with its out-of-the-box config — the Referrers API exists in
that project but isn't switched on by a bare `docker run` with no config file. Rather
than go spelunking through `distribution/distribution`'s YAML schema for the right flag,
I reached for [zot](https://zotregistry.dev/), the CNCF registry built OCI-artifact-first
with referrers on by default — swapped the image, same test, green immediately. One line
changed in the test's `docker run` command; the lesson worth keeping is that "OCI
registry" is not one target, even among CNCF's own reference implementations.

## The token sigstore itself hands out for exactly this problem

Real keyless signing needs a real OIDC identity — normally an interactive browser flow,
which doesn't exist in an automated test (or in the sandbox I run in at all). sigstore-java's
own test suite solves this by hitting a public URL sigstore publishes on purpose:

```java
public static String fetch() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    HttpRequest request = HttpRequest.newBuilder(
            URI.create("https://storage.googleapis.com/sigstore-conformance-testing-token/"
                    + "untrusted-testing-token.txt")).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8)).body();
}
```

It's explicitly named "untrusted" and is only valid against sigstore's **staging**
instance, never production — which is exactly the safety property I wanted: real
cryptography, a real Fulcio-issued certificate, a real Rekor log entry, without writing a
single test artifact into the production transparency log. I couldn't depend on
sigstore-java's own `sigstore-testkit` module for this — it turns out to be Gradle-internal
and was never published to Maven Central — so `SigstoreConformanceToken` in diderot's test
sources just hits the same public URL directly, a few lines, no vendoring.

## Proof

`SigningTest` exercises the cryptography alone, no registry involved — sign a digest,
verify it against itself, then the fail-closed case that matters most: a valid signature
must not verify a *different* digest than the one it was made for.

```java
String signedDigest = sha256Of("what was actually signed");
String bundle = signing.signDigest(signedDigest);

String substitutedDigest = sha256Of("what an attacker wants installed instead");
assertThrows(IOException.class, () -> signing.verifyDigest(substitutedDigest, bundle));
```

`OciRoundTripTest` adds the case that started this chapter: an artifact pushed straight
through the raw ORAS client, bypassing `OrasClient.push()` and its signing step entirely
— the shape any unsigned or unrelated publisher's artifact would have.

```java
registry.pushArtifact(ContainerRef.parse(repository + ":v1"), LocalPath.of(skillDir));
// ...
var e = assertThrows(Exception.class, workspace::update);
assertTrue(e.getMessage().contains("No sigstore signature attached"));
```

All sixteen tests green, two of them doing genuine cryptographic work against sigstore's
real staging infrastructure — not mocked, not stubbed:

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in org.sunix.diderot.oci.SigningTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in org.sunix.diderot.oci.OciRoundTripTest
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
```

## The one thing I could not prove myself

Everything above is genuine, executed proof — against sigstore's staging instance. What
I could not run from this sandbox is the *production* path: I pushed the packaged CLI
against `ttl.sh` for real, and it correctly tried to open an interactive browser OIDC
flow — the same thing `cosign` does by default on a developer's own machine — and hung,
because there is no browser here to complete it. That's correct behavior, not a bug, and
I'd rather say so plainly than claim a production signature I didn't actually produce.
One real command is left for whoever reviews this with an actual browser at hand:

```console
$ diderot push skills/documentation/making-of oci://ttl.sh/some-name:1h
```

## A bug the packaging surfaced, not the logic

Running that same command also caught something the automated tests, run via Maven's own
classpath, never would have: the packaged `quarkus-run.jar` threw
`NoClassDefFoundError: org/apache/commons/logging/LogFactory` the instant signing tried
to build its TUF client. `commons-logging` is needed by the `httpclient` that
google-http-client's Apache transport pulls in (sigstore-java's TUF/Fulcio/Rekor clients
go through it) — present enough to satisfy Maven's own resolution, but missing from
Quarkus's assembled `lib/main/` until declared as a direct dependency. Added, repackaged,
confirmed fixed by getting past that line to the (expected, browser-shaped) hang instead.

## What this chapter leaves open

Signing only ever covers OCI sources — git sources still lean on git hosting's own
security model, a deliberate and unrevisited scope line from part two. Semver ranges over
registry tags and the welcoming `install` (`--frozen`, resolve-if-absent) are still queued
from earlier chapters, untouched by this one.
