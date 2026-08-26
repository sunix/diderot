# Building <project>: <what it is>, one prompt at a time

*A first-person account of how this project came together — the tooling, the false starts,
and the implementation work — kept here for new contributors, or anyone curious how it came
together. Written in a blog-ish style rather than as formal docs; may still turn into an
actual blog post at some point. Updated on request as the work progresses.*

*Last updated: YYYY-MM-DD.*

## Why this exists

<!-- The motivation or triggering event, in the author's voice. What question is this
project answering? What happened that made it start? -->

## <First theme or decision, as a statement — e.g. "Designing the experiment: forks, then worktrees">

<!-- What was tried first, why it fell apart, what was landed on instead. Reversals are the
most valuable content — keep them. -->

## <Next section — one idea per section>

<!-- Be concrete: link the actual PRs/issues, quote the measured numbers, use tables for
data. Recount the discussion with the LLM: what it proposed, what I corrected, how it
resolved. -->

<!-- When the session generated code, show proof it works — the pattern is always the same
three beats:

The fix only counts once a test pins it down. Here is the one Claude generated:

```<lang>
<the generated test, trimmed to the relevant assertion>
```

This proves it because <what would have failed before the change, and why passing now
means the behavior is correct>. Running it:

```text
<real test-runner output, trimmed — actually executed, not described>
```
-->

<!--
At the end of each work session: append new ## sections covering (1) what was done,
(2) the discussions with the LLM, (3) proof that generated code works. Update the
"Last updated" date, and never rewrite past sections — if a past conclusion turned out
wrong, say so in a new section.

When this file no longer reads in one sitting and a milestone boundary exists, split into
doc/making-of/NN-slug.md posts and turn this file into an index:

## Posts

1. [Kicking off <project>: <subtitle>](doc/making-of/01-kicking-off.md) —
   one sentence on what the post covers.
2. [<Milestone>: <subtitle>](doc/making-of/02-milestone.md) —
   one sentence on what the post covers.
-->
