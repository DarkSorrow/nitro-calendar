# Implementation phases (tracker)

**Purpose:** High-level order of work and a lightweight place to record **where we are** and **what was done**.  
**Detail:** Behavior and technical rules are in [implem.md](./implem.md) and [CLAUDE.md](../CLAUDE.md). Per-phase playbooks live in this folder (links below)—keep *this* file short.

**How to use:** Update the status line and checkboxes as you complete work. Avoid pasting long design notes here.

---

## Per-phase playbooks

| Phase | Doc |
|-------|-----|
| 1 — Contracts & specs | [phase-1-contracts-specs.md](./phase-1-contracts-specs.md) |
| 2 — Native calendar (Gregorian) | [phase-2-native-calendar.md](./phase-2-native-calendar.md) |
| 3 — Wheel + datetime | [phase-3-wheel-datetime.md](./phase-3-wheel-datetime.md) |
| 4 — Data bridge & markers | [phase-4-data-bridge-markers.md](./phase-4-data-bridge-markers.md) |
| 5 — Multi-calendar & hardening | [phase-5-multi-calendar-hardening.md](./phase-5-multi-calendar-hardening.md) |

---

## Phase 0 — Baseline (scaffold)

**Status:** Done

- [x] Nitro module scaffold (`NitroCalendarView` placeholder, Swift/Kotlin stubs, `nitro.json` autolinking)
- [x] TS export + example app consuming the package; first-run `npm install` then `npm run nitrogen` (see [CONTRIBUTING.md](../CONTRIBUTING.md))
- [x] Toolchain verified: `npm run nitrogen`, `npm run typecheck`, `npm test` pass from repo root

Product calendar UI, full Nitro contracts, and wheel spec start in **Phases 1–3**.

---

## Phase 1 — Contracts & specs

**Status:** Done

- [x] Nitro specs for calendar view (props / methods / events aligned with `implem.md`)
- [x] Nitro specs for wheel picker view
- [x] Regenerate Nitrogen bindings; TS exports wired in `example/`

---

## Phase 2 — Native calendar (Gregorian first)

**Status:** Done (implementation complete; baseline notes captured for device profiling pass)

- [x] iOS + Android: grid, scrolling/paging as designed in `implem.md`
- [x] Header UX: day / month / year modes, today ↔ back, week↔month collapse
- [x] RTL: navigation semantics coordinated with `I18nManager` (no double flip)

---

## Phase 3 — Native wheel + datetime composition

**Status:** Not started

- [ ] Cross-platform wheel engine (snap, inertia parity)
- [ ] Time modes (24h / 12h) and composed layout: calendar above, wheels below

---

## Phase 4 — Data bridge & markers

**Status:** Not started

- [ ] Timestamp-first selection model end-to-end
- [ ] Dots / markers: bounded payloads, throttled range, dedupe

---

## Phase 5 — Multi-calendar & hardening

**Status:** Not started

- [ ] Native `CalendarType` mapping (Foundation + ICU) per calendar engine spec in `implem.md`
- [ ] Consistency tests (same instant → same civil fields per type, where applicable)
- [ ] Performance pass: profiling checklist, memory / UI CPU

---

## Optional later

- Release / versioning notes in `CONTRIBUTING.md` or changelog when publishing
