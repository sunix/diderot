# Building diderot: a package manager for AI agent skills, one prompt at a time

*A first-person account of how this project comes together — the tooling, the false starts,
and the implementation work — kept here for new contributors, or anyone curious how it came
together. Written in a blog-ish style rather than as formal docs, split into one post per
chapter so each stays a readable size. Updated on request as the work progresses.*

*Last updated: 2026-08-24.*

## Posts

1. [From a name to a git-backed lockfile](doc/making-of/01-from-a-name-to-a-git-lockfile.md) —
   the gap survey (what `gh skill`, `npx skills`, and plugin marketplaces already do), the
   name, the Go-vs-Java reversal, stealing Helm's homework, and M1: `update` / `install` /
   `status` over git sources with content-digest locking. Also a
   [blog post](https://blog.sunix.org/posts/building-diderot-a-package-manager-for-ai-agent-skills-one-prompt-at-a-time-part-1/).
2. [OCI at last: skills in real registries](doc/making-of/02-oci-push-and-pull.md) — the
   JKube detour, `diderot push` as an OCI artifact via the ORAS Java SDK, digest-pinned
   `oci://` sources, the tree digest that came back identical after a trip through a
   registry, and closing the loop for real: a GitHub Action publishing to ghcr.io and a
   genuinely unrelated project (Erasmus) installing from it with zero credentials.
3. [Signing works, and waits for the users who need it](doc/making-of/03-considering-signing.md) —
   a working keyless-signing feature (proven against sigstore's staging instance; the
   production browser flow never ran here) that isn't landing in `main` yet: what a
   signature actually guarantees versus what it sounds like it guarantees, where the
   signing identity comes from on a laptop vs. in CI, how Docker Hub, Helm, Bitnami,
   Notation, quay.io and Red Hat each handle this — and why v1 is about developer
   experience while signing belongs to the enterprise milestone.
