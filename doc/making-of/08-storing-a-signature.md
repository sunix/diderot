# Where a signature lives in a registry

*Part eight of [diderot's making-of](../../MAKING-OF.md): a registry has nowhere to put a signature,
so how does one get stored and found again from nothing but a digest — and what happens when someone
asks why it has to be found at all.*

> The step after [part seven](07-signing-foundations.md), which got sigstore compiling into a native
> binary and left the bundle with nowhere to go. This chapter builds the answer twice: once the way
> the container ecosystem does it, then again after a review question showed that half of that work
> was the cost of a decision rather than a requirement. Checking the signature — pinning an identity,
> failing closed when it changes — is the step after this one, and it is
> [part nine](09-verifying-the-signer.md).

## Publishing a signature is easy, finding it again is not

### What has to work

Two questions, in this order. First, **where does the signature go** when `diderot push --sign`
produces one — a registry has to hold it somewhere. Second, **how is it found again**: someone runs
`diderot update`, and from nothing but the reference they declared —
`oci://ghcr.io/sunix/skills/making-of`, resolved to a tag and a digest — diderot has to locate the
signature belonging to that digest and check it.

The second is the hard one, and it is not a cryptographic difficulty but a lookup problem: a registry
holds manifests, and you reach a manifest one of exactly two ways — by a tag somebody chose, or by
its digest. There is no third slot labelled "things related to this one". Which is why the two
questions cannot be answered separately: **how it is stored is decided entirely by what makes it
findable.**

### Two artifacts that know nothing about each other

Before any linking, the thing I had to settle first:

> Once a skill is signed, is there still one artifact in the registry, or two?

Two. Nothing is bolted onto the skill: the signature bundle is pushed as **its own ordinary
artifact**, in the same repository, with its own manifest and its own digest. So after
`push --sign`, this is what is actually sitting there — the skill, pushed first, addressed by the
digest that ends up in `diderot.lock`:

```json
// manifest A — the skill, at sha256:8b81085393c4…
{
  "mediaType": "application/vnd.oci.image.manifest.v1+json",
  "artifactType": "application/vnd.diderot.skill.v1",
  "layers": [ { "…": "the skill directory, one tar+gzip layer" } ]
}
```

and the bundle, pushed afterwards, at a digest of its own:

```json
// manifest B — the signature, at sha256:4d7e91ff02ab…
{
  "mediaType": "application/vnd.oci.image.manifest.v1+json",
  "artifactType": "application/vnd.dev.sigstore.bundle.v0.3+json",
  "layers": [ { "…": "the sigstore bundle itself" } ]
}
```

Read those two as the registry sees them: **nothing relates them**. A says nothing about B, B says
nothing about A, and neither digest is derivable from the other. They are two unrelated uploads that
happen to share a repository. A consumer who resolves the skill gets A's digest and has no way to
learn that B was ever pushed — which is the lookup problem, now in front of us rather than described.

### The one field that links them, and why only one direction exists

OCI 1.1's contribution is a single field, added to **B** — the same manifest as above, with one line
it did not have:

```json
// manifest B — the signature, as it is actually pushed
{
  "mediaType": "application/vnd.oci.image.manifest.v1+json",
  "artifactType": "application/vnd.dev.sigstore.bundle.v0.3+json",
  "subject": { "digest": "sha256:8b81085393c4…" },   // ← added: A's digest
  "layers": [ { "…": "the sigstore bundle itself" } ]
}
```

B is built that way from the start, so the line is not an edit to something already published —
which matters, since editing a published manifest is precisely what cannot be done. A is not shown
again because A genuinely does not change: same document, same digest, as before anything was
signed.

The [distribution spec](https://github.com/opencontainers/distribution-spec/blob/main/spec.md) calls what that produces a referrers list, in its
[definitions](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#definitions):

> **Referrers List**: a list of manifests with a `subject` relationship to a specified digest.

It reads: *this signature concerns the manifest whose digest is `sha256:8b81085393c4…`*. And the
direction is not a choice. **B can only exist after A does** — you sign a digest, so the digest
has to exist first, which means at the moment A is written there is nothing yet to point at.

The obvious repair is to go back and add the pointer to A afterwards, once B exists. That fails too,
and it is worth knowing exactly why, which was the other question I could not answer from memory:

> When you edit the manifest you are not touching the content, so are you touching the digest — or
> does the digest cover the content *and* the manifest? If it is only the content, wouldn't the
> normal direction be simpler?

It covers the manifest. A registry addresses a manifest by the hash of **the manifest document
itself**, not of the content it points at — one `curl` and one `sha256sum` to settle:

```console
$ curl … https://ghcr.io/v2/sunix/skills/making-of/manifests/1.1.0 -D- -o manifest.json
docker-content-digest: sha256:8b81085393c43ba0c46dcfe987f2713dd4ea8b31b881fbd5025a31b9e46eaeb4
$ sha256sum manifest.json
8b81085393c43ba0c46dcfe987f2713dd4ea8b31b881fbd5025a31b9e46eaeb4
```

The same number, and `8b81085393c4…` is what the lock pins and what the signature attests to. So
editing A rewrites the document, changes its hash, and breaks both at once: every lock pinning that
digest now resolves to a different manifest, and the signature attests to a manifest nobody can fetch
any more. The content is untouched by this — the skill's bytes sit in a layer with a digest of their
own, and diderot keeps its tree digest in an annotation on that manifest — but content is not what
you address.

So A stays as it is, B points at A, and the consequence is the lookup problem in full: **holding the
skill's manifest tells you nothing about whether a signature exists.**

### The referrers API is exactly the missing lookup

Which is what the API is for. Give the registry a digest, and it answers with the manifests that
declare themselves about it:

```
GET /v2/<name>/referrers/<digest of A>   →   an index listing B
```

The registry indexes those `subject` fields backwards, so a consumer holding only A's digest — all a
lock ever contains — can ask *"is there anything about this?"* without knowing in advance that a
signature was ever made, or what it would have been called. Anything else attached later, an SBOM or
a build attestation, arrives through the same endpoint.

### Then I tried it on the registry that matters

That is what [PR #6](https://github.com/sunix/diderot/pull/6) implemented, and it works — against
the registry it was tested on. ai-skills publishes to ghcr.io, so that is where it had to work:

```console
# ghcr.io, on two digests diderot resolves and pulls every day
referrers/sha256:8b81085393c4…  →  404  {"code":"MANIFEST_UNKNOWN"}
referrers/sha256:b61d9507ba16…  →  404  {"code":"MANIFEST_UNKNOWN"}
```

`MANIFEST_UNKNOWN` is a genuinely ambiguous answer: it could mean the endpoint does not exist, or it
could mean I was asking about a digest that does not. A bare 404 proves neither, and reading one as
proof is a mistake I have made in this repository before. So the same request went to
[zot](https://zotregistry.dev/), which does implement the API, against a freshly pushed artifact with
nothing attached to it at all:

```console
# zot, same request shape, no referrers pushed
referrers/sha256:accc3af6f97a…  →  200  {"mediaType":"…image.index.v1+json","manifests":[]}
```

An empty list, not an error — and [Listing Referrers](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-referrers) says that is the
only correct answer:

> If a query results in no matching referrers, an empty manifest list MUST be returned. […] If the
> registry supports the referrers API, the registry MUST NOT return a `404 Not Found` to a referrers
> API request.

Which looked like it settled the matter: ghcr answers 404 for digests it serves on every other
endpoint, and a registry implementing the API is forbidden from doing that.

Except that review caught the hole in it:

> Maybe it is just empty. Did we test a digest that actually *has* a referrer?

No — and that is the weak point. Every digest I tried has nothing attached to it, so strictly what I
measured is *404 when the answer would be empty*. Turning that into non-support leans on the spec's
`MUST NOT`, which is to say it assumes ghcr is conformant about referrers in order to prove it is
not. That reasoning is circular, and it is exactly the shape of the 401 I misread two chapters ago.

Closing it properly needs a manifest with a `subject` actually pushed to ghcr, which needs a token
with `write:packages` this machine does not have. Two things can be said meanwhile. The evidence all
points one way: hundreds of `sha256-…​.sig` tags sit in the ghcr repositories of cosign, Flux and
Trivy, which is what clients produce when they store signatures by naming convention. And that is
*not* proof either — cosign uses the tag scheme by default even where referrers work, so those tags
are consistent with ghcr lacking the API and equally consistent with nobody having asked it for one.
I pulled one of those signature manifests to check whether it carried a `subject` that a conformant
registry would have had to list, and it does not: `schemaVersion`, `config`, `layers`, and nothing
else.

So: **ghcr.io very probably does not implement the referrers API, and this has not been proven.** The
experiment that settles it is one diderot will run on its own, the first time the publish workflow
pushes a signed skill, because [Pushing Manifests with Subject](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-manifests-with-subject)
makes the answer arrive unasked:

> When processing a request for an image manifest with the `subject` field, a registry implementation
> that supports the referrers API MUST respond with the response header `OCI-Subject: <subject
> digest>` to indicate to the client that the registry processed the request's `subject`.

One header on the push response, present or absent, and no interpretation needed.
Until then the transport is chosen on the safe assumption rather than on a measurement, which is the
right way round: the fallback works on registries that do support referrers, and the reverse is not
true.

### And the spec had already thought about it

This is where I expected to be inventing a workaround, and found the spec had written one.
[Unavailable Referrers API](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#unavailable-referrers-api) makes falling back a requirement rather
than a permission:

> A client querying the referrers API and receiving a `404 Not Found` MUST fallback to using an image
> index pushed to a tag described by the referrers tag schema.

The schema is a name computed from the digest: the algorithm, a `-`, and the encoded part, so a
subject at `sha256:8b81085393c4…` has its referrers list at the tag `sha256-8b81085393c4…`. Nothing
is discovered; the client works the name out and pulls it. It needs nothing from the registry beyond
pushing and pulling a tag, which is the one capability every registry has — and the cost is that
clients now maintain that list themselves, which the
[Referrers Tag Schema](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#referrers-tag-schema) is candid about:

> Maintaining the content of this tag is the responsibility of clients pushing and deleting image
> manifests that contain a `subject` field. […] multiple clients could attempt to update the tag
> simultaneously resulting in race conditions and data loss.

One thing to settle later rather than quietly: cosign has an **older, different** convention of its
own — `sha256-<hex>.sig`, holding the signature artifact directly rather than an index of referrers.
Following the spec is the more correct choice. Following cosign's *tag name* is tempting for interop
and buys less than it looks like it does: `cosign verify` needs cosign's payload layout too, not just
its naming, so borrowing the tag alone would produce something that looks interoperable and is not.

## The question that dissolved the problem

At this point I had the whole machinery mapped, and a review question landed that I did not have a
good answer to: **why are there two artifacts at all?** You sign a *content*, not a container — and
if the skill later moves to another registry, or to a tarball on an FTP server, the same signature
ought to still mean something.

Following that through, none of the machinery above is something OCI imposes. It is the consequence
of one choice made three sections earlier: **we signed the manifest digest.** A signature over a
manifest cannot be stored inside that manifest, because storing it would change the digest it
attests. So it has to live elsewhere; living elsewhere means it has to be found; being found is the
referrers API, its 404, the tag schema, and an index clients maintain by hand with the race the spec
warns about. This chapter up to here is the price of that one decision.

Sign the **content** instead and the circularity is gone — the bundle can ride inside the artifact,
because what it attests is not the document it is stored in.

### What everyone else does, which I should have checked first

[Helm](https://helm.sh/docs/topics/provenance/), whose homework this project has been copying since
part one, signs content: its provenance file carries `Chart.yaml`, **the SHA-256 of the `.tgz`** and
a file → SHA-256 map, signed with OpenPGP. In an HTTP repository it sits beside the chart as
`<chart>.tgz.prov`; pushed to a registry it becomes **another layer in the same OCI manifest**
(`application/vnd.cncf.helm.chart.provenance.v1.prov`), which `helm pull --verify` locates by media
type.

[PyPI's attestations](https://peps.python.org/pep-0740/) sign the distribution's filename and
SHA-256 in an in-toto statement, served beside the file by the index. Maven Central publishes a
detached `.asc` per artifact. RPM puts the signature in the package header, JARs in `META-INF`. The
pattern is consistent: **package managers sign content and keep the signature with it.**

The outlier is the container world — cosign signing an image's manifest digest, stored as a separate
artifact — and its reasons are real, but they are *its* reasons: the signed object is the registry
object, tags are mutable, an image is usually an index over per-architecture manifests, and third
parties attest **after** publication (a scanner, a security team, an SBOM added later). That last
one is precisely what `subject` and the referrers API exist for, and it is what this design gives
up. diderot signs what it publishes, when it publishes it, and needs nobody to attach anything
afterwards.

A skill is a package, not an image. It should be signed like one.

## What gets signed: a new digest, because the one we had is SHA-1

Signing content means naming the content, and diderot already has a name for it: `tree:<sha>`, the
git tree hash from part one that `install` and `status` compare against. Reaching for it was the
obvious move, and it is the wrong one, for a reason worth spelling out because it reaches further
than signing.

SHA-1's **collision resistance** is broken — two different inputs with the same hash were produced in
2017, and by 2020 a *chosen-prefix* collision, where both sides start from content the attacker
picked, cost around $45,000 of rented GPU time and less since. What is **not** broken is preimage
resistance: given a hash, nobody can produce content matching it. That distinction decides how much
this matters:

- The leaked-token attack from [part seven](07-signing-foundations.md) is untouched. Passing it
  would mean matching the digest of a skill *already published*, which is a second preimage, and
  that remains out of reach.
- What a collision buys is stealth, and only for a **publisher**: craft two directories that hash
  the same, have the harmless one signed and audited, serve the other. Identical `tree:`, so
  `status` reports no drift and the signature still verifies. A skill directory can hold images or
  binaries, which is where collision padding sits without looking strange.

And a detail specific to this project: git itself stopped hashing with bare SHA-1 in 2.13 — it uses
sha1dc, which detects the known attack patterns and refuses the object.
[`GitTreeHasher`](../../src/main/java/org/sunix/diderot/core/GitTreeHasher.java) reimplements git's
hashing in pure Java and has no such detection, so it would accept a pair real git would reject.

A signature should not inherit any of that, so signing gets its own identity —
[`ContentDigest`](../../src/main/java/org/sunix/diderot/core/ContentDigest.java), a sha256 over a
flat sorted listing. The walk is deliberately the tree hasher's walk, so both digests always describe
the same set of files:

```java
private static void collect(Path dir, String prefix, List<String> lines) throws IOException {
    try (var children = Files.list(dir)) {
        for (Path child : children.toList()) {
            String name = child.getFileName().toString();
            if (name.equals(".git")) {
                continue;
            }
            String path = prefix + name;
            if (Files.isSymbolicLink(child)) {
                byte[] target = Files.readSymbolicLink(child).toString().getBytes(StandardCharsets.UTF_8);
                lines.add(line("120000", sha256(target), path));
            } else if (Files.isDirectory(child)) {
                collect(child, path + "/", lines);
            } else {
                String mode = Files.isExecutable(child) ? "100755" : "100644";
                lines.add(line(mode, sha256(Files.readAllBytes(child)), path));
            }
        }
    }
}
```

`tree:` keeps its job — drift detection, and the lock's content identity — and the new digest exists
to be signed. Two identities is one more than ideal; migrating the lock to sha256 is
[#43](https://github.com/sunix/diderot/issues/43).

### Proof: an oracle rather than a fixture

A hash test that asserts a constant proves only that the code still does what it did yesterday. Part
one tested the tree hasher against **real git**; no external tool computes this digest, so the oracle
is an independent reimplementation in shell — different language, same specification:

```java
@Test
void matchesAnIndependentShellImplementation() throws Exception {
    Path skill = tmp.resolve("skill");
    Files.createDirectories(skill.resolve("templates"));
    Files.writeString(skill.resolve("SKILL.md"), "---\nname: making-of\n---\nInstructions.\n");
    Files.writeString(skill.resolve("templates/MAKING-OF.md"), "# template\n");
    Files.writeString(skill.resolve("README.md"), "read me\n");

    // sha256 of: the format line, then "<mode> <sha256> <path>\n" per file, sorted by path.
    String oracle = Git.run(skill, "sh", "-c",
            "{ printf 'diderot-content-v1\\n'; "
                    + "find . -type f | sed 's|^\\./||' | LC_ALL=C sort | "
                    + "while read -r f; do printf '100644 %s %s\\n' "
                    + "\"$(sha256sum \"$f\" | cut -d' ' -f1)\" \"$f\"; done; } | "
                    + "sha256sum | cut -d' ' -f1").trim();

    assertEquals("sha256:" + oracle, ContentDigest.sha256Of(skill));
}
```

Five more cases pin down what "different content" means: one byte, the executable bit, a file moved
between directories, a symlink that starts pointing elsewhere, and `.git` staying invisible so a
checkout and an export sign the same.

## The code, after

The write side is now an argument to `push`:

```java
public String push(Path skillDir, String reference, String bundleJson) throws IOException {
    Map<String, String> annotations = new LinkedHashMap<>();
    annotations.put(TREE_DIGEST_ANNOTATION, "tree:" + GitTreeHasher.treeSha(skillDir));
    if (bundleJson != null) {
        annotations.put(SIGNATURE_ANNOTATION, Base64.getEncoder()
                .encodeToString(bundleJson.getBytes(StandardCharsets.UTF_8)));
    }
    Manifest manifest = registryFor(reference).pushArtifact(
            ContainerRef.parse(reference),
            ArtifactType.from(SKILL_ARTIFACT_TYPE),
            Annotations.ofManifest(annotations),
            land.oras.LocalPath.of(skillDir));
    return manifest.getDescriptor().getDigest();
}
```

The read side is one manifest fetch and a base64 decode:

```java
public Optional<String> fetchSignature(String repository, String digest) {
    Manifest manifest = registryFor(repository)
            .getManifest(ContainerRef.parse(repository).withDigest(digest));
    Map<String, String> annotations = manifest.getAnnotations();
    if (annotations == null) {
        return Optional.empty();
    }
    return Optional.ofNullable(annotations.get(SIGNATURE_ANNOTATION))
            .map(encoded -> new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
}
```

That is the whole transport. Deleted along the way: `pushSignature`, the referrers-tag index with its
read-back-then-write, the exception used as a signal, the artifactType constants — and
`SignatureTransportTest`, whose two registries proved something that is no longer done. What survives
is this chapter's first half, which is the half worth keeping: the measurement stands, and
`sha256-<hex>` is still the right answer to *"how do you attach something to a manifest you cannot
change"*. It simply stopped being our question.

One deliberate difference from Helm: it uses a layer, this uses a manifest annotation. The reason is
`cachedPull`, which extracts every layer — a provenance layer would land inside the skill directory
and change the very content digest it attests. An annotation stays outside the content, and 7.6 KB of
base64 is nothing against a registry's manifest limit.

## Proof: one signature, two tags

The question that started the rework has a test of its own. Sign once, publish the same content
twice — as a release and as a floating tag — and resolve it through both:

```java
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
```

The first assertion is [#21](https://github.com/sunix/diderot/issues/21) reproduced on purpose,
sleep included: the SDK stamps a `created` annotation with second granularity, so two pushes in the
same second are byte-identical and two a second apart are not. Under the old design those two
manifest digests meant two signatures and two signature artifacts for identical bytes. Here the same
bundle comes back through either tag, because what it attests is the content that both manifests
carry — and [part nine](09-verifying-the-signer.md) is where that bundle gets checked rather than
merely found.

## What `push --sign` prints now

Signing moved ahead of the push, because there is nothing left to wait for: the content is known
before the registry is involved.

```java
String bundle = null;
if (sign) {
    String contentDigest = ContentDigest.sha256Of(dir);
    bundle = Signing.production().signDigest(contentDigest);
    out.printf("signed %s (%d byte sigstore bundle)%n", contentDigest, bundle.length());
}
String digest = oras.push(dir, ref, bundle);
out.printf("pushed %s -> %s@%s%n", skillDir, ref, digest);
```

One artifact, one digest, and the bundle inside it.

## Two answers the lookup problem forced, before any feature code

Two questions from [#25](https://github.com/sunix/diderot/issues/25) got their design answers while
this was being worked out, both following the rule part six stated — the human is in control of an
agent's capabilities, and of trust.

**A skill that gains a signature later**: `update` should notice and report the identity it found —
as a proposal, never an automatic edit. Auto-adopting the first signature that appears buys little
anyway: whoever can push to a registry can sign with their own identity too.

**Unsigned skills stay supported**, and the naive rule — "verify if a signature exists" — is
downgrade-attackable as written: strip the signature and the check silently disappears. So the lock
must remember what it saw, and the rule becomes *never regress*: unsigned may stay unsigned, unsigned
may become signed (proposed, not adopted), but signed-with-pinned-signer that turns up unsigned or
differently-signed **fails closed**. Optional to adopt, impossible to lose by accident.

## What this chapter still owes

**The check.** Storing a signature and finding it again is not verifying it: nothing yet calls
`fetchSignature` from `update` or `install`, and `verifyDigest` is still the unpinned call from
[PR #6](https://github.com/sunix/diderot/pull/6). That is the next step, and the two design answers
above are what it implements — [#25](https://github.com/sunix/diderot/issues/25).

**The ghcr.io measurement**, which this chapter spent three sections on and no longer needs. Whether
ghcr implements the referrers API stopped being on anyone's path the moment the signature moved
inside the artifact: nothing diderot does asks that endpoint anything. The measurement stays
unfinished and stays interesting — it would settle whether the largest registry in the ecosystem is
OCI 1.1-conformant, and one push of a manifest carrying `subject` answers it — but it is now
curiosity rather than a blocker, which is a better place for it.

**Two content identities**, `tree:` for drift and sha256 for signatures, where one would do.
[#43](https://github.com/sunix/diderot/issues/43) is the migration, and it is not urgent precisely
because the signature already binds to the stronger of the two.
