# ADR-0013: korogue rename, monorepo with kotile, and the module layout

- **Status:** Accepted
- **Date:** 2026-06-10

## Context

Two structural decisions came together:

1. **Name clash.** "krogue" was already used by a KDE build of Rogue. To avoid confusion (and
   match the Kotlin `Ko-` prefix convention) the project is renamed **korogue**
   (`github.com/ksletmoe/korogue`).
2. **Repo topology.** kotile (the renderer) lived in a sibling repo consumed via a Gradle
   composite build (`includeBuild("../kotile")`). That works, but korogue is kotile's only
   consumer and the two are co-developed in lockstep — we repeatedly froze kotile to dodge
   cross-repo churn. Meanwhile korogue's own build had the **root project doing triple duty**:
   engine library + demo `application` + multi-project aggregator, so the published engine jar
   even shipped the demo code.

## Decision

- **Rename** the Kotlin package (`com.sletmoe.krogue.*` → `com.sletmoe.korogue.*`), Gradle name,
  maven group, docs, and CI to **korogue**. bd issue ids stay `krogue-*` (opaque tracker ids).
- **Monorepo.** Fold kotile into the korogue repo via `git subtree` (history preserved) as
  Gradle subprojects, replacing the composite build with regular `include(...)`. kotile keeps its
  **own maven coordinates** (`com.sletmoe:kotile`, published from `:kotile:library`) — same repo,
  separate publishes. Benefit: atomic cross-cutting commits across renderer + engine, one CI, one
  clone; no composite-build substitution.
- **Idiomatic module layout:**
  - **root** — a pure aggregator (no production sources); hosts the `nexus-publish` plugin, which
    gathers every subproject's publication into one Sonatype staging repo.
  - **`:engine`** — the korogue library (`com.sletmoe.korogue:korogue`, signed). Depends on
    `api(project(":kotile:library"))` because kotile types appear in its public API.
  - **`:demo`** — the runnable demo (`MyGame`/`Main`), consuming `:engine`. **Not published**, so
    the engine artifact no longer contains demo code. Run via `./gradlew :demo:run`.
  - **`:kotile:library`** / **`:kotile:demo`** — kotile, unchanged.

## Consequences

- The renderer↔engine boundary can now be changed in one atomic commit (the friction that had us
  freezing kotile all along is gone).
- The published engine jar is clean (no demo). `:engine` exposing kotile as `api` is correct: a
  downstream game extending `Game`/drawing through `TileSurface` gets the kotile types it needs.
- `publishToSonatype` aggregates `:engine` (signed) **and** `:kotile:library` into one staging
  repo. kotile is **not yet Maven-Central-ready** (unsigned, incomplete POM, `1.0-SNAPSHOT`
  version) — it must be made ready or excluded from the aggregate before a real release
  (tracked on krogue-5-sonatype).
- The Rogue example is a **separate** downstream repo (`korogue-rogue`), per ADR-0012 (revised) —
  a truer public-API test than an in-repo module.
- Two one-time chores remain for the user: the local working directory is still
  `~/development/krogue` (rename at leisure), and the old `~/development/kotile` repo is now a
  redundant copy.

## Alternatives considered

- **Keep kotile a sibling composite build.** Rejected: the composite build fixes *build* friction
  but not *commit* friction; co-developed, single-consumer libraries are simpler in one repo.
- **Keep the engine at the root project** (lower churn). Rejected: it left the root as a
  library+app+aggregator hybrid and shipped demo code in the published jar; a pure-aggregator root
  with `:engine`/`:demo` is the conventional, cleaner layout (and what `nexus-publish` expects).
- **Merge kotile's history by copying files** (no subtree). Rejected: `git subtree` preserves
  kotile's commit history under `kotile/`.
