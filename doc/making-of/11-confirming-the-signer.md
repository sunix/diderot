# Asking before trusting a signer

*Part eleven of [diderot's making-of](../../MAKING-OF.md): `add` and `update` show who signed a
skill and ask before trusting them. The question had already shipped once and been taken out; this
part puts it back and answers the reason it was removed.*

> [Part ten](10-the-first-real-signature.md) ended with a real signed skill on ghcr and a list of
> what was still owed, starting with `add` discovering an identity instead of somebody pasting one.
> That was built straight afterwards, twice, and the second version removed the question the first
> one asked. This part starts there.

## A question that shipped, then went away

Parts [six](06-add-and-remove.md) and [seven](07-signing-foundations.md) both drew the same screen
long before any of it existed. `add` resolves the skill, so it has the signature in hand. It shows
who signed it, and asks:

```console
$ diderot add oci://ghcr.io/sunix/skills/making-of
  signed by   https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
  issuer      https://token.actions.githubusercontent.com
  Trust this signer for making-of? [y/N] y
```

[PR #59](https://github.com/sunix/diderot/pull/59) built exactly that. Later that day
[PR #61](https://github.com/sunix/diderot/pull/61) took the question out again and made `add` pin
the signer without asking. Its commit message gave three reasons:

> - nothing asks before writing the resolved digest into diderot.lock, and pinning a signer is the
>   same act - recording what was true at declaration time so a later change is visible;
> - the prompt showed a string the reader had never seen. Answering it is not a security
>   judgement, and the value of the pin - change detection - does not depend on anyone having read
>   the identity;
> - the no-terminal branch is where agents and CI live, and there the default outcome was an
>   unverified project that looked exactly like a verified one.

The third one is a bug report, not an opinion. #59 checked `System.console() == null`; with no
terminal it printed a note, pinned nothing, and exited 0. In a CI log that looks the same as success.

So the request for [#63](https://github.com/sunix/diderot/issues/63), *"when adding and updating a
skill, the user should confirm the signature"*, could not just revert #61. It needed a different
answer to that third point.

## The rule, before any code

The rule went into #63 before any code was written, as a table. It is a single sentence:
**diderot asks whenever it would trust a signer the project has not trusted yet.**

| Command | Situation | Before | Now |
|---|---|---|---|
| `add` | skill is signed | pinned silently (#61) | shows the signer and asks; no adds nothing |
| `update` | pinned signer still signs it | `signer ok` | unchanged, no question |
| `update` | **somebody else** signs it | refused; fix `diderot.yaml` by hand | shows both, asks; yes re-pins |
| `update` | signed, but pins nobody | not checked | offers to pin |

The answer to #61's third point is the row it doesn't show: **with no terminal, a confirmation
that is required becomes a refusal, never a skip.** The refusal prints the signer and says how to
proceed. `--yes` is how a script says it has read the identity. An agent that runs a bare `add`
gets a failing command it can act on, instead of a green one that quietly verified nothing.

That makes three points, and the branch has one commit per point.

## `add` asks, and refuses where nobody can answer

*Commit `ece8693` — feat(add): show who signed a skill, and ask before trusting them. Files:
[`Confirm.java`](../../src/main/java/org/sunix/diderot/core/Confirm.java),
[`Workspace.java`](../../src/main/java/org/sunix/diderot/core/Workspace.java),
[`AddCommand.java`](../../src/main/java/org/sunix/diderot/commands/AddCommand.java),
[`SignatureVerificationTest.java`](../../src/test/java/org/sunix/diderot/core/SignatureVerificationTest.java),
[`signing-e2e.yml`](../../.github/workflows/signing-e2e.yml).*

The goal is easiest to see as the case that was wrong. This is `main`, before the change, adding
the real `making-of` skill with stdin redirected from `/dev/null`, which is what an agent's shell
looks like. Look at the exit code and at what ended up in the file:

```console
$ diderot add oci://ghcr.io/sunix/skills/making-of </dev/null
locked making-of            ghcr.io/sunix/skills/making-of:latest@sha256:0f590367124b (tree:b22ca3276855…)
  signed by   https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
  issuer      https://token.actions.githubusercontent.com
  pinned      in diderot.yaml — --no-pin-signer declares it without that
added making-of            oci://ghcr.io/sunix/skills/making-of (latest)
locked making-of            ghcr.io/sunix/skills/making-of:latest@sha256:0f590367124b (tree:b22ca3276855…, signer ok)
exit=0
```

Nobody read that identity, and it is now the trust anchor in `diderot.yaml`. The same command
after the change, with no terminal and then with one:

```console
$ diderot add oci://ghcr.io/sunix/skills/making-of </dev/null
  signed by   https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
  issuer      https://token.actions.githubusercontent.com
error: Skill 'making-of' is signed, and there is no terminal to confirm the signer on. Read the
       identity above, then pass --yes to trust it, or --no-pin-signer to declare the skill without
       a signer. Nothing was written.
exit=1

$ diderot add oci://ghcr.io/sunix/skills/making-of
  signed by   https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
  issuer      https://token.actions.githubusercontent.com
  Trust this signer for making-of? [y/N] y
locked making-of            ghcr.io/sunix/skills/making-of:latest@sha256:0f590367124b (tree:b22ca3276855…, signer ok)
added making-of            oci://ghcr.io/sunix/skills/making-of (latest, signer pinned)
```

The first run left the directory empty. The second was typed in a real pseudo-terminal
(`script -qec`), so `Confirm.console()` really read the `y`. Neither answer came from a test double.

### Three answers, not two

Open `Confirm.java` at `ask`. It returns `Optional<Boolean>`, which looks clumsy until you list
what a person at a prompt can give back:

```java
/** {@code true} for yes, {@code false} for no, empty when there is no one to ask. */
Optional<Boolean> ask(String question);
```

"No" is a decision, and "nobody to ask" is the absence of one. #59 merged the two: no terminal
meant "don't pin", which is also what "no" means, and the caller couldn't tell them apart. Keeping
three answers is what lets `add` treat them differently: `false` refuses because you said so,
`empty` refuses and tells you about `--yes`.

The interface has three implementations: `console()`, `yes()` for the flag, and `nobody()`. The
tests add their own lambdas, which is how they ask questions with no terminal involved.

### Where the question is asked

Now `Workspace.java`, at `trustSigner`. This is the whole of point one, trimmed:

```java
if (skill.signer != null) {
    return enforcePinnedSigner(skill.name, skill.signer, repository, digest, content);
}
if (unpinned == UnpinnedSigner.IGNORE) {
    return null;
}
String bundle = oci.fetchSignature(repository, digest).orElse(null);
if (bundle == null) {
    return null;                                     // unsigned: nothing to ask about
}
Signer found = selfConsistentSigner(bundle, content);
if (found == null) {
    throw new IOException(problem + " Nothing was written.");
}
printSigner(found);
Optional<Boolean> answer = confirm.ask("  Trust this signer for " + skill.name + "?");
if (answer.orElse(false)) {
    skill.signer = found;
    return found;
}
throw new IOException(answer.isEmpty() ? /* --yes, --no-pin-signer */ : /* not trusted */);
```

A pinned signer goes straight to part nine's check: it was confirmed when it was pinned, so nothing
is asked. An unsigned skill returns before anything is printed. Most of the ecosystem is unsigned,
and a line about that on every `add` is a line people learn to skip.

The line worth stopping on is `selfConsistentSigner`. Before anybody is asked to trust an
identity, the signature has to verify **against that same identity, over the bytes just pulled**.
`Signing.identityOf` only reads the certificate. Without this check the prompt could be showing a
name copied into a bundle that signs something else. It also replaced `add`'s second resolution,
the one #59 added just to verify the pin it had written. The verification now happens before the
question, which is the more useful place for it.

`AddCommand` shrinks to choosing the policy and the confirmer:

```java
Workspace workspace = new Workspace(root, new GitCli(GitCli.defaultCacheRoot()),
        new OrasClient(OrasClient.defaultCacheRoot()), out)
        .confirmingWith(yes ? Confirm.yes() : Confirm.console());

LockedSkill locked = workspace.resolve(skill,
        noPinSigner ? UnpinnedSigner.IGNORE : UnpinnedSigner.REQUIRE);
```

### Proof

There is no red run of these unit tests: they call `resolve(skill, policy)` and `confirmingWith`,
which didn't exist before this commit, so on `main` they don't compile. The red/green pair for this
point is the two `add` runs above, against the real registry. The tests pin each answer down
separately. The no-terminal one is the claim this whole part rests on:

```java
@Test
void addWithNoTerminalRefusesAndSaysHowToProceed() throws Exception {
    String repository = publishSigned("add-headless");
    Manifest.ManifestSkill skill = unpinnedSkill(repository);

    IOException refusal = assertThrows(IOException.class,
            () -> workspace(tmp.resolve("add-headless-consumer"), new StringWriter())
                    .confirmingWith(Confirm.nobody())
                    .resolve(skill, Workspace.UnpinnedSigner.REQUIRE));

    assertTrue(refusal.getMessage().contains("--yes"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("--no-pin-signer"), refusal.getMessage());
}
```

`publishSigned` is the same fixture as part nine: a `registry:2` container, and a real signature
from sigstore's staging instance. The identity the test expects is the one Fulcio actually put in
the certificate, so sigstore is the oracle here, not a string in the test. Together with the yes,
no and unsigned cases:

```console
$ ./mvnw test -Dtest='SignatureVerificationTest#add*+anUnsignedSkillIsAddedWithoutAQuestion'
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 28.08 s -- in org.sunix.diderot.core.SignatureVerificationTest
[INFO] BUILD SUCCESS
```

`signing-e2e` now has the headless refusal as its own step, run against the native binary with
`</dev/null`, and the step that pins uses `add --yes`.

## A different signer stops being a dead end

*Commit `648b800` — feat(update): when somebody else signs a pinned skill, ask instead of only
refusing. Files: [`Workspace.java`](../../src/main/java/org/sunix/diderot/core/Workspace.java)
(`trustSigner`, `update`),
[`ManifestEditor.java`](../../src/main/java/org/sunix/diderot/core/ManifestEditor.java)
(`pinSigner`), [`UpdateCommand.java`](../../src/main/java/org/sunix/diderot/commands/UpdateCommand.java),
[`ManifestEditorTest.java`](../../src/test/java/org/sunix/diderot/core/ManifestEditorTest.java).*

Part six designed this refusal and ended its message with an instruction: *"If this change is
legitimate, update `signer:` in diderot.yaml."* That is a person copying an identity string into a
YAML file by hand, which is exactly what `add` exists to avoid. Here is the case on `main`, with a
project pinned to a workflow that no longer signs the skill:

```console
$ diderot update
error: Skill 'making-of': ghcr.io/sunix/skills/making-of@sha256:0f590367124b… is signed, but not by the expected signer.
       expected  https://github.com/sunix/ai-skills/.github/workflows/release.yml@refs/heads/main
       found     https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
       Nothing was written.
```

That is correct, and it's still where the project stays when the answer is no. With this commit,
the same project in a terminal:

```console
$ diderot update
  making-of is now signed by somebody other than the signer diderot.yaml pins
  pinned      https://github.com/sunix/ai-skills/.github/workflows/release.yml@refs/heads/main (https://token.actions.githubusercontent.com)
  signed by   https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
  issuer      https://token.actions.githubusercontent.com
  Trust the new signer for making-of? [y/N] y
locked making-of            ghcr.io/sunix/skills/making-of:latest@sha256:0f590367124b (tree:b22ca3276855…, signer ok)
pinned making-of            signer in diderot.yaml
wrote diderot.lock
```

The old pin goes above the new one because that is the comparison a person has to make. A renamed
workflow and a stranger's workflow look completely different side by side.

### Getting the bundle out of the refusal

`enforcePinnedSigner` is part nine's code and still the only place a pin is checked. The mismatch
used to be a plain `IOException`, which carried the message but dropped the bundle that produced
it. It is now `SignerMismatch`, an `IOException` that keeps the bundle, so `trustSigner` can ask
about exactly what was refused:

```java
try {
    return enforcePinnedSigner(skill.name, skill.signer, repository, digest, content);
} catch (SignerMismatch mismatch) {
    Signer found = selfConsistentSigner(mismatch.bundle, content);
    if (found == null) {
        throw mismatch;
    }
    // print pinned, then found, then ask
    if (answer.orElse(false)) {
        skill.signer = found;
        return found;
    }
    throw new IOException(mismatch.getMessage() + /* --yes, when nobody could answer */, mismatch);
}
```

It is still a subclass of `IOException`, so `install` didn't change. `install` never asks: it
re-checks a lockfile that a teammate produced, and the place to change trust is `update`.

### Writing the pin last

Now `update` in the same file. A manifest can hold several skills, and the third one can refuse
after the first was re-pinned. So the confirmed pins are collected and written only once every
skill has resolved:

```java
Map<String, Signer> newPins = new LinkedHashMap<>();
for (ManifestSkill skill : manifest.skills) {
    Signer before = skill.signer;
    LockedSkill locked = /* … resolve … */;
    if (skill.signer != before) {
        newPins.put(skill.name, skill.signer);
    }
    // …
}
if (!newPins.isEmpty()) {
    ManifestEditor editor = ManifestEditor.of(Files.readString(manifestPath()));
    newPins.forEach(editor::pinSigner);
    Files.writeString(manifestPath(), editor.text());
}
Yaml.write(lockPath(), lock);
```

`skill.signer != before` compares references, not values. `trustSigner` only ever assigns a new
`Signer` when someone said yes, so a changed reference is exactly "a pin was confirmed in this run".

`ManifestEditor.pinSigner` follows the rule part six set for this class: splice lines, never
re-serialise. It finds the entry, removes the `signer:` line and the deeper-indented lines under
it, and appends the new block at the end of the entry. The second test is the one that would catch
a doubled block:

```java
editor.pinSigner("making-of", new Signer("new@example.test", "https://new.example.test"));

assertEquals("""
        skills:
          - name: making-of
            source: oci://ghcr.io/sunix/skills/making-of
            version: latest
            signer:
              identity: new@example.test
              issuer: https://new.example.test
          - name: other
        …""", editor.text());
```

The input there had `signer:` *before* `version:`. Hand-written manifests come in any key order,
and the block moving to the end of the entry is the one visible edit.

### Proof

These tests were written first and run against the previous commit, where `confirmingWith` already
existed, so the red is real. The yes case:

```java
@Test
void updateAsksBeforeTrustingANewSignerAndRePinsOnYes() throws Exception {
    String repository = publishSigned("update-changed");
    Path project = project("update-changed-consumer", repository, PUBLISHER, ISSUER);
    List<String> asked = new ArrayList<>();

    LockFile lock = workspace(project, output).confirmingWith(answering(true, asked)).update();

    assertEquals(List.of("  Trust the new signer for making-of?"), asked);
    assertEquals(manifestYaml(repository, IDENTITY, ISSUER), Files.readString(project.resolve("diderot.yaml")));
    assertEquals(IDENTITY, lock.skills.get(0).signer.identity);
}
```

Before, `update` never reached a question:

```console
[ERROR] SignatureVerificationTest.updateAsksBeforeTrustingANewSignerAndRePinsOnYes -- <<< ERROR!
java.io.IOException:
Skill 'making-of': 127.0.0.1:32774/skills/update-changed@sha256:e920254f35b9… is signed, but not by the expected signer.
[ERROR] SignatureVerificationTest.updateKeepsRefusingANewSignerThatIsDeclinedOrCannotBeAskedAbout -- <<< FAILURE!
       Nothing was written. ==> expected: <true> but was: <false>
[ERROR] Tests run: 6, Failures: 1, Errors: 1, Skipped: 0
```

The second failure is the refusal with nobody to ask: the old message never mentioned `--yes`.
Then, after the commit:

```console
$ ./mvnw test -Dtest='SignatureVerificationTest#updateAsks*+updateKeeps*,ManifestEditorTest#pinning*'
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in org.sunix.diderot.core.ManifestEditorTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in org.sunix.diderot.core.SignatureVerificationTest
[INFO] BUILD SUCCESS
```

One more test came with this commit, `updateDoesNotAskAboutASignerAlreadyPinned`, and it passed in
the red run as well. That is expected: it checks that the old behaviour survives (a confirmer that
fails the test if it is ever called), not the new one. Its green says nothing about this feature.
It is there to catch the day a refactor starts asking about every signer on every update.

## Offering a pin to a skill that pins nobody

*Commit `6f57bba` — feat(update): offer to pin a signed skill that pins nobody yet. Files:
[`Workspace.java`](../../src/main/java/org/sunix/diderot/core/Workspace.java) (`UnpinnedSigner`,
the end of `trustSigner`), [`SignatureVerificationTest.java`](../../src/test/java/org/sunix/diderot/core/SignatureVerificationTest.java).*

ai-skills' own `diderot.yaml` is this case. It declares `making-of` with no `signer:` because it
predates 0.5.0, so its signed skill resolves with no check at all. Point one only helps new
declarations. Point three is how existing ones catch up: `update` shows the signer and offers to
pin it.

The policy is the third value of the enum `add` already uses:

```java
public enum UnpinnedSigner {
    IGNORE,   // add --no-pin-signer, and resolve() by default
    OFFER,    // update: show, offer; no or nobody to ask leaves it unpinned
    REQUIRE   // add: confirmed, or nothing is resolved
}
```

The difference from `REQUIRE` is all at the end of `trustSigner`, and it is the one place this
part lets a missing terminal pass:

```java
Optional<Boolean> answer = confirm.ask(required
        ? "  Trust this signer for " + skill.name + "?"
        : "  Pin this signer for " + skill.name + " in diderot.yaml?");
// … yes pins, as before; for REQUIRE, anything else throws …
out.println(answer.isEmpty()
        ? "  not pinned  no terminal to ask on — `diderot update --yes` pins it"
        : "  not pinned");
return null;
```

Why the exception, after a whole part arguing against skipping? Because nothing gets weaker here.
`update` never pinned these skills, and every pipeline that runs it today expects exit 0. Failing
would break them to report a state they were already in. The note says how to change that state.
The real run is ai-skills' manifest, copied into a scratch directory:

```console
$ diderot update </dev/null
  signed by   https://github.com/sunix/ai-skills/.github/workflows/push-skill-to-oci.yml@refs/heads/main
  issuer      https://token.actions.githubusercontent.com
  not pinned  no terminal to ask on — `diderot update --yes` pins it
locked making-of            ghcr.io/sunix/skills/making-of:latest@sha256:0f590367124b (tree:b22ca3276855…)
wrote diderot.lock
exit=0
```

### Proof

Again the tests came first and ran against the previous commit:

```java
@Test
void updateOffersToPinASignedSkillThatPinsNobody() throws Exception {
    String repository = publishSigned("update-offer");
    Path project = project("update-offer-consumer", repository, null, null);
    List<String> asked = new ArrayList<>();

    LockFile lock = workspace(project, new StringWriter())
            .confirmingWith(answering(true, asked)).update();

    assertEquals(List.of("  Pin this signer for making-of in diderot.yaml?"), asked);
    assertEquals(manifestYaml(repository, IDENTITY, ISSUER),
            Files.readString(project.resolve("diderot.yaml")),
            "the pin lands in the entry, and only there");
}
```

```console
[ERROR] SignatureVerificationTest.updateOffersToPinASignedSkillThatPinsNobody -- <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <[  Pin this signer for making-of in diderot.yaml?]> but was: <[]>
[ERROR] SignatureVerificationTest.updateWithNoTerminalLeavesAnUnpinnedSkillAsItWas -- <<< FAILURE!
 ==> expected: <true> but was: <false>
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0
```

`but was: <[]>` is the bug in one value: before, the question was never asked. The second failure
needs a caveat. That test asserts two things: the manifest and lock are left unpinned, and the
output names `update --yes`. Only the second assertion failed. The first was already true, because
leaving the file alone is what the old code always did. So the red run proves the note is new, not
that headless `update` is careful. After the commit:

```console
$ ./mvnw test -Dtest='SignatureVerificationTest#updateOffers*+updateWithNoTerminal*'
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

With the README section in its own commit (`8ac87cc`), the full suite on the branch ran green:
fifteen signature tests against a real registry and sigstore staging, none skipped.

## Following one `add` through the code

From the top, for anyone opening this for the first time. `AddCommand.call` builds a `Workspace`,
gives it a `Confirm` (`console()`, or `yes()` under `--yes`) and calls
`resolve(skill, REQUIRE)`. `Workspace.resolve` dispatches on the source. A git skill never reaches
any of this: git sources have no signatures. An OCI skill goes to `lockOciSkill`, which pulls the
content into the cache, computes the tree digest, and hands content and digest to `trustSigner`.

`trustSigner` is the policy. It reads `skill.signer` (what the manifest pins), uses
`oci.fetchSignature` (the registry boundary) for the bundle, `selfConsistentSigner` and then
`Signing.verifySignature` (the sigstore boundary, part nine's single call) to establish who really
signed these bytes, and `confirm.ask` (the human boundary) to decide. Its one side effect is
setting `skill.signer` on yes. It writes no files. Writing belongs to the callers: `AddCommand`
writes the new entry with `ManifestEditor.add`, and `update` writes re-pins with
`ManifestEditor.pinSigner`, after everything has resolved.

The tests follow the same split. `ManifestEditorTest` is pure text in and text out.
`SignatureVerificationTest` drives `Workspace` directly against a real registry and real staging
signatures, swapping only the human for a lambda through `confirmingWith`. `AddRemoveTest` covers
what is left in the command: the spelling of its flags, including `-y`.

## The claim that was half right

`Confirm.console()` doesn't stop at `System.console() != null`. It also calls
`Console.isTerminal()`, looked up reflectively because diderot compiles for Java 21 and the method
only exists from 22. The first version of the javadoc gave the reason as fact: *"From JDK 22 on,
`System.console()` returns a console even with stdin redirected."* The commit message said the
same.

Before repeating that here as a finding, it got checked. A four-line probe printed what
`System.console()` returned, run with stdin piped:

```console
openjdk version "21.0.4"   →  System.console() = null
openjdk version "23.0.2"   →  System.console() = non-null, isTerminal() = false
openjdk version "25.0.2"   →  System.console() = null
```

(JDK 23 ran in an `eclipse-temurin:23-jdk` container, since only 21 and 25 were installed. The 21
probe leaves out `isTerminal()`, which doesn't compile there.) So the claim was half right. On 22 to 24 a pipe really does get a console object, and #59's null check
would have printed its question into a pipe and read the empty reply as "no". On 25 the null check
is enough again. The `isTerminal()` call stays because diderot also runs on whatever JVM JBang
finds, and nothing stops that from being 22, 23 or 24. The javadoc and the commit message now say "22 to 24", with the versions
it was checked on.

The native binaries never take that branch at all: they are built on JDK 21, where `isTerminal()`
doesn't exist and a non-null console really is a terminal (the 21 probe in a pseudo-terminal printed
`non-null`). `Console` is registered for reflection anyway, so a native image built on a newer JDK
would find `isTerminal()` the way the JVM does instead of silently falling back. `signing-e2e`'s
headless step is what runs `Confirm.console()` inside the native image.

## What this leaves open

**"No" isn't remembered.** A skill declared with `--no-pin-signer` is offered a pin on every
interactive `update`. That is acceptable for a project still migrating, and tiresome for one that
decided on purpose. Recording that decision would take a manifest field that means "unpinned on
purpose", and none exists yet.

**Signer confirmation stops at `update`.** `install` still only enforces: a lockfile from a
teammate is re-checked, never re-trusted. That is deliberate, and it means a changed signer reaches
a project only through someone running `update` at a terminal, or with `--yes` in a script.

**Finding a skill still means knowing its URL.** Every example in this part starts from
`oci://ghcr.io/sunix/skills/making-of`, typed in full. Global catalogs, with sunix/ai-skills as the
default and `diderot search` over them, are
[#64](https://github.com/sunix/diderot/issues/64), and the next thing to build.
