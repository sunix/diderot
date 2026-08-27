# Starting the signing work: two walls before any feature

*Part seven of [diderot's making-of](../../MAKING-OF.md): resuming the signing that part three
parked — and the two questions that had to be answered before writing any of it: where does a
signature live on ghcr, and does sigstore survive a native image.*

## The goal: `^1.0.0` you can defend

Part six ended on what `add` is really preparing for. The target, concretely — none of it built yet:

```console
$ diderot add oci://ghcr.io/sunix/skills/making-of
  signed by   https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
  issuer      https://token.actions.githubusercontent.com
  Trust this signer for making-of? [y/N] y
```

and from then on, a release signed by anything else fails closed, naming both identities. The
argument for wanting it came out of part five: a range with no pinned signer means automatically
adopting whatever the publisher pushes; with one, only what the *expected* publisher pushes.
`^1.0.0` stops being an act of faith renewed at every release.

The starting point was not zero. Signing was built and proven against real Fulcio certificates and
real Rekor entries back in [#6](https://github.com/sunix/diderot/pull/6), then deliberately parked:
its verification accepted *any* valid signature — no identity pinning, which is the entire question.
The `Signing` class comes back from that branch unchanged. But before building policy on top of it,
two assumptions it rested on needed checking, and both turned out false.

## Wall one: ghcr has no Referrers API

#6 attached the signature bundle to the artifact as an **OCI referrer**. The registry ai-skills
actually publishes to does not implement that API — and I say that from measurement, not from a 404,
because a bare 404 proves nothing and I have been burned by exactly that reading before. The same
request shape, against two registries:

```console
# ghcr.io, two digests diderot resolves and pulls every day
referrers/sha256:8b81085393c4…  →  404  {"code":"MANIFEST_UNKNOWN"}
referrers/sha256:b61d9507ba16…  →  404  {"code":"MANIFEST_UNKNOWN"}

# zot, freshly pushed artifact, no referrers attached at all
referrers/sha256:accc3af6f97a…  →  200  {"mediaType":"…image.index.v1+json","manifests":[]}
```

A registry that implements the API answers 200 with an empty index when there is nothing to list.
ghcr answers 404 for digests it demonstrably serves, so it is the endpoint that is missing, not the
manifest. The transport will be cosign's pre-referrers tag scheme instead — the signature as its own
manifest under `sha256-<hex>.sig` — which needs nothing a registry can lack.

## Wall two: seven native builds

The primary artifact is a GraalVM binary per platform, so if sigstore-java cannot be compiled into
one, signing is a JVM-only feature and the design changes. That question could invalidate everything
after it, so it went first: restore `Signing`, add a `push --sign` flag whose real job is giving
`native-image` a reachable path into the sigstore graph — an unreachable dependency gets optimised
away and the probe proves nothing — and let CI answer. It cannot be answered here: `native-image`
peaks above 4 GB and this machine has 2.

The answer took seven rounds, each about ten minutes, and the instructive part is that the first
three fixes were the wrong *kind* of fix:

| round | failure | lesson |
|---|---|---|
| 1 | `Log4JLogger` init fails: `NoClassDefFoundError: org/apache/log4j/Priority` | deferred that class |
| 2 | same, now `Log4jApiLogFactory` (log4j2's adapter) | deferred the whole package |
| 3 | `Slf4jLogFactory` **instances in the image heap** | stop naming classes, ask why the dependency is there |
| 4–5 | shaded netty's logging probe: `Log4J2Logger` unresolved at parse | deferring is provably useless here |
| 6 | one error left: `SecureRandom` in the image heap | read the trace instead of guessing |
| 7 | — | green |

Round 3's question had a good answer: `commons-logging` was only on the classpath because #6 put it
there, for google-http-client's Apache transport — and `slf4j-api` was already wired by Quarkus. So
instead of taming its one-adapter-per-backend zoo, stop shipping it: `jcl-over-slf4j` provides the
same API onto the slf4j already present, and has no adapters to fail. The whole family vanished from
the next build.

Round 5 is the one worth keeping for later, because the failed fix *reached* the build and did
nothing — I checked the actual `native-image` command line rather than assuming the flag was lost:

```text
Error: Discovered unresolved type during parsing:
  io.grpc.netty.shaded.io.netty.util.internal.logging.Log4J2Logger
Parsing context:
   at …InternalLoggerFactory.getDefaultFactory(InternalLoggerFactory.java:111)
   at …ByteBufUtil.<clinit>(ByteBufUtil.java:62)
```

netty probes logging backends in a static initialiser, and `--initialize-at-run-time` cannot help:
the initialiser still has to be **compiled into the image** to run later, the parser still meets the
absent class inside it, and Quarkus links everything at build time, so an unresolved type is fatal
at parse regardless of when the class initialises. Deferring changes *when*; the problem was *what*.
Quarkus's netty extension solves it the only way that works — a substitution that removes the probe —
but it only sees real netty, and grpc ships a *copy* under `io.grpc.netty.shaded.*`. The fix was to
stop using the copy: exclude `grpc-netty-shaded`, depend on the API-identical `grpc-netty` plus
`quarkus-netty`, and let the substitution do its work.

Round 6 left a single error, and its trace named the culprit — which was not the BouncyCastle I had
been blaming on reputation:

```text
Trace: Object was reached by
  trying to constant fold static field org.apache.http.impl.auth.NTLMEngineImpl.RND_GEN
```

Apache HttpClient's NTLM engine, riding in via google-http-client, holds a `SecureRandom` in a
static field. NTLM is a Windows authentication scheme nothing here speaks; one targeted
`--initialize-at-run-time` and the build went green:

```text
build: success
native-smoke: success
```

What survives of six rounds of flailing is two lines in `application.properties` and two dependency
changes in the pom. There is also a properties-format trap recorded for whoever touches that line
next: `\,` inside `quarkus.native.additional-build-args` does not escape the list separator — the
properties format unescapes it *before* the list is split, so the flag after the comma silently
vanishes. Two flags as two list items is the form that works.

## The argument I lost, and was wrong about

Between rounds four and five I recommended giving up on in-process sigstore and shelling out to
`cosign`, the way `GitCli` shells out to git. The case looked strong — I checked that cosign
*requires* identity pinning in keyless mode, the exact check #6 lacked, and its `legacy` signature
transport works on ghcr today.

The author said no: **users download nothing.** diderot's whole pitch is one line to install;
requiring a second 135 MB binary contradicts it. Configure native-image until it works.

He was right, and the rounds table above understates how close I was to being wrong twice: the
recommendation came *after* round four, when the remaining depth looked unbounded — and it took
exactly three more targeted fixes. The general shape is worth keeping: the cost of an external
dependency is permanent and paid by every user; the cost of build configuration is paid once, here.

## What the walls decided, before any feature code

Two questions from the issue got their design answers while this was going on, both following the
rule part six stated — the human is in control of an agent's capabilities, and of trust.

**A skill that gains a signature later**: `update` should notice and report the identity it found —
as a proposal, never an automatic edit. Auto-adopting the first signature that appears buys little
anyway: whoever can push to a registry can sign with their own identity too.

**Unsigned skills stay supported**, and the naive rule — "verify if a signature exists" — is
downgrade-attackable as written: strip the signature and the check silently disappears. So the lock
must remember what it saw, and the rule becomes *never regress*: unsigned may stay unsigned, unsigned
may become signed (proposed, not adopted), but signed-with-pinned-signer that turns up unsigned or
differently-signed **fails closed**. Optional to adopt, impossible to lose by accident.

## What this chapter leaves open

Everything user-visible: the tag-scheme transport, identity pinning in `verifyDigest` — still the
unpinned call from #6, still the actual gap — the `signer:` block in the manifest, and the
never-regress checks in `update`. And one honest caveat carried forward from part five's postscript:
`native-smoke` proves the binary builds and starts, not that the keyless flow — TUF roots, Fulcio,
Rekor — runs in a native image. That gets its real test when the publish workflow signs with the
ambient GitHub OIDC token.
