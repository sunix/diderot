# Two commands, and a file I wasn't allowed to rewrite

*Part six of [diderot's making-of](../../MAKING-OF.md): `add` and `remove`, and why the easy
implementation of them is destructive.*

## The goal: declaring a skill, not copying one

Every verb diderot had operated on a manifest somebody had already written by hand, so the first step
of using it was always "open `diderot.yaml` and get the YAML right" — the step every other package
manager removed years ago. But the reason it matters here has less to do with typing than with what a
hand-added skill *is*.

Working on a project and wanting a skill, the path of least resistance is to copy the folder in. It
works immediately, and then nothing on disk says where it came from, which release it was, or whether
it still matches what the publisher published. A month later there is no way to answer "is this
current?" without going and looking by hand. Going through the tool turns the same act into a
declaration: a source, a version constraint I chose, and a content digest in the lock that `status`
re-checks on demand — plus, now that ranges resolve, a `tag:` line saying which release `^1.0.0`
actually landed on.

It also leaves the door open on trust, which the copy has no room for. Signing is not built
([#25](https://github.com/sunix/diderot/issues/25)), but if it is, the place a signature gets verified
is the resolve path — the one `add` already goes through. A directory somebody dropped in has nowhere
to put that answer, and no question to attach it to.

And it is the safer thing to hand to an agent. Not mainly because "append a mapping to a list in a
YAML file" is an error-prone instruction to give a language model, though it is: what `diderot add`
leaves behind is three reviewable lines and a digest, where an agent copying files leaves an opaque
directory nobody can check. If human validation ever belongs anywhere in this, it belongs on a
declaration.

So the target was two commands. Watch the second line of each: the point is that neither leaves you
anything to finish by hand.

```console
$ diderot add oci://ghcr.io/sunix/skills/making-of --version "^1.0.0"
added making-of        oci://ghcr.io/sunix/skills/making-of (^1.0.0)
locked making-of       ghcr.io/sunix/skills/making-of:1.1.0@sha256:8b81085393c4 (tree:89f4bb27c343…)
run `diderot install` to put it on disk.

$ diderot remove making-of
removed making-of      diderot.yaml, diderot.lock, 1 installed directory
  deleted .claude/skills/making-of
```

The name is inferred from the source's last segment, so the common case needs no `--name`. `rm` is an
alias for `remove`.

## The file I wasn't allowed to rewrite

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
that is to run it. Here is a manifest written by a person, comments and all:

```yaml
# Skills this project depends on.
# Keep making-of first: the others reference its style rules.
skills:
  - name: making-of          # the journal skill
    source: oci://ghcr.io/sunix/skills/making-of
    version: "^1.0.0"

targets: [claude]
```

And here is what came back out of that round-trip, with one skill added:

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

The other correction came from `remove`. I had `skillsHeader` refuse every inline list, which meant
`diderot remove nope` on a manifest containing `skills: []` failed with a lecture about rewriting it
as a block instead of saying there was nothing to remove. An empty inline list holds nothing an author
could lose, so it is converted; a *populated* one is still refused, because treating it as empty would
have `remove` report nothing to remove for a skill that is plainly declared. Two shapes that look
alike and want opposite handling.

## Three behaviours that don't show up in a diff

**A failed resolution puts the file back.** `add` writes the manifest before it resolves, which is the
right order — the entry should be real before anything tries to pin it — but it means a source that
turns out to have no `SKILL.md` would leave a declaration nothing can satisfy. So the original text is
held in a local and rewritten on any failure, including the case where the file did not exist and has
to be deleted again.

**A duplicate name is refused with the source it already has.** Silently ending up with two entries
for one skill is worse than an error, and an error that says *which* source the existing entry points
at is worth three extra words.

**`add` says what it did not do.** Pinning only the new skill means the lock can legitimately be
missing entries the manifest declares, and `install` would skip them without comment. So it says so:

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
$ cat diderot.yaml
# Skills this project depends on.
# Keep making-of first: the others reference its style rules.
skills:
  - name: release-please
    source: oci://ghcr.io/sunix/skills/release-please
    version: "^1.0.0"

targets: [claude]
```

Two edits later, both comments are still there, the blank line is still there, `targets: [claude]` is
still flow-style, and the version the tool wrote is quoted like the one the human wrote.

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
