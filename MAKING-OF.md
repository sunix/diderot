# Building diderot: a package manager for AI agent skills, one prompt at a time

*A first-person account of how this project came together — the tooling, the false starts,
and the implementation work — kept here for new contributors, or anyone curious how it came
together. Written in a blog-ish style rather than as formal docs; may still turn into an
actual blog post at some point. Updated on request as the work progresses.*

*Last updated: 2026-08-21 (M1 landed).*

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
[release-please skill](https://github.com/sunix/ai-skills/tree/main/skills/github-actions/release-please).
diderot's first users are its own build scripts.

Next up, milestone M1: the git-source resolver and the lockfile — the shortest path to
`diderot install` doing something real against ai-skills.

## M1: git does git, Java does the hashing

Same day, second session. Before the how, the what — M1 is one user story, and it's mine:
I keep reusable skills in [ai-skills](https://github.com/sunix/ai-skills), and in every
project where I work with an agent I want to declare which ones it should have, like any
other dependency. Concretely, drop this in a project:

```yaml
# diderot.yaml
skills:
  - name: making-of
    source: git+https://github.com/sunix/ai-skills#skills/documentation/making-of
    version: main
targets: [claude]
```

then run `diderot update && diderot install`, and `.claude/skills/making-of/` exists with
exactly the bytes the lockfile pinned — same bytes tomorrow, same bytes on a teammate's
machine or in the throwaway workspace a remote coding agent spins up, even if the
`main` branch has moved on, because `diderot.lock` records the commit **and** a content
digest. And when I (or an agent, it happens) fumble an
installed skill file, `diderot status` must say `DRIFTED` and exit non-zero instead of
letting the corruption ride along silently — with `diderot install` as the repair. That
full loop — declare, lock, install, verify, repair — is what M1 had to deliver, and it
landed in one sitting.

## Three verbs, and who runs which

Mid-review I realized I couldn't cleanly answer "when does a user type `update`?" — which
means the journal hadn't said it. So, the honest user manual first.

**`diderot update`** reads `diderot.yaml` — the *constraints*. And a constraint promises
nothing; `main` here is a moving branch, **not** a fixed version:

```yaml
# diderot.yaml — what the user maintains
skills:
  - name: making-of
    source: git+https://github.com/sunix/ai-skills#skills/documentation/making-of
    version: main    # wherever ai-skills' main points today — a moving target
```

`update` goes to the network and nails that moving target down: the branch becomes the
exact commit it pointed to *at that instant*, plus the digest of the skill's bytes, both
written to `diderot.lock`:

```yaml
# diderot.lock — what update generates; never edited by hand
skills:
- name: making-of
  source: git+https://github.com/sunix/ai-skills#skills/documentation/making-of
  resolved: 64358bc5644155d4513bf17e421119fc6eec9127    # the commit main pointed to
  digest: tree:0f755a27d65b67d80d6d1ee2ed0d8a7963fa8f23  # the exact bytes of the skill dir
```

Run `update` again next month and `resolved` may well change — `main` moved, and
re-resolving it is exactly the job. It is the only command that ever moves the lock.
**`diderot install`**
reads *only* the lock: no resolution, no opinion, just materialize the pinned bytes and
verify them — the `npm ci` / `helm dependency build` of the family. **`diderot status`**
is the read-only audit: compare disk against lock, `ok`/`DRIFTED`/`MISSING`, non-zero
exit on trouble.

Who types what: the manifest author, day one, runs `update` then `install`. A teammate cloning
a repo whose lock is committed runs **`install` and nothing else** — by far the most
travelled path. And "teammate" increasingly means a machine: a remote coding
agent — a cloud Claude Code session, a Copilot coding agent — wakes up in a fresh
throwaway workspace with nothing but the repository clone, and needs its skills
materialized before it starts thinking. One `diderot install` in the bootstrap script and
the agent's toolbox travels with the repo, identical on every spin-up — that audience is
who the lockfile really serves. Bumping versions later is `update && install`. `status`
is for "why is this skill weird" moments — and for any script that wants a non-zero exit
the moment something drifted.

Writing that down triggered the obvious challenge (mine, this time — Claude defended the
Helm split): is `update` even necessary? Half yes, half no. As a *mandatory first step*
it's friction — cargo generates the lock on first build, npm resolves what's missing —
so a coming change will make `install` welcoming: resolve by itself when the lock is
absent or the manifest declares a skill the lock doesn't know, with a `--frozen` flag for
unattended runs — a remote agent's bootstrap, a release script — that fails instead of
resolving. But as the *explicit "advance the versions" verb*,
`update` is irreplaceable: when a lock exists, `install` must never move it — that's the
whole reproducibility contract. One verb obeys the lock, one verb moves it.

## A tour of the machinery, for whoever opens the hood next

M1 is eight new classes plus three test files, and the tour follows the verbs in lockfile
order: `update` writes it, `install` obeys it, `status` audits it.

`diderot update` enters through `commands/UpdateCommand.java` — and finds almost nothing
there. The picocli commands (`UpdateCommand`, `InstallCommand`, `StatusCommand`, ~40
lines each) are deliberately dumb: parse the options, build the engine, translate
exceptions into `error: …` plus exit code 1, and that's the whole class:

```java
@Override
public Integer call() {
    PrintWriter out = spec.commandLine().getOut();
    try {
        new Workspace(directory.toAbsolutePath().normalize(),
                new GitCli(GitCli.defaultCacheRoot()), out).update();
        return 0;
    } catch (Exception e) {
        spec.commandLine().getErr().println("error: " + e.getMessage());
        return 1;
    }
}
```

All three delegate to the same object, `core/Workspace.java`, which *is* the feature: one
class, three public methods — `update()`, `install()`, `status()` — and not a single
picocli import, which is exactly what lets the tests drive it without spawning a CLI.

`Workspace.update()` reads `diderot.yaml` into `core/Manifest.java` — a dumb Jackson DTO,
like its sibling `core/LockFile.java`; `core/Yaml.java` holds the one configured
YAMLMapper they share. Then the whole resolution is one loop:

```java
for (ManifestSkill skill : manifest.skills) {
    SourceRef ref = SourceRef.parse(skill.source);      // scheme + repo URL + path in repo
    Path repo = git.ensureFresh(ref.url());
    String commit = git.resolveCommit(repo, skill.version);
    if (!git.blobExists(repo, commit, ref.path() + "/SKILL.md")) {
        throw new IOException("Skill '" + skill.name + "': no SKILL.md at …");
    }
    locked.resolved = commit;
    locked.digest = "tree:" + git.treeSha(repo, commit, ref.path());
}
Yaml.write(lockPath(), lock);
```

`core/SourceRef.java` is the parser on that first line — a record with a `Kind`:

```java
public record SourceRef(Kind kind, String url, String path) {
    public enum Kind { GIT, OCI }
```

It splits `git+https://github.com/sunix/ai-skills#skills/documentation/making-of` into
`GIT`, the repo URL, and the path inside the repo — and it already parses `oci://` too.
M2 will plug into that same seam; until then `update` fails politely on an OCI source
instead of mysteriously.

Then comes the only class allowed to touch the outside world: `git/GitCli.java`, the
single subprocess boundary. `ensureFresh()` keeps one **bare** clone per repository URL,
shared by all projects on the machine:

```java
Path repo = cacheRoot.resolve(cacheKey(url));   // ~/.cache/diderot/git/<sha256(url) prefix>
if (!Files.isDirectory(repo)) {
    run(null, "git", "clone", "--bare", "--quiet", url, repo.toString());
} else {
    run(repo, "git", "fetch", "--quiet", "--prune", "origin",
            "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*");
}
```

Its siblings are one git invocation each: `resolveCommit()` turns `main` (or a tag, or a
short SHA) into a full commit via `rev-parse <ref>^{commit}`; `blobExists()` is
`cat-file -e <commit>:<path>` — the SKILL.md gate above; `treeSha()` asks
`rev-parse <commit>:<path>` for the directory's tree SHA, which becomes the
`digest: tree:…` line in the lockfile.

The lock written, `Workspace.install()` takes over — today, tomorrow, or on a colleague's
machine. It reads `diderot.lock` back, and for each skill × target
(`core/TargetLayout.java` maps `claude` → `.claude/skills` and `agents` →
`.agents/skills`) it replaces the installed directory with exactly the locked bytes, and
proves it before moving on:

```java
Path dest = target.skillsDir(root).resolve(skill.name);
deleteRecursively(dest);
git.extract(repo, skill.resolved, ref.path(), dest);   // git archive → commons-compress untar
String actual = "tree:" + GitTreeHasher.treeSha(dest);
if (!actual.equals(skill.digest)) {
    throw new IOException("Digest mismatch for '" + skill.name + "' in " + dest + " …");
}
```

Notice what `install` does **not** do: transform anything. A skill is already in the
format agents consume — a folder with a `SKILL.md` — so installing is a byte-for-byte
materialization of the locked git tree into `.claude/skills/`: no rendering, no
templating, no rewriting for the agent's benefit. And that identity is load-bearing, not
laziness: it's the only reason a digest recorded from the *source* tree can be checked
against the *installed* directory at all. The day install transforms something, the
verification model dies with it.

Last of the trio, `status()` is the install-time verification turned into a standalone
check — the same `GitTreeHasher` hash pointed at the installed directories:

```java
if (!Files.isDirectory(dest)) {
    state = "MISSING";
} else if (("tree:" + GitTreeHasher.treeSha(dest)).equals(skill.digest)) {
    state = "ok";
} else {
    state = "DRIFTED";
}
```

— with any `MISSING`/`DRIFTED` turning the exit code non-zero, so any script can gate
on it. The
test side mirrors the layout: `GitTreeHasherTest` (git as oracle, more on that below),
`SourceRefTest`, and `WorkspaceEndToEndTest`, which builds throwaway upstream repos with
`testutil/Git.java` and runs the whole update→install→drift→repair story against them —
no network anywhere in the suite.

## Two design calls, one favorite piece

Two design calls worth recording. First, **git does git**: everything `GitCli` runs is a
subprocess call to the real binary — no protocol reimplementation, no JGit. It's the
approach Go modules used for years, and a package manager that requires `git` on the PATH
is a trade I'll take over a protocol reimplementation in week one.

Second, my favorite piece of the milestone: drift detection needed a way to hash
what's actually installed in `.claude/skills/`, where there is no `.git` to ask. So
`GitTreeHasher` re-implements git's object hashing in ~80 lines of pure Java — blobs as
`sha1("blob <size>\0" + content)`, trees as sorted `"<mode> <name>\0" + sha` entries, with
git's quirky rule that directories sort as if their name ended in `/`. The lockfile digest
and the on-disk hash speak the same language, so `install` can verify what it just wrote
and `status` can catch a single flipped byte.

Two snippets carry most of that weight. First, the sort line:

```java
// git sorts tree entries as if directory names had a trailing '/'
entries.sort(Comparator.comparing(e -> e.isDir() ? e.name() + "/" : e.name()));
```

Bytewise, `-` (0x2D) sorts before `/` (0x2F), so under git's rule a file named
`sub-file.txt` comes *before* a directory named `sub` — while a naive name sort puts them
the other way around. Wrong order means different tree bytes, which means a different SHA
that matches nothing. Every file also gets git's object header before hashing:

```java
sha1.update((type + " " + content.length + "\0").getBytes(UTF_8)); // "blob 42\0", "tree 137\0"
return sha1.digest(content);
```

— the same `<type> <size>\0` framing git has used since day one, which is why our digests
and git's agree at all. And on the extraction side, the classic zip-slip guard:

```java
Path out = root.resolve(entry.getName()).normalize();
if (!out.startsWith(root)) {
    throw new IOException("Archive entry escapes destination: " + entry.getName());
}
```

A skill archive is third-party content; an entry named `../../.bashrc` must die before it
writes anything outside the target directory.

The main discussion with Claude was about how to *prove* that hasher correct. A fixed
expected-SHA string in the test would just assert that the code does what the code does.
Instead the test builds a directory designed to hurt — nested dirs, an executable script,
and a file named `sub-file.txt` that sorts differently once you know the trailing-`/`
rule — then asks the **real git** (`git init && git add -A && git write-tree`) for the
answer and requires our Java to match it:

```java
String expected = gitTreeSha(repo);              // real `git write-tree`
assertEquals(expected, GitTreeHasher.treeSha(repo));
```

This proves the point because git itself is the oracle: any divergence from git's object
format — a wrong mode string, naive sorting, a missed header byte — fails the test, not
just the cases I thought of. All eleven tests (hasher, source parsing, and a full
update→install→drift→repair cycle against local fixture repos) came back green:

```text
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

And the real-world run, against the actual ai-skills repo over the network — including
sabotaging an installed file to watch `status` catch it:

```text
$ diderot update
locked making-of      https://github.com/sunix/ai-skills@64358bc56441 (tree:0f755a27d65b…)
$ diderot install
installed making-of   -> .claude/skills/making-of (tree:0f755a27d65b… verified)
$ echo "sabotage" >> .claude/skills/making-of/SKILL.md && diderot status
DRIFTED  making-of    .claude/skills/making-of      # exit 1
$ diderot install && diderot status
ok       making-of    .claude/skills/making-of      # exit 0
```

Final cross-check, the one that made me smile: `git rev-parse
origin/main:skills/documentation/making-of` on ai-skills returns
`0f755a27d65b67d80d6d1ee2ed0d8a7963fa8f23` — character for character the digest diderot
had written into `diderot.lock`.

## Closing part one

The git chapter ends here, and it ends whole: a project can declare its skills, lock
them, install them byte-for-byte anywhere, and catch anyone — human or agent — who bends
an installed file. Two stories are already queued for part two: the welcoming `install`
(resolve by itself when there's no lock yet, `--frozen` for unattended runs), and M2,
where ORAS
finally enters — `push` to a real registry, `oci://` sources resolved by digest, and
cosign. See you there.
