# krogue — Agent Configuration

krogue is a reusable, extensible roguelike **game engine** built on **kotile** (a
general-purpose libGDX tile renderer; sibling repo at `~/development/kotile`).

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
- Commits use a `Co-Authored-By: Claude …` trailer (`attribution.commit` is set in
  `.claude/settings.json`).

## Build & test

```bash
./gradlew test           # full suite (Kotest)
./gradlew compileKotlin   # quick compile check
./gradlew runKotile       # run the demo
```

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
