# Agent instructions

## Commit conventions

- **Conventional Commits** are required for all commit messages and pull request titles
  (format: `<type>[scope]: <description>`, e.g. `feat: add git source resolver`,
  `fix(lock): pin by tree SHA`). release-please computes version bumps from them:
  `feat:` → minor, `fix:` → patch, `feat!:` / `BREAKING CHANGE:` → major.
- **One logical commit per pull request** — squash intermediate commits before opening
  or updating a PR.

## Cutting a release

1. **A repository setting has to be on**, or release-please fails at the very last step
   with `GitHub Actions is not permitted to create or approve pull requests`: Settings →
   Actions → General → Workflow permissions → *Allow GitHub Actions to create and approve
   pull requests*. Without it, release-please still creates its `release-please--branches--main`
   branch and the run still goes red — so check that workflow's status, don't assume
   silence means success.
2. Merge the Release PR release-please opens. That publishes the GitHub Release and tag.
3. **Trigger `Release binaries` manually** against the new tag (Actions → Release binaries
   → Run workflow). It listens on `release: published` too, but a Release created with the
   default `GITHUB_TOKEN` cannot trigger another workflow — GitHub's anti-loop safeguard.
   Setting a `RELEASE_PLEASE_TOKEN` secret (a PAT) removes this step.
4. Check the release has all twelve assets: five native binaries, `diderot.jar`, and a
   `.sha256` beside each.
5. Smoke-test the published installer on a machine that has never built this project:
   `curl -fsSL https://raw.githubusercontent.com/sunix/diderot/main/install.sh | sh`

To force a specific version (the first release bootstraps at `1.0.0` regardless of the
pom, which is rarely what an early project wants), put `Release-As: 0.1.0` in the body of
the squash-merge commit.

## Project conventions

- Java 21, Quarkus, picocli. CLI output goes through picocli's out/err — no logging
  frameworks in command code.
- Every command class states its Helm equivalent in its `description` when one exists.
- Update `MAKING-OF.md` at the end of each working session (see the
  [making-of skill](https://github.com/sunix/ai-skills/tree/main/skills/documentation/making-of)):
  what was done, the discussions with the LLM, and proof that generated code works.
