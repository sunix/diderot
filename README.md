# diderot

**Package manager for AI agent skills** — Helm-style manifests and lockfiles over git and OCI registry sources.

Named after Diderot's *Encyclopédie* — the "Dictionnaire raisonné des sciences, des arts et **des métiers**": a registry of skills, meant to be distributed.

> ⚠️ **Early development, but usable.** `v0.1.0` is out: git and OCI registry sources work end to end (`push` / `update` / `install` / `status`), installable in one line — no compiling required (see below). Signature verification is built but held back so this MVP could ship first. Watch [MAKING-OF.md](MAKING-OF.md) for the story as it unfolds.

## Why

[Agent Skills](https://agentskills.my/specification/) (`SKILL.md` folders) are portable across 30+ AI coding tools, and git-based installers already exist (`gh skill`, `npx skills`, Claude Code plugin marketplaces). What none of them offer:

- **OCI registry distribution** — push skills to the registries enterprises already run (ghcr.io, Harbor, Artifactory, ECR), with their auth, replication, and air-gap story.
- **Content-digest lockfiles** — tags move; digests don't. Two repos sharing a `diderot.lock` get the same bytes — including the throwaway workspace a remote coding agent spins up from a fresh clone.
- **Signing** — cosign signatures verified at lock and install time.

## How it works

Declare skills in `diderot.yaml`:

```yaml
skills:
  - name: making-of
    source: git+https://github.com/sunix/ai-skills#skills/documentation/making-of
    version: main            # branch, tag, or commit
  - name: release-please
    source: oci://ghcr.io/sunix/skills/release-please   # an OCI registry
    version: v1                                         # exact tag (semver ranges: planned)
targets: [claude]            # agent layouts: claude (.claude/skills), agents (.agents/skills)
```

`diderot update` resolves each constraint to a commit and records a **content digest** — the git
tree SHA of the skill directory — in `diderot.lock`:

```yaml
lockfileVersion: 1
skills:
- name: making-of
  source: git+https://github.com/sunix/ai-skills#skills/documentation/making-of
  resolved: 64358bc5644155d4513bf17e421119fc6eec9127
  digest: tree:0f755a27d65b67d80d6d1ee2ed0d8a7963fa8f23
```

`diderot install` extracts exactly those bytes into the agent directories and verifies the digest
of what landed on disk (diderot re-computes git tree SHAs in pure Java, no `.git` needed).
There is **no transformation step**: skills are already in the format agents read (`SKILL.md`
folders), so install materializes the locked tree byte for byte — which is precisely what makes
digest verification possible.
`diderot status` uses the same hashing to report `ok` / `DRIFTED` / `MISSING` per skill —
re-running `install` repairs drift. Git sources require the `git` binary on the PATH.

For OCI sources, `update` resolves the tag to a **manifest digest** and pins that in
`resolved`; the `digest` line stays a git tree SHA of the content, so verification is
identical whatever the source. Publishing is one command (auth comes from your existing
`docker login` credentials):

```bash
diderot push skills/documentation/making-of oci://ghcr.io/sunix/skills/making-of:v1
```

Skills travel as OCI artifacts (`artifactType: application/vnd.diderot.skill.v1`, one
tar+gzip layer), so they land in any OCI-conformant registry: ghcr.io, Harbor,
Artifactory, ECR, or a plain [`registry:2`](https://hub.docker.com/_/registry).

Then, Helm-style:

| Command | Helm equivalent | Effect |
|---------|-----------------|--------|
| `diderot update` | `helm dependency update` | resolve constraints, fetch, (re)write `diderot.lock` |
| `diderot install` | `helm dependency build` | install exactly what the lock pins (by digest) |
| `diderot status` | — | report drift between installed skills and the lock |
| `diderot push <dir> <oci-ref>` | `helm push` | package a skill and push it to an OCI registry |

## Install

One line, and it picks the binary for your platform, verifies its SHA-256 checksum, and
refuses to install anything that doesn't match:

```bash
curl -fsSL https://raw.githubusercontent.com/sunix/diderot/main/install.sh | sh
```

Installs to `~/.local/bin` by default; override with `DIDEROT_INSTALL_DIR`, and pin a
version with `DIDEROT_VERSION=v0.1.0`. Native binaries are published for Linux and macOS
(x86_64 and aarch64) and Windows (x86_64).

Already have [JBang](https://www.jbang.dev/)? It can install `diderot` as a real command,
resolving the catalog and the release jar for you — no download step, and it picks a
suitable JDK if yours is older than 21:

```bash
jbang app install diderot@sunix
diderot --help
```

Or run it without installing anything at all:

```bash
jbang diderot@sunix --help
```

(Both resolve [`sunix/jbang-catalog`](https://github.com/sunix/jbang-catalog);
`diderot@sunix/diderot` works too, reading this repository's own `jbang-catalog.json`.)

Or grab a binary by hand from the [releases
page](https://github.com/sunix/diderot/releases) — every asset ships a `.sha256`
alongside it. On any platform without a native binary, the portable `diderot.jar` from
the same release runs anywhere with Java 21+ (`java -jar diderot.jar --help`).

## Roadmap

- **M1** ✅ — `update` / `install` / `status` over git sources (pin by tree SHA), standard Agent Skills layout (`.claude/skills/`).
- **M2** ✅ — OCI backend via [oras-java](https://github.com/oras-project/oras-java): `push`, `oci://` sources, pin by manifest digest.
- **M3** ✅ — distribution: GraalVM native binaries per platform built in CI, the one-line installer above, and a JBang catalog entry.
- **Next** — semver ranges over registry tags; a `--frozen`/welcoming `install` (still under DX reflection).
- **Enterprise milestone** — keyless signing (sigstore) with **identity pinning**: verify not just that a signature exists, but that the expected publisher made it, for teams consuming skills from private registries. Signing itself is already built and proven against sigstore staging on the `feat/m2b-signing` branch ([PR #6](https://github.com/sunix/diderot/pull/6)); v1 deliberately focuses on developer experience instead.
- **Later** — additional `--target` layouts for tools that diverge from the standard directory convention.

## Building from source

Requires Java 21+.

```bash
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar --help

# Native binary (requires GraalVM, or add -Dquarkus.native.container-build=true)
./mvnw package -Dnative
./target/diderot-*-runner --help
```

Dev mode: `./mvnw quarkus:dev` (Quarkus + picocli; see the [Quarkus picocli guide](https://quarkus.io/guides/picocli)).

## Contributing

Commits and PR titles follow [Conventional Commits](https://www.conventionalcommits.org/) — releases are automated with release-please. See [AGENT.md](AGENT.md).

## License

[Apache-2.0](LICENSE).
