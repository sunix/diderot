# All I wanted was versioned skills

*Part four of [diderot's making-of](../../MAKING-OF.md): using diderot for real, to publish
an actual library of skills — and the five things that got in the way.*

## The goal: a version that means something

Part three ended with [ai-skills](https://github.com/sunix/ai-skills) installing diderot
rather than building it. Which made the next gap obvious the moment I looked at a
consumer's manifest:

```yaml
skills:
  - name: making-of
    source: oci://ghcr.io/sunix/skills/making-of
    version: v1
```

That `v1` looks like a version. It isn't. Every publish overwrote the same tag, so it meant
"whatever `v1` points at today" — the exact moving-target problem
[part one](01-from-a-name-to-a-git-lockfile.md) went to such lengths to explain about git
branches, reintroduced by my own publishing side. The registry held no history. And the
only actual version numbers in ai-skills described the *whole library*, so bumping one
after editing a single skill said nothing about which skill had changed.

What I wanted instead:

```yaml
    version: "^1.0.0"     # newest 1.x of this skill, resolved from real tags
```

Which needs each skill versioned independently, published under an immutable semver tag,
automatically, on release. A morning's work, I thought.

## Per-skill versions, which release-please already knows how to do

The mechanism turned out to exist: release-please has a monorepo mode where each directory
is its own package. One config, five packages:

```json
{
  "separate-pull-requests": false,
  "packages": {
    "skills/documentation/making-of":         { "release-type": "simple", "component": "making-of" },
    "skills/github-actions/pr-preview-surge": { "release-type": "simple", "component": "pr-preview-surge" },
    "skills/github-actions/push-to-surge":    { "release-type": "simple", "component": "push-to-surge" },
    "skills/github-actions/release-please":   { "release-type": "simple", "component": "release-please" },
    "skills/webapp/github-star-button":       { "release-type": "simple", "component": "github-star-button" }
  }
}
```

which makes the commit scope the thing that decides what moves:

```text
feat(making-of): require a machinery tour     ->  making-of 1.0.0 -> 1.1.0
fix(push-to-surge): correct the publish dir   ->  push-to-surge 1.0.0 -> 1.0.1
docs: tidy the index                          ->  nothing bumped
```

Merging the Release PR tags each released skill (`making-of-v1.1.0`) and then publishes it.
Two tags per skill: the semver one, and a floating `latest`.

## The design argument I lost, which fixed a bug I didn't know about

My first sketch published a floating **`v1`** alongside `1.1.0`, the way GitHub Actions
does with `actions/checkout@v4`. The objection came back immediately, and it was right on
two counts: "the newest 1.x" is a *range* a resolver computes from the tag list, so a
major-only tag duplicates work that resolution already does; and worse, `version: v1` in a
manifest **reads like a pin while quietly moving underneath you**. `latest` at least cannot
be misread — nobody thinks `latest` is stable.

The reason GitHub Actions needs floating majors is that it has no range syntax at all; the
tag *is* the mechanism. diderot will have ranges ([#19](https://github.com/sunix/diderot/issues/19)),
so it doesn't need the crutch.

Then the interesting part. Choosing `latest` sent me to check what diderot does when a
manifest omits `version:` on an OCI source, and there it was, in code I wrote in part two:

```java
String tag = skill.version == null || skill.version.isBlank() || "HEAD".equals(skill.version)
        ? "latest"
        : skill.version;
```

A documented default, resolving `latest` — and never once exercised against a registry,
because nothing I publish had ever pushed that tag. So the documented behaviour did this:

```console
$ cat diderot.yaml
skills:
  - name: making-of
    source: oci://ghcr.io/sunix/skills/making-of      # no version: line at all
$ diderot update
error: Response code: 404
```

A design decision about tag naming, taken for entirely different reasons, turned out to
repair a latent bug in the tool. (That error message deserves its own complaint:
`Response code: 404` names neither the reference that failed nor the tags that do exist.
Filed for later.)

## Trap one: I renamed a workflow and made it untestable

The publish workflow needed to serve two callers now — the release job, and manual
dispatches — so I renamed it from `push-skill-to-oci.yml` to the more accurate
`publish-skills.yml`. Then went to test it:

```console
$ gh workflow run publish-skills.yml --ref feat/per-skill-releases
HTTP 404: workflow publish-skills.yml not found on the default branch
```

Third time this rule has bitten in three chapters: **`workflow_dispatch` only works once
the file exists on the default branch.** A modification to an existing workflow can be
tested with `--ref`; a *renamed* one is a new file, so it cannot be tested until merged.
Renaming also abandons the workflow's run history in the Actions UI.

So the rename went back. `push-skill-to-oci.yml` is a slightly less apt name for a file
that now also gets called by the release job — and that is a much cheaper price than
merging blind.

## Trap two: the same permission wall, on a different repository

With the rename reverted and the publish path tested, merging the whole thing produced
this:

```text
release-please failed: GitHub Actions is not permitted to create or approve pull requests.
```

The identical wall from part three, which cost five silent red runs on this repository.
The setting is **per repository**, so having enabled it for diderot did precisely nothing
for ai-skills. release-please had again done all its work first — read all five
`version.txt` files, computed the bumps, written five changelogs, pushed its branch — and
died on the final API call.

Hitting the same thing twice is what finally moved the lesson somewhere useful. It had been
sitting in diderot's `AGENT.md`, a file nobody rereads. It now lives in
[the release-please skill](https://github.com/sunix/ai-skills/pull/27) itself, which tells
the agent to **say it out loud** to the user rather than filing it, and to go check that
workflow's run status after the first merge instead of assuming that no Release PR means
nothing to release.

The skill I wrote to automate releases now warns about the thing that stopped my releases,
twice. That is either satisfying or embarrassing, and I haven't decided which.

## Trap three: the release cannot trigger the publish

One more that part three had already predicted: a Release created with the default
`GITHUB_TOKEN` cannot trigger another workflow, so `on: release: published` would never
fire. Rather than requiring a PAT, the publish job is **chained inside the same run**:

```yaml
  publish:
    needs: release-please
    if: needs.release-please.outputs.releases_created == 'true'
    uses: ./.github/workflows/push-skill-to-oci.yml
    with:
      paths: ${{ needs.release-please.outputs.paths_released }}
```

`paths_released` is the list of packages release-please actually released, so the publish
step pushes exactly those skills and nothing else. No PAT, no secret to rotate, no second
workflow waiting on an event that never arrives.

## Trap four: pushing the same bytes twice gives two different digests

With everything green, the first real run published five skills under two tags each. Watch
the digests of one skill:

```console
==> skills/webapp/github-star-button -> ghcr.io/…/github-star-button:1.0.0 (and :latest)
pushed … 1.0.0@sha256:eed5e241191966773afa46e4521732dba2fa1ea0c5a8db2e54f428efe4c7a413
pushed … latest@sha256:19b3cac9f80731a25bcbb74671faa56c522bb6c089a286ebbd87404a8e47ecf2
```

Identical content, two different manifests. The cause is in the SDK, in code I had read in
part two without registering what it implied:

```java
if (!manifestAnnotations.containsKey(Const.ANNOTATION_CREATED) && containerRef.getDigest() == null) {
    manifestAnnotations.put(Const.ANNOTATION_CREATED, Const.currentTimestamp());
}
```

A fresh `org.opencontainers.image.created` per push, so every push is a new manifest.

Nothing is broken for consumers, and I checked that rather than assuming it — resolving
either tag locks the *same* content digest:

```text
locked pinned    …@sha256:eed5e2411919 (tree:7ed2bd85eefa884a860dcd613f9502ce0e258763)
locked floating  …@sha256:19b3cac9f807 (tree:7ed2bd85eefa884a860dcd613f9502ce0e258763)
```

But it is an awkward property for a tool whose whole design rests on content addressing:
**the same bytes should produce the same identifier.** It also quietly weakens the promise
I had just written into ai-skills' contributing guide — a semver tag *looks* immutable, yet
re-running a publish would repoint it at a new manifest. The guide now says so, and
[#21](https://github.com/sunix/diderot/issues/21) holds the two ways out: make the push
deterministic, or add a `diderot tag` that re-points an existing manifest instead of
uploading another one.

There is a small irony in the timestamp, too. Because each push produces its own manifest,
deleting the legacy `v1` tags was safe: they had manifests of their own, distinct from
`1.1.0` and `latest`. Had pushes been idempotent, `v1` and `1.1.0` would have shared one
manifest, and deleting either would have taken the other with it.

## Proof

Five jobs, then the chained publish, then five skills at `1.1.0` and `latest`:

```text
run: success
  success  release-please
  success  publish / publish
```

The changelogs are the part that proves the monorepo config really did split by path
rather than dumping every commit into every skill. `making-of` got its own five `feat`
entries; `push-to-surge`, which those commits never touched, got two:

```text
=== making-of
* **making-of:** goal-first entries, oracle proofs, code walkthroughs ([#18])
* **making-of:** require a machinery tour when a session creates several files ([#19])
* **making-of:** require code and commands to read shoulder-to-shoulder ([#24])
…
=== push-to-surge
* adopt Agent Skills format and become a Claude Code plugin marketplace ([#17])
* per-skill releases, published as semver plus latest ([#25])
```

And then the thing this whole detour was for. A manifest with **no `version:` line at
all** — the case that returned a 404 that morning:

```console
$ diderot update
locked making-of        …@sha256:fbfdef0d7375 (tree:89f4bb27c343…)
locked release-please   …@sha256:cbb9d438a856 (tree:1a6bc020a24c…)
$ diderot install && diderot status
ok  making-of       .claude/skills/making-of
ok  release-please  .claude/skills/release-please
```

Cross-checked against the oracle, as ever:

```console
$ git -C ai-skills rev-parse main:skills/documentation/making-of
89f4bb27c343d75cc6b9dfa6494bf508f221d89b
$ git -C ai-skills rev-parse main:skills/github-actions/release-please
1a6bc020a24cd1dee000c54441f92d855ce8b4e7
```

The registry now shows the transition, mid-flight:

```json
"tags": ["v1", "1.1.0", "latest"]
```

`v1` frozen on content nothing updates any more, the semver tag beside it. Deleting the
five legacy `v1` tags was the last step — and needed a token scope I don't hold, so it
went back to a human with a browser, which is the correct place for "permanently delete
published artefacts" to live anyway.

## What this chapter leaves open

`^1.0.0` still doesn't work: real semver tags now exist to resolve *against*, but the
resolver that would compute a range from them is [#19](https://github.com/sunix/diderot/issues/19),
untouched. This chapter built the prerequisite, not the feature.

Two diderot defects came out of it and neither is fixed: `push` isn't idempotent
([#21](https://github.com/sunix/diderot/issues/21)), and a failed tag resolution says
`Response code: 404` without naming the reference or listing what the repository actually
publishes.

A stray `1.0.0` remains on `github-star-button` — a tag from testing the publish workflow
before merge, with no release behind it. Harmless, and exactly the kind of thing a range
resolver would one day resolve to, so it should go.

And the queue is unchanged: `add` and `remove` next, since declaring a skill still means
hand-editing `diderot.yaml`.
