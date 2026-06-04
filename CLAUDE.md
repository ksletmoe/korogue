# krogue — Claude Code Configuration

krogue is a reusable, extensible roguelike **game engine** built on **kotile**
(a general-purpose libGDX tile renderer; sibling repo at `~/development/kotile`).

## Project memory

- **Design & decisions:** `docs/ARCHITECTURE.md`
- **Current state & gotchas:** `docs/STATUS.md`
- **Task backlog:** beads — run `bd ready` for the next actionable work, `bd list`
  / `bd show <id>` for detail. The backlog (issues + dependencies) is committed
  under `.beads/`.

Before non-trivial work, skim the docs and `bd ready`. When a durable decision or
task emerges, record it in the relevant doc or with `bd create`.

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

Lean, native Claude Code: delegate self-contained slices to subagents
(general-purpose, or your own `.claude/agents/*.md`), do small interlocking changes
inline, and have a fresh-context agent review keystone work. Match tool weight to
task.
