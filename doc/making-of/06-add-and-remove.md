# Adding the `add` and `remove` commands

*Part six of [diderot's making-of](../../MAKING-OF.md): declaring a skill without hand-editing
`diderot.yaml`, and why the easy implementation of that is destructive.*

## The goal: a command that adds a skill to `diderot.yaml`

Declaring a skill meant opening the manifest and typing the entry. Removing one meant deleting it and
remembering the lock. That is all `add` and `remove` are for.

Start from a project that already depends on one skill — and note the comments, because they matter
later:

```yaml
# Skills this project depends on.
# Keep making-of first: the others reference its style rules.
skills:
  - name: making-of          # the journal skill
    source: oci://ghcr.io/sunix/skills/making-of
    version: "^1.0.0"

targets: [claude]
```

One command declares a second one. The name is inferred from the source's last segment, so the common
case needs no `--name`:

```console
$ diderot add oci://ghcr.io/sunix/skills/release-please --version "^1.0.0"
added release-please       oci://ghcr.io/sunix/skills/release-please (^1.0.0)
locked release-please      ghcr.io/…/release-please:1.2.0@sha256:f1ecb79525a4 (tree:d87753e53a07…)
run `diderot install` to put it on disk.
```

And the manifest afterwards:

```yaml
# Skills this project depends on.
# Keep making-of first: the others reference its style rules.
skills:
  - name: making-of          # the journal skill
    source: oci://ghcr.io/sunix/skills/making-of
    version: "^1.0.0"
  - name: release-please
    source: oci://ghcr.io/sunix/skills/release-please
    version: "^1.0.0"

targets: [claude]
```

Three lines added, nothing else moved. `remove` is the same in reverse, `rm` is an alias for it, and
it reports what it actually found rather than what it assumed — here the skill had been declared and
pinned but never installed, so there was no directory to delete and it does not claim there was:

```console
$ diderot remove release-please
removed release-please     diderot.yaml, diderot.lock
```

## Why a command, when the file is right there

Editing that file by hand gets you the same declaration in the end: the same source, the same
constraint, the same content digest in the lock once `update` runs. What it also gets you is every
way of getting it slightly wrong. A mapping indented one space off. `sources:` instead of `source:`.
A name that collides with an entry further down. A typo in the registry path that looks fine and only
fails at the next `update`, by which time you have stopped thinking about it. And the lock, which is
easy to forget entirely, since the manifest looks finished without it.

`add` closes all of those in one stroke, and not by being careful with the YAML — by **resolving
before it finishes**. A bad path fails immediately, with the file put back as it was; a duplicate
name is refused and told which source it already points at; the lock is written in the same breath,
so forgetting it is not an available mistake. That is worth having on its own.

But it is still the smaller half of why it got built now.

The case that isn't small is the one arriving next. None of what follows is built: signing was
written and proven against real Fulcio certificates and real Rekor entries in
[#6](https://github.com/sunix/diderot/pull/6), then deliberately not merged because verification had
no certificate identity pinning — it proved *a* valid signature existed for a digest, not that the
expected publisher made it. [#25](https://github.com/sunix/diderot/issues/25) holds what resuming it
needs.

But sketching the developer's side of it produced an argument sharper than convenience. **You cannot
type an identity you do not know yet.** Who signed a skill is a fact about the artifact, discovered by
pulling it and reading the signature attached to it in the registry. Somebody editing YAML in an
editor has nothing to discover it with; a command does. So `add` grows a discovery step, the same
shape as SSH meeting a host key for the first time:

```console
$ diderot add oci://ghcr.io/sunix/skills/making-of --version "^1.0.0"
added making-of        oci://ghcr.io/sunix/skills/making-of (^1.0.0)
resolving making-of    ghcr.io/sunix/skills/making-of:1.1.0@sha256:8b81085393c4

  signed by   https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
  issuer      https://token.actions.githubusercontent.com

  diderot has no signer recorded for this skill yet.
  Trust this signer for making-of? [y/N] y
```

Answering once puts the *expectation* in the manifest — not the signature, which lives in the
registry beside the artifact and changes with every release, but the policy, which is stable and
reviewable:

```yaml
  - name: making-of
    source: oci://ghcr.io/sunix/skills/making-of
    version: "^1.0.0"
    signer:
      identity: https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
      issuer: https://token.actions.githubusercontent.com
```

And then the case that makes the whole thing worth the trouble — a release signed by something else,
because the publisher renamed a workflow, or somebody pushed from a branch:

```console
$ diderot update
error: Skill 'making-of': ghcr.io/sunix/skills/making-of:1.2.0 is signed, but not by the expected signer.
         expected  …/push-skill-to-oci.yml@refs/heads/main
         found     …/push-skill-to-oci.yml@refs/heads/experiment
       Nothing was written. If this change is legitimate, update `signer:` in diderot.yaml.
```

Fail closed, both identities named, nothing written, and the decision handed back to a human — as a
one-line change to a file in version control, where a reviewer sees it too.

Which is where it meets [part five](05-semver-ranges.md), and the argument I did not see until I
wrote this flow out. **A range with no pinned signer means automatically adopting whatever the
publisher pushes.** With one, it means automatically adopting only what the *expected* publisher
pushes. `^1.0.0` is an act of faith renewed at every release until something asks that question
once, and `add` is where asking it belongs.

There is a governance point underneath this that took saying out loud. A skill is not a dependency
like a library, sitting there until called: it is a **capability the agent will act on**. So an agent
adding skills is an agent extending itself, and that is exactly where a person belongs in the loop.
An agent can find a skill, propose it, run the command — it should not be the thing that decides its
own capabilities are trustworthy.

Which is an argument for `add`, not against it. What an agent running `add` produces is a proposal:
three lines in a file under version control, a digest, and one day a signer, none of which mean
anything until somebody reads them. What an agent copying a directory into `.claude/skills/` produces
is a fait accompli, with nothing to review and nowhere to review it.

It also settles what looked like a loose end. With no terminal there is nobody to answer `[y/N]`, so
`add` would need `--signer`/`--issuer` to state the expectation up front, or an explicit
`--trust-on-first-use` — and the default has to be **refusal**, because the case where nobody can
answer is precisely the case where nothing should be quietly trusted.

One thing I would still not decide alone: whether the lock should also record the identity actually
verified at lock time. It would make a change of signer visible in a pull request diff, where the
digest alone says nothing — which is the same instinct as the rest of this, keeping the human's view
of it in a file rather than in a moment.

## Seven lines that delete your comments

The obvious implementation needs no new concepts at all — `Yaml`, `Manifest` and `ManifestSkill` were
already there, and they compose exactly the way you would hope:

```java
Manifest manifest = Yaml.read(manifestPath, Manifest.class);
ManifestSkill added = new ManifestSkill();
added.name = name;
added.source = source;
added.version = version;
manifest.skills.add(added);
Yaml.write(manifestPath, manifest);
```

Seven lines, no branching, nothing to get wrong. It is also destructive, and the fastest way to see
that is to run it. The manifest going in, comments and all:

```yaml
# Skills this project depends on.
# Keep making-of first: the others reference its style rules.
skills:
  - name: making-of          # the journal skill
    source: oci://ghcr.io/sunix/skills/making-of
    version: "^1.0.0"

targets: [claude]
```

And what comes back out:

```yaml
skills:
- name: making-of
  source: oci://ghcr.io/sunix/skills/making-of
  version: ^1.0.0
- name: release-please
  source: oci://ghcr.io/sunix/skills/release-please
  version: ^1.2.0
targets:
- claude
```

Both comments deleted, the list indentation changed, `targets: [claude]` expanded into a block, the
quotes dropped. Nothing is *wrong* — it is valid YAML that means the same thing — and it is still
unusable, because a tool that eats your comments the first time you run it does not get run a second
time.

The asymmetry is the thing to hold on to: `diderot.lock` is **generated**, so rewriting it wholesale
is exactly right and diderot has always done that. `diderot.yaml` is **authored**. Same file format,
opposite rules.

## Following one `diderot add` from the prompt to the file

None of the new code means much in isolation, so the way in is to take a single `diderot add` from
the shell prompt through to the line it changes on disk. It passes through four places, each with one
job: the command that parses the request, the editor that splices the file, the resolver that pins
what was declared, and the lock writer whose whole responsibility is leaving everything else alone.

It lands in `AddCommand`, which does the parsing and the ordering and delegates every actual
decision. First it works out a name — `oci://ghcr.io/sunix/skills/making-of` and
`git+https://…#skills/documentation/making-of` both give `making-of`, so the last path segment is the
default and `--name` is the escape hatch. Then it reads the manifest, refuses if that name is already
declared, and hands the splice to `ManifestEditor`.

That class is where the care went. Open `add`:

```java
public void add(String name, String source, String version) {
    int header = skillsHeader();
    if (header < 0) {
        if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
            lines.add("");
        }
        lines.add("skills:");
        header = lines.size() - 1;
    }
    if (lines.get(header).substring("skills:".length()).trim().equals("[]")) {
        lines.set(header, "skills:");
    }
    String indent = listIndent(header);
    List<String> entry = List.of(
            indent + "- name: " + name,
            indent + "  source: " + source,
            indent + "  version: " + scalar(version, quotesVersions()));
    lines.addAll(insertionPoint(header), entry);
}
```

Every line of that is a decision about somebody else's file. There is no model and no parser: the
file is a `List<String>`, and the only lines that change are the three being inserted. `listIndent`
reads the existing entries' indentation rather than imposing two spaces, so a file written with four
keeps four. `insertionPoint` walks back over trailing blank lines so the new entry lands after the
last skill and not after the blank line that separates the block from `targets:`. And `skillsHeader`
is the gatekeeper — it recognises a block list under `skills:` and refuses anything else instead of
guessing, because guessing wrong here means silently corrupting an input.

Back in `AddCommand`, the manifest is written and only then resolved, through a new entry point on
`Workspace`:

```java
/**
 * Resolves one manifest entry and nothing else. `add` uses this rather than a full
 * {@link #update()}: re-resolving the whole manifest to declare one new skill would quietly move
 * every floating constraint already in the lock, which is not what the user asked for.
 */
public LockedSkill resolve(ManifestSkill skill) throws IOException {
```

That javadoc is the whole argument. `update()` was right there and reusing it would have been one
line, but a manifest holding `version: latest` or `version: "^1.0.0"` would have had those pins moved
as a side effect of adding something unrelated. `resolve` does one skill; `putLockEntry` merges it
into the lock and leaves every other entry byte for byte as it found it.

`remove` runs the same layers in reverse, with one ordering trap: the installed directories are
derived from the manifest's `targets:`, so they have to be worked out **before** the declaration is
deleted. `RemoveCommand` calls `Workspace.uninstall` first, then edits the manifest, then unpins the
lock — and reports which of the three actually had something in it, so removing a half-installed
skill tells you what it found rather than pretending it did all three.

## Two things a failing test knew better than I did

The version quoting looked like a non-question. `^1.0.0` needs no quotes — `^` is not a YAML
indicator — so I emitted it bare, and a test comparing whole files failed: the authored fixture had
`version: "^1.0.0"` and the new entry beneath it read `version: ^1.0.0`. Valid, and visibly a
different hand.

So quoting follows the file, the same courtesy already extended to indentation. Which produced the
second failure, and this one was genuinely instructive:

```java
private boolean quotesVersions() {
    for (int[] item : items()) {
        String raw = rawField(item, "version");
        if (raw == null || !(raw.startsWith("\"") || raw.startsWith("'"))) {
            continue;
        }
        // Quotes YAML forced (`"*"`, `">=1 <2"`) say nothing about what the author prefers;
        // only quotes around a value that would have been fine bare are a style choice.
        if (scalar(unquote(raw), false).equals(unquote(raw))) {
            return true;
        }
    }
    return false;
}
```

The test added three skills in a row — `^1.0.0`, then `*`, then `main` — and asserted the last one
came out bare. It didn't. `*` opens a YAML alias, so it *must* be quoted, and my first version read
those forced quotes as evidence of a preference and started quoting everything after it. A style is
what somebody chose, not what the format compelled; the fix is that last comparison, which asks
whether the value would have survived unquoted before counting it as a choice.

The other correction came from `remove`. I had `skillsHeader` refuse every inline list, so
`diderot remove nope` on a manifest containing `skills: []` failed with a lecture about rewriting it
as a block, instead of simply saying there was nothing to remove. The whole fix is the last clause of
one condition:

```java
String rest = line.substring("skills:".length()).trim();
// `skills: []` holds nothing an author could lose, so it is workable: reads see no
// entries and `add` converts the line to a block. A *populated* inline list is refused,
// because pretending it is empty would have `remove` report nothing to remove for a
// skill that is plainly declared.
if (!rest.isEmpty() && !rest.startsWith("#") && !rest.equals("[]")) {
    throw new IllegalStateException("diderot.yaml declares skills inline (`" + line.trim()
            + "`). Rewrite it as a block list, one `- name:` per line, and try again.");
}
```

`[]` holds nothing an author could lose, so it is workable — reads see no entries, and `add` rewrites
the line as a block header. A *populated* inline list stays refused, and the comment says why in the
place somebody would be tempted to relax it: pretending it is empty would have `remove` report
nothing to remove for a skill that is plainly declared. Two shapes that look nearly identical and
want opposite handling.

## Leaving nothing for you to find out later

Three decisions in `AddCommand` that are all the same decision, really: never leave the project in a
state you have to discover for yourself.

The first is about the order of operations. `add` writes the manifest *before* it resolves, which is
the right way round — the entry should be real before anything tries to pin it — and it means a source
that turns out to have no `SKILL.md` would leave a declaration nothing can satisfy. So the original
text is kept in a local, and the `catch` is the interesting part:

```java
} catch (Exception e) {
    restore(manifestPath, original);
    spec.commandLine().getErr().println("error: " + e.getMessage());
    return 1;
}
```

`restore` is deliberately dumb, and look at what it does with the empty string:

```java
private void restore(Path manifestPath, String original) {
    if (original == null) {
        return;
    }
    try {
        if (original.isEmpty()) {
            Files.deleteIfExists(manifestPath);
        } else {
            Files.writeString(manifestPath, original);
        }
    } catch (Exception ignored) {
        spec.commandLine().getErr().println(
                "warning: could not restore " + manifestPath + " — check it by hand.");
    }
}
```

`original` is `null` when nothing was read yet, empty when there was no manifest at all, and the file's
text otherwise — three states, three answers, and the empty one matters: `diderot add` in a fresh
directory that then fails should not leave a `diderot.yaml` behind. And when the restore itself fails,
it says so rather than swallowing it, because at that point the only honest thing is to tell you to go
and look.

The second is the duplicate. Two entries for one name is worse than an error, so `add` checks before
it writes anything:

```java
String existing = editor.sourceOf(skillName).orElse(null);
if (existing != null) {
    throw new IllegalStateException("Skill '" + skillName + "' is already declared, from "
            + existing + ". Change its version in diderot.yaml, or remove it first.");
}
```

The value of that is entirely in `existing` being in the message. "Already declared" sends you to read
the file; "already declared, from oci://…/making-of" is often all you needed, because the usual cause
is adding the same skill from a slightly different source and not realising.

The third costs two lines and closes the gap that pinning-only-the-new-skill opens: the lock can now
legitimately be missing entries the manifest declares, and `install` would skip them without a word.
So `add` says so on its way out.

```text
note: not pinned in diderot.lock yet: making-of — run `diderot update`.
```

## Proof

Forty-two tests, seventeen of them new, and they split along the seam that matters. Ten drive
`ManifestEditor` as pure text-in, text-out — indentation, quoting, both inline cases, an absent
`skills:` key — because that is where a bug would be silent. The load-bearing one compares whole
files:

```java
assertEquals("""
        # Skills this project depends on.
        # Keep making-of first: the others reference its style rules.
        skills:
          - name: making-of          # the journal skill
            source: oci://ghcr.io/sunix/skills/making-of
            version: "^1.0.0"
          - name: release-please
            source: oci://ghcr.io/sunix/skills/release-please
            version: "^1.2.0"

        targets: [claude]
        """, editor.text(),
        "both comments, the alignment, the blank line and `targets: [claude]` survive verbatim");
```

Comparing the entire file rather than asserting "contains the new entry" is deliberate: the failure
mode here is not a missing addition, it is collateral damage somewhere else in the file, and only a
whole-file comparison sees that.

The other seven go through `CommandLine.execute` against a local fixture repository, so the splicing,
the single-skill pinning and the rollback are exercised as a user meets them rather than through the
internals. Two assertions carry most of the weight:

```java
assertTrue(lock.contains(firstResolved),
        "the first skill is still pinned to exactly the commit it was pinned to");

assertEquals(before, Files.readString(project.resolve("diderot.yaml")),
        "the manifest is byte-for-byte what it was, comment included");
```

The first would fail the moment `add` reached for `update()`; the second would fail if the rollback
were forgotten, and it checks the bytes rather than the parse, so a rollback that restored the
*meaning* while losing the comment would still be caught.

```text
Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Then the part that isn't a test. Against ghcr.io, on that same commented manifest, with the whole
cycle run in order — and the thing to watch is the file, not the output:

```console
$ diderot add oci://ghcr.io/sunix/skills/release-please --version '^1.0.0'
added release-please       oci://ghcr.io/sunix/skills/release-please (^1.0.0)
locked release-please      ghcr.io/…/release-please:1.2.0@sha256:f1ecb79525a4 (tree:d87753e53a07…)
note: not pinned in diderot.lock yet: making-of — run `diderot update`.
$ diderot update && diderot install
locked making-of           ghcr.io/…/making-of:1.1.0@sha256:8b81085393c4 (tree:89f4bb27c343…)
locked release-please      ghcr.io/…/release-please:1.2.0@sha256:f1ecb79525a4 (tree:d87753e53a07…)
wrote diderot.lock
installed making-of        -> .claude/skills/making-of (tree:89f4bb27c343… verified)
installed release-please   -> .claude/skills/release-please (tree:d87753e53a07… verified)
$ diderot status
ok       making-of            .claude/skills/making-of
ok       release-please       .claude/skills/release-please
$ diderot remove making-of
removed making-of      diderot.yaml, diderot.lock, 1 installed directory
  deleted .claude/skills/making-of
```

Two edits against a file written by hand, a real registry on the other end, and every digest matching
what the tests had already pinned in isolation. What the file looked like afterwards is worth its own
section, because that is where the one thing this does badly shows up.

## The comment that now lies

`remove` deletes an entry's own lines and touches nothing around them, and that is where it goes
wrong. Start from the manifest as it was, and read the second comment:

```yaml
# Skills this project depends on.
# Keep making-of first: the others reference its style rules.
skills:
  - name: making-of
    source: oci://ghcr.io/sunix/skills/making-of
    version: "^1.0.0"
  - name: release-please
    source: oci://ghcr.io/sunix/skills/release-please
    version: "^1.0.0"
```

Now `diderot remove making-of`, and read it again:

```yaml
# Skills this project depends on.
# Keep making-of first: the others reference its style rules.
skills:
  - name: release-please
    source: oci://ghcr.io/sunix/skills/release-please
    version: "^1.0.0"
```

The instruction outlived the thing it was about. Anyone opening the file now is told to keep first a
skill that is not there, which is worse than no comment at all — a stale instruction gets read as a
true one.

The fix looks obvious for about a second: delete the comments belonging to the entry. Except that
"belonging" has no definition. A comment above an entry might describe that entry, or introduce the
whole block, or be a note about the entry *after* it — and the one here is a fourth thing again, a
rule about ordering that concerns two entries and belongs to neither. Every guess deletes somebody's
prose in some file somewhere, and losing prose is worse than leaving a line that has gone out of
date. So it stays, recorded as a defect rather than argued into a decision.

## What this chapter leaves open

**The shorthand people will actually type.** `diderot add ghcr.io/sunix/skills/making-of@^1.0.0` is
the form that comes naturally, and `@` is also legal inside a range, so the grammar wants deciding
rather than guessing.

**Changing the version of a skill already declared.** `add` refuses a duplicate today. Editing a
value in place is a different splice: the field has to be found inside an existing entry and replaced
without disturbing a trailing comment on the same line.

**And nothing yet installs skills into a shared location.** Both commands write into the project, so
a skill wanted across every repository still has to be added to each of them.
