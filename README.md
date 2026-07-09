# App Monitor

> A JetBrains IDE plugin that turns your IDE into a **live runtime dashboard** for the apps you care
> about — **independent of how they were started** (IDE run configuration, terminal, docker, system
> service). Add an app by **name + port** and watch its memory, CPU, listening ports, uptime and
> up/down status refresh live, like `docker stats`.

**Status:** Fase 0 (MVP) shipped — **v0.1.0**. See the [CHANGELOG](CHANGELOG.md) for what changed in
each version and the [ROADMAP](docs/ROADMAP.md) for what comes next.

Compatible with IntelliJ IDEA, WebStorm, PyCharm, PhpStorm and other IntelliJ-based IDEs **2023.3+**.

---

## What it is

App Monitor tracks **targets**, not the process the IDE launched. A target is a **TCP port**, a
**process-name pattern** or a **PID**, and it is re-resolved to a live process on every refresh — so
the plugin can watch anything running on your machine, whatever launched it. When a target can't be
found it shows **down**, and flips back to **up** the moment it reappears.

## Features (v0.1.0)

- **Live table**, refreshed every 2 seconds: Name · Port(s) · PID · Uptime · Status · Memory ·
  Mem % · CPU %. Row selection is kept across refreshes.
- **Add apps by target**: a TCP port (the common case), a process-name regex, or a fixed PID.
  Optional fields (health URL, memory alert, tag) are ready for upcoming features.
- **Kill Process on Port** — kill whatever process (and its entire tree) is listening on a port, plus
  **Force Kill** of a selected running app's process tree. Goodbye `EADDRINUSE`.
- **Per-project persistence** — the list of monitored apps is saved per project and survives IDE
  restarts.
- **Cross-platform** — Linux, macOS and Windows.
- docker-style **instantaneous CPU %** (delta of cumulative CPU time) and whole **process-tree**
  memory aggregation.

## Installation

### From the JetBrains Marketplace

Once published: **Settings/Preferences → Plugins → Marketplace**, search for **App Monitor**, and
click **Install**.

### From a built ZIP (install from disk)

1. Build the ZIP (see [Building from source](#building-from-source)) or download a release artifact.
2. **Settings/Preferences → Plugins → ⚙ → Install Plugin from Disk…**
3. Select `app-monitor-<version>.zip` and restart the IDE when prompted.

## Usage

1. Open the **App Monitor** tool window (bottom tool-window stripe).
2. Click **＋ Add App**, type a name and a **port** (or switch the target to *Process name* / *PID*).
3. Watch it live. **Double-click** a row to edit it; use the toolbar to **Remove**, **Refresh**,
   **Kill Process on Port** or **Force Kill** the selected app's process tree.

The monitored list is stored per project, so each project keeps its own set of apps.

## Building from source

You need a JDK/JBR available. The build compiles with a Java 21 toolchain but emits **Java 17**
bytecode so the plugin loads on the 2023.3 runtime. If `java` is not on your `PATH`, point
`JAVA_HOME` at a JetBrains Runtime (for example the one bundled with a local IDE):

```bash
export JAVA_HOME=/path/to/jbr
./gradlew buildPlugin      # -> build/distributions/app-monitor-<version>.zip
./gradlew test             # run the unit tests
```

Built on the **IntelliJ Platform**; primary language **Kotlin**.

## Roadmap & changelog

- [docs/ROADMAP.md](docs/ROADMAP.md) — phased plan and the full feature list.
- [docs/CHECKLIST.md](docs/CHECKLIST.md) — living per-phase feature checklist.
- [docs/ARQUITETURA.md](docs/ARQUITETURA.md) — architecture notes (target model, PID resolution).
- [CHANGELOG.md](CHANGELOG.md) — what changed in each version.

## License

Licensed under the **Apache License 2.0** — see [license.txt](license.txt).
