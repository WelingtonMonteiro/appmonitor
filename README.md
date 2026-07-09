# App Monitor

> A JetBrains IDE plugin that turns your IDE into a **live runtime dashboard** for the apps you care
> about — **independent of how they were started** (IDE run configuration, terminal, docker, system
> service). Add an app by **name + port** and watch its memory, CPU, listening ports, uptime and
> up/down status refresh live, like `docker stats`.

**Status:** actively developed — **v0.6.0** (start / stop / restart apps by command). See the
[CHANGELOG](CHANGELOG.md) for what changed in each version and the [ROADMAP](docs/ROADMAP.md) for
what comes next.

Compatible with IntelliJ IDEA, WebStorm, PyCharm, PhpStorm and other IntelliJ-based IDEs **2023.3+**.

---

## What it is

App Monitor tracks **targets**, not the process the IDE launched. A target is a **TCP port**, a
**process-name pattern** or a **PID**, and it is re-resolved to a live process on every refresh — so
the plugin can watch anything running on your machine, whatever launched it. When a target can't be
found it shows **down**, and flips back to **up** the moment it reappears.

## Features

- **Live table**, refreshed every 2 seconds: Name · Port(s) · PID · Uptime · Status · Memory ·
  Mem % · CPU % · **Mem trend**. Row selection is kept across refreshes.
- **Add apps by target**: a TCP port (the common case), a process-name regex, or a fixed PID.
- **Start / Stop / Restart by command** — give an app a start command (with optional stop command,
  working directory and `.env` file) and control it from the toolbar, even for apps the IDE never
  launched.
- **Auto-discovery** — scan the machine's listening ports and add the unmonitored ones (with their
  owning command) in one click.
- **Status-bar widget** — "▲up ▼down" with total memory in the tooltip; click to open the panel.
- **Tags & colours** — tag apps (backend, frontend, infra…) with a colour, and **filter** the table
  by name, tag or port.
- **Memory trend sparkline** per app, plus a **full-session memory chart** with labelled axes, the
  peak marked, a **memory-leak analysis** (growth rate, per-hour projection, R², monotonic fraction)
  and a verdict — **export** the history to CSV or the analysis to a text report.
- **HTTP health check** — set a health URL and the Status column shows **healthy** / **unhealthy**
  (2xx/3xx = healthy), on top of up/down.
- **Alerts & notifications** — a balloon fires when an app goes **down**, when its **memory/CPU**
  crosses the alert you set, or when a **steady memory leak** is detected (edge-triggered, re-arms
  on recovery).
- **Per-process breakdown** — the memory chart's **Processes** tab lists every PID of the tree with
  its command, memory and share of the tree.
- **Show/hide columns** from the toolbar, **persisted per project**.
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

- [docs/ARQUITETURA.md](docs/ARQUITETURA.md) — architecture notes (target model, PID resolution).
- [CHANGELOG.md](CHANGELOG.md) — what changed in each version.

## License

Licensed under the **Apache License 2.0** — see [license.txt](license.txt).
