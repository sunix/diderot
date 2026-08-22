# OCI at last: skills in real registries

*Part two of [diderot's making-of](../../MAKING-OF.md): `diderot push`, `oci://` sources,
and the digest that survived the round trip.*

## The goal: same three verbs, registry behind them

This chapter had one target: make an OCI registry a first-class skill source, with the
exact same user experience as git. Publish a skill:

```console
$ diderot push skills/documentation/making-of oci://ghcr.io/sunix/skills/making-of:v1
pushed skills/documentation/making-of -> ghcr.io/sunix/skills/making-of:v1@sha256:9276e6...
```

Consume it — only the `source` line changes, the three verbs don't:

```yaml
# diderot.yaml
skills:
  - name: making-of
    source: oci://ghcr.io/sunix/skills/making-of
    version: v1          # a tag - a moving target, like a git branch
```

`diderot update` must nail the tag down to a **manifest digest** (the registry-world
equivalent of a commit), `install` must materialize the exact bytes and verify them, and
`status` must keep catching a single flipped byte. If the plumbing shows anywhere above
the `source:` line, the design is wrong.

## The JKube detour

Before picking the library I went back to something I half-remembered: didn't JKube push
and pull images already? I contributed to those projects — jkube-kit, the fabric8
tooling — but I didn't write their registry clients, so we went and read the code
instead of trusting my memory. Two different answers in there: the default `docker` build
strategy never speaks to a registry at all — `DockerAccessWithHcClient` (inherited from
docker-maven-plugin) POSTs to the **Docker daemon**'s `/images/{name}/push` with the
credentials in an `X-Registry-Auth` header, and the daemon does the talking. The `jib`
strategy is the interesting one: it embeds Google's jib-core, which speaks the registry
protocol in pure Java, no daemon anywhere.

The lesson carried over directly: pure-Java registry talk is a solved problem, but
jib-core is shaped for *container images* (layers plus a container config), not for
arbitrary artifacts with a custom type. For ORAS-style artifacts the purpose-built SDK is
[oras-java](https://github.com/oras-project/oras-java) — alpha, as accepted back in part
one — and diderot, like the jib path, will never have a daemon dependency.

## What landed

The dependency is `io.quarkiverse.oras:quarkus-oras` 0.6.4, the Quarkiverse extension
wrapping ORAS Java SDK 0.8.3 — the extension buys the native-image configuration we'll
want for the distribution milestone. On top of it, one new class:
`oci/OrasClient.java`, the registry twin of `GitCli` — the second and only other class
allowed to touch the outside world.

Its `push` is small because the SDK does the packaging (a directory travels as one
tar+gzip layer, flagged for auto-unpack on pull):

```java
Manifest manifest = registryFor(reference).pushArtifact(
        ContainerRef.parse(reference),
        ArtifactType.from("application/vnd.diderot.skill.v1"),
        Annotations.ofManifest(Map.of(TREE_DIGEST_ANNOTATION, treeDigest)),
        LocalPath.of(skillDir));
return manifest.getDescriptor().getDigest();
```

Two things worth noticing: skills get their own `artifactType`, so a registry browser can
tell a diderot skill from a Helm chart; and the manifest carries the directory's git-tree
digest as an annotation — provenance stamped at push time.

Pulls are digest-addressed and cached once, forever:

```java
Path slot = cacheRoot.resolve(digest.replace(':', '-'));   // ~/.cache/diderot/oci/sha256-...
if (!Files.isDirectory(content)) {
    registryFor(ref).pullArtifact(ContainerRef.parse(repository + "@" + digest), pulling, true);
    Files.move(pulling, content, StandardCopyOption.ATOMIC_MOVE);
}
```

A directory named by a digest can never go stale, so there is nothing to invalidate — the
atomic move just guards against a torn download. Auth rides on what the SDK gives us:
`Registry.builder().defaults()` reads `~/.docker/config.json` (plain HTTP only for
localhost registries, which the tests use).

In `Workspace`, resolution grew a second branch. The git path was untouched; the OCI path
resolves the tag to a manifest digest, pulls once into the cache, gates on `SKILL.md`,
and — the important line — hashes the pulled content with the same `GitTreeHasher` as
everything else:

```java
String digest = oci.resolveDigest(ref.url() + ":" + tag);   // tag -> sha256:..., no download
Path content = oci.cachedPull(ref.url(), digest);
locked.resolved = digest;                                   // the pin
locked.digest = "tree:" + GitTreeHasher.treeSha(content);   // the same digest language as git
```

That last line is the design paying off: `install` and `status` did not change *at all*
for OCI sources. They compare disk against `tree:...` and never ask where the bytes came
from.

## Proof

The integration test runs against a real registry, not a mock. `registry:2` is the
official reference Docker/OCI registry image — the same software that runs Docker Hub
and ghcr.io — and the test starts one itself: a real, local, disposable container on a
free port bound to `127.0.0.1` only, so nothing is exposed to the network, torn down at
the end of the run:

```java
containerId = Git.run(Path.of("."), "docker", "run", "-d", "--rm",
        "-p", "127.0.0.1:0:5000", "registry:2").trim();
String portLine = Git.run(Path.of("."), "docker", "port", containerId, "5000/tcp").trim();
registryHostPort = portLine.lines().findFirst().orElseThrow(); // e.g. 127.0.0.1:32768
```

(No Docker daemon on the machine running the tests → the test skips itself via
`assumeTrue`, rather than failing.)

Against that live registry, the central assertion is the identity that makes the
lockfile trustworthy:

```java
String sourceTreeDigest = "tree:" + GitTreeHasher.treeSha(skillDir);
String pushedDigest = oras.push(skillDir, repository + ":v1");
LockFile lock = workspace.update();
assertEquals(pushedDigest, lock.skills.get(0).resolved);
assertEquals(sourceTreeDigest, lock.skills.get(0).digest,
        "what was pushed is byte-for-byte what got locked");
```

This proves the pipeline end to end: if the tar+gzip packing, the registry upload, the
pull, or the extraction had bent one byte, the tree hash of the pulled content would not
equal the tree hash of the source directory. All green, thirteen tests now:

```text
[INFO] Tests run: 2, ... -- in org.sunix.diderot.oci.OciRoundTripTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Then the real world, no fixtures: the actual making-of skill from ai-skills, pushed to
ttl.sh (an anonymous, ephemeral public registry), consumed back through the full loop:

```text
$ diderot push .../skills/documentation/making-of oci://ttl.sh/diderot-e2e-making-of:1h
pushed ... -> ttl.sh/diderot-e2e-making-of:1h@sha256:9276e608423a...

$ diderot update
locked making-of   ttl.sh/diderot-e2e-making-of@sha256:9276e608423a (tree:3eb1d8fe4795...)
$ diderot install
installed making-of -> .claude/skills/making-of (tree:3eb1d8fe4795... verified)
$ echo "sabotage" >> .claude/skills/making-of/SKILL.md && diderot status
DRIFTED  making-of   .claude/skills/making-of      # exit 1
$ diderot install && diderot status
ok       making-of   .claude/skills/making-of      # exit 0
```

And the cross-check that made my day, git as the oracle one more time:

```text
$ git -C ai-skills rev-parse HEAD:skills/documentation/making-of
3eb1d8fe4795b78fa0328b333b5abda9ad4560fb
```

Character for character the `tree:` digest in the lockfile — computed from bytes that
went disk → tar.gz → registry → pull → extraction. Two unrelated pipelines, one digest:
the lock really is source-agnostic.

## What this chapter leaves open

Three honest gaps. Tags resolve exactly (`version: v1` means the tag `v1`) — semver
ranges like `^1.0.0` over registry tags are not implemented yet. Signing is absent: the
SDK's `attachArtifact` and the referrers API are exactly the seam cosign needs, and
that's the next chapter. And the ORAS SDK is still alpha — this time it cost nothing but
one noisy WARN log to silence, but the bet from part one stands.
