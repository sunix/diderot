# Agent instructions

## Commit conventions

- **Conventional Commits** are required for all commit messages and pull request titles
  (format: `<type>[scope]: <description>`, e.g. `feat: add git source resolver`,
  `fix(lock): pin by tree SHA`). release-please computes version bumps from them:
  `feat:` → minor, `fix:` → patch, `feat!:` / `BREAKING CHANGE:` → major.
- **One logical commit per pull request** — squash intermediate commits before opening
  or updating a PR.

## Project conventions

- Java 21, Quarkus, picocli. CLI output goes through picocli's out/err — no logging
  frameworks in command code.
- Every command class states its Helm equivalent in its `description` when one exists.
- Update `MAKING-OF.md` at the end of each working session (see the
  [making-of skill](https://github.com/sunix/ai-skills/tree/main/skills/documentation/making-of)):
  what was done, the discussions with the LLM, and proof that generated code works.
