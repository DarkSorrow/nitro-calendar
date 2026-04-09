# Calendar Display & Animations Bugfix Design

## Overview

The `@novastera-oss/nitro-calendar` package has two interrelated defects: (1) display rendering problems where cells are sized from screen width instead of measured view bounds, causing overflow/gaps in nested containers, and (2) a complete absence of animated transitions for month navigation, collapse/expand, and mode switches.

The current implementations are pure UIKit (`UICollectionView` + `UIStackView` + `UISwipeGestureRecognizer`) on iOS and pure Android Views (`RecyclerView` + `LinearLayout` + `GestureDetector`) on Android. Both extend the Nitrogen-generated `HybridNitroCalendarSpec` / `HybridNitroWheelPickerViewSpec` base classes and expose a `var view: UIView` / `override val view: View` property to the Nitro bridge.

The fix **migrates** the rendering layer to SwiftUI (embedded via `UIHostingController` inside the existing `var view: UIView`) on iOS and Jetpack Compose (embedded via `ComposeView` as `override val view: View`) on Android. The Nitro bridge contract — `HybridNitroCalendarSpec`, `HybridNitroWheelPickerViewSpec`, all prop setters, methods, and event callbacks — is **unchanged**. No Nitrogen regeneration is required.

This migration replaces legacy gesture recognizers and instant data reloads with first-party platform animation primitives: `TabView(.page)` / `scrollTargetBehavior(.viewAligned)` on iOS and `HorizontalPager` on Android for month navigation; `Picker(.wheel)` / `scrollTargetBehavior(.viewAligned)` on iOS and `LazyColumn + rememberSnapFlingBehavior` on Android for the wheel picker; `withAnimation(.easeInOut)` / `animateContentSize` / `Crossfade` for collapse and mode transitions. All animation state lives in the native layer; the JS bridge continues to send only data updates (timestamps, appearance tokens, mode commands).

---

## Glossary

- **Bug_Condition (C)**: Any input or state that triggers one of the 13 defects catalogued in the requirements (wrong cell sizing, missing animation, stutter, instant cut).
- **Property (P)**: The desired native behavior when the bug condition holds — correct cell sizing, smooth animated transitions, no stutter.
- **Preservation**: All 14 unchanged-behavior clauses (3.1–3.14) that must not regress after the fix.
- **HybridNitroCalendarSpec**: Nitrogen-generated abstract base class (`HybridView` subclass) that both iOS and Android implementations extend. Defines all `abstract var` props and `abstract fun` methods. Do not modify.
- **HybridNitroWheelPickerViewSpec**: Same pattern for the wheel picker.
- **UIHostingController**: iOS mechanism to embed a SwiftUI view tree inside a UIKit `UIView`. Used to host `CalendarRootView` inside `HybridNitroCalendar.view`.
- **ComposeView**: Android `View` subclass that hosts a Jetpack Compose tree. Used as `HybridNitroCalendar.view` and `HybridNitroWheelPickerView.view`.
- **CalendarViewModel**: `@ObservableObject` class on iOS that bridges Nitro prop setters to SwiftUI `@Published` state.
- **MutableState fields**: Kotlin `mutableStateOf(...)` fields on the Android `HybridNitroCalendar` class that bridge Nitro prop setters to Compose state.
- **HorizontalPager**: `androidx.compose.foundation.pager.HorizontalPager` — stable, Accompanist-free paging composable.
- **rememberSnapFlingBehavior**: `androidx.compose.foundation.rememberSnapFlingBehavior(lazyListState)` — snap-to-item fling for `LazyColumn`.
- **scrollTargetBehavior(.viewAligned)**: iOS 17+ SwiftUI modifier that snaps a `ScrollView` to view-aligned pages.
- **isBugCondition**: Pseudocode predicate that returns `true` when a given input/state triggers one of the catalogued defects.
- **CalendarViewMode**: Nitro-generated enum — `.day | .week | .month | .year` (matches `CalendarViewMode` in `NitroCalendar.nitro.ts`).
- **VirtualPageIndex**: An integer offset from midpoint 10 000 used to represent infinite month navigation (20 001 total pages).

---

## Bug Details

### Bug Condition

The bugs manifest across six distinct trigger categories. Each maps to one or more requirements defects.

**Formal Specification:**

```
FUNCTION isBugCondition(input)
  INPUT: input — one of:
    LayoutInput    { containerWidth: Int, cellsComputed: Boolean, sourceIsScreen: Boolean }
    ModeSwitch     { fromMode: CalendarMode, toMode: CalendarMode, animated: Boolean }
    CollapseToggle { newState: CollapseState, animated: Boolean }
    MonthNav       { trigger: "swipe" | "chevron", interactive: Boolean, pagerUsed: Boolean }
    WheelRender    { platform: "ios" | "android", primitiveUsed: String, stutterMs: Int }
    FirstPaint     { platform: "android", layoutPassCount: Int }
  OUTPUT: boolean

  CASE LayoutInput:
    RETURN input.sourceIsScreen = true
           OR (input.containerWidth > 0 AND NOT input.cellsComputed)

  CASE ModeSwitch:
    RETURN NOT input.animated

  CASE CollapseToggle:
    RETURN NOT input.animated

  CASE MonthNav:
    RETURN NOT input.interactive
           OR NOT input.pagerUsed

  CASE WheelRender:
    RETURN (input.platform = "ios"     AND input.primitiveUsed ≠ "Picker.wheel"
                                       AND input.primitiveUsed ≠ "ScrollView.viewAligned")
        OR (input.platform = "android" AND input.primitiveUsed ≠ "LazyColumn.snapFling")
        OR input.stutterMs > 0

  CASE FirstPaint:
    RETURN input.platform = "android" AND input.layoutPassCount > 1
END FUNCTION
```

### Examples

- **Cell overflow**: Calendar placed in a 320 pt container; `UIScreen.main.bounds.width` = 390 pt → cells overflow by 70 pt. Expected: cells = 320 / 7 ≈ 45.7 pt each.
- **Mode cut**: User taps month label → `reloadData()` fires instantly with no crossfade. Expected: ≤ 200 ms opacity/scale transition.
- **Collapse jump**: User taps collapse chevron → grid height jumps from 6-row to 1-row instantly. Expected: 250 ms animated height change.
- **Discrete swipe (iOS)**: `UISwipeGestureRecognizer` fires once at gesture end; no drag preview. Expected: `TabView(.page)` tracks finger continuously.
- **Wheel stutter (Android)**: `RecyclerView.post { scrollTo(center) }` fires 48 ms after attach, causing visible jump. Expected: `LazyColumn` + `rememberSnapFlingBehavior` renders at correct position on first frame.
- **Double invalidation (Android)**: `lastDayGridLayoutSize = 0 to 0` logged twice on first attach. Expected: single layout pass.

---

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors (Requirements 3.1–3.14):**
- Day cell taps fire `onDateChange` with correct `timestampMs` and update the selected-day highlight.
- Month label tap switches to month-picker (3×4 grid of 12 months).
- Year label tap switches to year-picker (12-year slice, current year at index 6).
- Month/year cell tap navigates and returns to day-grid mode.
- Back button in picker mode returns to day-grid without changing selection.
- `isRTL=true` swaps prev/next navigation actions (not icon assets).
- `minTimestampMs` / `maxTimestampMs` disable out-of-range cells and block selection.
- Collapse toggle preserves selected date and focused week.
- `onSettled` fires exactly once per wheel gesture end; `onValueChange` fires only on index change.
- `loop=true` wraps seamlessly at both ends with no visible seam.
- `appearance` / `strings` prop changes re-apply without remount.
- `goToToday()`, `goToMonth()`, `goToYear()` imperative methods navigate correctly.
- `onVisibleRangeChange` emits throttled range covering visible month ± one buffer month.
- Horizontal month swipes do not conflict with vertical parent `ScrollView` scroll.

**Scope:**
All inputs that do NOT trigger `isBugCondition` must be completely unaffected. This includes: day cell taps, header button taps, imperative method calls, appearance/strings prop updates, RTL flag changes, and range-boundary enforcement.

---

## Hypothesized Root Cause

1. **Screen-width cell sizing**: `UIScreen.main.bounds.width` (iOS) and a hardcoded fallback (Android) are read before the view's layout pass completes. The fix is to read `collectionView.bounds.width` / `recycler.width` inside `layoutSubviews` / `onLayout` and invalidate layout only when the measured width changes.

2. **Legacy gesture recognizers for month navigation**: `UISwipeGestureRecognizer` (iOS) and `GestureDetector` fling (Android) are discrete — they fire once at gesture end. They cannot provide the continuous, finger-tracking drag preview that `TabView(.page)` / `HorizontalPager` provide natively.

3. **Instant data reload for mode switches**: `reloadData()` / `notifyDataSetChanged()` replace the entire data source synchronously. SwiftUI `withAnimation` + opacity/scale and Compose `Crossfade` / `AnimatedVisibility` are the correct replacements.

4. **No height animation for collapse**: The row-count state change is applied outside any animation block. Wrapping it in `withAnimation(.easeInOut(duration: 0.25))` (iOS) or applying `animateContentSize(tween(250, FastOutSlowInEasing))` to the grid container (Android) is the fix.

5. **Legacy wheel implementations**: The iOS `UICollectionView` drum and Android `RecyclerView + LinearSnapHelper + post{}` both bypass platform scroll physics. `Picker(.wheel)` / `scrollTargetBehavior(.viewAligned)` (iOS) and `LazyColumn + rememberSnapFlingBehavior` (Android) provide free inertia, snapping, and haptics.

6. **Android first-paint double invalidation**: `afterUpdate` and `onViewAttachedToWindow` both trigger a layout invalidation with `lastDayGridLayoutSize = 0 to 0`. The fix is to guard the invalidation with a size-change check so it only fires when the measured size actually changes.

---

## Correctness Properties

Property 1: Bug Condition — Cell Width From Measured Bounds

_For any_ layout input where the calendar container has a non-zero measured width W, the fixed implementation SHALL compute each day cell width as `floor(W / columnCount)` (with remainder distributed to edge cells), never reading from `UIScreen.main.bounds` or a hardcoded fallback when W is available.

**Validates: Requirements 2.1, 2.2, 2.6**

---

Property 2: Bug Condition — Mode Transition Animated

_For any_ mode switch from `dayGrid` to `monthPicker`, `yearPicker`, or back, the fixed implementation SHALL trigger a native animation (crossfade or scale-in, duration ≤ 200 ms) so that `isBugCondition(ModeSwitch{animated: false})` is never true after the fix.

**Validates: Requirements 2.3, 2.10**

---

Property 3: Bug Condition — Collapse Height Animated

_For any_ collapse toggle between `week` and `month` states, the fixed implementation SHALL animate the container height change using `withAnimation(.easeInOut(duration: 0.25))` (iOS) or `animateContentSize(tween(250, FastOutSlowInEasing))` (Android), so that `isBugCondition(CollapseToggle{animated: false})` is never true after the fix.

**Validates: Requirements 2.4, 2.9**

---

Property 4: Bug Condition — Interactive Month Navigation

_For any_ swipe gesture on the month grid, the fixed implementation SHALL use `TabView(.page)` or `ScrollView(.horizontal)+scrollTargetBehavior(.viewAligned)` (iOS) / `HorizontalPager` (Android) so that scroll position tracks the user's finger in real time, and `isBugCondition(MonthNav{interactive: false})` is never true after the fix.

**Validates: Requirements 2.7, 2.8**

---

Property 5: Bug Condition — Platform-Native Wheel Primitives

_For any_ wheel picker render, the fixed implementation SHALL use `Picker(.wheel)` or `ScrollView+scrollTargetBehavior(.viewAligned)` (iOS) / `LazyColumn+rememberSnapFlingBehavior` (Android), so that `isBugCondition(WheelRender{stutterMs > 0})` and `isBugCondition(WheelRender{primitiveUsed ≠ correct})` are never true after the fix.

**Validates: Requirements 2.11, 2.12**

---

Property 6: Bug Condition — Android Single Layout Pass on First Paint

_For any_ first attach of the calendar on Android, the fixed implementation SHALL perform exactly one layout pass and one data bind, so that `isBugCondition(FirstPaint{layoutPassCount > 1})` is never true after the fix.

**Validates: Requirements 2.5**

---

Property 7: Preservation — Day Selection and Event Firing

_For any_ input where `isBugCondition` returns false (normal day cell tap, header tap, imperative call), the fixed implementation SHALL produce exactly the same result as the original implementation: `onDateChange` fires with the correct `timestampMs`, the selected-day highlight updates, and no regression occurs in picker mode navigation, RTL semantics, range enforcement, or collapse state preservation.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8**

---

Property 8: Preservation — Wheel Picker Behavioral Contract

_For any_ wheel interaction where `isBugCondition` returns false (normal scroll, loop wrap, settled event), the fixed implementation SHALL preserve: `onSettled` fires exactly once per gesture end, `onValueChange` fires only on index change, `loop=true` wraps seamlessly, and `scrollTo(index)` animates correctly.

**Validates: Requirements 3.9, 3.10, 3.12**

---

Property 9: Preservation — Appearance, Strings, and Range Events

_For any_ prop update where `isBugCondition` returns false (appearance/strings change, visible range change, parent ScrollView scroll), the fixed implementation SHALL re-apply colors/fonts/labels without remount, emit throttled `onVisibleRangeChange` with correct range + buffer, and not conflict with vertical parent scroll.

**Validates: Requirements 3.11, 3.13, 3.14**

---

## Fix Implementation

### Nitro Bridge Contract (Do Not Change)

Both implementation classes are generated by Nitrogen and must not be modified. The bridge is:

- **iOS**: `HybridNitroCalendar: HybridNitroCalendarSpec` — `HybridNitroCalendarSpec` is a `typealias` combining `HybridNitroCalendarSpec_protocol & HybridNitroCalendarSpec_base`. The `var view: UIView` property is what Nitro renders. All props are Swift `var` properties with `get`/`set`; all methods are `func` throwing. Nitrogen generates the C++ bridge in `nitrogen/generated/ios/`.
- **Android**: `HybridNitroCalendar(context: ThemedReactContext): HybridNitroCalendarSpec()` — `HybridNitroCalendarSpec` extends `HybridView`. The `override val view: View` property is what Nitro renders. All props are Kotlin `abstract var` with `@DoNotStrip @Keep` annotations; all methods are `abstract fun`. Nitrogen generates the JNI bridge in `nitrogen/generated/android/`.
- **Wheel**: Same pattern — `HybridNitroWheelPickerView` extends `HybridNitroWheelPickerViewSpec` on both platforms.

The Nitro bridge calls prop setters and methods directly on the main thread. No `UIHostingController` or `AbstractComposeView` is involved in the current architecture — the implementations are pure UIKit (iOS) and pure Android Views (Android). The fix **migrates** the rendering layer to SwiftUI (via `UIHostingController`) and Jetpack Compose (via `ComposeView`) while keeping the same `HybridNitroCalendarSpec` / `HybridNitroWheelPickerViewSpec` contract intact.

---

### iOS — Migration to SwiftUI via UIHostingController

**Integration pattern**

`HybridNitroCalendar` keeps `var view: UIView` pointing to a plain `UIView` container. Inside `init()`, a `UIHostingController<CalendarRootView>` is created, its `view` is added as a subview pinned to all edges, and `addChild` / `didMove` lifecycle calls are made. All Nitro prop setters update an `@ObservableObject` view model; SwiftUI reacts automatically.

```
HybridNitroCalendar (HybridNitroCalendarSpec)
  var view: UIView  ← plain container UIView
    └── UIHostingController<CalendarRootView>.view  (pinned to edges)
          └── CalendarRootView (SwiftUI)
                ├── CalendarHeaderView
                ├── ZStack + .animation(.easeInOut(duration:0.2), value: vm.mode)
                │     ├── DayGridView  ← TabView(.page) or ScrollView+scrollTargetBehavior
                │     ├── MonthPickerView  ← LazyVGrid 3×4
                │     └── YearPickerView  ← LazyVGrid 3×4 with paging
                └── (WheelPickerView embedded separately via HybridNitroWheelPickerView)
```

**CalendarViewModel — prop bridge**

```swift
@MainActor
final class CalendarViewModel: ObservableObject {
    @Published var selectedTimestampMs: Double = Date().timeIntervalSince1970 * 1000
    @Published var mode: CalendarViewMode = .day          // matches Nitro CalendarViewMode enum
    @Published var collapsedWeekMode: Bool = false
    @Published var isRTL: Bool = false
    @Published var appearance: CalendarAppearance = HybridNitroCalendar.defaultAppearance()
    @Published var strings: CalendarStrings = HybridNitroCalendar.defaultStrings()
    @Published var pageIndex: Int = 10_000                // virtual pager midpoint
    @Published var yearSliceStart: Int = Date().year - 6
    @Published var minTimestampMs: Double? = nil
    @Published var maxTimestampMs: Double? = nil
    @Published var weekStartsOn: Int = 1
    var calendarEngine: Foundation.Calendar = .current
    // Callbacks forwarded to Nitro event props
    var onDateChange: ((DateChangeEvent) -> Void)?
    var onVisibleRangeChange: ((VisibleRangeChangeEvent) -> Void)?
    var onViewModeChange: ((ViewModeChangeEvent) -> Void)?
}
```

Each Nitro prop setter on `HybridNitroCalendar` writes to `vm` (the view model). Example:

```swift
var selectedTimestampMs: Double = Date().timeIntervalSince1970 * 1000 {
    didSet { vm.selectedTimestampMs = selectedTimestampMs }
}
var appearance: CalendarAppearance = HybridNitroCalendar.defaultAppearance() {
    didSet { vm.appearance = appearance }
}
```

Imperative methods (`goToToday`, `goToMonth`, `goToYear`, `setCollapsedWeekModeEnabled`, `setMarkers`) update `vm` properties on the main thread; SwiftUI reacts in the next render pass.

**Month Grid — TabView(.page) virtual paging**

20 001 pages, midpoint = 10 000. `pageIndex` in `vm` drives the displayed month offset.

```swift
TabView(selection: $vm.pageIndex) {
    ForEach(0..<20_001, id: \.self) { idx in
        MonthGridPage(monthOffset: idx - 10_000, vm: vm)
            .tag(idx)
    }
}
.tabViewStyle(.page(indexDisplayMode: .never))
```

Chevron taps: `withAnimation(.easeInOut(duration: 0.3)) { vm.pageIndex += vm.isRTL ? 1 : -1 }`.
Swipes are handled natively by `TabView` — no `UISwipeGestureRecognizer` needed.

For iOS 17+ (preferred when targeting iOS 17+):

```swift
ScrollView(.horizontal) {
    LazyHStack(spacing: 0) {
        ForEach(0..<20_001, id: \.self) { idx in
            MonthGridPage(monthOffset: idx - 10_000, vm: vm)
                .containerRelativeFrame(.horizontal)
        }
    }
    .scrollTargetLayout()
}
.scrollTargetBehavior(.viewAligned)
```

**Cell sizing — GeometryReader replaces UIScreen.main.bounds**

```swift
GeometryReader { geo in
    let columns = (vm.mode == .month || vm.mode == .year) ? 3 : 7
    let cellW = geo.size.width / CGFloat(columns)
    LazyVGrid(columns: Array(repeating: GridItem(.fixed(cellW), spacing: 0), count: columns),
              spacing: 0) {
        ForEach(dayItems) { item in DayCell(item: item, vm: vm) }
    }
}
```

No `UIScreen.main.bounds` reference anywhere in the SwiftUI layer.

**Mode transition — ZStack + .animation**

```swift
ZStack {
    if vm.mode == .day || vm.mode == .week {
        DayGridView(vm: vm)
            .transition(.opacity.combined(with: .scale(scale: 0.97)))
    } else if vm.mode == .month {
        MonthPickerView(vm: vm)
            .transition(.opacity.combined(with: .scale(scale: 0.97)))
    } else {
        YearPickerView(vm: vm)
            .transition(.opacity.combined(with: .scale(scale: 0.97)))
    }
}
.animation(.easeInOut(duration: 0.2), value: vm.mode)
```

**Collapse animation**

```swift
// In the collapse button action:
withAnimation(.easeInOut(duration: 0.25)) {
    vm.collapsedWeekMode.toggle()
}
// SwiftUI animates the height change automatically when row count changes.
```

**Wheel Picker — HybridNitroWheelPickerView migration to SwiftUI**

`HybridNitroWheelPickerView` follows the same `UIHostingController` pattern. For standard use (no loop, standard height):

```swift
// Inside WheelRootView (SwiftUI)
Picker("", selection: $selectedIndex) {
    ForEach(0..<values.count, id: \.self) { i in
        Text(values[i]).tag(i)
    }
}
.pickerStyle(.wheel)
.frame(height: CGFloat(visibleCount ?? 5) * CGFloat(itemHeight))
.clipped()
.onChange(of: selectedIndex) { idx in
    onValueChange?(WheelPickerValueChangeEvent(index: Double(idx), value: values[idx]))
}
```

For `loop == true` or custom `itemHeight` (iOS 17+):

```swift
ScrollView {
    LazyVStack(spacing: 0) {
        ForEach(virtualItems, id: \.id) { item in
            Text(item.value)
                .frame(height: CGFloat(itemHeight))
                .scrollTransition { content, phase in
                    content
                        .opacity(phase.isIdentity ? 1 : 0.4)
                        .scaleEffect(phase.isIdentity ? 1 : 0.85)
                }
        }
    }
    .scrollTargetLayout()
}
.scrollTargetBehavior(.viewAligned)
.frame(height: CGFloat(visibleCount ?? 5) * CGFloat(itemHeight))
```

`onSettled` fires in `.onChange(of: scrollView.isScrolling) { if !$0 { emitSettled() } }` — no `DispatchQueue.main.async` chains.

---

### Android — Migration to Jetpack Compose via ComposeView

**Integration pattern**

`HybridNitroCalendar` keeps `override val view: View` pointing to a `ComposeView`. The `ComposeView.setContent { }` call sets up the root composable. Nitro prop setters update `MutableState` fields declared on the `HybridNitroCalendar` class; Compose recomposes only affected subtrees.

```
HybridNitroCalendar (HybridNitroCalendarSpec, context: ThemedReactContext)
  override val view: View  ← ComposeView
    └── setContent { CalendarRoot(vm) }
          ├── CalendarHeader(vm)
          ├── Crossfade(targetState = vm.mode, animationSpec = tween(200))
          │     ├── DAY / WEEK  → DayGridView  ← HorizontalPager
          │     ├── MONTH       → MonthPickerView  ← LazyVerticalGrid 3×4
          │     └── YEAR        → YearPickerView  ← LazyVerticalGrid 3×4 with paging
          └── (WheelPickerView embedded separately via HybridNitroWheelPickerView)
```

**State hoisting — MutableState fields on HybridNitroCalendar**

```kotlin
// These are read inside the Compose tree; Nitro prop setters write to them on the main thread.
private var selectedTimestampMs by mutableStateOf(System.currentTimeMillis().toDouble())
private var renderedMode by mutableStateOf(CalendarViewMode.DAY)
private var collapsedWeekMode by mutableStateOf(false)
private var isRTL by mutableStateOf(false)
private var appearance by mutableStateOf(defaultAppearance())
private var strings by mutableStateOf(defaultStrings())
private var pageIndex by mutableStateOf(10_000)   // virtual pager midpoint
private var yearSliceStart by mutableStateOf(currentYear - 6)
private var minTimestampMs by mutableStateOf<Double?>(null)
private var maxTimestampMs by mutableStateOf<Double?>(null)
private var markers by mutableStateOf<Map<Long, IntArray>>(emptyMap())
```

Each Nitro `override var` setter writes to the corresponding `mutableStateOf` field. Example:

```kotlin
override var selectedTimestampMs: Double = System.currentTimeMillis().toDouble()
    set(value) {
        field = value
        _selectedTimestampMs = value   // MutableState field → triggers recomposition
        selectedCalendar = calendarFor(value)
    }
```

Imperative methods (`goToToday`, `goToMonth`, `goToYear`, `setCollapsedWeekModeEnabled`, `setMarkers`) update the `mutableStateOf` fields on the main thread.

**Month Grid — HorizontalPager virtual paging**

```kotlin
val pagerState = rememberPagerState(initialPage = pageIndex) { 20_001 }

HorizontalPager(
    state = pagerState,
    modifier = Modifier.fillMaxWidth()
) { page ->
    val monthOffset = page - 10_000
    MonthGridPage(monthOffset = monthOffset, vm = vm)
}

// Sync pagerState.currentPage back to vm.pageIndex for chevron taps
LaunchedEffect(pagerState.currentPage) {
    vm.pageIndex = pagerState.currentPage
    vm.emitVisibleRangeIfChanged()
}
```

Chevron taps:

```kotlin
val scope = rememberCoroutineScope()
// In header button onClick:
scope.launch {
    pagerState.animateScrollToPage(
        page = pagerState.currentPage + if (isRTL) 1 else -1,
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    )
}
```

No `GestureDetector` or `CalendarGridRecyclerView` needed — `HorizontalPager` handles swipes natively.

**Cell sizing — fillMaxWidth + GridCells.Fixed**

```kotlin
// Compose measures cells from available width automatically — no hardcoded fallback needed.
LazyVerticalGrid(
    columns = GridCells.Fixed(if (mode == DAY || mode == WEEK) 7 else 3),
    modifier = Modifier.fillMaxWidth()
) {
    items(dayItems) { item -> DayCell(item = item, vm = vm) }
}
```

No `recycler.width` fallback or `lastDayGridLayoutSize` guard needed.

**Mode transition — Crossfade**

```kotlin
Crossfade(
    targetState = renderedMode,
    animationSpec = tween(200),
    modifier = Modifier.fillMaxWidth()
) { mode ->
    when (mode) {
        CalendarViewMode.DAY, CalendarViewMode.WEEK -> DayGridView(vm)
        CalendarViewMode.MONTH -> MonthPickerView(vm)
        CalendarViewMode.YEAR  -> YearPickerView(vm)
    }
}
```

**Collapse animation — animateContentSize**

```kotlin
Box(modifier = Modifier
    .fillMaxWidth()
    .animateContentSize(animationSpec = tween(250, easing = FastOutSlowInEasing))
) {
    val rowCount = if (collapsedWeekMode) 1 else computeRowCount(dayItems)
    MonthGrid(items = dayItems, rowCount = rowCount, vm = vm)
}
```

**Android first-paint fix — remove redundant invalidations**

The current `init` block posts `lastDayGridLayoutSize = 0 to 0` in both `onViewAttachedToWindow` and `afterUpdate`, causing two layout passes. With Compose, this is eliminated entirely — `ComposeView` measures and lays out in a single pass driven by Compose's layout system. The `dayGridGlobalLayoutListener` and `lastDayGridLayoutSize` tracking are removed.

**Wheel Picker — HybridNitroWheelPickerView migration to Compose**

`HybridNitroWheelPickerView` replaces `RecyclerView + LinearSnapHelper` with `LazyColumn + rememberSnapFlingBehavior`. The `override val view: View` points to a `ComposeView`.

```kotlin
val lazyListState = rememberLazyListState(
    initialFirstVisibleItemIndex = rowForLogical(currentIndex)
)
val snapBehavior = rememberSnapFlingBehavior(lazyListState)

LazyColumn(
    state = lazyListState,
    flingBehavior = snapBehavior,
    contentPadding = PaddingValues(vertical = (halfVisible * itemHeight).dp),
    modifier = Modifier.height((visibleCount * itemHeight).dp)
) {
    items(totalItems) { idx ->
        val logical = idx % values.size
        WheelCell(
            value = values[logical],
            isSelected = logical == currentIndex,
            itemHeight = itemHeight,
            appearance = appearance
        )
    }
}
```

`onSettled` fires via `snapshotFlow { lazyListState.isScrollInProgress }`:

```kotlin
LaunchedEffect(lazyListState) {
    snapshotFlow { lazyListState.isScrollInProgress }
        .filter { !it }   // scroll just stopped
        .collect {
            val snapIndex = lazyListState.firstVisibleItemIndex  // or snap-aligned index
            val logical = snapIndex % values.size
            commitSelection(logical, emitSettled = true)
        }
}
```

No `scheduleApplyWheel()`, no `recycler.post { recycler.post { recycler.postDelayed(..., 48) } }` chains. The 3D drum perspective is replaced by `.graphicsLayer` + `scrollTransition` on each cell:

```kotlin
WheelCell(...)
    .graphicsLayer {
        val distance = (itemCenterY - containerCenterY) / itemHeightPx
        rotationX = (-distance * 22f).coerceIn(-35.5f, 35.5f)
        scaleX = (1f - 0.125f * abs(distance).coerceAtMost(2.9f)).coerceAtLeast(0.76f)
        scaleY = scaleX
        alpha = (1f - 0.34f * abs(distance).coerceAtMost(2.9f)).coerceAtLeast(0.22f)
        cameraDistance = 12f * density
    }
```

---

## Testing Strategy

### Validation Approach

Two-phase: first surface counterexamples on unfixed code to confirm root cause, then verify fix correctness and preservation.

### Exploratory Bug Condition Checking

**Goal**: Demonstrate each bug on unfixed code. Confirm or refute root cause hypotheses.

**Test Cases (run on UNFIXED code — expected to fail):**

1. **Cell width source test**: Measure cell width when container is 320 pt wide; assert it equals `320/7`. Will fail — returns `UIScreen.main.bounds.width / 7`.
2. **Mode switch animation test**: Trigger day→monthPicker; assert animation layer is active during transition. Will fail — instant cut.
3. **Collapse animation test**: Toggle collapse; assert height changes over ≥ 250 ms. Will fail — instant jump.
4. **Interactive swipe test (iOS)**: Begin a pan gesture; assert scroll offset tracks finger before gesture ends. Will fail — `UISwipeGestureRecognizer` fires only at end.
5. **Interactive swipe test (Android)**: Begin a drag; assert `pagerState.currentPageOffsetFraction` is non-zero mid-gesture. Will fail — `GestureDetector` fling fires only at end.
6. **Wheel stutter test (Android)**: Attach wheel; assert no `post{}` call with delay > 0 ms fires after attach. Will fail — 48 ms `post{}` is present.
7. **Android double invalidation test**: Attach calendar; assert `onLayout` is called exactly once. Will fail — called twice.

**Expected Counterexamples:**
- Cell widths do not match container width.
- No animation layer active during mode/collapse transitions.
- Scroll offset is zero mid-gesture (discrete recognizer).
- `post{}` with 48 ms delay is present in wheel attach path.

### Fix Checking

**Goal**: Verify all bug conditions are resolved after the fix.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  result := fixedImplementation(input)
  ASSERT expectedBehavior(result)
END FOR
```

**Test Cases (run on FIXED code — expected to pass):**

1. Cell width = `containerWidth / 7` for any container width (property test over widths 200–800 pt).
2. Mode transition duration ≤ 200 ms (measure animation layer duration).
3. Collapse animation duration = 250 ms ± 20 ms.
4. Scroll offset tracks finger mid-gesture (non-zero `currentPageOffsetFraction` / `contentOffset`).
5. Wheel renders at correct position on first frame (no post-attach jump).
6. Android first attach triggers exactly one layout pass.

### Preservation Checking

**Goal**: Verify all 3.1–3.14 behaviors are unchanged.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT originalBehavior(input) = fixedBehavior(input)
END FOR
```

**Test Cases:**

1. **Day selection preservation**: Tap day cell → `onDateChange` fires with correct `timestampMs`.
2. **Month picker grid**: Tap month label → 12 months shown in 3×4 grid.
3. **Year picker grid**: Tap year label → 12-year slice, current year at index 6.
4. **Picker cell tap**: Tap month/year cell → navigates and returns to day-grid.
5. **Back button**: Tap back → returns to day-grid, selection unchanged.
6. **RTL navigation**: `isRTL=true` → prev/next actions swapped, icons unchanged.
7. **Range enforcement**: Date outside `[min, max]` → cell disabled, selection blocked.
8. **Collapse state preservation**: Toggle collapse → selected date and focused week unchanged.
9. **onSettled exactly once**: Wheel gesture end → `onSettled` fires exactly once.
10. **Loop wrap seamless**: Scroll past end with `loop=true` → no visible seam.
11. **Appearance/strings update**: Change `appearance` → UI updates without remount.
12. **Imperative methods**: `goToToday()` / `goToMonth()` / `goToYear()` → navigate correctly.
13. **onVisibleRangeChange**: Navigate month → event emits with visible month ± 1 buffer.
14. **No scroll conflict**: Horizontal swipe in parent `ScrollView` → no vertical scroll conflict.

### Unit Tests

- Cell width computation for various container widths and column counts.
- `isBugCondition` predicate returns correct values for each input category.
- `CalendarViewModel` / Compose state updates propagate correctly from Nitro props.
- Virtual page index ↔ month offset conversion (midpoint arithmetic).
- RTL navigation action mapping (`isRTL` flips prev/next, not icons).
- `rowHeight` computed as `availableHeight / visibleRowCount` when not explicitly set.

### Property-Based Tests

- **Property 1**: For any container width W ∈ [100, 1000], cell width = `floor(W / 7)` (±1 rounding).
- **Property 2**: For any mode transition sequence, animation duration ≤ 200 ms and no instant cut.
- **Property 3**: For any collapse toggle, height animation duration = 250 ms ± tolerance.
- **Property 4**: For any month navigation gesture, scroll offset is continuous (not discrete).
- **Property 7**: For any day cell tap, `onDateChange` fires with `timestampMs` matching the tapped date.
- **Property 8**: For any wheel gesture end, `onSettled` fires exactly once.
- **Property 9**: For any `appearance` prop update, UI reflects new tokens without remount.

### Integration Tests

- Full month navigation flow: swipe 12 months forward and back; assert correct month displayed and `onVisibleRangeChange` emitted each time.
- Mode cycle: day → monthPicker → day → yearPicker → day; assert animated transitions and correct state at each step.
- Collapse cycle: expand → collapse → expand; assert selected date and focused week preserved throughout.
- Wheel + calendar composition: scroll wheel to new time; assert `onSettled` fires once and `onTimeChange` reflects settled value.
- RTL full flow: `isRTL=true`; swipe right → assert previous month shown; swipe left → assert next month shown.
- Narrow container: calendar in 280 pt container; assert no cell overflow and correct sizing after rotation.
