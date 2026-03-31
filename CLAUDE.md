# CLAUDE.md

## Project Context

This repository is a React Native Nitro Module for calendar and time picker UI.

- Package: `@novastera-oss/nitro-calendar`
- Runtime model: native-driven UI with JS orchestration
- Platforms: iOS (Swift) and Android (Kotlin)
- JS/TS target: React 19.2+ ecosystem and modern React Native

## Core Product Direction

- Gregorian calendar support is first-class.
- Additional calendar systems use a single JS-facing `CalendarType`; **all civil-field math and multi-calendar behavior are implemented natively** (iOS: Foundation `Calendar`; Android: `android.icu.util.Calendar`). JavaScript passes **epoch timestamps** and configuration, not hand-built YMD as the source of truth. Full rules: [doc-implem/implem.md](./doc-implem/implem.md) section **Calendar engine specification**.
- Time picker is a native wheel-based control displayed below the calendar in composed flows.

## Source of Truth for Behavior

Use these files as migration references for expected behavior when you maintain a **local** copy (the `legacy-js-exemple/` directory is gitignored and not shipped in this repo):

- `legacy-js-exemple/molecules/calendar-view-gregorian.tsx`
- `legacy-js-exemple/molecules/calendar-view.tsx`
- `legacy-js-exemple/organisms/calendar-time-picker.tsx`
- `legacy-js-exemple/services/calendar.ts`
- `doc-implem/implem.md`

Legacy files define current UX semantics. New native implementation may improve internals and performance, but should preserve user-facing behavior unless explicitly changed.

## Architecture Rules

- Native must own per-frame work: scrolling, inertia, snapping, layout transitions.
- JS must not be in the per-frame interaction loop.
- Keep cross-layer payloads small and bounded to visible data plus minimal buffer.
- Avoid redundant updates by deduping unchanged payloads before sending to native.

## Header and Navigation Requirements

- Header shape in day mode: `< month year [today] >`
- Month and year labels are interactive.
- Month picker: 12 months displayed as 3 columns x 4 rows.
- Year picker: 12-year slices with current year centered at index 6 in initial page.
- In picker modes, `today` becomes `back`.
- Support month/week collapse toggle with up/down chevrons.

## RTL and Icon Contract

- RTL is handled in the app via **`I18nManager`**. Avoid duplicating flips: do not mirror in native **and** manually swap chevron assets/handlers for the same effect.
- **`isRTL`** (or native layout direction) informs **prev/next navigation and swipe polarity**, not decorative icon mirroring. Left/right chevrons stay visually consistent; **behavior** (what moves backward vs forward in time) follows RTL rules.
- Vertical semantics remain unchanged.

Baseline icon mapping:

- `chevron-right`: iOS `chevron.right`, Android `chevron_right`
- `chevron-left`: iOS `chevron.left`, Android `chevron_left`
- `chevron-up`: iOS `chevron.up`, Android `expand_less`
- `chevron-down`: iOS `chevron.down`, Android `expand_more`

## Performance Targets

- Smooth scrolling and wheel interaction under high-velocity gestures.
- Reduced memory pressure versus JS-heavy legacy rendering paths.
- Reduced UI thread CPU spikes through native batching and bounded redraw paths.
- Prefer coarse, meaningful events (`onValueChange`, range changed, settled) over noisy callbacks.

## Coding Guidelines

- Keep TypeScript strict and avoid `any`.
- Prefer functional components and clear interface-based props in TS.
- Keep native code concise, testable, and modular.
- Add comments only when logic is non-obvious.
- Optimize for maintainability and profiling visibility.

## Practical Workflow

When implementing features:

0. Check [doc-implem/phases.md](./doc-implem/phases.md) for current phase and mark progress there (keep that file high-level).
1. Update or confirm the contract in `doc-implem/implem.md`.
2. Add or update Nitro specs.
3. Implement native behavior in Swift and Kotlin.
4. Wire JS bindings and example usage.
5. Validate parity with legacy behavior and profile performance.

