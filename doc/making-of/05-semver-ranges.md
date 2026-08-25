# Newest 1.x, and the trap inside `latest`

*Part five of [diderot's making-of](../../MAKING-OF.md): the feature part four built the
prerequisite for, and the five-minute experiment that changed how it works.*

## The goal: a manifest that can say "newest 1.x"

[Part four](04-releasing-the-skills.md) ended on an admission. It had gone to considerable
trouble to publish real semver tags, and then closed with this: *"`^1.0.0` still doesn't work:
real semver tags now exist to resolve against, but the resolver that would compute a range from
them is [#19](https://github.com/sunix/diderot/issues/19), untouched. This chapter built the
prerequisite, not the feature."*

So a consumer could pin, and could track a moving tag, and could not do the thing dependency
managers exist for. What I wanted to write:

```yaml
skills:
  - name: release-please
    source: oci://ghcr.io/sunix/skills/release-please
    version: "^1.0.0"      # newest 1.x, whatever that is today
```

and what I wanted back is a lock pinned to a specific release, chosen by comparing what the
registry publishes:

```console
$ diderot update
locked release-please  ghcr.io/sunix/skills/release-please:1.2.0@sha256:f1ecb79525a4 (tree:d87753e53a07…)
```

Note the `:1.2.0` in that line. It wasn't there before, and it turned out to matter — more on
that below.

## The five-minute experiment that changed the design

The plan was ordinary: don't hand-roll semver comparison, because pre-release ordering and range
grammar are a famous source of subtle bugs. `org.semver4j:semver4j` does both. The only real
question looked like a formality: **how do I tell a range from a plain tag?**

The obvious answer is to ask the library. If it parses as a range, it's a range. Before writing
that, I ran the library against the strings a manifest actually contains — and the third line is
why this chapter exists:

```text
RangeListFactory.create("^1.0.0")     -> >=1.0.0 and <2.0.0
RangeListFactory.create("garbage!!")  ->                      (no exception either)
RangeListFactory.create("latest")     -> >=0.0.0
```

semver4j reads `latest` as **"any version"**. Handed that to a resolver and `version: latest` stops
fetching the tag named `latest` and starts resolving to the newest release in the repository — a
pin that quietly becomes a moving target, which is the precise failure mode
[part one](01-from-a-name-to-a-git-lockfile.md) spent pages arguing against, and the same mistake
part four caught me making with `v1`. Twice now the moving-target problem has come back wearing a
different hat.

Worse, it fails silently. `latest` resolving to `2.0.0` looks like it worked.

So classification is **syntactic** and never delegated: a constraint is a range only if it is
written like one.

```java
/**
 * Characters that appear only in a range: the comparators, the caret and tilde shorthands, the
 * union operator, the wildcard, and the space that separates an intersection such as
 * {@code >=1.0.0 <2}.
 */
private static final Pattern RANGE_SYNTAX = Pattern.compile("[\\^~><=|*\\s]");

/** X-ranges ({@code 1.x}, {@code 1.2.X}) contain no operator, so they need a rule of their own. */
private static final Pattern X_RANGE = Pattern.compile("v?\\d+(\\.\\d+)?\\.[xX]");
```

Two regexes and a comment, standing between the tool and a bug that would have been very hard to
notice from the outside. The lesson is not about semver4j, which is behaving exactly as npm does —
it is that a permissive parser is a terrible classifier, and that finding this cost one throwaway
program run before any design, rather than one confused bug report after release.

There is a test named after it, so nobody deletes the rule as redundant:

```java
@Test
void latestIsATagEvenThoughARangeParserWouldAcceptIt() {
    assertFalse(VersionConstraint.isRange("latest"));
}
```

## What the resolver does, once classification is settled

Start with `VersionConstraint.select`, which is the whole of the range logic. It is shorter than
the discussion above deserves:

```java
public static Optional<String> select(String range, List<String> tags) {
    Semver best = null;
    String bestTag = null;
    for (String tag : tags) {
        Semver candidate = parseOrNull(tag);
        if (candidate == null || !satisfiesOrFalse(candidate, range)) {
            continue;
        }
        int cmp = bestTag == null ? 1 : candidate.compareTo(best);
        if (cmp > 0 || (cmp == 0 && tag.compareTo(bestTag) < 0)) {
            best = candidate;
            bestTag = tag;
        }
    }
    return Optional.ofNullable(bestTag);
}
```

Three details in there are deliberate rather than incidental.

`parseOrNull` returning null instead of throwing is what lets a range survive a real registry. A
repository holds `latest`, `main`, date stamps, a stray `1h` from someone testing against
`ttl.sh` — and a range must skip all of it in silence rather than failing because a human once
pushed a badly named tag.

The tag comes back **as written**, not as the parsed version, because `v1.2.3` and `1.2.3` are both
common in the wild and only the literal string exists in the registry. semver4j accepts the `v`
prefix on the way in, which I checked rather than hoped.

And the `cmp == 0` branch exists for the case where a repository carries both `1.2.3` and `v1.2.3`.
They are the same version, so without a tie-break the winner would depend on the order the registry
happened to list its tags in. Deterministic beats lucky.

Then the caller decides whether any of that applies:

```java
private String resolveTag(ManifestSkill skill, SourceRef ref) throws IOException {
    // "HEAD" is the git-flavored default; for a registry the moving default tag is "latest".
    if (skill.version == null || skill.version.isBlank() || "HEAD".equals(skill.version)) {
        return "latest";
    }
    if (!VersionConstraint.isRange(skill.version)) {
        return skill.version;
    }
    ...
}
```

Read the middle branch as the compatibility promise: anything that isn't written like a range takes
the path it took before this feature existed, so no manifest in the world changes meaning because
diderot learned about ranges.

## What didn't change, which is the point

`install` and `status` were not touched. Not adapted, not extended — untouched. They read the
pinned digest and nothing else, so *how* resolution happened is invisible to them, exactly as
[part two](02-oci-push-and-pull.md) found when the OCI backend arrived and the same two verbs
needed no changes either. Twice is enough to call it a property rather than a coincidence:
resolution is the only layer allowed to be clever, and reproducibility lives below it.

## Two things that fell out of needing the tag list

**The lock now records the tag it landed on.** This was not in the plan; it became obvious the
first time I read a lock produced by a range. `resolved` is a manifest digest, which is the right
thing to verify and a useless thing to read:

```yaml
- name: release-please
  source: oci://ghcr.io/sunix/skills/release-please
  resolved: sha256:f1ecb79525a464d5afe7aa5b2ea9a548221a8d73247756127dc5b3ffe23f6771
  tag: 1.2.0
  digest: tree:d87753e53a07325bdeb60f35fb45b929ae4c6b33
```

Ask for `^1.0.0` and the answer to "which release am I on?" was previously an opaque hash. The
field is informational — `install` never reads it, and it is honest about its own weakness: it
records that *at lock time* this tag pointed at that digest. A tag can be re-pushed and this line
cannot notice, which is exactly why the digest, not the tag, stays the authority. Part four's
[#21](https://github.com/sunix/diderot/issues/21) makes that less theoretical than it sounds.

**A failed resolution finally says what exists.** Part four filed a complaint about diderot's own
error message: *"`Response code: 404` names neither the reference that failed nor the tags that do
exist."* Listing tags is now something the resolver does anyway, so the fix cost almost nothing.
Run against the real repository:

```console
$ diderot update
error: Skill 'release-please': no tag in ghcr.io/sunix/skills/release-please satisfies ^9.0.0.
       Published versions, newest first: 1.2.0, 1.1.0.
```

Both lines of that are load-bearing: the range that failed, and the answer to the question the
user is about to go and look up by hand.

## Proof

Twenty-five tests, twelve of them new. The nine unit tests pin the classification rules down; the
three integration tests run against a real `registry:2` container, and they are built so that
passing means something specific. Every release carries **different content**, so the assertion is
about which release landed on disk and not merely about a matching hash — and the expected digest is
never written into the test, it is whatever publishing `1.2.3` returned:

```java
assertEquals("1.2.3", lock.skills.get(0).tag,
        "^1.0.0 excludes 2.0.0 as a major bump, 1.3.0-rc.1 as a pre-release, "
                + "and latest/main as unparseable");
assertEquals(pushed.get("1.2.3"), lock.skills.get(0).resolved,
        "the lock pins the exact manifest digest that publishing 1.2.3 produced");
```

One of the three exists purely to defend the discovery above: it publishes `1.0.0`, `latest` and
`2.0.0`, then asserts that `version: latest` locks the digest of `latest` rather than of `2.0.0`.

```text
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Then the part that isn't a test: the same two ranges against ghcr.io, resolving the skills part four
published, with no credentials of any kind.

```console
$ diderot update
locked release-please  ghcr.io/sunix/skills/release-please:1.2.0@sha256:f1ecb79525a4 (tree:d87753e53a07…)
locked making-of       ghcr.io/sunix/skills/making-of:1.1.0@sha256:8b81085393c4 (tree:89f4bb27c343…)
$ diderot install && diderot status
ok  making-of       .claude/skills/making-of
ok  release-please  .claude/skills/release-please
```

And cross-checked against the oracle, which is the habit this journal keeps returning to — the tree
digests a range produced, next to what real git says about the same directories:

```console
$ git -C ai-skills rev-parse origin/main:skills/github-actions/release-please
d87753e53a07325bdeb60f35fb45b929ae4c6b33
$ git -C ai-skills rev-parse origin/main:skills/documentation/making-of
89f4bb27c343d75cc6b9dfa6494bf508f221d89b
```

Character for character. `^1.0.0` picked a release, the digest chain held from a registry back to a
git tree hash computed in pure Java, and nothing in the verification path knew a range had been
involved.

## The mistake: I branched from the wrong branch

Worth recording because it was invisible and because the review caught it, not me.

The pull request looked wrong on GitHub: it claimed to add
`doc/making-of/04-releasing-the-skills.md`, a file already merged into `main`. Eleven files and 736
insertions, where my commit had nine files and 446.

Nothing was wrong with the code. My working copy was still on part four's branch from the previous
session, and I had run `git checkout -b feat/semver-ranges` without looking at where I was standing.
Part four had been **squash-merged**, so `main` carried that chapter under a new commit while my
branch carried the original one. Same content, two commits, and a merge base sitting before both of
them — so the diff honestly reported the chapter as new.

```console
$ git log --oneline origin/main..HEAD
988a74c feat(oci): resolve semver ranges over registry tags
6650187 docs: add chapter four, releasing the skills for real     # already in main as 947517e
```

The fix was one command, because the content was identical and the replayed commit therefore became
empty:

```console
$ git rebase --onto origin/main 6650187 feat/semver-ranges
Successfully rebased and updated refs/heads/feat/semver-ranges.
```

The uncomfortable part is what would have happened unnoticed. Git would **not** have complained at
merge time: two identical additions of the same file conflict with nothing. There would have been no
error, just a duplicated chapter-four commit buried in the history and a pull request whose diff
misrepresented itself to every reviewer. A conflict announces itself; this doesn't. Checking the
branch point before creating a branch is cheaper than fetching after.

## What this chapter leaves open

**The native binaries are unproven with this dependency.** `v0.1.0` ships GraalVM builds, so a new
dependency has to survive `native-image`. The static evidence is about as good as it gets — semver4j
is 46 KB of pure Java with no reflection, no `Class.forName`, no service loaders, no bundled
resources and no transitive dependencies — but the machine writing this had 2 GB of RAM available and
`native-image` peaks above 4 GB, so I did not run it. Recorded as unverified rather than dressed up
as checked; the release build is what will actually answer it.

**Ranges over git tags don't exist.** [#19](https://github.com/sunix/diderot/issues/19) raised them
as an eventual want and this chapter did not deliver them: on a git source, `version:` is still a
branch, a tag or a commit.

**And declaring a skill is still hand-editing YAML**, which is next
([#24](https://github.com/sunix/diderot/issues/24)). That issue turned up its own trap while being
written: `diderot.yaml` is authored, `diderot.lock` is generated, and the Jackson round-trip that is
perfectly correct for the second one **deletes every comment** in the first. So `add` and `remove`
are not a YAML-append exercise; they are a surgical-editing exercise, which is a different piece of
work than it looks.
