# diderot

**Package manager for AI agent skills** — Helm-style manifests and lockfiles over git and OCI registry sources.

Named after Diderot's *Encyclopédie* — the "Dictionnaire raisonné des sciences, des arts et **des métiers**": a registry of skills, meant to be distributed.

> ⚠️ **Early development.** The command skeleton exists; nothing is implemented yet. Watch [MAKING-OF.md](MAKING-OF.md) for the story as it unfolds.

## Why

[Agent Skills](https://agentskills.my/specification/) (`SKILL.md` folders) are portable across 30+ AI coding tools, and git-based installers already exist (`gh skill`, `npx skills`, Claude Code plugin marketplaces). What none of them offer:

- **OCI registry distribution** — push skills to the registries enterprises already run (ghcr.io, Harbor, Artifactory, ECR), with their auth, replication, and air-gap story.
- **Content-digest lockfiles** — tags move; digests don't. Two repos sharing a `diderot.lock` get the same bytes.
- **Signing** — cosign signatures verified at lock and install time.

## How it will work

Declare skills in `diderot.yaml`:

```yaml
skills:
  - name: making-of
    source: oci://ghcr.io/sunix/skills/making-of
    version: "^1.0.0"
  - name: release-please
    source: git+https://github.com/sunix/ai-skills#skills/github-actions/release-please
    version: "main"
targets: [claude]
```

Then, Helm-style:

| Command | Helm equivalent | Effect |
|---------|-----------------|--------|
| `diderot update` | `helm dependency update` | resolve constraints, fetch, (re)write `diderot.lock` |
| `diderot install` | `helm dependency build` | install exactly what the lock pins (by digest) |
| `diderot status` | — | report drift between installed skills and the lock |
| `diderot push <dir> <oci-ref>` | `helm push` | package a skill and push it to an OCI registry |

## Roadmap

- **M1** — `update` / `install` / `status` over git sources (pin by tree SHA), standard Agent Skills layout (`.claude/skills/`).
- **M2** — OCI backend via [oras-java](https://github.com/oras-project/oras-java): `push`, `oci://` sources, pin by OCI digest; cosign verification via sigstore-java.
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
