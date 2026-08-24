# One line to install it

*Part three of [diderot's making-of](../../MAKING-OF.md): the milestone where diderot
stops being a thing you compile and becomes a thing you install. The packaging itself
landed in [PR #9](https://github.com/sunix/diderot/pull/9) (branch
`feat/m3-packaging`); the release that followed took three more attempts, and the second
half of this chapter is why.*

## The goal: stop telling people to build it

Every proof in the previous two chapters ended with the same embarrassing footnote —
each step rebuilt diderot from Java sources, because there was nothing to download.
That's fine for the person writing it and useless for anybody else. The target for this
chapter is exactly one line working on a machine that has never seen this project:

```console
$ curl -fsSL https://raw.githubusercontent.com/sunix/diderot/main/install.sh | sh
Installing diderot v0.1.0 (linux-x86_64) into /root/.local/bin
Installed /root/.local/bin/diderot
diderot 0.1.0
```

No JDK, no Maven, no clone. And for the Java crowd who already have
[JBang](https://www.jbang.dev/), not even an install:

```console
$ jbang diderot@sunix/diderot --help
```

## Signing waits

Signing got built before this, and it works — it just isn't merged.

The choice was between polishing it and having something people can actually run, and
this chapter is the second one: an MVP you install in one line instead of compiling from
source. Signing is also a bigger job than it first looks — checking that *somebody*
signed a skill is easy, checking that the *expected publisher* did is the real work — and
none of it helps anyone while diderot still isn't installable. So it waits on the
`feat/m2b-signing` branch ([PR #6](https://github.com/sunix/diderot/pull/6)), tests
included, for a milestone where it earns its place.

## Three artefacts, one release

The shape of the milestone, concretely — this is what a release publishes, twelve assets
for a tag like `v0.1.0`:

```text
diderot-v0.1.0-linux-x86_64             ← install.sh picks one of these five,
diderot-v0.1.0-linux-x86_64.sha256        based on uname
diderot-v0.1.0-linux-aarch64
diderot-v0.1.0-linux-aarch64.sha256
diderot-v0.1.0-darwin-x86_64
diderot-v0.1.0-darwin-x86_64.sha256
diderot-v0.1.0-darwin-aarch64
diderot-v0.1.0-darwin-aarch64.sha256
diderot-v0.1.0-windows-x86_64.exe
diderot-v0.1.0-windows-x86_64.exe.sha256
diderot.jar                             ← what JBang runs; also the fallback
diderot.jar.sha256                        for any platform not listed above
```

Five native binaries, one portable jar, and a `.sha256` beside every single one.
`install.sh` picks the right binary; JBang points at the jar; anybody uneasy about piping
a script into a shell downloads by hand and checks the hash themselves.

The native binaries are built by a matrix in `.github/workflows/release-binaries.yml` —
five platforms, GraalVM per runner, one command each:

```yaml
- uses: graalvm/setup-graalvm@v1
  with:
    java-version: "21"
    distribution: graalvm-community
- name: Build native binary
  run: ./mvnw -B package -DskipTests -Dnative
```

It lives in CI for the obvious reason: release artefacts get built from the pushed commit,
by a machine nobody has touched. Hand-building them on a laptop risks shipping whatever
was edited locally and never committed — and nobody can reproduce the result afterwards.
(It also happens that `native-image` wants several gigabytes of RAM — 4.14GB peak, as
the build log further down shows — against roughly one available where this was written,
so no native binary was ever built on the machine this was written on.)

Which raised an uncomfortable question while reviewing all this: does that mean nobody
finds out whether this code even *survives* AOT compilation until release day? Waiting
for a release to discover that a missing reflection registration breaks `native-image`
is exactly the kind of feedback loop that should not exist. So the ordinary CI grew a
canary — one platform, built and run on every pull request:

```yaml
  native-smoke:
    steps:
      - uses: graalvm/setup-graalvm@v1
      - run: ./mvnw -B package -DskipTests -Dnative
      - name: The binary has to actually run
        run: |
          binary="$(ls target/diderot-*-runner)"
          "$binary" --version
          "$binary" --help
```

Release day builds five of those; this builds one, and any future change that breaks
native compilation now fails on the pull request that caused it.

Two details in that workflow that aren't obvious. The jar is deliberately published
*without* a version in its filename:

```bash
mv target/*-runner.jar diderot.jar
```

because that makes `releases/latest/download/diderot.jar` a stable URL — which is what
the JBang catalog alias points at, so `jbang diderot@sunix/diderot` keeps working across
releases without editing anything. That pattern is GitHub's own, documented in [Linking to
releases](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases):
*"To link directly to a download of your latest release asset that was manually uploaded,
the suffix is `/releases/latest/download/asset-name.zip`."* It resolves to whichever
release is latest, which only works if the filename never changes — hence dropping the
version from it. The version still lives in the release tag and the URL path. And the trigger has a wart inherited from
[my own release-please skill](https://github.com/sunix/ai-skills/tree/main/skills/github-actions/release-please),
which warns about exactly this: a Release created with the default `GITHUB_TOKEN` will
*not* fire `on: release: published`, because GitHub refuses to let one workflow trigger
another. Hence a `workflow_dispatch` alongside it, so the binaries can always be built
against a tag by hand. The skill predicted the trap; I still walked into it while writing
the workflow, and reading my own warning is what got me out.

## The installer, and what it refuses to do

`install.sh` is POSIX `sh` on purpose — it has to run before anything is installed, on
whatever the machine already has. It maps `uname` onto asset names, resolves the latest
release through the GitHub API (or takes `DIDEROT_VERSION`), downloads binary plus
checksum, and then does the only genuinely important thing in the whole file:

```sh
expected="$(cut -d' ' -f1 < "$tmp/diderot.sha256")"
actual="$(sha256sum "$tmp/diderot" | cut -d' ' -f1)"
[ "$expected" = "$actual" ] || die "checksum mismatch for $binary (expected $expected, got $actual). Not installing."
```

It also refuses to proceed when the checksum file is *missing* rather than shrugging and
installing anyway — an installer that verifies only when convenient verifies nothing.
And it never writes into the target directory until after that comparison passes, so a
failed install can't leave a half-written binary behind.

## Proof

Everything except the native build could be exercised here, so it was.

The interesting test is the one that tries to get a tampered binary installed. A local
stand-in release, served over `http://127.0.0.1`, with the payload swapped *after* its
checksum was generated:

```console
$ printf '#!/bin/sh\necho "PWNED"\n' > diderot-v0.0.1-test-linux-x86_64   # checksum NOT regenerated
$ DIDEROT_VERSION=v0.0.1-test sh install-local.sh
Installing diderot v0.0.1-test (linux-x86_64) into .../bin
install.sh: checksum mismatch for diderot-v0.0.1-test-linux-x86_64 (expected 8479e8c7a0ab…, got f1bc342ea869…). Not installing.
exit=1
$ ls bin | wc -l
0
```

Refused, non-zero exit, and — the part that matters — nothing written. The same
stand-in release with an honest checksum installs and runs:

```console
$ DIDEROT_VERSION=v0.0.1-test sh install-local.sh
Installed .../bin/diderot
$ .../bin/diderot
diderot v0.0.1-test (fake)
```

Both no-release-yet and wrong-version paths fail with something a human can act on
rather than a raw `curl` code:

```console
$ sh install.sh
install.sh: could not determine the latest release of sunix/diderot — either it has
published none yet, or api.github.com is unreachable from here. Set DIDEROT_VERSION=<tag>
to pick one explicitly.
```

JBang running a Quarkus uber-jar through a catalog alias also isn't an assumption — a
local `jbang-catalog.json` pointing at the real uber-jar:

```console
$ jbang diderot --version
diderot 1.0.0-SNAPSHOT
```

And the canary answered the AOT question on the pull request, before any of this was
merged. It ran on a GitHub-hosted `ubuntu-latest` runner (image `ubuntu24/20260816.277`),
with GraalVM Community 21 installed by `graalvm/setup-graalvm@v1` — not on my machine,
which is the point. `native-image` narrates its own work, so here is most of it, trimmed
only where it repeats:

```text
[1/8] Initializing...                                              (5.7s @ 0.18GB)
[2/8] Performing analysis...  [*****]                             (54.8s @ 1.27GB)
[3/8] Building universe...                                         (8.5s @ 1.69GB)
[4/8] Parsing methods...      [***]                                (5.2s @ 2.05GB)
[5/8] Inlining methods...     [***]                                (4.0s @ 1.36GB)
[6/8] Compiling methods...    [*******]                            (46.7s @ 1.84GB)
[7/8] Layouting methods...    [***]                                (5.4s @ 1.30GB)
[8/8] Creating image...       [**]                                 (4.5s @ 1.87GB)
          13.7s (10.0% of total time) in 140 GCs | Peak RSS: 4.14GB | CPU load: 3.68
Produced artifacts:
 /home/runner/work/diderot/diderot/target/…/diderot-1.0.0-SNAPSHOT-runner (executable)
Finished generating 'diderot-1.0.0-SNAPSHOT-runner' in 2m 15s.
```

Then the two commands that matter, run against that freshly produced executable:

```text
diderot 1.0.0-SNAPSHOT
Usage: diderot [-hV] [COMMAND]
```

So this code does compile ahead-of-time, and the result runs: no missing reflection
registration, and nothing in `oras-java`, Jackson's YAML support or commons-compress that
AOT can't handle. Two details worth pulling out of that log. **Peak RSS: 4.14GB** —
which retroactively settles the earlier hand-waving about memory: the build genuinely
needs about four gigabytes, against roughly one available where this was written, so
that wasn't an excuse but a measurement. And look at what `--version` prints: that's the
fix from the section below working *in native mode*, where reading a config value at
runtime is precisely the sort of thing AOT compilation likes to break. It didn't.

Four platforms remain unproven until a release runs the full matrix; this one is real.

## The bug that only a packaging milestone would find

That JBang run is also how I found something the previous two chapters never could.
The first time it printed:

```text
diderot 0.1.0-SNAPSHOT
```

while the pom said `1.0.0-SNAPSHOT`. The version in `--version` was a string literal
sitting in an annotation:

```java
@Command(name = "diderot", version = "diderot 0.1.0-SNAPSHOT", ...)
```

Harmless while nothing ships. Actively bad the moment release automation starts bumping
the pom, because release-please would move the pom and leave that literal alone — every
published binary confidently reporting a version nobody released. The fix reads the
version the build actually produced:

```java
public String[] getVersion() {
    String version = ConfigProvider.getConfig()
            .getOptionalValue("quarkus.application.version", String.class)
            .orElse("unknown");
    return new String[] { "diderot " + version };
}
```

and now `--version` agrees with the pom, verified on a real jar *and* on the native
binary. A packaging milestone earning its keep before it has even published anything.

## Then the release refused to happen

With all of that merged, cutting `v0.1.0` should have been a formality. Instead it took
three tries, and the first problem had been sitting there for days.

I went looking for the Release PR release-please was supposed to have opened, and there
wasn't one. There never had been. That workflow had failed on **every single push to
`main` since M1** — five consecutive red runs, and nobody had thought to look, because
nothing downstream depended on it yet. The error was not in our configuration:

```text
release-please failed: GitHub Actions is not permitted to create or approve pull requests.
```

A repository setting, off by default (Settings → Actions → General → Workflow
permissions). release-please had done everything right — parsed sixteen commits, computed
a version, generated a CHANGELOG, even created and pushed its own
`release-please--branches--main` branch with the bump commit — and then hit a wall on the
final API call. The lesson isn't about that checkbox: it's that **a workflow nothing
depends on yet will fail silently for as long as you let it.** Five runs is five chances
to notice.

Second try: with the setting on, release-please immediately proposed `1.0.0`. Which is
technically what the pom said — `1.0.0-SNAPSHOT`, straight from the Quarkus scaffold,
untouched since the walking skeleton — but a wildly inappropriate number for something
whose README says "early development", whose command surface is about to grow `add` and
`remove`, and whose signing story is explicitly postponed. `1.0.0` is a promise about API
stability. So the pom went to `0.1.0-SNAPSHOT`.

Third try, and this is the part where I was simply wrong: I had asserted, in that very
pull request, that release-please would now recompute `0.1.0`. It didn't. It rebased onto
the new pom and proposed `1.0.0` again, because a **first** release bootstraps at `1.0.0`
regardless of what the pom holds — the pom only drives subsequent bumps. The documented
way out is a footer in the squash-merge commit body:

```text
Release-As: 0.1.0
```

which finally produced `chore(main): release 0.1.0`. I'd stated the behaviour without
checking it, and the tool corrected me — worth writing down precisely because it's the
sort of confident-and-wrong claim that costs someone else an afternoon.

All three traps now live in [`AGENT.md`](../../AGENT.md) as an actual procedure, next to
the fourth one we already knew about: the binaries workflow needs a manual dispatch,
because a Release created with the default `GITHUB_TOKEN` cannot trigger
`on: release: published`.

## What this chapter leaves open

Four of the five platforms are still theory: only Linux x86_64 has actually been compiled
and run, so cutting `v0.1.0` and watching macOS, ARM and Windows either produce binaries
or teach me something is the next real task. `install.sh` has never downloaded from an
actual GitHub Release either — the verification logic is proven against a stand-in, the
live URLs aren't. `jbang diderot@sunix` (the short form, without the repository) needs a
`sunix/jbang-catalog` repository that doesn't exist yet; `jbang diderot@sunix/diderot`
works from this repo's own catalog in the meantime. And Windows gets a native binary but
no installer — `install.sh` covers Linux and macOS, and a PowerShell equivalent is a
separate, later errand.
