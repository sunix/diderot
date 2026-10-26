# The first real signature

*Part ten of [diderot's making-of](../../MAKING-OF.md): everything was proven in tests. Publishing
one real skill took ten rounds and found what the tests could not.*

> [Parts eight](08-storing-a-signature.md) and [nine](09-verifying-the-signer.md) built signing and
> checked it against a registry in a container and sigstore's staging instance. This part publishes
> a skill people actually install, and verifies it the way they would.

## The goal: a real skill, not a fixture

The request was plain: *test it for real, with a real skill*. The one to use was obvious —
`making-of` itself, the skill this journal's own tooling comes from, published at
`ghcr.io/sunix/skills/making-of` since part four.

The first run needed no new code at all, and it is the one worth starting with, because the answer
came back as a refusal. A project that declares the real skill and pins the workflow that publishes
it:

```yaml
skills:
  - name: making-of
    source: oci://ghcr.io/sunix/skills/making-of
    version: "^1.0.0"
    signer:
      identity: https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
      issuer: https://token.actions.githubusercontent.com
```

```console
$ diderot update
error: Skill 'making-of': ghcr.io/sunix/skills/making-of@sha256:d14f082bef1a… carries no signature,
       but diderot.yaml pins a signer.
       expected  https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
       Nothing was written.
```

Correct, and useful: everything published so far predates signing, so there is nothing to verify and
the project is told exactly that. `diderot.lock` was untouched, the copy already installed stayed on
disk, and `install` refused in the same words rather than trusting the lockfile — which is the
behaviour part nine describes, observed for the first time on an artifact nobody made for a test.

Then the real work: publish that skill signed, and run the same two commands again.

## What the tests could not see

Publishing needed two things. ai-skills' workflow had to declare `id-token: write` and pass
`--sign`, which is three lines. And it installs diderot from the latest release, so signing had to
ship: v0.4.0.

The first signed publish failed in under two seconds:

```console
error: Could not build a sigstore signer:
       resource dev/sigstore/tuf/sigstore-tuf-root/root.json not found.
```

Part seven had written this down, in the one place a prediction is worth making — at the end, under
what the chapter leaves open:

> `native-smoke` proves the binary builds and starts, not that the keyless flow — TUF roots, Fulcio,
> Rekor — runs in a native image. That gets its real test when the publish workflow signs with the
> ambient GitHub OIDC token.

That is exactly what happened, on the first attempt. The cause is a single property of
`native-image`: it compiles what it can reach by following the bytecode, and **anything a program
finds by name at run time is invisible to it**. sigstore-java reads its trust root out of its own
jar. Nothing in the bytecode names that file, so it was not in the binary.

The JVM build never notices. Every test in parts eight and nine passed, on a JVM, against real
certificates — and none of them could have caught this.

## An error that said nothing

The fix for the trust root was one line of configuration. The next failure was worse than a failure:

```console
error: Could not build a sigstore signer:
```

Nothing after the colon. The exception carried no message, and `PushCommand` was printing
`e.getMessage()`, so the type was thrown away with it. There was no way to tell a missing class from
a refused token, and the only instrument left was another release.

So the command stopped doing that:

```java
private static String describe(Throwable error) {
    StringBuilder chain = new StringBuilder();
    for (Throwable t = error; t != null; t = t.getCause()) {
        if (!chain.isEmpty()) {
            chain.append("\n  caused by ");
        }
        String message = t.getMessage();
        chain.append(message == null || message.isBlank()
                ? t.getClass().getName()
                : t.getClass().getSimpleName() + ": " + message);
        if (t == t.getCause()) {
            break;
        }
    }
    return chain.toString();
}
```

It earned its keep on the next run, which named its own cause instead of being silent:

```console
error: IOException: Could not build a sigstore signer:
  caused by IllegalArgumentException: unable to create new instance of class
    com.google.api.client.json.webtoken.JsonWebSignature$Header
    because it has no accessible default constructor
  caused by NoSuchMethodException: …JsonWebSignature$Header.<init>()
```

A library that throws without a message is not rare. Printing the chain costs twelve lines and turns
"something went wrong" into a name you can search for.

## Ten rounds, one cause

What follows looks like ten problems. It is one, meeting the program at every layer that needed it.
`native-image` cannot see three kinds of thing: **resources** read by name, **classes and members
reached by reflection**, and **implementations found through `ServiceLoader`**. sigstore uses all
three, at four depths.

| round | what failed | why | fix |
|---|---|---|---|
| 1 | `resource …/sigstore-tuf-root/root.json not found` | trust root read from the jar | list it in `quarkus.native.resources.includes` |
| 2 | `Could not parse trusted root` | protobuf resolves accessors by name | register the proto classes |
| 3 | `no such provider: BC` | BouncyCastle registers itself at run time | `quarkus-security` + `security-providers=BC` |
| 4 | provider found in the image heap | the registration above initialises it while building, and part seven deferred all of BouncyCastle | drop the hand-rolled deferral |
| 5 | `JsonWebSignature$Header … no accessible default constructor` | the identity token is a JWT, parsed reflectively | register the JWT classes |
| 6 | `No functional channel service provider found` | gRPC finds its transport through `ServiceLoader` | ship the service files, name the providers |
| 7 | `PooledByteBufAllocator` in the image heap | naming a gRPC provider initialises it at build time, and its allocator is a netty type Quarkus defers | defer `io.grpc.netty` too |
| 8 | `Random` in the image heap | `RetriableStream.random` jitters retries; a frozen seed would ship in every copy | defer that class |
| 9 | `ServiceConfigurationError: UdsNettyChannelProvider not found` | I shipped a service file and registered part of what it names | register all of it |
| 10 | `"io.intoto.EnvelopeOuterClass$Envelope" missing method "newBuilder"` | a generated class in a package the registration did not list | register the whole jar |

Round nine is mine. A service file is a promise about every line in it: shipping it while registering
three of its five providers is worse than not shipping it, because `ServiceLoader` then reads a name
it cannot load and the binary dies on startup — before printing its version, for every command.

Round ten is the one that ended the guessing:

```console
error: IOException: Could not build a sigstore signer:
  caused by IllegalStateException: Generated message class
    "io.intoto.EnvelopeOuterClass$Envelope" missing method "newBuilder".
```

Another generated class, in another package. The registration had grown by hand — the proto package,
then the client packages — and each narrower list compiled, passed every test on a JVM, and failed
one step further into the first real signature. So it stopped being a list of packages and became
the jar:

```java
 * <p>Listing the whole jar rather than the packages known to bite is deliberate. Each narrower
 * attempt — the proto package, then the client packages — compiled, passed every test on the JVM,
 * and failed one step further into the first real signature. The cost of being broad here is image
 * size; the cost of being precise was a round trip through CI per package.
```

747 classes, generated by one command that the javadoc records, so the next upgrade of sigstore-java
is a regeneration rather than another nine rounds.

## Four minutes instead of a release

Rounds one to five each cost a release: merge the fix, merge the snapshot PR, merge the release PR,
wait for six native binaries, dispatch the publish in the other repository, read the error. Three
merges and a quarter of an hour to learn one fact.

The reason it was that expensive is worth naming, because it is a property of the system and not of
the bug. Signing only breaks where it is **whole**: a native binary *and* a real OIDC identity.
diderot's CI had the binary and no identity; ai-skills' workflow had both but could only install
released binaries. Neither half could fail usefully.

So the test moved to where the code is:

```yaml
on:
  workflow_dispatch:
  push:
    branches:
      - 'fix/native-**'

permissions:
  contents: read
  # The whole reason this file exists: without it the runner has no identity to sign with.
  id-token: write

jobs:
  sign-for-real:
    runs-on: ubuntu-latest
    services:
      registry:
        image: registry:2
        ports:
          - 5000:5000
```

Nothing is published: the target is a registry in a service container. What is real is the
signature — a certificate from the public Fulcio, an entry in the public Rekor log, issued to this
workflow. That is also why it runs on request and on signing branches rather than on every push.

Two choices inside it did most of the work. It signs **with the jar first and the native binary
second**, so a failure says which of the two differs rather than leaving it to be argued: every
round from then on began with the jar succeeding. And it does not stop at signing — it pins the
identity that signed, which the workflow can compute about itself, and requires `signer ok`:

```yaml
              signer:
                identity: https://github.com/${{ github.repository }}/.github/workflows/signing-e2e.yml@${{ github.ref }}
                issuer: https://token.actions.githubusercontent.com
```

A hypothesis now costs four minutes. Rounds six to ten took one evening.

## The test that only said what it accepted

There was a second test in this story, and its first version was wrong in a way worth keeping.

`native-smoke` had been extended to run `push --sign` on the built binary. With no identity on the
runner, signing must fail; the step decided whether the failure was acceptable by listing the
failures it would accept — an OIDC error, an identity error — and failing the job on anything else.

Then the image got far enough to reach Fulcio and failed there instead, with
`Failed to obtain signing certificate`. That is good news: the trust root loaded, protobuf parsed,
BouncyCastle worked. The job failed anyway, because the good news was not on the list.

Inverted, it has not needed touching since:

```yaml
          for broken in \
            "not found" \
            "no such provider" \
            "cannot be found" \
            "Could not parse trusted root" \
            "ClassNotFoundException" \
            "NoClassDefFoundError" \
            "NoSuchMethodException" \
            "InstantiationException" \
            "UnsatisfiedLinkError"
          do
            case "$out" in
              *"$broken"*)
                echo "::error::the native image is missing something: matched '$broken' in: $out"
                exit 1 ;;
            esac
          done
          echo "sigstore ran inside the native image and stopped on credentials, as expected"
```

A test that lists what it tolerates has to be edited every time reality improves. A test that lists
what it refuses states its claim: *this image is not missing anything*, whatever happens afterwards.

## A release with no binaries, again

One more failure, and it is the one part four already told. The release that would carry the fix,
v0.4.2, was created — and had no binaries attached. release-please had died on a transient GitHub
API error *after* creating the release, so the chained job that builds the binaries was skipped.

The publish then failed at `Install diderot`, because `install.sh` had nothing to download.

Two things made this quick rather than confusing. `release-binaries.yml` has a manual entry,
written for exactly this case after v0.2.0 shipped empty, and it attached the twelve assets to the
existing tag without a new release. And the second attempt put the check in front of the action:

```bash
n=$(gh release view v0.4.2 --repo sunix/diderot --json assets -q '[.assets[].name] | length')
[ "$n" -ge 12 ] || { echo "not publishing"; exit 1; }
```

The first attempt had printed the same number — `0` — and published anyway, because the check and
the dispatch were in one command and nothing connected them. The lesson part four recorded was not
"be careful"; it is that a check which cannot stop the action is decoration.

## Proof: a skill anyone can verify

The publish, with the native binary doing the signing:

```console
signed sha256:2d456036daade15f… (9629 byte sigstore bundle)
pushed skills/documentation/making-of -> ghcr.io/sunix/skills/making-of:1.4.0@sha256:8eab06b25d3f
signed sha256:2d456036daade15f… (9615 byte sigstore bundle)
pushed skills/documentation/making-of -> ghcr.io/sunix/skills/making-of:latest@sha256:0f590367124b
```

Then the opening scenario, run again with nothing changed but the artifact — same project, same pin,
diderot installed by `install.sh` the way anyone would:

```console
$ diderot update
locked making-of  ghcr.io/sunix/skills/making-of:1.4.0@sha256:8eab06b25d3f
                  (tree:b22ca3276855…, signer ok)
wrote diderot.lock

$ diderot install
installed making-of  -> .claude/skills/making-of (tree:b22ca3276855… verified)
```

And the refusal, with the pin changed to somebody else:

```console
$ diderot update
error: Skill 'making-of': ghcr.io/sunix/skills/making-of@sha256:8eab06b25d3f… is signed,
       but not by the expected signer.
       expected  https://github.com/attacker/tools/.github/workflows/release.yml@refs/heads/main
       found     https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
       Nothing was written.
```

The `found` line is not a fixture. It is the identity Fulcio issued to ai-skills' publish workflow,
read back out of the certificate stored with the skill. The scenario part seven opened with — a
leaked registry token can push bytes, but cannot sign as that workflow — is now something a reader
can check with six lines of `diderot.yaml`.

## What this leaves open

**The workflow signs each tag separately.** Two pushes, two `--sign`, two bundles for one content
digest. Part eight showed that one signature covers every tag because it attests the content, so the
second signing is work nobody needs: sign once, pass the bundle to both pushes.

**Verification still needs the network**, even with a valid cached trust root
([#44](https://github.com/sunix/diderot/issues/44)) — which matters more now that there is something
real to verify.

**Nothing has been said to anyone.** A skill is signed, and the only way to find out is to pin a
signer and watch what happens. `status` does not mention it, and there is no command that shows who
signed what: [#39](https://github.com/sunix/diderot/issues/39) and
[#40](https://github.com/sunix/diderot/issues/40). That is the next thing worth building, along with
`add` discovering an identity instead of asking somebody to paste one, and the never-regress rule on
[#25](https://github.com/sunix/diderot/issues/25).
