# Bugfix Requirements Document

## Introduction

The `@novastera-oss/nitro-calendar` package has two interrelated issues that degrade the user experience:

1. **Display not working well** — the calendar grid, header, and picker cells have visual rendering problems: cells are not sized correctly relative to the available width, the grid can appear clipped or misaligned, the collapse/expand transition is an instant jump with no visual continuity, and mode switches (day → month picker → year picker → day) are abrupt reloads rather than smooth transitions.

2. **Animations don't exist yet** — month-to-month navigation (swipe/chevron), week↔month collapse, and mode transitions all lack animated transitions. The wheel picker's 3D drum effect is implemented but the calendar grid has zero animation. The architecture mandates that all per-frame work stays native; JS must not drive any animation loop.

These issues span both platforms (iOS Swift / Android Kotlin) and affect Phase 3 completion (device profiling pass, cross-platform parity table) as well as the foundation needed for Phases 4 and 5.

---

## Platform Primitives Mandate

All animation and scroll work MUST use the modern first-party platform APIs listed below. Legacy UIKit collection view hacks, third-party snap libraries, and Accompanist are explicitly forbidden.

### iOS — SwiftUI primitives

| Concern | Required primitive |
|---|---|
| Wheel picker (time) | `Picker` with `.pickerStyle(.wheel)` for standard use; `ScrollView` + `scrollTargetBehavior(.viewAligned)` + `.scrollTransition` for custom cell height / loop / external control (iOS 17+) |
| Swipeable month grid | `TabView` with `.tabViewStyle(.page(indexDisplayMode: .never))` for bounded ranges; `ScrollView(.horizontal)` + `scrollTargetBehavior(.viewAligned)` for infinite/virtual paging (iOS 17+) |
| Height / crossfade transitions | `withAnimation` + `.animation(.easeInOut)` in SwiftUI; `UIView.animate` only when bridging to a UIKit host layer |
| Collapse row animation | `withAnimation(.easeInOut(duration: 0.25))` wrapping the row-count state change |

### Android — Jetpack Compose primitives

| Concern | Required primitive |
|---|---|
| Wheel picker (time) | `LazyColumn` + `rememberSnapFlingBehavior(lazyListState)` from `androidx.compose.foundation` (Compose BOM 2023.06+, stable in 2024/2025 BOMs) — no third-party WheelPickerCompose |
| Swipeable month grid | `HorizontalPager` from `androidx.compose.foundation.pager` (migrated from Accompanist, stable since Compose BOM 2023.06+) — Accompanist pager is deprecated and MUST NOT be used |
| Height / crossfade transitions | `AnimatedVisibility`, `animateContentSize()`, `Crossfade` from `androidx.compose.animation` |
| Collapse row animation | `animateContentSize(animationSpec = tween(250, easing = FastOutSlowInEasing))` on the grid container |

### Interop note (Nitro / React Native bridge)

Because Nitro view components host native views inside a React Native layout, the SwiftUI and Compose trees are embedded via `UIHostingController` (iOS) and `ComposeView` / `AbstractComposeView` (Android). All animation state lives entirely inside the native layer; the JS bridge only sends data updates (timestamps, appearance tokens, mode commands) and never drives per-frame work.

---

## Bug Analysis

### Current Behavior (Defect)

**Display issues**

1.1 WHEN the calendar grid first renders or the device rotates THEN the system computes cell widths using `UIScreen.main.bounds.width` (iOS) or a hardcoded fallback (Android) instead of the actual measured view width, causing cells to overflow or leave gaps.

1.2 WHEN the calendar is placed inside a `ScrollView` or a container narrower than the screen THEN the system renders cells at the wrong size because the layout pass has not completed before `sizeForItemAt` / `onMeasure` is called.

1.3 WHEN the user switches from day-grid mode to month-picker or year-picker mode THEN the system swaps the `RecyclerView` adapter / `UICollectionView` data source with an instant `reloadData()` / `notifyDataSetChanged()`, producing a hard visual cut with no transition.

1.4 WHEN the user taps the collapse/expand chevron to toggle week↔month mode THEN the system immediately replaces the grid content with no height animation, causing the calendar container to jump in size.

1.5 WHEN the calendar is first attached to the window on Android THEN the system posts multiple redundant layout invalidations (`lastDayGridLayoutSize = 0 to 0` in `afterUpdate` and `onViewAttachedToWindow`) that can cause a visible flash or double-render on first paint.

1.6 WHEN the `appearance.rowHeight` or `appearance.dayCellSize` props are not explicitly set THEN the system falls back to hardcoded constants (40 pt / 44 dp) that may not fill the allocated height, leaving blank space at the bottom of the grid.

**Animation gaps**

1.7 WHEN the user swipes left/right or taps a chevron to navigate between months THEN the system calls `requestRefresh()` → `reloadData()` / `notifyDataSetChanged()` with no slide or crossfade animation, producing an instant content swap.

1.8 WHEN the user navigates between months on iOS THEN the system uses `UISwipeGestureRecognizer` (discrete, fires once at gesture end) instead of a continuous paging scroll that tracks the user's finger, so there is no interactive drag preview before the page commits. The correct replacement is `TabView(.page)` or `ScrollView(.horizontal)` + `scrollTargetBehavior(.viewAligned)`.

1.9 WHEN the user navigates between months on Android THEN the system uses a `GestureDetector` fling/swipe detector that fires after the gesture ends, with no intermediate visual feedback during the drag. The correct replacement is `HorizontalPager` (`androidx.compose.foundation.pager`).

1.10 WHEN the week↔month collapse is toggled THEN the system changes the grid content instantly; neither platform animates the height change or the row count transition. The correct replacement is `withAnimation(.easeInOut(duration: 0.25))` (iOS) / `animateContentSize` (Android Compose).

1.11 WHEN the calendar switches between day-grid and picker modes (month/year) THEN the system performs an instant data reload with no crossfade or scale transition on either platform. The correct replacement is SwiftUI `withAnimation` + opacity/scale (iOS) and `Crossfade` / `AnimatedVisibility` (Android Compose).

1.12 WHEN the wheel picker is used on Android THEN the system drives it with `RecyclerView` + `LinearSnapHelper` + chained `recycler.post {}` calls with a 48 ms hard delay, producing a visible stutter on first render. The correct replacement is `LazyColumn` + `rememberSnapFlingBehavior` (`androidx.compose.foundation`).

1.13 WHEN the wheel picker is used on iOS THEN the system drives it with a custom `UICollectionView` drum implementation instead of the platform-native `Picker(.wheel)` or `ScrollView` + `scrollTargetBehavior(.viewAligned)` (iOS 17+), missing free scroll physics, snapping, and haptics.

---

### Expected Behavior (Correct)

**Display fixes**

2.1 WHEN the calendar grid first renders or the device rotates THEN the system SHALL compute cell widths from the actual measured `collectionView.bounds.width` (iOS) / `recycler.width` (Android) at layout time, falling back to screen width only when the view has not yet been measured.

2.2 WHEN the calendar is placed inside a `ScrollView` or a narrow container THEN the system SHALL defer cell sizing until the view's `onLayout` / `layoutSubviews` callback fires with a non-zero width, so cells always fill the available space correctly.

2.3 WHEN the user switches from day-grid mode to month-picker or year-picker mode THEN the system SHALL animate the transition with a short crossfade (≤ 200 ms) or a scale-in effect so the mode change feels intentional rather than abrupt.

2.4 WHEN the user taps the collapse/expand chevron THEN the system SHALL animate the calendar height change natively (UIView `animate` / `ValueAnimator`) so the container smoothly grows or shrinks rather than jumping.

2.5 WHEN the calendar is first attached to the window on Android THEN the system SHALL perform a single layout pass and a single data bind, eliminating the redundant invalidation that causes the first-paint flash.

2.6 WHEN `appearance.rowHeight` is set THEN the system SHALL use that value to fill the grid height evenly; when it is not set, the system SHALL compute a row height that fills the available grid height divided by the number of visible rows.

**Animation additions**

2.7 WHEN the user swipes left/right to navigate months THEN the system SHALL show a native interactive slide that tracks the gesture in real time: on iOS via `TabView(.page)` or `ScrollView(.horizontal)` + `scrollTargetBehavior(.viewAligned)` (iOS 17+); on Android via `HorizontalPager` (`androidx.compose.foundation.pager`). All animation frames stay on the native UI thread with no JS involvement.

2.8 WHEN the user taps a chevron to navigate months THEN the system SHALL programmatically advance the `TabView` selection (iOS) or call `pagerState.animateScrollToPage` (Android) so the new month slides in from the correct edge in ≤ 300 ms.

2.9 WHEN the week↔month collapse is toggled THEN the system SHALL animate the row count change and container height change using `withAnimation(.easeInOut(duration: 0.25))` (iOS SwiftUI) or `animateContentSize(tween(250, FastOutSlowInEasing))` (Android Compose), with no JS involvement.

2.10 WHEN the calendar switches between day-grid and picker modes THEN the system SHALL use `withAnimation` + opacity/scale transition (iOS) or `Crossfade` / `AnimatedVisibility` (Android Compose) to transition the grid content in ≤ 200 ms, with no JS involvement in the animation loop.

2.11 WHEN the wheel picker is rendered on iOS THEN the system SHALL use `Picker` with `.pickerStyle(.wheel)` for standard time columns, or `ScrollView` + `scrollTargetBehavior(.viewAligned)` + `.scrollTransition` for custom cell height / loop / external control, replacing the current `UICollectionView` drum implementation.

2.12 WHEN the wheel picker is rendered on Android THEN the system SHALL use `LazyColumn` + `rememberSnapFlingBehavior` (`androidx.compose.foundation`) replacing the current `RecyclerView` + `LinearSnapHelper` implementation, eliminating the chained `post {}` + 48 ms stutter entirely.

---

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the user taps a day cell THEN the system SHALL CONTINUE TO fire `onDateChange` with the correct `timestampMs` and update the selected-day highlight.

3.2 WHEN the user taps the month label in the header THEN the system SHALL CONTINUE TO switch to month-picker mode and display 12 months in a 3 × 4 grid.

3.3 WHEN the user taps the year label in the header THEN the system SHALL CONTINUE TO switch to year-picker mode with the current year at index 6 in the initial 12-year slice.

3.4 WHEN the user taps a month or year cell in picker mode THEN the system SHALL CONTINUE TO navigate to that month/year and return to day-grid mode.

3.5 WHEN the user taps the `back` button in picker mode THEN the system SHALL CONTINUE TO return to day-grid mode without changing the selected date.

3.6 WHEN `isRTL` is true THEN the system SHALL CONTINUE TO swap the prev/next navigation actions (not the icon assets) so time moves in the correct direction for RTL users.

3.7 WHEN `minTimestampMs` or `maxTimestampMs` are set THEN the system SHALL CONTINUE TO disable out-of-range day cells and prevent selection outside the allowed range.

3.8 WHEN `collapsedWeekMode` is toggled THEN the system SHALL CONTINUE TO preserve the selected date and keep the focused week visible after the transition.

3.9 WHEN the wheel picker is scrolled THEN the system SHALL CONTINUE TO fire `onSettled` exactly once per gesture end and fire `onValueChange` only on index change (not per pixel).

3.10 WHEN `loop` is true on the wheel picker THEN the system SHALL CONTINUE TO wrap seamlessly at both ends with no visible seam or position jump.

3.11 WHEN `appearance` or `strings` props change THEN the system SHALL CONTINUE TO re-apply colors, fonts, and labels without requiring a full component remount.

3.12 WHEN `goToToday()`, `goToMonth()`, or `goToYear()` imperative methods are called THEN the system SHALL CONTINUE TO navigate to the correct date and emit the appropriate events.

3.13 WHEN `onVisibleRangeChange` is wired THEN the system SHALL CONTINUE TO emit a throttled range event covering the visible month plus one adjacent month buffer on each side.

3.14 WHEN the calendar is rendered inside a `ScrollView` THEN the system SHALL CONTINUE TO allow vertical scroll of the parent without conflicting with horizontal month-navigation swipes.
