# korogue — Agent Configuration

korogue is a reusable, extensible roguelike **game engine** built on **kotile** (a
general-purpose libGDX tile renderer; in-repo Gradle subproject `:kotile:library`,
sources under `kotile/`).

> Canonical agent-instructions file. `CLAUDE.md` is a symlink to this one.

## Project memory

- **Design (current):** `docs/ARCHITECTURE.md`
- **Current state & gotchas:** `docs/STATUS.md`
- **Decision history (why):** `docs/adr/` — append-only Architecture Decision Records.
  When a significant or hard-to-reverse decision is made, record one (copy
  `docs/adr/template.md`); link it from the resolving beads `decision` issue.

## Task tracking

Use **beads** (`bd`) as the source of truth for what to work on and what is done.
Plan from `bd ready`; claim with `bd update <id> --status in_progress`; close with
`bd close <id>` (which unblocks dependents); and `bd create` / `bd link` new work as
you discover it — don't let it live only in your head or a commit message. Full
workflow and sync details are in the "Beads Issue Tracker" section below and via
`bd prime`. Durable design knowledge goes in `docs/`, not beads.

## Rules

- Read a file before editing it.
- Never commit secrets, credentials, or `.env` files.
- Don't save working files or tests to the repo root — use `src/`, `docs/`, etc.
- Keep files focused (~500 lines).
- Commits use a `Co-Authored-By: Claude …` trailer (Claude Code's default
  attribution, which includes the current model version).
- **Anything an agent posts to GitHub must say so.** `gh` authenticates as the
  repo owner, so an agent-written PR body, PR/issue comment, or review reads as
  if a human typed it. Anyone reading the thread — a human, or another review bot
  weighing how much to trust a claim — deserves to know a model wrote it. So end
  every `gh pr comment` / `gh issue comment` / `gh pr review` / `gh pr create`
  body with an attribution line:

  ```text
  🤖 Written by <actual model name> via Claude Code, posted by @<owner>'s gh CLI.
  ```

  `<actual model name>` and `<owner>` are placeholders — replace both with the
  real values (e.g. `Claude Opus 4.8`) before posting; never submit the literal
  placeholder text. Name the actual model you are, not a generic "an AI". If you
  rewrite a comment (`--edit-last`) the line must survive the rewrite. This is
  not the same as the commit trailer: that covers what landed in git, this
  covers what was said about it — and review threads are where the unverified
  claims live.
- **Do not state a verification you did not run.** Say what you actually ran and
  what it printed. If something is unverified, say so — the GL tests only execute
  on Linux CI, so "compiles clean" and "verified" are very different claims here.
- **Verify the artifact you are shipping, in the shape you are shipping it.**
  Because the GL suite is skipped on macOS, the usual move is a throwaway harness
  (`JavaExec` + `-XstartOnFirstThread`, see `kotile/demo`) that renders real pixels
  locally. That harness is *not* the thing being shipped, and it drifts from the
  committed test silently. It has produced a wrong claim to a reviewer three times
  in one session, each time because the harness reproduced the **logic** but not
  the **environment**:
  - the harness rendered against framebuffer 0 while the committed test ran inside
    `HeadlessGl`'s capture FBO, so the bug's `handle != 0` condition never held and
    the shipped test could not fail at all;
  - the harness snapshotted the viewport around a single render while the test
    compared across a `resize`, so CI failed on an assertion the harness liked;
  - the harness used a 40x40 window, making the `resize(40, 40)` under test a
    no-op, and nearly produced a confidently wrong rebuttal to a reviewer.

  So mirror the committed test's **geometry and GL state**, not just its steps:
  same window size, same bound framebuffer, same call order, same points where
  values are captured. **When harness and test disagree, treat the result as
  unresolved and investigate both** — the harness may be wrong, but the
  committed test may also be stale or defective. Don't report a verification
  claim, red or green, until you've reconciled the two and exercised the
  committed test's actual shape. A regression test must fail against the
  un-fixed code and pass after the fix, proven in the *test's* shape, not the
  harness's: a test that cannot fail against the un-fixed code is worse than
  no test, because it reads as coverage. And never report a harness result as
  though it were the test's.
- **The Rogue example is a faithful recreation — don't deviate on gameplay.**
  Reproduce original Rogue's behavior, rules, and data exactly (the canonical
  BSD 5.4.4 C source is at `~/Downloads/rogue5.4.4`; transcribe tables and port
  logic from there, not from memory). The renderer/engine plumbing may be
  idiomatic Kotlin, but *what the game does* must match. Do not silently
  simplify, "improve", or shortcut a mechanic for implementation convenience —
  if a faithful port has to wait on something else, port what you can and file
  a beads issue for the rest; never bake a divergence in quietly. RNG need not
  be bit-identical (different PRNG), only algorithmically faithful.

## Build & test

```bash
./gradlew test            # full suite (Kotest), all modules
./gradlew :engine:compileKotlin   # quick compile check of the engine
./gradlew :demo:run       # run the demo (macOS -XstartOnFirstThread is wired in)
```

Module layout (multi-project): root is a pure aggregator; `:engine` is the korogue
library, `:demo` is the runnable demo (consumes `:engine`), and kotile is in-repo as
`:kotile:library` (publishable `com.sletmoe:kotile`) + `:kotile:demo`.

Always run tests after code changes and verify the build before committing.

## Working style

Lean, native Claude Code, and **the lead session orchestrates** — never delegate
orchestration. For focused, self-contained slices, delegate to the voltagent
**specialist** subagents (e.g. `voltagent-lang:*`, `voltagent-domains:*`,
`voltagent-qa-sec:*` for language / domain / QA work) or your own
`.claude/agents/*.md`. Do **not** use the voltagent **meta / orchestration** agents
(`voltagent-meta:*`) — you are the orchestrator. Do small interlocking changes
inline; have a fresh-context agent review keystone work. Match tool weight to task.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:970c3bf2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->

<!-- BEGIN BEADS CODEX SETUP: generated by bd setup codex -->
## Beads Issue Tracker

Use Beads (`bd`) for durable task tracking in repositories that include it. Use the `beads` skill at `.agents/skills/beads/SKILL.md` (project install) or `~/.agents/skills/beads/SKILL.md` (global install) for Beads workflow guidance, then use the `bd` CLI for issue operations.

### Quick Reference

```bash
bd ready                # Find available work
bd show <id>            # View issue details
bd update <id> --claim  # Claim work
bd close <id>           # Complete work
bd prime                # Refresh Beads context
```

### Rules

- Use `bd` for all task tracking; do not create markdown TODO lists.
- Run `bd prime` when Beads context is missing or stale. Codex 0.129.0+ can load Beads context automatically through native hooks; use `/hooks` to inspect or toggle them.
- Keep persistent project memory in Beads via `bd remember`; do not create ad hoc memory files.

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.
<!-- END BEADS CODEX SETUP -->
