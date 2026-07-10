# Changelog

All notable changes to **App Monitor** are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

- **Fase 4 is complete** — the planned roadmap (Fase 0–4) is done. The only deferred item is remote
  monitoring over SSH, which stays out of scope for now. See [docs/ROADMAP.md](docs/ROADMAP.md).

## [0.13.0] - 2026-07-10

**Fase 4 (part 3) — action rules.**

### Added
- **Automated action rules** per app, in the Add/Edit dialog:
  - **Restart when it goes down** — when the app has a start command, auto-run it as soon as the app
    is found to be down.
  - **Sustained-memory action** — *if memory ≥ N MB held for M minutes → notify / kill / restart*.
  Both rules are **edge-triggered**: they fire once when the condition becomes true and re-arm only
  when it clears (a crash loop or a persistently high value can't fire every refresh). Every firing
  is recorded in the **Events** tab.

## [0.12.0] - 2026-07-10

**Fase 4 (part 2) — per-app dashboard: events.**

### Added
- **Events tab** in the memory dialog: a per-app timeline of what happened and when —
  **started / stopped / restarted** (root-PID change), **healthy / unhealthy** (health-check
  transitions) and **memory / CPU / leak** alerts, each timestamped and bounded to the last 200.
  Events are recorded on every refresh regardless of the notification setting, so the log is complete
  even with balloons turned off.

## [0.11.0] - 2026-07-10

**Fase 4 (part 1) — persistent history across sessions.**

### Added
- **Persistent memory history**: each app's memory session is now saved to disk (under the IDE
  system directory, per project) and **restored on the next IDE start**, so the memory chart and
  leak analysis are no longer reset when you close the IDE. Persistence is best-effort and never
  interferes with monitoring.
- **Previous-session comparison**: when a monitored process restarts, its finished session is kept
  as the **previous** one. The memory chart draws it **dashed underneath** the current session on a
  shared scale, and the analysis panel adds a peak comparison (this session vs previous).

### Internal
- New `HistoryStore` (IDE-free, unit-tested against a temp dir) and `MemoryHistory.fromCsv` round-trip
  the sessions as CSV; the sampler restores on first sight, archives on session end and flushes on close.

## [0.10.0] - 2026-07-10

**Fase 2 (part 4) — multiple targets per app.**

### Added
- **Multiple targets per app**: an app can aggregate more than one target into a single row. Set
  **Extra ports** (comma-separated) in the Add/Edit dialog to watch an app that listens on several
  ports, or a **process-name target plus a port**. Each target is resolved every refresh and the
  process trees are **unioned**, so memory, CPU, uptime and listening ports are measured over all of
  them at once. Ignored for docker targets.

## [0.9.0] - 2026-07-10

**Fase 2 (part 3) — env viewer.**

### Added
- **Env Viewer**: for an app with a `.env` file configured, a toolbar button opens a read-only table
  of its variables. Secret-looking values (keys containing password / token / secret / key / …) are
  **masked** by default, with a **Show values** toggle to reveal them; the list is never editable.

## [0.8.0] - 2026-07-09

**Fase 3 (part 3) — docker-aware.**

### Added
- **Docker container target**: add an app with the **Docker container** target (name or id) and its
  CPU, memory, memory %, published ports, uptime and up/down status are read from `docker stats` /
  `docker inspect` / `docker port` instead of a host process. The Mem-trend sparkline, full-session
  memory chart and leak analysis all work the same as for a process target.

## [0.7.0] - 2026-07-09

**Fase 3 (part 2) — settings page.**

### Added
- **Settings page** (Settings → Tools → App Monitor): configure the **refresh interval** (1–60s) and
  toggle **alert notifications**. Changes take effect on the next refresh, no restart needed.

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

[Unreleased]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.13.0...HEAD
[0.13.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.12.0...v0.13.0
[0.12.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.10.0...v0.11.0
[0.10.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/WelingtonMonteiro/appmonitor/releases/tag/v0.1.0
