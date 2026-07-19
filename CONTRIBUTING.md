# Contributing to korogue

Thanks for your interest in korogue! This guide gets you from a fresh clone to a merged pull
request. korogue is a roguelike **game engine** (`:engine`) built on **kotile**, an in-repo libGDX
tile renderer (`:kotile:library`); the runnable demo is `:demo`. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design and [README.md](README.md) for an
overview.

## Development environment

- **JDK 21.** The build pins a JDK 21 toolchain (Gradle provisions it via the foojay resolver, so
  you don't strictly need it on `PATH`, but having it there is convenient). Kotlin 2.3.21, Gradle
  9.5.1 (use the checked-in `./gradlew` wrapper — don't install Gradle separately), libGDX 1.14.1.
- **macOS:** libGDX's LWJGL3 backend needs `-XstartOnFirstThread` for GLFW. It's already wired into
  `:demo:run` and the kotile demo/harness tasks, so `./gradlew :demo:run` just works. If you launch
  a game or harness by hand, pass the JVM arg yourself.
- No other system dependencies for building or running locally on macOS/Windows. On Linux, the
  GL-gated rendering tests need a display — see [Testing](#testing).

Clone and verify:

```bash
git clone https://github.com/ksletmoe/korogue.git
cd korogue
./gradlew build      # compile + assemble + test + ktlint; also installs the pre-commit hook
./gradlew :demo:run  # sanity-check the demo opens a window
```

## Building and testing

```bash
./gradlew test                    # full Kotest suite, all modules
./gradlew :engine:compileKotlin   # fast compile check of the engine only
./gradlew :demo:run               # run the demo
./gradlew build                   # the full gate: compile, assemble, test, ktlint (what CI runs)
```

### Testing

Tests are [Kotest](https://kotest.io/) (example-based `FunSpec` + matchers). **Add tests with your
change** — a bug fix gets a regression test that fails before the fix and passes after; a feature
gets coverage of its behaviour. Put tests under the module's `src/test/kotlin`, never in the repo
root.

**One environment caveat worth knowing up front:** kotile's rendering tests actually boot OpenGL,
so they are **GL-gated** — they run on Linux under a virtual display and **self-skip on macOS**
(where `$DISPLAY` is unset). This means a green `./gradlew test` on a Mac has *not* exercised the GL
paths. CI is the source of truth for those:

```bash
# Linux, to run the GL-gated tests locally (matches CI):
xvfb-run -a ./gradlew build --no-daemon
```

When you touch rendering code, say plainly what you actually ran. "Compiles clean on macOS" and
"the rendering tests pass" are different claims — only Linux/CI can make the second. If you verify a
rendering change with a throwaway harness (see `kotile/demo`'s `JavaExec` tasks), mirror the
committed test's **geometry and GL state** — window size, bound framebuffer, call order, and the
points where values are captured — not just its steps: a harness that reproduces the logic but not
the GL environment can report a passing result the real test would fail on. A regression test must
still fail against the un-fixed code and pass after the fix, proven in the *test's* shape.

### Code style and formatting

Formatting is enforced by [ktlint](https://github.com/pinterest/ktlint) and runs as part of
`./gradlew check`/`build`. A checked-in **pre-commit hook** (`.githooks/pre-commit`) auto-formats
your staged Kotlin with `ktlintFormat` so commits stay green; it's wired up automatically the first
time you run `./gradlew check`/`build` (which points `git config core.hooksPath` at `.githooks/`).
To format on demand:

```bash
./gradlew ktlintFormat   # fix formatting
./gradlew ktlintCheck    # check without fixing
```

Beyond formatting, match the surrounding code. A few house conventions:

- **Comments explain *why*, not *what*.** Don't narrate what the next line does or restate the
  code; comment a non-obvious constraint or decision. Prefer KDoc on public API.
- **Components are immutable data classes; all entity writes go through `World`** (ADR-0003,
  ADR-0005). "Mutating" means producing a new value and replacing it via `World.update`.
- **Colours:** author model colours as `NormalizedRgb` in components/light values; GDX `Color` is
  the presentation colour of the UI toolkit and `world.Tile` (ADR-0035). Geometry uses kotile's
  `Vector2Int` and the engine's `IntRect` — the codebase is `java.awt`-free.
- Keep files focused (~500 lines) and don't save working files to the repo root.

## Task tracking with beads (`bd`)

korogue tracks work in **[beads](https://github.com/gastownhall/beads)** (`bd`), not GitHub issues
or markdown TODO lists. Issues live in a local Dolt DB and sync over the git remote.

```bash
bd prime            # print the full workflow + command reference (run this first)
bd ready            # find work that's unblocked and ready to pick up
bd show <id>        # view an issue
bd update <id> --claim   # claim it before you start
bd close <id>       # close it when the work merges
```

If you're proposing something new, `bd create` an issue for it so the work is tracked, and link
related issues with `bd dep add`. Persist project memory (insights meant to outlive a session) in
beads via `bd remember "…"`, not in ad-hoc markdown files. Durable *design* knowledge is different —
it belongs in `docs/` (ARCHITECTURE / STATUS / ADRs), not in beads.

## Pull requests

1. **Branch** off `mainline`.
2. **Keep the change focused** and reference the beads issue id (e.g. `krogue-2zt`) in the branch
   name / PR description where it helps.
3. **Include tests** for behaviour you add or fix (see [Testing](#testing)).
4. **Run `./gradlew build` locally** before opening the PR (and note if the GL-gated tests could
   only run on CI for you).
5. **Sign your commits with the attribution trailer.** Every commit must end with a
   `Co-Authored-By: Claude …` trailer (Claude Code's default attribution, which names the model
   version) — the project uses it to mark AI-assisted work.
6. **Record an ADR for significant or hard-to-reverse decisions.** korogue keeps an append-only
   Architecture Decision Record log in [docs/adr/](docs/adr/) — copy
   [`docs/adr/template.md`](docs/adr/template.md), fill it in, and link it from the resolving beads
   issue. If your change alters engine behaviour or the current design, update
   [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) / [docs/STATUS.md](docs/STATUS.md) too.
7. CI (`./gradlew build` under Xvfb on JDK 21) must be green.

### The Rogue example is a faithful port

The reference consumer of this engine is [korogue-rogue](https://github.com/ksletmoe/korogue-rogue),
a faithful reimplementation of **Rogue 5.4.4** (ADR-0012). If you contribute there, or to anything
that changes what that game does: reproduce original Rogue's behaviour, rules, and data **exactly**,
transcribed from the canonical BSD 5.4.4 C source — not from memory. The engine plumbing may be
idiomatic Kotlin, but the *gameplay* must match; don't silently simplify or "improve" a mechanic for
convenience. If a faithful port has to wait on an engine feature, port what you can and file a beads
issue for the rest. (RNG need not be bit-identical — a different PRNG is fine — only algorithmically
faithful.)

## License

By contributing, you agree that your contributions are licensed under the project's
[BSD 3-Clause License](LICENSE).
