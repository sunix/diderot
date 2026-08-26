# Prompt: Maintain a Making-Of Journal

Use this prompt to instruct an AI agent to create or update a first-person making-of journal in a repository.

---

Maintain a making-of journal for this repository: a first-person, blog-ish account of how the project is being built, written in the author's voice, for new contributors and anyone curious how it came together.

## If the file does not exist yet (bootstrap)

- Create `MAKING-OF.md` at the repository root. Copy the skeleton from `skills/documentation/making-of/templates/MAKING-OF.md`.
- Open with the three-part header block:
  1. A title stating the project and the angle (pattern: "Building <project>: <what it is>, one prompt at a time").
  2. An italic intro paragraph: what this document is, its style ("a first-person log / journal, not formal docs — kept for the reasoning, including the dead ends and reversals"), who it is for, and its update policy ("updated on request as the work progresses").
  3. A `*Last updated: YYYY-MM-DD.*` line.
- Write the first section as "Why this exists": the motivation or the triggering event, in the author's voice.
- Draft the initial entries from the git history, existing issues/PRs, and the current conversation. Ask the author about anything you cannot reconstruct (motivations, off-repo events) instead of inventing it.
- Write in the language the author uses when talking to you.

## If the file exists (end-of-session update)

- Read the whole file first and match its voice, language, and formatting exactly.
- Append one or more new `##` sections covering what happened since the last update. Do not pad: if the session produced one decision, write one short section.
- Each update must cover four things, in this order:
  1. **The goal, with a concrete example** — open with what the session set out to accomplish, shown concretely (the user story, the input the user writes, the command they run, the result they should get) *before* any how. The reader must know what success looks like before reading how it was reached.
  2. **What was done** — the changes, decisions, and outcomes of the session.
  3. **The discussions with the LLM** — what the agent proposed, what the author pushed back on or corrected, and how the disagreement resolved. A correction the author made to the agent's first answer is prime content.
  4. **Proof that generated code works** — see "Show proof, not claims" below. If code was written this session, the update is not complete without its evidence.
- When the session generated interesting code, also **walk through it**: quote the load-bearing snippets (trimmed) and explain why they are written that way — the tricky rule they encode, the edge case they guard. The journal is where a reader should understand the code's ideas, not just learn that it exists.
- **Write every code and command block as a shoulder-to-shoulder explanation**, as if the author had invited another developer to look at their screen. Applies to both kinds of block:
  - *Code*: name the file worth opening, then take a path through it — "start with `push`, which is shorter than you'd expect", "now the other direction, and the interesting part is what it *doesn't* have", "look at that last line". Never a declarative label followed by a fence.
  - *Commands and output*: say what is about to be run and what to watch for, so the reader knows which question the output answers before seeing it — "so watch what it prints", "then the two commands that matter, run against that freshly produced executable".
  - Every fenced block earns the sentence above it: that sentence says *why the block is there*, not merely that it exists. A lead-in ending in a bare colon after a flat clause ("…torn down at the end of the run:") is the tell that it doesn't.
  - When the context is long, put the short pointer *before* the block and the explanation *after* it — the reader should reach the code within a line or two, then be told what they just read.
  - Never open a walkthrough on inventory (dependency coordinates, a list of new files): that reads as a changelog. Open on what the reader should do or notice, and let the details follow.
- When the session created several files, give the **tour of the machinery**: every new file's role and who calls what, told as a story — follow one command or request through the layers, from entry point to effect — never as a bare inventory, and with enough trimmed snippets that the reader never has to leave the journal for the code. Cover what a maintainer or new contributor needs to open the hood: entry points, the core engine, the boundaries to the outside world (subprocesses, network, filesystem), and how the tests mirror the layout. Guard against tunnel vision: don't let the one clever piece of the session eclipse the feature around it.
- Update the `*Last updated:*` date. Change nothing else in the header block.
- Never rewrite or delete past sections. If a past conclusion turned out wrong, say so in the new section — the reversal is the content.

## Show proof, not claims

When a session produced generated code (a fix, a feature), never just state that it works. Include the evidence, as a reader would need it to believe you:

- **A snippet of the test** that exercises the change — the generated test itself, trimmed to the relevant assertion, in a fenced code block.
- **Why this test proves it** — one or two sentences connecting the assertion to the bug or requirement: what would have failed before the change, and why passing now means the behavior is correct (not just that the code runs).
- **The actual output of running it** — the real test-runner output (trimmed), in a fenced code block. A green run that was actually executed, not a description of one. If the test was also run against the pre-fix code to show it failing, include that red output too — it is the strongest proof.

Prefer **oracle-based proofs** over hardcoded expectations: when a reference implementation exists (the real git, the real compiler, the live API), the test should compare against the oracle's answer instead of a fixed expected value — a hardcoded value only asserts that the code does what the code does. Record that choice in the journal entry; the reasoning is content.

If the change cannot be proven by a test (docs, config), show the equivalent evidence: the command run and its real output.

## Content rules (both modes)

- **Blog style, not changelog style.** Full sentences and narrative flow, written to be read — the kind of text that could be published as a blog post as-is. Section titles are statements or hooks ("Turns out the install script needed fixes first"), not labels ("Fixes"). No bare bullet lists of commits.
- **First person, past tense, honest.** The human author's journal, drafted by you. No marketing tone, no "we are excited to".
- **Record reasoning, not just outcomes.** What was tried first, why it fell apart, what was landed on instead. Dead ends get their own paragraphs.
- **Record the conversation.** The back-and-forth with the LLM is part of the story: what was asked, what the agent got wrong, what the author corrected. Quote the pivotal exchange when it explains a decision.
- **Show proof, not claims.** Generated code is only "done" in the journal when the evidence is shown (see the section above).
- **Be concrete.** Link actual PRs, issues, and files; quote measured numbers; use tables for data. When a claim was verified, say against what.
- **No meta-narration.** The journal never talks about the writing or updating of the journal itself — no "I updated this making-of", no describing the journal's structure, its update ritual, or the skill that produced it. Sole exception: a repository whose subject *is* the making-of practice (e.g. the repo hosting this skill).
- **Keep it readable in one sitting.** Sections short, one idea each.

## Splitting into a post series

When the single file no longer reads in one sitting **and** a natural boundary exists (a milestone shipped):

- Move the body into `doc/making-of/NN-<slug>.md` posts (copy the post skeleton from `skills/documentation/making-of/templates/making-of-post.md`).
- Turn the root `MAKING-OF.md` into an index: keep the header block, replace the body with a numbered "Posts" list, one line per post with a link and a one-sentence summary of what it covers.
- New milestones get new posts; the index gets one new line.
