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
    version: "^1.0.0"                                   # newest 1.x, or an exact tag to pin
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
- name: release-please
  source: oci://ghcr.io/sunix/skills/release-please
  resolved: sha256:f1ecb79525a464d5afe7aa5b2ea9a548221a8d73247756127dc5b3ffe23f6771
  tag: 1.2.0                    # what "^1.0.0" resolved to, for humans; the digest is the authority
  digest: tree:d87753e53a07325bdeb60f35fb45b929ae4c6b33
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

### Declaring a skill without editing YAML

```console
$ diderot add oci://ghcr.io/sunix/skills/making-of --version "^1.0.0"
added making-of        oci://ghcr.io/sunix/skills/making-of (^1.0.0)
locked making-of       ghcr.io/sunix/skills/making-of:1.1.0@sha256:8b81085393c4 (tree:89f4bb27c343…)
run `diderot install` to put it on disk.

$ diderot remove making-of
removed making-of      diderot.yaml, diderot.lock, 1 installed directory
  deleted .claude/skills/making-of
```

The name comes from the last segment of the source unless you pass `--name`. `add` pins **only the
skill it just added** — re-resolving the whole manifest would quietly move every floating constraint
already in the lock — and it puts the manifest back untouched if the new source turns out not to
resolve. `remove --keep-installed` leaves the directories alone.

Both commands edit `diderot.yaml` by splicing lines rather than by rewriting it from a parsed model,
so comments, key order, indentation and flow sequences like `targets: [claude]` all survive. They
understand one shape — a block list under `skills:` — and refuse anything else rather than guessing.

### Versions and ranges

For a registry source, `version:` is either a tag or a **semver range** resolved against the tags
the repository publishes:

```yaml
version: 1.2.0        # that tag, pinned
version: latest       # that tag, which moves
version: "^1.0.0"     # newest 1.x
version: "~1.2.0"     # newest 1.2.x
version: ">=1.0.0 <2" # explicit bounds
version: 1.2.x        # same idea, x-range spelling
                      # omit the line entirely and you get `latest`
```

Anything written like a range (`^ ~ > < = | *`, a space, or an `x`-range) is resolved against the
tag list; anything else is used as a literal tag, so a pin stays a pin. Tags that aren't semver —
`latest`, `main`, a date stamp — are skipped rather than treated as errors, and pre-releases are
excluded unless the range asks for them, following npm's rule. Whatever the range picks, the lock
still pins the **manifest digest**, so `install` is unaffected by how resolution happened:

```console
$ diderot update
locked release-please  ghcr.io/sunix/skills/release-please:1.2.0@sha256:f1ecb79525a4 (tree:d87753e53a07…)
```

When nothing satisfies the range, the error says what the repository does publish rather than
leaving you to go and look:

```console
$ diderot update
error: Skill 'release-please': no tag in ghcr.io/sunix/skills/release-please satisfies ^9.0.0.
       Published versions, newest first: 1.2.0, 1.1.0.
```

Ranges over **git** tags are not implemented; for a git source, `version:` remains a branch, tag or
commit.

Then, Helm-style:

| Command | Helm equivalent | Effect |
|---------|-----------------|--------|
| `diderot add <source>` | `helm dependency add` | declare a skill in `diderot.yaml` and pin that one in the lock |
| `diderot remove <name>` | — | undeclare it, unpin it, and delete the installed copies |
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
- **M4** ✅ — semver ranges over registry tags (`^1.0.0`, `~1.2.0`, `>=1.0.0 <2`), resolved from the tag list and pinned by digest.
- **Next** — `add` / `remove`, so declaring a skill isn't hand-editing YAML ([#24](https://github.com/sunix/diderot/issues/24)); a `--frozen`/welcoming `install` (still under DX reflection).
- **Enterprise milestone** — keyless signing (sigstore) with **identity pinning**: verify not just that a signature exists, but that the expected publisher made it, for teams consuming skills from private registries. Signing itself is already built and proven against sigstore staging on the `feat/m2b-signing` branch ([PR #6](https://github.com/sunix/diderot/pull/6)); v1 deliberately focuses on developer experience instead — [#25](https://github.com/sunix/diderot/issues/25) tracks what resuming it needs.
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
