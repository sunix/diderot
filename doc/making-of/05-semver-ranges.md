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

`version:` has to carry two different kinds of value. Either a tag name, used as written:

```yaml
version: 1.2.0        # the tag called 1.2.0
version: latest       # the tag called latest
```

or a range, which is not a name at all but a question about the whole tag list:

```yaml
version: "^1.0.0"     # of everything published, the highest below 2.0.0
```

Those are opposite code paths — one request against one reference, versus list-everything-and-
compare — so something has to decide which kind each string is. That decision is one method
returning one boolean, `VersionConstraint.isRange`, consulted from exactly one place. Everything
else in the feature follows from what it answers, which is why a chapter can be spent on it.

Comparing versions was never the open question. Pre-release ordering and npm's range grammar are a
classic source of subtle bugs, so that job goes to [semver4j](https://github.com/semver4j/semver4j)
(`org.semver4j:semver4j`) rather than to eighty lines of my own. What was open is the classification
*above* it — and my instinct was to delegate that too: hand the string to semver4j's
`RangeListFactory`, and if it parses, call it a range. The familiar try-to-parse-and-fall-back idiom.

A classifier is only useful if it can say no, so the question I actually needed answering was:
**which of these would the parser refuse?** Every string below is something a real manifest holds.

```text
RangeListFactory.create("^1.0.0")      -> >=1.0.0 and <2.0.0
RangeListFactory.create("~1.2.0")      -> >=1.2.0 and <1.3.0
RangeListFactory.create(">=1.0.0 <2")  -> >=1.0.0 and <2.0.0
RangeListFactory.create("1.2.x")       -> >=1.2.0 and <1.3.0

RangeListFactory.create("1.0.0")       -> =1.0.0
RangeListFactory.create("v1.0.0")      -> =1.0.0
RangeListFactory.create("1.0.0-rc.1")  -> =1.0.0-rc.1

RangeListFactory.create("latest")      -> >=0.0.0
RangeListFactory.create("v1")          -> >=1.0.0 and <2.0.0
RangeListFactory.create("main")        -> (empty, and no exception)
RangeListFactory.create("1h")          -> (empty, and no exception)
RangeListFactory.create("garbage!!")   -> (empty, and no exception)
```

None of them, is the answer. The first group really is ranges. The second group is worth knowing
about — an exact version *is* a range, `=1.0.0` — and is harmless, since resolving `=1.0.0` against
the tag list lands on `1.0.0` anyway. The third group is where the idea dies, and it dies twice.

`latest` comes back as `>=0.0.0`. That is not a version but a range meaning *at least 0.0.0*, and
since every semver version is at least 0.0.0, it is the range everything satisfies — "whatever is
newest".

`v1` comes back as `>=1.0.0 and <2.0.0`, which is to say "the newest 1.x". Part four deleted the
`v1` tags from the registry precisely because `v1` **reads like a pin while moving underneath you**.
Delegate classification and diderot would not merely have tolerated that misreading, it would have
implemented it.

And the refusals are not refusals you can use: `main`, `1h` and `garbage!!` all come back as an
empty range list without raising, so a branch name somebody deliberately tracks is indistinguishable
from a typo.

npm's grammar has no failure mode for an arbitrary word and semver4j follows npm faithfully. What
comes back is a best-effort reading — exactly the right behaviour for a parser, and exactly the
wrong input for a decision.

That is worth watching rather than arguing about, so I wrote the tempting version first. One line
carries the whole of it:

```java
// delegated to the range parser
public static boolean isRange(String version) {
    if (version == null || version.isBlank()) {
        return false;
    }
    return !RangeListFactory.create(version.trim()).get().isEmpty();   // "it parsed, so it's a range"
}
```

Then I needed a registry to try it against, arranged the way registries really are rather than the
way a test would flatter me: two releases, and a moving tag that is *not* on the newest one. It is
short enough to run yourself — a registry in a container, two skill directories, then three pushes.
Watch the last one: it publishes `latest` from the **same `v1` directory content** as `1.0.0`.

```console
$ docker run -d --rm -p 127.0.0.1:5000:5000 registry:2
$ mkdir v1 v2
$ printf -- '---\nname: demo\n---\nThis is 1.0.0.\n' > v1/SKILL.md
$ printf -- '---\nname: demo\n---\nThis is 2.0.0.\n' > v2/SKILL.md
$ diderot push v1 oci://127.0.0.1:5000/skills/demo:1.0.0
$ diderot push v2 oci://127.0.0.1:5000/skills/demo:2.0.0
$ diderot push v1 oci://127.0.0.1:5000/skills/demo:latest
```

Which is where the misunderstanding this whole section exists to prevent lives, so it is worth
stopping on. That third push is not a trick I set up to make a demo work — it is the ordinary thing.
In a registry, `latest` is **a name, not a computation**: nothing derives it from the version
numbers, it is simply a tag pointing at an artifact, exactly as `1.0.0` is. A publisher pushes it
deliberately, the same way they push `stable` or `edge`, and `docker pull ubuntu:latest` does not
hand you the highest-numbered Ubuntu — it hands you whatever the maintainers decided to put there.
So a repository holding `2.0.0` while `latest` stays on `1.0.0` is a decision, not an oversight: a
publisher shipping a major rewrite routinely keeps `latest` on the proven line until the new one has
earned it.

So, a manifest asking for the moving tag by name:

```yaml
skills:
  - name: demo
    source: oci://127.0.0.1:5000/skills/demo
    version: latest
```

and that manifest against that registry, with classification delegated to the parser:

```console
$ diderot update
locked demo   127.0.0.1:5000/skills/demo:2.0.0@sha256:6279987133b8 (tree:3acde171bcc0…)
$ diderot install
installed demo -> .claude/skills/demo (tree:3acde171bcc0… verified)
$ tail -1 .claude/skills/demo/SKILL.md
This is 2.0.0.
```

`2.0.0` is genuinely the highest version in that repository, and that is not in dispute — it is just
not what `latest` points at. The manifest asked for `latest`, `latest` is `1.0.0`, and what landed on
disk is `2.0.0`: **a different artifact, different bytes**. There it is, in one line. And it looks
like a success, which is the trap — `2.0.0` is a real release with a newer number, so the eye slides
straight over it.

Turn that into a publisher and a consumer and it stops being abstract. The publisher has shipped a
major rewrite and is deliberately keeping `latest` on the 1.x line until it has proven itself. Every
consumer who wrote `latest` for exactly that reason gets handed the breaking major anyway — manifest
still reading `latest`, and nothing on screen suggesting a substitution took place.

"Newest release" was never the unsayable thing here, either: the range syntax says it, with `*` or
`^2.0.0`. `latest` is simply not how you spell it.

So classification is syntactic and never delegated: a constraint is a range only if it is written
like one.

```java
// kept syntactic
public static boolean isRange(String version) {
    if (version == null || version.isBlank()) {
        return false;
    }
    String v = version.trim();
    return RANGE_SYNTAX.matcher(v).find() || X_RANGE.matcher(v).matches();
}
```

Which reduces "written like a range" to two patterns:

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

`^1.0.0` contains a caret, `>=1.0.0 <2` contains comparators and a space, `1.2.x` matches the second
pattern. `latest`, `main`, `v1` and `1.2.0` contain none of it and are therefore names, handed to the
registry exactly as the author typed them. Same manifest, same registry, same commands as above:

```console
$ diderot update
locked demo   127.0.0.1:5000/skills/demo:latest@sha256:f0594fc722ab (tree:5c467d704854…)
$ diderot install
installed demo -> .claude/skills/demo (tree:5c467d704854… verified)
$ tail -1 .claude/skills/demo/SKILL.md
This is 1.0.0.
```

`latest` resolved to `latest`, which is `1.0.0`, which is what the publisher put there.

Two regexes standing between the tool and a bug with no symptom. The lesson is not about semver4j,
which behaves exactly as npm does — it is that **a permissive parser makes a terrible classifier**,
because a classifier's entire job is being able to say no. Finding that cost one throwaway program
run before any design work, rather than one confused bug report after a release.

There is a test named after it, so nobody deletes the rule as redundant:

```java
@Test
void latestIsATagEvenThoughARangeParserWouldAcceptIt() {
    assertFalse(VersionConstraint.isRange("latest"));
}
```

## What the resolver does, once classification is settled

Start with `VersionConstraint.select`, which is the whole of the range logic. Before the code, what
it is being asked for. Two things go in — the constraint exactly as the author wrote it, and the tag
list exactly as the registry hands it over, junk and all — and one tag comes back, or nothing:

```text
tags:  1.0.0   1.1.0   1.2.3   2.0.0   1.3.0-rc.1   latest   main   1h   1.1

"^1.0.0"      -> 1.2.3     highest below 2.0.0
"~1.1.0"      -> 1.1.0     highest below 1.2.0
">=1.0.0 <2"  -> 1.2.3
"*"           -> 2.0.0     anything satisfies it, so the highest release wins
"^3.0.0"      -> nothing   which has to become an error, not a guess
```

Two of the requirements hide in that tag list rather than in the ranges. `1.3.0-rc.1` is numerically
higher than `1.2.3` and must still lose, because a pre-release is not something a caret range asks
for. And `latest`, `main`, `1h` and `1.1` are not versions at all — `1.1` included, since semver
wants three components — so they have to be stepped over without a word, because their presence in
a repository is normal and is nobody's mistake.

Which raises the question anyone arriving from npm will ask, since the whole point of taking a
library is not writing this by hand: doesn't semver4j resolve a range against a set of versions?
It does not. npm's `semver` package has `maxSatisfying(versions, range)`; semver4j has no equivalent
and, checking the jar rather than assuming, no collection-level API at all. It answers *"does this
one version satisfy this range?"* and *"which of these two versions is higher?"*, and stops there.

Which is the right split, as it turns out. The part that is genuinely hard to get right — the range
grammar, and where a pre-release sorts against a release — is the part I did not write. What is left
is a loop over a list, and it fits in one:

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

And the `cmp == 0` comparison is there for a repository carrying both `1.2.3` and `v1.2.3`.
They are the same version, so without a tie-break the winner would depend on the order the registry
happened to list its tags in. Deterministic beats lucky.

Then the caller, where the three things a `version:` can be become three cases — and where
`select` is finally reached:

```java
private String resolveTag(ManifestSkill skill, SourceRef ref) throws IOException {
    // "HEAD" is the git-flavored default; for a registry the moving default tag is "latest".
    if (skill.version == null || skill.version.isBlank() || "HEAD".equals(skill.version)) {
        return "latest";
    }
    if (!VersionConstraint.isRange(skill.version)) {
        return skill.version;
    }
    List<String> tags = listTags(skill, ref);
    Optional<String> chosen = VersionConstraint.select(skill.version, tags);
    if (chosen.isEmpty()) {
        throw new IOException("Skill '" + skill.name + "': no tag in " + ref.url()
                + " satisfies " + skill.version + ". " + describeTags(tags));
    }
    return chosen.get();
}
```

The first case is the absent constraint: no `version:` line at all, and a registry's moving default
is `latest` where git's is `HEAD`. The second is the compatibility promise — anything not
written like a range takes the path it took before this feature existed, so no manifest in the world
changes meaning because diderot learned about ranges. Only the third talks to the registry,
and its three lines are the shape of the whole feature: list the tags, choose one, and refuse to
carry on if nothing fits. That refusal is the deliberate part — an empty answer from `select` is
never allowed to fall through to something plausible-looking, it becomes an error naming the range
and listing what the repository does publish.

That `listTags` is the genuinely new thing, and it is worth naming because "the tag list" is a phrase
this chapter leans on. Resolving a literal tag is one request for one reference — *give me the
manifest of `demo:1.0.0`*. A range cannot be answered that way, because the question is about
everything published, so it takes a call diderot had never needed before:
`GET /v2/<repository>/tags/list`, which is the registry telling you every tag it holds.

```java
/**
 * Every tag the repository advertises, in registry order. The SDK's single-argument
 * {@code getTags} already follows the pagination links and accumulates the pages (with a guard
 * against a registry that keeps pointing at itself), so a long tag list needs no paging here.
 */
public List<String> listTags(String repository) {
    Tags tags = registryFor(repository).getTags(ContainerRef.parse(repository));
    return tags.tags() == null ? List.of() : List.copyOf(tags.tags());
}
```

Two statements, and the interesting part is the comment above them. My first instinct was to write the paging
loop myself, since a repository with hundreds of tags will hand them over a page at a time behind
`Link` headers. Reading the SDK first saved that work: `getTags` already follows the links,
accumulates the pages, and guards against a registry whose `Link` header points back at itself. So
the paging is handled, and I know it is handled because I went and looked rather than assuming
either way.

## What didn't change, which is the point

`install` and `status` were not touched. Not adapted, not extended — untouched. They read the
pinned digest and nothing else, so *how* resolution happened is invisible to them, exactly as
[part two](02-oci-push-and-pull.md) found when the OCI backend arrived and the same two verbs
needed no changes either. Twice is enough to call it a property rather than a coincidence:
resolution is the only layer allowed to be clever, and reproducibility lives below it.

## Two side effects worth keeping

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

**A failed resolution now says what exists.** Part four filed a complaint against diderot's own
error message — *"`Response code: 404` names neither the reference that failed nor the tags that do
exist"* — and that is better seen than paraphrased. Here is the released `v0.1.0` binary asked for
a tag that isn't there, against a repository publishing `1.0.0` and `2.0.0`:

```console
$ diderot update
error: Response code: 404
```

That is the entire message. It doesn't name the skill, the reference it tried, or either of the two
tags that would have worked, so the only way forward is to go and list the repository by hand. The
same manifest, on this branch:

```console
$ diderot update
error: Skill 'demo': cannot resolve 127.0.0.1:5000/skills/demo:3.0.0 (Response code: 404).
       Published versions, newest first: 2.0.0, 1.0.0.
```

Nothing clever happened there, and that is the point of it being in this chapter rather than in a
bug fix of its own: reading the tag list is something the resolver now has to do anyway, so making a
failure explain itself cost one call and a `catch`. Literally a catch — the registry error is not
replaced, it is wrapped with the two facts it was missing:

```java
String digest;
try {
    digest = oci.resolveDigest(ref.url() + ":" + tag);
} catch (RuntimeException e) {
    // Bare registry errors name neither the reference that failed nor what does exist.
    throw new IOException("Skill '" + skill.name + "': cannot resolve " + ref.url() + ":" + tag
            + " (" + e.getMessage() + "). " + publishedTags(ref), e);
}
```

And the helper it calls has the one decision in this whole fix worth pausing on:

```java
/** What the repository does publish - the context a bare 404 leaves out. */
private String publishedTags(SourceRef ref) {
    try {
        return describeTags(oci.listTags(ref.url()));
    } catch (RuntimeException e) {
        return "Its tag list could not be read either (" + e.getMessage() + ").";
    }
}
```

Fetching the tag list to explain a failure means doing registry work *inside* an error path, where it
can fail too — a repository that doesn't exist, or credentials that don't. If that second call threw,
its exception would replace the first, and the user would be told about the tag listing instead of
about the thing they actually asked for. So it degrades to a sentence and lets the original error
stand. An error handler that can fail is worse than no error handler.

Ranges fared worse still before this branch, by the way — not rejected as unsupported, just pasted
into a URL:

```console
$ diderot update
error: Illegal character in path at index 47: http://127.0.0.1:5000/v2/skills/demo/manifests/^9.0.0
```

## Proof

Twenty-five tests, twelve of them new. The nine unit tests pin the classification rules down. The
three integration tests run against a real `registry:2` container, and the caret one is worth reading
with its setup rather than as a pair of assertions, because an assertion is only as good as the
situation it was made in. Trimmed only of plumbing — the client, the temp directories, the
`Workspace` construction:

```java
Map<String, String> pushed = new LinkedHashMap<>();
for (String version : List.of("1.0.0", "1.1.0", "1.2.3", "2.0.0", "1.3.0-rc.1")) {
    pushed.put(version, publish(oras, repository, version, "Release " + version + ".\n"));
}
// The moving tags a registry always carries beside its releases.
publish(oras, repository, "latest", "Whatever is newest.\n");
publish(oras, repository, "main", "Whatever is on main.\n");

Files.writeString(project.resolve("diderot.yaml"), """
        skills:
          - name: ranged
            source: oci://%s
            version: "^1.0.0"
        targets: [claude]
        """.formatted(repository));

LockFile lock = workspace.update();

assertEquals("1.2.3", lock.skills.get(0).tag,
        "^1.0.0 excludes 2.0.0 as a major bump, 1.3.0-rc.1 as a pre-release, "
                + "and latest/main as unparseable");
assertEquals(pushed.get("1.2.3"), lock.skills.get(0).resolved,
        "the lock pins the exact manifest digest that publishing 1.2.3 produced");
assertTrue(output.toString().contains(":1.2.3@"),
        "update names the tag it chose, since a range makes the answer invisible otherwise");

workspace.install(null);
assertEquals("---\nname: ranged\nversion: 1.2.3\n---\nRelease 1.2.3.\n",
        Files.readString(project.resolve(".claude/skills/ranged/SKILL.md")),
        "the content on disk is 1.2.3's, so the range resolved to a release and not just a digest");
assertEquals(0, workspace.status(null));
```

Read the top half as the setup deciding what the answer has to be. Five releases go up, each with its
own content, and `pushed` remembers the manifest digest every push returned. Then the two tags a
registry always carries alongside its releases. The manifest declares exactly one skill, which is why
`lock.skills.get(0)` is `ranged` and not an arbitrary index.

Which means every candidate the range has to reject is really in that registry: `2.0.0` is a major
bump, `1.3.0-rc.1` is numerically *higher* than the right answer but is a pre-release, and `latest`
and `main` are not versions at all. Only `1.2.3` survives, and it survives for four different
reasons at once.

Two of the assertions are the ones I would want a reviewer to check. The expected digest is never
written into the test — it is whatever `publish` returned for `1.2.3`, so the test cannot drift into
agreeing with a hardcoded value. And the last one reads the file that actually landed on disk, so
passing means 1.2.3's **content** is installed, not merely that some digest matched.

One of the three exists purely to defend the discovery earlier in this chapter: it publishes `1.0.0`,
`latest` and `2.0.0`, then asserts that `version: latest` locks the digest of `latest`.

```text
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Then the part that isn't a test: the same two ranges against ghcr.io, resolving the skills part four
published, with no credentials of any kind.

```console
$ diderot update
locked release-please  …/release-please:1.2.0@sha256:f1ecb79525a4 (tree:d87753e53a07325bdeb60f35fb45b929ae4c6b33)
locked making-of       …/making-of:1.1.0@sha256:8b81085393c4 (tree:89f4bb27c343d75cc6b9dfa6494bf508f221d89b)
$ diderot install && diderot status
ok  making-of       .claude/skills/making-of
ok  release-please  .claude/skills/release-please
```

Then the check this journal keeps coming back to. That `tree:` value is a git tree hash diderot
computes itself, in pure Java, without ever opening a `.git` directory — so real git can be asked the
same question about the same directory, and answer independently:

```console
$ git -C ai-skills rev-parse origin/main:skills/github-actions/release-please
d87753e53a07325bdeb60f35fb45b929ae4c6b33
$ git -C ai-skills rev-parse origin/main:skills/documentation/making-of
89f4bb27c343d75cc6b9dfa6494bf508f221d89b
```

The same forty characters as the two `tree:` values above, both of them. Which is all this check
needs to say: the range picked a release, everything below the choice produced exactly what it
produced before, no regression.

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
