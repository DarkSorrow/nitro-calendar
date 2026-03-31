# @novastera-oss/nitro-calendar

Native mobile calendar components built with Nitro Modules.  
Gregorian calendar support is the first target, with an extensible path for additional calendar systems later.

## Installation

```sh
npm install @novastera-oss/nitro-calendar react-native-nitro-modules
```

`react-native-nitro-modules` is required because this library relies on [Nitro Modules](https://nitro.margelo.com/).

## Current Status

This package currently exposes a Nitro host view scaffold (`NitroCalendarView`) with a temporary `color` prop only.  
Full calendar UI, markers, and wheels are planned next.

## Theming and i18n (planned contract)

The library is designed to **embed in any app** without reimplementing your design system or translation stack in native code. You will pass:

- **`appearance`** — structured colors, typography, spacing, and radii (mapped from Tamagui, StyleSheet, or tokens in JS).
- **`strings`** — resolved labels (month names, weekdays, “today”, “back”, etc.) from your i18n layer, so copy matches the rest of the app.
- **`calendarType`** and **`weekStartsOn`** — drive native calendar math and layout order while you memoize `appearance` + `strings` when locale or theme changes.
- **`minTimestampMs` / `maxTimestampMs`** (optional) — inclusive bounds for selectable dates; in composed date-time flows, native also uses them with the **selected day** to clamp **time** on the wheels without spamming JS during scroll (**settled** events only).

Details and TypeScript shapes: [doc-implem/phase-1-contracts-specs.md](./doc-implem/phase-1-contracts-specs.md) (§1.4). Architecture notes: [doc-implem/implem.md](./doc-implem/implem.md) (styling and localization contract).

## Usage (Current API)

```tsx
import { NitroCalendarView } from '@novastera-oss/nitro-calendar';

// ...
<NitroCalendarView color="tomato" style={{ width: 120, height: 120 }} />
```

## Planned Composition

- Calendar surface in a native view.
- Time picker surface displayed below the calendar in a plain vertical layout.
- Shared **appearance** and **string** contracts for calendar and wheel so theming and translations stay consistent with the host app.

## Performance Model

Interaction stays native-driven (scrolling and wheel physics), while JavaScript reacts with coarse updates only.  
The intended contract is: keep JS out of the per-frame loop, throttle range updates, and send minimal payloads across the bridge.

## Implementation Notes

Detailed architecture and implementation direction live in [doc-implem/implem.md](./doc-implem/implem.md).  
Phase order and a short progress checklist: [doc-implem/phases.md](./doc-implem/phases.md).

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
