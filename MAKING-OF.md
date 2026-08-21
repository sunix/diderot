# Building diderot: a package manager for AI agent skills, one prompt at a time

*A first-person account of how this project came together — the tooling, the false starts,
and the implementation work — kept here for new contributors, or anyone curious how it came
together. Written in a blog-ish style rather than as formal docs; may still turn into an
actual blog post at some point. Updated on request as the work progresses.*

*Last updated: 2026-08-21.*

## Why this exists

It started with a different question entirely: I had just made
[ai-skills](https://github.com/sunix/ai-skills), my library of reusable agent skills,
installable in other projects — and I wanted a way to manage those installs. Something like
Helm, but for skills: push them to OCI registries, declare them in a per-repo manifest,
install and update them like dependencies.

Before writing a line, Claude and I surveyed what already existed, and the survey stung a
little: most of the idea was already built. `gh skill` (GitHub CLI v2.90+) does
install/pin/update from GitHub repos. `npx skills` (Vercel) has the manifest
(`.skills.json`) and the lockfile. Claude Code has plugin marketplaces. The Helm-like CLI I
was picturing existed three times over — for git sources.

What none of them do: **OCI registries** (the artifact stores enterprises already run, with
auth, replication, signing, and an air-gap story), **content-digest lockfiles** (every
existing tool pins tags or versions; tags move, digests don't), and **cosign verification**.
That's the gap. That's the project.

## First, the name

The hardest part, obviously. The shortlist came from checking npm, crates.io, and GitHub
availability in one pass: *metier* (the skill **and** the Jacquard loom — free everywhere),
*compagnon* (the French institution of craft-skill transmission), *guilde*, and *diderot*.

I picked **diderot**, against the availability argument: the npm name is squatted by a
dependency-injection library dead since 2022, and PyPI is taken too. Don't care. The
Encyclopédie's full title is *"Dictionnaire raisonné des sciences, des arts et des
métiers"* — a registry of skills, built to be distributed. A CLI that pushes skills to
registries could not be named anything else. (crates.io is free, and `@sunix/diderot`
exists as an npm fallback if ever needed.)

## Go was the obvious choice, so naturally it's Java

Claude's recommendation was unambiguous: Go. `oras-go` is *the* reference OCI-artifact
library (Helm and the `oras` CLI itself are built on it), sigstore tooling is Go-native,
and every potential contributor in that ecosystem already speaks it.

I chose Java with Quarkus anyway, and it's not stubbornness — it's twenty years of it
([the CV](https://blog.sunix.org/cv/)). I've been part of the [Paris JUG](https://www.parisjug.org/)
crew since 2015 and led it from 2019 to 2023, and most of my open source work has been
Java in exactly this problem space: the
[Fabric8 Kubernetes Java client](https://github.com/fabric8io/kubernetes-client) and
[Eclipse JKube](https://github.com/eclipse-jkube/jkube) at Red Hat — Java talking to
container registries and cloud-native APIs, which is precisely what "the OCI ecosystem
speaks Go" is supposed to rule out — plus Eclipse Che and Nuxeo before that. More
recently jdtls-mcp and Erasmus (too new to have made the CV yet). GraalVM native-image
closes the distribution gap: same single static binary as Go, and Quarkus makes that
path boring.

The acknowledged risk stands: [oras-java](https://github.com/oras-project/oras-java) is
the official ORAS SDK but still *incubating*, so I'm probably signing up for upstream
contributions along the way. That's how jdtls-mcp went with the MCP Java SDK, and
honestly, filing real issues against a young SDK is half the fun.

## Stealing Helm's homework

The design is a deliberate Helm transposition. `Chart.yaml` declares dependencies with
version constraints; `helm dependency update` resolves them and writes `Chart.lock`;
`helm dependency build` reproduces exactly what the lock says. Same split here:
`diderot.yaml` → `diderot update` → `diderot.lock` → `diderot install`.

One deliberate improvement over Helm: the lock pins **content digests**, not versions —
OCI digest for registry sources, git tree SHA for git sources (the trick `gh skill`
already uses). A version tag can be re-pushed; a digest can't lie.

One thing deliberately *not* taken from Helm: templating and values. A skill is markdown
and files; there's nothing to render.

## The scaffold

Today's output is the walking skeleton: a Quarkus 3.38 + picocli project (`diderot
update/install/status/push` all answer `--help` and honestly reply "not implemented yet"),
this journal, and release automation via release-please — which is dogfooding, since the
workflow comes straight from my own
[release-please skill](https://github.com/sunix/ai-skills/tree/main/skills/github-actions/release-please),
and this file exists because of the
[making-of skill](https://github.com/sunix/ai-skills/tree/main/skills/documentation/making-of)
shipped in ai-skills this very morning. diderot's first users are its own build scripts.

Next up, milestone M1: the git-source resolver and the lockfile — the shortest path to
`diderot install` doing something real against ai-skills.
