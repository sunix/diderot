# diderot

**Package manager for AI agent skills** — Helm-style manifests and lockfiles over git and OCI registry sources.

Named after Diderot's *Encyclopédie* — the "Dictionnaire raisonné des sciences, des arts et **des métiers**": a registry of skills, meant to be distributed.

> ⚠️ **Early development.** Git and OCI registry sources work end to end (`push` / `update` / `install` / `status`); signing is next. Watch [MAKING-OF.md](MAKING-OF.md) for the story as it unfolds.

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
Artifactory, ECR, or a plain `registry:2`.

Then, Helm-style:

| Command | Helm equivalent | Effect |
|---------|-----------------|--------|
| `diderot update` | `helm dependency update` | resolve constraints, fetch, (re)write `diderot.lock` |
| `diderot install` | `helm dependency build` | install exactly what the lock pins (by digest) |
| `diderot status` | — | report drift between installed skills and the lock |
| `diderot push <dir> <oci-ref>` | `helm push` | package a skill and push it to an OCI registry |

## Roadmap

- **M1** ✅ — `update` / `install` / `status` over git sources (pin by tree SHA), standard Agent Skills layout (`.claude/skills/`).
- **M2** ✅ — OCI backend via [oras-java](https://github.com/oras-project/oras-java): `push`, `oci://` sources, pin by manifest digest.
- **M2b** — signing: cosign signatures attached via the referrers API, verified at lock and install time; semver ranges over registry tags.
- **M3** — distribution: native binaries per platform (GraalVM), one-line `curl | bash` installer, JBang catalog (`jbang diderot@sunix`).
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
