# Changelog

All notable changes to **App Monitor** are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

- Next: docker stats as a target, a settings page, and the Multiple Run bridge (Fase 3), plus the
  leftover Fase 2 bits (env viewer, multiple targets per app). See [docs/ROADMAP.md](docs/ROADMAP.md).

## [0.6.0] - 2026-07-09

**Fase 3 (part 1) — start / stop / restart.**

### Added
- **Start / Stop / Restart from the toolbar**. Give an app a **start command** (with an optional
  **stop command**, **working directory** and **`.env` file**) and control it from the tool window —
  App Monitor becomes a small process manager for apps it did not launch. The command runs through
  the OS shell in the working directory with the `.env` variables applied; **Stop** falls back to
  killing the process tree when no stop command is set, and **Restart** stops, waits, then starts.

## [0.5.0] - 2026-07-09

**Fase 2 (part 2) — tags & filter.**

### Added
- **Tags with colour**: give an app a **tag** and a **colour** in the Add/Edit dialog; a new **Tag**
  column shows it rendered in that colour, so backend/frontend/infra apps are easy to tell apart.
- **Filter field** in the toolbar: narrow the table by **name, tag or port** (case-insensitive).
  Alerts and the status-bar widget still consider every app, not just the filtered ones.

## [0.4.0] - 2026-07-09

**Fase 2 (part 1) — discovery & status bar.**

### Added
- **Auto-discovery**: a toolbar button scans every TCP port in LISTEN on the machine and lists the
  ones not already monitored, with the owning command — tick the ones you want and add them all at
  once, each with an editable name (defaulted from the command).
- **Status-bar widget**: a compact "App Monitor: ▲up ▼down" indicator with the total memory in its
  tooltip; click it to open the tool window. Toggle it from the status-bar widgets menu.

## [0.3.0] - 2026-07-09

**Fase 1 completed — alerts, process breakdown, column chooser.**

### Added
- **Alerts & notifications**: an IDE balloon fires when an app **goes down**, when its **memory** or
  **CPU** crosses the alert threshold you set on it, or when a **steady memory leak** is detected.
  Each condition fires once (edge-triggered) and re-arms when the app recovers, so it never spams.
- **Per-process breakdown**: the memory chart gained a **Processes** tab listing every PID of the
  app's tree with its command, memory and share of the tree — a runaway child is easy to spot.
- **Show/hide columns** from the toolbar, **persisted per project**.

## [0.2.0] - 2026-07-09

**Fase 1 (part 1) — rich memory observability.**

### Added
- **Mem trend sparkline** column: a per-app mini-chart of the last minute of memory, coloured red
  when it is climbing and green when flat/falling. Click it to open the full chart.
- **Full-session memory chart** (click the sparkline or the toolbar **Memory Chart** button): RSS
  over time with labelled axes, gridlines and the peak marked, plus a **leak analysis** — growth
  rate (MiB/min), projection per hour, R² of the trend and how often memory was never freed — and a
  plain verdict (stable / growing / likely leak / shrinking).
- **Export** the memory history to **CSV** or the analysis to a **text report**.
- **HTTP health check**: set a health URL on an app and the **Status** column refines to
  **healthy** / **unhealthy** (2xx/3xx = healthy), on top of up/down.

### Internal
- `MemoryHistory` (leak analysis, IDE-free) is written in **Kotlin** with unit tests. Per-app memory
  sessions are recorded across refreshes (bounded ~2.7h) and reset when the process restarts.

## [0.1.0] - 2026-07-09

First release — **Fase 0 (MVP)**: add an app by target and watch it live, whatever launched it.

### Added
- **App Monitor tool window** (bottom stripe) with a docker-stats-like live table: Name, Port(s),
  PID, Uptime, Status (up/down), Mem, Mem % and CPU %, refreshed every 2s on a background thread
  with the row selection preserved across refreshes.
- **Targets instead of ProcessHandler** — watch an app by **TCP port**, **process-name regex** or
  **PID**. The target is re-resolved to a live PID every refresh, so an app that went down and came
  back is picked up again (and shows **down** while it is gone).
- **Add / Edit / Remove** apps. Adding needs only a name + port; optional fields (health URL, memory
  alert, tag) are already there for later phases.
- **Kill Process on Port** — kill whatever process (and its whole tree) listens on a TCP port, plus
  **Force Kill** of a selected running app's process tree. Goodbye `EADDRINUSE`.
- **Per-project persistence** of the monitored-apps list (`.idea/appMonitor.xml`), surviving IDE
  restarts.
- **Windows support from day one** — port resolution and listening ports via `netstat`/PowerShell
  next to `lsof`/`ps` on Linux/macOS.
- Instantaneous, docker-style **CPU %** (delta of cumulative CPU time between two samples) and whole
  **process-tree** memory aggregation.

### Notes
- Primary language is Kotlin; the process sampler is in Java for now (to be converted later).
- Built for IntelliJ Platform 2023.3+ (build 233), no upper bound. Java 17 bytecode.
- 36 unit tests (ps/lsof/netstat parsing, target/regex matching, persistence round-trip, list ops).

[Unreleased]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/WelingtonMonteiro/appmonitor/releases/tag/v0.1.0
