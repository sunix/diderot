# Where a signature lives in a registry

*Part eight of [diderot's making-of](../../MAKING-OF.md): a registry has nowhere to put a signature,
so how does one get stored — and found again from nothing but a digest?*

> **Draft.** The work described here is not built. [Part seven](07-signing-foundations.md) got
> sigstore compiling into a native binary; this chapter is the question that comes next, written
> while the answer was being worked out rather than after. It gains its proof section when
> `OrasClient` learns to store and fetch a bundle, and the measurement it is missing —
> whether ghcr.io really lacks the referrers API — arrives with the first signed push.

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

That is what #6 implemented, and it works — against the registry #6 tested on. ai-skills publishes to
ghcr.io, so that is where it had to work:

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

A proof section. Everything above is design and measurement; none of it is running code yet. When
`OrasClient` gains `pushSignature` and `fetchSignature`, this chapter gets what the others have —
the code walked through, the tests that hold it, and a real signature pushed to a real registry and
fetched back.

Two things will be settled by that work rather than argued here. Whether ghcr.io implements the
referrers API, which a signed push answers for free through the `OCI-Subject` response header. And
which transport diderot writes: the spec's referrers index, cosign's tag, or both.
