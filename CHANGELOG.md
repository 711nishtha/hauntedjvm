# Changelog

All notable changes to this project are documented here. Versions follow semantic versioning;
a change that alters what a given seed produces is always called out, because it changes how
resumed sessions continue.

## [0.9.0] - 2026-09-28

First public release.

### Simulation
- Deterministic, event-sourced simulation of FACILITY-07: persons, processes, doors, rooms,
  cameras, objects, the facility controller, and the unaccounted.
- Counter-based seeded randomness; replay from checkpoints plus events; rewind that forks the
  timeline and lets highly aware minds keep memories of the discarded future.
- Behaviour state machines driven by traits, affect, memories and company; memory decay,
  collective forgetting, and rumours that distort in the retelling.
- Anomaly director with seeded perturbations, multi-step chains and rare events; an independent
  detector for invariant violations; escalation levels 0 to 5 with hysteresis.

### Application
- JavaFX workstation: eight camera feeds in 2.5D with infrared mode, the badge tracker floor
  plan, the process table, timeline scrubbing and playback, inspector, anomaly list and notes.
- Separate REAL JVM (MXBeans and JFR), SIMULATION and INCIDENT telemetry.
- Procedural audio; no recorded assets.
- Session save and load with log digest and replay verification; Markdown incident reports.
- `--headless` mode that prints the incident log and a reproducibility digest.
