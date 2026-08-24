# Signing works, and waits for the users who need it

*Part three of [diderot's making-of](../../MAKING-OF.md): a working, tested signing
feature that isn't landing in `main` yet — because v1 is about developer experience, and
signing is what the enterprise story will need.*

## The goal: an unsigned artifact must not install, silently

After M2 (OCI push/pull), the obvious next target: someone publishes a tampered skill —
or just one I never signed — and a consumer's `diderot.yaml` points at it.

```yaml
skills:
  - name: whatever
    source: oci://ghcr.io/someone/skills/whatever
    version: v1
```

`diderot update` on that manifest should refuse, loudly, before the content ever touches
a lockfile:

```console
$ diderot update
error: No sigstore signature attached to ghcr.io/someone/skills/whatever@sha256:... —
refusing to trust unsigned OCI content.
```

And `diderot push` should make that refusal painless for legitimate skills — signing has
to be automatic, not an opt-in flag, or the refusal above breaks the normal case too.

## What it actually did, from the user's side

The workflow was meant to be invisible:

- **`diderot push`** signed itself, automatically — no key to generate, no file to guard.
  Proving *who* is signing needs an identity, though, and where that comes from decides
  whether "invisible" holds; the next section is entirely about that, because it's where
  this got interesting.
- **`diderot update` / `diderot install`** verified automatically, with nothing to turn
  on and nothing to type. The very first time either command touched a given piece of
  content, it checked the signature before trusting a single byte of it — silently, when
  everything was fine.
- And when something *wasn't* fine — content with no signature at all, or a signature
  that didn't match what was actually being installed — `update`/`install` refused
  outright, with a clear error, instead of installing it anyway.

The verification half is not theoretical: it ran against **sigstore's staging
infrastructure** — real Fulcio-issued certificates, real Rekor transparency-log entries,
no mocks — twice over, once in isolation and once through the full push-then-install
cycle, green every time, including the case that matters most: a signature made for one
piece of content does not accept a different one.

The one thing I never got to run is the *production* signing path, and it's worth saying
plainly rather than letting the green tests imply otherwise. Signing against the real
public-good instance from a personal machine needs an interactive browser login, and
there is no browser in the sandbox this was built in — the attempt hung until I killed
it. So: the cryptography, the refusals, and the whole verify side are proven; a real
production signature from a laptop is not, and someone with a browser would have to be
the one to confirm it. That work still exists, parked on the `feat/m2b-signing` branch
behind the now-closed [PR #6](https://github.com/sunix/diderot/pull/6) — not deleted,
ready to pick back up.

One real snag along the way, worth being precise about: it was never the *signature*
that a test registry struggled with — the actual signing and verifying worked correctly
standing entirely on its own, before a registry ever entered the picture. What tripped
up the first two registries I tried locally was one specific, generic mechanism OCI
registries can use to **link** a signature (or any other attachment — an SBOM, for
instance) to the artifact it belongs to — the newest of several ways to do that linking,
which the library defaulted to. Two registries I tried didn't support that particular
mechanism; swapping in a third one that does fixed it immediately, with the signing and
verification code itself unchanged. (Older, still widely used linking conventions exist
and don't need any of that — more on this below, once quay.io comes up.)

## Where the identity actually comes from

"Sign" sounds like one thing; in practice it means "prove who's signing," and there are a
few genuinely different ways to answer that, each with a different concrete workflow.

**A person, on their own machine — a browser popup.** This is what happens by default
outside of any automation: no identity is available anywhere, so the tool opens a browser
to log in with an email/Google/GitHub account, and that identity ends up on the
certificate. Note there's no "log in once and forget it" here — nothing caches that
identity to disk between runs, and the certificate Fulcio hands back is deliberately
short-lived (minutes), so every signing run needs the identity again. This is the *last
resort* in the lookup order, not the primary design: sigstore-java looks for an
already-provided token first, a CI-native identity second, and only then falls back to
the browser. [Chainguard has a good walkthrough of the
mechanics.](https://edu.chainguard.dev/open-source/sigstore/how-to-keyless-sign-a-container-with-sigstore/)

**A CI job, using its own built-in identity — no secret, no browser.** GitHub Actions
(and GitLab CI, and others) can hand a workflow a short-lived identity token that describes
*the workflow itself* — this exact repository, this exact workflow file, this branch —
without the workflow owner storing anything. A minimal real example, once the workflow
declares `permissions: id-token: write`:

```yaml
- uses: sigstore/cosign-installer@v3.8.2
- run: cosign sign --yes ghcr.io/owner/image@sha256:...
```

No `--key`, and no browser — GitHub's own OIDC provider vouches for the job, Fulcio
issues a certificate for *that workflow's* identity, and it's over in one step. (`--yes`
is unrelated to the browser: it just accepts the "this will be recorded in a public
transparency log" confirmation without waiting for someone to type `y`.) This is the
standard way real projects do keyless signing in CI today; [Chainguard's "zero-friction
keyless signing"
post](https://www.chainguard.dev/unchained/zero-friction-keyless-signing-with-github-actions)
and this [minimal proof-of-concept
repo](https://github.com/chrisns/cosign-keyless-demo) both walk through it end to end.

**A fixed key, stored as a CI secret.** The older, still fully supported model: generate
a key pair once (`cosign generate-key-pair`), keep the private key and its password as
CI secrets, and sign with `cosign sign --key cosign.key`. No OIDC identity is involved at
all — trust rests entirely on "whoever holds this specific key," verified by matching a
public key, the same model Bitnami uses (see below). Also no browser, also fully
automatable, but it reintroduces exactly the key-custody problem keyless signing exists
to avoid: that key has to be generated, protected, and rotated by someone, forever.

So "cosign opens a browser" was never a hard limitation on automation — it's what happens
specifically when neither of the other two identity sources is available, which is
exactly the personal-machine case this session ran into.

## What it actually guarantees — and what it doesn't

Reviewing the PR, I asked for a concrete explanation of the guarantee before writing this
entry, and the answer mattered: verification never checked *who* had signed something —
only that *a* valid signature existed for the exact content being installed. It checks
"does a valid signature exist for this", never "was it signed by an identity I trust".
Concretely: anyone — call them Bob, signing with his own personal Gmail via the
interactive login flow — can publish their own malicious skill, sign it themselves, and
`diderot update` accepts it without a complaint.

So what's actually guaranteed is **integrity and public traceability**: content wasn't
silently swapped after signing, and if something bad is signed there's a permanent,
auditable trail back to *an* identity. What isn't guaranteed is **publisher
authenticity** — that it's really *me*. Two real, tested properties out of the PR: an
unsigned artifact is refused, and a signature for one digest doesn't verify a different
one (replay/substitution). Neither of those adds up to "only sunix's skills install" —
that needs identity pinning, which none of this attempted.

## Checking how everyone else actually handles this

Before deciding whether that gap was worth closing right now, I went and checked what the
rest of the ecosystem actually does — not from memory, since this space moves fast.

- **Docker Hub**: never signed by default. Docker Content Trust was always opt-in
  (`DOCKER_CONTENT_TRUST=1`), and it's being fully retired — the upstream Notary v1
  project was archived in July 2025, and Docker Official Image signing certificates
  started expiring that August. "Docker Verified Publisher" is a manual identity-vetting
  badge, explicitly *not* a cryptographic signature over the image bytes.
- **Helm / Artifact Hub**: `helm package --sign` produces a `.tgz.prov` file, clear-signed
  with the publisher's PGP key — but Artifact Hub has to display a "signed package" badge
  precisely because most charts don't have one. It's existed since Helm 2 and stayed a
  minority practice.
- **Bitnami / Broadcom**, who actually do sign at scale, don't use sigstore's keyless
  flow at all: their documented command is
  `cosign verify --key https://app-catalog.vmware.com/.well-known/cosign.pub <ref>
  --insecure-ignore-tlog` — a fixed, well-known public key, transparency log check
  explicitly skipped. Identity pinning solved by pinning a *key*, not an OIDC identity.
- **Notation** (the CNCF Notary v2) solves the same problem with classic PKI: a trust
  store of specific root CAs and an explicit trust policy — no ephemeral certificates at
  all. It's one of the two options Harbor and ACR moved to after deprecating Notary v1;
  they support Cosign alongside it rather than instead of it.
- **quay.io / Red Hat Quay** has supported `cosign` natively since Quay 3.6 — and the
  relevant detail for the linking question above is *how* cosign stores things there:
  as an ordinary artifact tagged with a name derived from the subject image's digest,
  cosign's long-standing convention, not the newer OCI Referrers API. (Documented in
  passing in a [Quay workshop write-up](https://olleb.com/quay-workshop/quay-oci.html);
  it's cosign's storage scheme rather than a quay-specific choice.) Either way it's
  concrete confirmation that the mechanism this session got stuck on was never a hard
  requirement — a whole public registry's signing story runs on the older convention.
- **Red Hat's own image signing** for RHEL/UBI images goes further still: the signature
  isn't stored in the registry at all. A config file under `/etc/containers/registries.d/`
  points the client at a *separate URL* to fetch a detached GPG signature from, keyed by
  the image digest — signed with the same GPG key Red Hat uses for RPMs. [Red Hat's docs
  walk through
  it](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/9/html/building_running_and_managing_containers/assembly_signing-container-images_building-running-and-managing-containers).
  Two naming traps worth flagging: Red Hat has published those detached signatures under
  a URL path called `sigstore` since well before the CNCF Sigstore project existed, and
  modern podman/skopeo *also* speak real Sigstore-style attachments via a
  `use-sigstore-attachments` option — so "sigstore" in Red Hat docs can mean either
  thing depending on vintage. The detached-GPG model is the one with no registry-side
  linking whatsoever: the strongest evidence that linking is a transport choice, not a
  cryptographic requirement.

Every publisher above who actually pins an expected signer (Helm's PGP key, Bitnami's and
Red Hat's fixed public keys, Notation's trust store) converges on the same rule: **pin
the expected signer**, never accept "signed by anyone verifiable." quay.io's entry adds a
different, narrower point — confirming that *storing and linking* a signature never
required the newer registry mechanism this session got stuck on.

The pattern in that list is the useful part, and it's not "nobody bothers." It's that
signing shows up exactly where a **vendor distributes to strangers at scale** — Red Hat,
Bitnami, anyone whose customers have a compliance department — and stays absent from
casual publishing, where none of the big public registries sign by default and none of
the hubs pin identities for you. Which says something fairly precise about *when* diderot
needs this, and it isn't now.

## Not a reversal — a sequence

There's an obvious objection to answer here, and it's my own words from
[part one](01-from-a-name-to-a-git-lockfile.md): the gap that justifies diderot existing
at all was *"OCI registries, content-digest lockfiles, and cosign verification"*. Three
things. So closing a PR that implements the third one looks like walking back the
premise.

It isn't, and the distinction is about *who diderot is for at each stage*. Those three
gaps matter to an audience diderot doesn't have yet: teams pushing skills to private
registries behind corporate auth, where "which of these artifacts do we actually trust"
is a question someone gets asked. Signing is load-bearing *there*. What v1 has instead
is one user — me — and one job: make the developer experience good enough that declaring
skills in a manifest beats copying folders around. Mandatory signature verification on
every push and pull buys that user nothing, and costs them a browser login on their own
laptop before they can publish anything.

So the sequencing is deliberate: v1 earns its keep on developer experience, and signing
lands when the enterprise/private-registry story becomes real — which is exactly when
it stops being ceremony and starts being the reason someone picks this tool. Not dropped
from the pitch; moved to the milestone where it pays for itself.

Two things I'd want in place before that milestone, both learned from building this one.
Identity pinning first: without it, "verified" means "somebody signed this," and the
enterprise question is specifically *who* — so it needs a real design
(`--certificate-identity`-style pinning declared per source in `diderot.yaml`, or
trust-on-first-use recorded in `diderot.lock`), not a bolt-on. And CI-native signing as
the primary path rather than the browser fallback, since the audience that needs
signatures is publishing from pipelines anyway, where the identity is both free and
narrower than any personal account.

Meanwhile `main` stays at M2 — OCI push/pull, no signing requirement — and the branch
with its tests keeps working, ready to reopen.
