# Changelog

All notable changes to **App Monitor** are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

- Fase 1 (planned): memory sparkline, full-session chart + leak analysis + export, HTTP health
  check, alerts/notifications, persisted column chooser. See [docs/ROADMAP.md](docs/ROADMAP.md).

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
  next to `lsof`/`ps` on Linux/macOS (the cross-platform debt the Multiple Run Monitor left behind).
- Instantaneous, docker-style **CPU %** (delta of cumulative CPU time between two samples) and whole
  **process-tree** memory aggregation.

### Notes
- Spun off from the **Multiple Run Monitor** (part of the
  [Multiple Run](https://github.com/WelingtonMonteiro/multiple_run) plugin). The process sampler is
  reused from it (kept in Java for now); everything else is new and written in Kotlin.
- Built for IntelliJ Platform 2023.3+ (build 233), no upper bound. Java 17 bytecode.
- 36 unit tests (ps/lsof/netstat parsing, target/regex matching, persistence round-trip, list ops).

[Unreleased]: https://github.com/WelingtonMonteiro/appmonitor/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/WelingtonMonteiro/appmonitor/releases/tag/v0.1.0
