# ADR-0006: Tooling — beads + docs/ADRs + native subagents

- **Status:** Accepted
- **Date:** 2026-06-03

## Context

The project was scaffolded with RuFlo/claude-flow: a heavyweight multi-agent framework
(swarm topologies, "hive-mind" consensus, neural training, an agent DB) plus a
vector-searchable memory store, wired in via MCP, hooks, and a large `CLAUDE.md`. In
practice, for a solo developer doing a mostly-sequential migration, almost none of that
machinery earned its overhead. The load-bearing pieces were (a) durable cross-session
memory and (b) subagents — and subagents are native to Claude Code. The framework also
imposed real costs: many concepts to learn, per-action hook latency, and footguns (e.g.
a keyword "routing" banner that was wrong every time).

## Decision

Move to a lean, legible, native stack:

- **beads** (`bd`) — the task backlog (dependency-aware issues; `bd ready` drives work).
- **checked-in markdown** — `docs/` for living knowledge and `docs/adr/` for decision
  history (this record).
- **native Claude Code subagents** for delegation, including the voltagent *specialist*
  personas, with the lead session orchestrating (no orchestration framework).

RuFlo was removed from the project, the plugins, and the machine. Existing project
memory was migrated out of RuFlo's store into `docs/` and beads first.

## Consequences

- Every piece is understandable and inspectable: markdown you can read, an issue graph
  you can query, plain subagents you can reason about. Lower per-turn overhead.
- Lost RuFlo's swarm/neural features — which were unused at this scale anyway.
- Cross-session continuity now relies on `docs/` + beads rather than a vector store;
  for hundreds of entries that is more than adequate and far more legible.

## Alternatives considered

- **Keep RuFlo** — rejected: ~90% ceremony for this project's scale, opaque, and with
  active footguns; the genuinely useful 10% (memory + subagents) is available natively.
- **Native file-memory instead of beads** — beads chosen for its dependency-aware
  backlog and ready-list, which a flat memory file doesn't provide.
