# Implementation Plan

- [x] 1. Write bug condition exploration tests (BEFORE implementing any fix)
  - **Property 1: Bug Condition** - Calendar & Wheel Rendering Defects
  - **CRITICAL**: These tests MUST FAIL on unfixed code — failure confirms the bugs exist
  - **DO NOT attempt to fix the tests or the code when they fail**
  - **NOTE**: These tests encode the expected behavior — they will validate the fix when they pass after implementation
  - **GOAL**: Surface counterexamples that demonstrate each bug exists
  - **Scoped PBT Approach**: For deterministic bugs, scope each property to the concrete failing case(s)
  - iOS Calendar — Cell width source test: place calendar in a 320 pt container; assert each cell width = floor(320/7) ≈ 45 pt. Will FAIL — returns UIScreen.main.bounds.width/7 instead (isBugCondition(LayoutInput{sourceIsScreen: true}))
  - iOS Calendar — Interactive swipe test: begin a pan gesture on the month grid; assert scroll offset (contentOffset.x) is non-zero before gesture ends. Will FAIL — UISwipeGestureRecognizer fires only at gesture end (isBugCondition(MonthNav{interactive: false, pagerUsed: false}))
  - iOS Wheel — Primitive test: inspect the view hierarchy after init; assert no UICollectionView drum is present and Picker(.wheel) or ScrollView+scrollTargetBehavior is used. Will FAIL — UICollectionView drum is present (isBugCondition(WheelRender{platform: "ios", primitiveUsed: "UICollectionView"}))
  - Android Calendar — Cell width source test: place calendar in a 320 dp container; assert each cell width = floor(320/7) ≈ 45 dp. Will FAIL — hardcoded fallback or screen width used (isBugCondition(LayoutInput{sourceIsScreen: true}))
  - Android Calendar — Interactive swipe test: begin a drag on the month grid; assert pagerState.currentPageOffsetFraction is non-zero mid-gesture. Will FAIL — GestureDetector fires only at gesture end (isBugCondition(MonthNav{interactive: false, pagerUsed: false}))
  - Android Calendar — Double invalidation test: attach calendar; assert onLayout is called exactly once. Will FAIL — lastDayGridLayoutSize = 0 to 0 posted in both afterUpdate and onViewAttachedToWindow (isBugCondition(FirstPaint{layoutPassCount > 1}))
  - Android Wheel — Stutter test: attach wheel; assert no post{} call with delay > 0 ms fires after attach. Will FAIL — scheduleApplyWheel() chains recycler.post{recycler.post{recycler.postDelayed(..., 48)}} (isBugCondition(WheelRender{platform: "android", stutterMs: 48}))
  - Run all tests on UNFIXED code
  - **EXPECTED OUTCOME**: All tests FAIL (this is correct — it proves the bugs exist)
  - Document counterexamples found (e.g., "cell width = 390/7 instead of 320/7", "contentOffset.x = 0 mid-gesture", "onLayout called 2 times")
  - Mark task complete when tests are written, run, and failures are documented
  - _Requirements: 1.1, 1.2, 1.7, 1.8, 1.9, 1.12, 1.13, 1.5_

- [-] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Unchanged Behavior Baseline (Requirements 3.1–3.14)
  - **IMPORTANT**: Follow observation-first methodology — run UNFIXED code with non-buggy inputs first
  - Observe: day cell tap fires onDateChange with correct timestampMs on unfixed code
  - Observe: month label tap switches to 3×4 month picker grid on unfixed code
  - Observe: year label tap switches to 12-year slice with current year at index 6 on unfixed code
  - Observe: picker cell tap navigates and returns to day-grid on unfixed code
  - Observe: back button returns to day-grid without changing selection on unfixed code
  - Observe: isRTL=true swaps prev/next actions (not icons) on unfixed code
  - Observe: minTimestampMs/maxTimestampMs disable out-of-range cells on unfixed code
  - Observe: collapse toggle preserves selected date and focused week on unfixed code
  - Observe: onSettled fires exactly once per wheel gesture end on unfixed code
  - Observe: loop=true wraps seamlessly at both ends on unfixed code
  - Observe: appearance/strings prop changes re-apply without remount on unfixed code
  - Observe: goToToday(), goToMonth(), goToYear() navigate correctly on unfixed code
  - Observe: onVisibleRangeChange emits throttled range covering visible month ± 1 buffer on unfixed code
  - Observe: horizontal swipes do not conflict with vertical parent ScrollView on unfixed code
  - Write property-based tests asserting all 14 observed behaviors hold for all non-buggy inputs (isBugCondition returns false)
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: All 14 preservation tests PASS (confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13, 3.14_

- [x] 3. Migrate iOS NitroCalendar.swift to SwiftUI via UIHostingController

  - [x] 3.1 Add UIHostingController + CalendarViewModel scaffolding
    - Remove UICollectionView, UIStackView, UISwipeGestureRecognizer, CalendarCoordinator, CalendarGridCell from HybridNitroCalendar
    - Keep `var view: UIView` pointing to a plain UIView container
    - Create `@MainActor final class CalendarViewModel: ObservableObject` with @Published fields: selectedTimestampMs, mode (CalendarViewMode), collapsedWeekMode, isRTL, appearance, strings, pageIndex (Int = 10_000), yearSliceStart, minTimestampMs, maxTimestampMs, weekStartsOn, calendarEngine, and callback closures (onDateChange, onVisibleRangeChange, onViewModeChange)
    - In `init()`: create `UIHostingController<CalendarRootView>`, add its view as subview pinned to all edges with addChild/didMove lifecycle calls
    - Wire all Nitro prop setters to write to `vm` (CalendarViewModel) — e.g., `var selectedTimestampMs: Double { didSet { vm.selectedTimestampMs = selectedTimestampMs } }`
    - Wire imperative methods (goToToday, goToMonth, goToYear, setCollapsedWeekModeEnabled, setMarkers) to update vm properties on main thread
    - _Bug_Condition: isBugCondition(LayoutInput{sourceIsScreen: true}) — UIScreen.main.bounds.width used in sizeForItemAt_
    - _Expected_Behavior: cell width = floor(containerWidth / columnCount) from GeometryReader, never UIScreen.main.bounds_l@tore1980$_
    - _Preservation: All Nitro prop setters and imperative methods continue to function identically from the JS bridge perspective_
    - _Requirements: 2.1, 2.2, 3.1, 3.11, 3.12_

  - [x] 3.2 Implement CalendarRootView and CalendarHeaderView in SwiftUI
    - Create `struct CalendarRootView: View` with `@ObservedObject var vm: CalendarViewModel`
    - Implement CalendarHeaderView: left/right chevron buttons, month label button, year label button, today/back button, collapse button — all wired to vm actions
    - Chevron taps: `withAnimation(.easeInOut(duration: 0.3)) { vm.pageIndex += vm.isRTL ? 1 : -1 }`
    - Month label tap: `vm.mode = .month`; year label tap: `vm.mode = .year`; back tap: `vm.mode = collapsedWeekMode ? .week : .day`
    - Collapse button tap: `withAnimation(.easeInOut(duration: 0.25)) { vm.collapsedWeekMode.toggle() }`
    - Weekday row: HStack of 7 labels from vm.strings.weekdayNamesMin, hidden in month/year picker modes
    - _Requirements: 3.2, 3.3, 3.5, 3.6, 3.8_

  - [x] 3.3 Implement ZStack mode transition with .animation(.easeInOut(duration:0.2))
    - Wrap DayGridView, MonthPickerView, YearPickerView in a ZStack
    - Apply `.transition(.opacity.combined(with: .scale(scale: 0.97)))` to each child
    - Apply `.animation(.easeInOut(duration: 0.2), value: vm.mode)` to the ZStack
    - This replaces the instant `reloadData()` / `applyViewMode()` cut
    - _Bug_Condition: isBugCondition(ModeSwitch{animated: false}) — reloadData() fires instantly_
    - _Expected_Behavior: crossfade/scale-in ≤ 200 ms so isBugCondition(ModeSwitch{animated: false}) is never true_
    - _Requirements: 2.3, 2.10_

  - [x] 3.4 Implement TabView(.page) month grid with GeometryReader cell sizing
    - Create `struct DayGridView: View` using `TabView(selection: $vm.pageIndex)` with 20_001 pages, midpoint = 10_000
    - Each page: `MonthGridPage(monthOffset: idx - 10_000, vm: vm).tag(idx)`
    - Apply `.tabViewStyle(.page(indexDisplayMode: .never))`
    - Inside MonthGridPage: use `GeometryReader { geo in let cellW = geo.size.width / CGFloat(columns) }` — NO UIScreen.main.bounds reference
    - Use `LazyVGrid(columns: Array(repeating: GridItem(.fixed(cellW), spacing: 0), count: 7))` for day cells
    - Day cell tap: update vm.selectedTimestampMs, emit onDateChange
    - Sync pagerState back to vm.pageIndex via `.onChange(of: vm.pageIndex)` for chevron taps
    - This replaces UISwipeGestureRecognizer with continuous finger-tracking paging
    - _Bug_Condition: isBugCondition(MonthNav{interactive: false, pagerUsed: false}) and isBugCondition(LayoutInput{sourceIsScreen: true})_
    - _Expected_Behavior: scroll offset tracks finger in real time; cell width = floor(containerWidth/7)_
    - _Requirements: 2.1, 2.2, 2.7, 2.8, 3.1, 3.7, 3.14_

  - [x] 3.5 Implement collapse animation with withAnimation(.easeInOut(duration:0.25))
    - In DayGridView, compute `rowCount = vm.collapsedWeekMode ? 1 : computeRowCount(dayItems)`
    - Wrap the row-count state change in `withAnimation(.easeInOut(duration: 0.25))` (triggered by collapse button in CalendarHeaderView)
    - SwiftUI animates the LazyVGrid height change automatically when row count changes
    - Preserve selected date and focused week across collapse toggle (vm.selectedTimestampMs unchanged)
    - _Bug_Condition: isBugCondition(CollapseToggle{animated: false}) — instant height jump_
    - _Expected_Behavior: container height animates over 250 ms so isBugCondition(CollapseToggle{animated: false}) is never true_
    - _Requirements: 2.4, 2.9, 3.8_

  - [x] 3.6 Implement MonthPickerView and YearPickerView (LazyVGrid 3×4)
    - Create `struct MonthPickerView: View` — LazyVGrid with 3 columns, 4 rows, 12 month name cells from vm.strings
    - Create `struct YearPickerView: View` — LazyVGrid with 3 columns, 4 rows, 12-year slice starting at vm.yearSliceStart (current year at index 6)
    - Cell tap in MonthPickerView: call vm equivalent of goToMonth, set vm.mode = .day
    - Cell tap in YearPickerView: call vm equivalent of goToYear, set vm.mode = .day
    - Apply appearance tokens (pickerCellTextColor, pickerCellSelectedBackgroundColor, etc.) from vm.appearance
    - _Requirements: 3.2, 3.3, 3.4_

- [x] 4. Migrate iOS NitroWheelPickerView.swift to SwiftUI Picker(.wheel)

  - [x] 4.1 Add UIHostingController + WheelViewModel scaffolding to HybridNitroWheelPickerView
    - Remove UICollectionView drum, WheelPickerCoordinator, WheelTextCell, applyWheelPerspectiveToVisibleCells from HybridNitroWheelPickerView
    - Keep `var view: UIView` pointing to a plain UIView container
    - Create WheelViewModel: @Published fields for values, selectedIndex, loop, visibleCount, itemHeight, appearance; callback closures for onValueChange, onSettled
    - In `init()`: create UIHostingController<WheelRootView>, add its view pinned to edges with addChild/didMove
    - Wire all Nitro prop setters to WheelViewModel fields
    - Wire scrollTo(index:) imperative method to update vm.selectedIndex on main thread and emit onSettled
    - _Bug_Condition: isBugCondition(WheelRender{platform: "ios", primitiveUsed: "UICollectionView"})_
    - _Expected_Behavior: Picker(.wheel) or ScrollView+scrollTargetBehavior used; no UICollectionView drum_
    - _Requirements: 2.11, 3.9, 3.10, 3.12_

  - [x] 4.2 Implement WheelRootView with Picker(.wheel) for standard use
    - For `loop == false` (or nil) and standard itemHeight: use `Picker("", selection: $vm.selectedIndex)` with `.pickerStyle(.wheel)`
    - Frame: `.frame(height: CGFloat(visibleCount ?? 5) * CGFloat(itemHeight)).clipped()`
    - `.onChange(of: vm.selectedIndex)`: emit onValueChange only when index actually changes
    - Apply appearance tokens (textColor, selectedTextColor, selectedBackgroundColor, fontSize, fontFamily)
    - _Requirements: 2.11, 3.9_

  - [x] 4.3 Implement ScrollView+scrollTargetBehavior(.viewAligned) for loop/custom height (iOS 17+)
    - For `loop == true` or custom itemHeight: use ScrollView with LazyVStack, `.scrollTargetLayout()`, `.scrollTargetBehavior(.viewAligned)`
    - Apply `.scrollTransition` for opacity/scale drum effect on each cell (replaces applyWheelPerspectiveToVisibleCells)
    - Wire `onSettled` via `.onChange(of: scrollView.isScrolling) { if !$0 { emitSettled() } }` — no DispatchQueue chains
    - Implement seamless loop wrap using virtual item count (values.count * 1000), midpoint centering
    - _Bug_Condition: isBugCondition(WheelRender{platform: "ios", primitiveUsed: "UICollectionView", stutterMs > 0})_
    - _Expected_Behavior: scroll physics from platform primitives; onSettled fires exactly once per gesture end_
    - _Requirements: 2.11, 3.9, 3.10_

- [x] 5. Migrate Android NitroCalendar.kt to Jetpack Compose via ComposeView

  - [x] 5.1 Add ComposeView scaffolding and mutableStateOf fields to HybridNitroCalendar
    - Remove CalendarGridRecyclerView, GestureDetector, DayAdapter, PickerAdapter, dayGridGlobalLayoutListener, lastDayGridLayoutSize from HybridNitroCalendar
    - Change `override val view: View` to a `ComposeView(context)` instance
    - Add private `mutableStateOf` fields: selectedTimestampMs, renderedMode, collapsedWeekMode, isRTL, appearance, strings, pageIndex (Int = 10_000), yearSliceStart, minTimestampMs, maxTimestampMs, markers
    - In `init {}`: call `(view as ComposeView).setContent { CalendarRoot() }` — CalendarRoot reads the mutableStateOf fields
    - Wire all Nitro `override var` setters to write to the corresponding mutableStateOf field (e.g., `set(value) { field = value; _selectedTimestampMs = value }`)
    - Wire imperative methods (goToToday, goToMonth, goToYear, setCollapsedWeekModeEnabled, setMarkers) to update mutableStateOf fields on main thread
    - _Bug_Condition: isBugCondition(LayoutInput{sourceIsScreen: true}) and isBugCondition(FirstPaint{layoutPassCount > 1})_
    - _Expected_Behavior: ComposeView measures from available width; single layout pass on first attach_
    - _Preservation: All Nitro prop setters and imperative methods continue to function identically from the JS bridge_
    - _Requirements: 2.1, 2.2, 2.5, 3.1, 3.11, 3.12_

  - [x] 5.2 Implement CalendarRoot composable with CalendarHeader and Crossfade mode transition
    - Create `@Composable fun CalendarRoot()` reading all mutableStateOf fields
    - Implement CalendarHeader: prev/next buttons, month label button, year label button, today/back button, collapse button — all wired to update mutableStateOf fields
    - Chevron taps: `scope.launch { pagerState.animateScrollToPage(page, tween(300, FastOutSlowInEasing)) }`
    - Collapse button tap: `collapsedWeekMode = !collapsedWeekMode` (triggers animateContentSize)
    - Wrap DayGridView/MonthPickerView/YearPickerView in `Crossfade(targetState = renderedMode, animationSpec = tween(200))`
    - _Bug_Condition: isBugCondition(ModeSwitch{animated: false}) — notifyDataSetChanged() fires instantly_
    - _Expected_Behavior: Crossfade tween(200) so isBugCondition(ModeSwitch{animated: false}) is never true_
    - _Requirements: 2.3, 2.10, 3.2, 3.3, 3.5, 3.6_

  - [x] 5.3 Implement HorizontalPager month grid with fillMaxWidth cell sizing
    - Create `@Composable fun DayGridView()` using `HorizontalPager(state = pagerState) { page -> MonthGridPage(monthOffset = page - 10_000) }`
    - pagerState: `rememberPagerState(initialPage = pageIndex) { 20_001 }`
    - Sync pagerState.currentPage back to pageIndex via `LaunchedEffect(pagerState.currentPage)`
    - Inside MonthGridPage: `LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.fillMaxWidth())` — NO hardcoded width fallback
    - Day cell tap: update selectedTimestampMs mutableStateOf, emit onDateChange
    - This replaces GestureDetector/CalendarGridRecyclerView with continuous finger-tracking paging
    - _Bug_Condition: isBugCondition(MonthNav{interactive: false, pagerUsed: false}) and isBugCondition(LayoutInput{sourceIsScreen: true})_
    - _Expected_Behavior: HorizontalPager tracks finger in real time; fillMaxWidth distributes cells correctly_
    - _Requirements: 2.1, 2.2, 2.7, 2.8, 3.1, 3.7, 3.14_

  - [x] 5.4 Implement collapse animation with animateContentSize(tween(250, FastOutSlowInEasing))
    - Wrap the day grid container in `Box(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(250, easing = FastOutSlowInEasing)))`
    - Compute `rowCount = if (collapsedWeekMode) 1 else computeRowCount(dayItems)` — Compose recomposes and animates height automatically
    - Preserve selectedTimestampMs and focused week across collapse toggle (mutableStateOf field unchanged)
    - _Bug_Condition: isBugCondition(CollapseToggle{animated: false}) — instant height jump_
    - _Expected_Behavior: animateContentSize tween(250) so isBugCondition(CollapseToggle{animated: false}) is never true_
    - _Requirements: 2.4, 2.9, 3.8_

  - [x] 5.5 Implement MonthPickerView and YearPickerView composables (LazyVerticalGrid 3×4)
    - Create `@Composable fun MonthPickerView()` — LazyVerticalGrid(columns = GridCells.Fixed(3)), 12 month name cells from strings mutableStateOf
    - Create `@Composable fun YearPickerView()` — LazyVerticalGrid(columns = GridCells.Fixed(3)), 12-year slice from yearSliceStart mutableStateOf (current year at index 6)
    - Cell tap in MonthPickerView: call goToMonth logic, set renderedMode = DAY
    - Cell tap in YearPickerView: call goToYear logic, set renderedMode = DAY
    - Apply appearance tokens from appearance mutableStateOf
    - _Requirements: 3.2, 3.3, 3.4_

  - [x] 5.6 Remove redundant layout invalidations (lastDayGridLayoutSize = 0 to 0)
    - Delete `lastDayGridLayoutSize` field and all references
    - Delete `dayGridGlobalLayoutListener` and its `root.viewTreeObserver.addOnGlobalLayoutListener(...)` call
    - Delete the `lastDayGridLayoutSize = 0 to 0` lines in `onViewAttachedToWindow` and `afterUpdate`
    - Delete `root.post { runRefreshUiNow() }` in init (ComposeView handles first paint in a single pass)
    - ComposeView's layout system performs a single measurement pass — no manual invalidation needed
    - _Bug_Condition: isBugCondition(FirstPaint{platform: "android", layoutPassCount > 1})_
    - _Expected_Behavior: exactly one layout pass on first attach; no post-attach flash_
    - _Requirements: 2.5_

- [x] 6. Migrate Android NitroWheelPickerView.kt to Jetpack Compose

  - [x] 6.1 Add ComposeView scaffolding and mutableStateOf fields to HybridNitroWheelPickerView
    - Remove RecyclerView, LinearSnapHelper, WheelAdapter, scheduleApplyWheel(), applyWheelPerspectiveToVisible(), globalWheelLayoutListener from HybridNitroWheelPickerView
    - Change `override val view: View` to a `ComposeView(context)` instance
    - Add private mutableStateOf fields: values, selectedIndex, loop, visibleCount, itemHeight, appearance
    - In `init {}`: call `(view as ComposeView).setContent { WheelRoot() }`
    - Wire all Nitro `override var` setters to write to mutableStateOf fields
    - Wire scrollTo(index:) to update selectedIndex mutableStateOf and emit onSettled
    - _Bug_Condition: isBugCondition(WheelRender{platform: "android", primitiveUsed: "RecyclerView", stutterMs: 48})_
    - _Expected_Behavior: ComposeView + LazyColumn; no post{} chains; renders at correct position on first frame_
    - _Requirements: 2.12, 3.9, 3.10, 3.12_

  - [x] 6.2 Implement WheelRoot composable with LazyColumn + rememberSnapFlingBehavior
    - Create `@Composable fun WheelRoot()` with `val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = rowForLogical(currentIndex))`
    - `val snapBehavior = rememberSnapFlingBehavior(lazyListState)`
    - `LazyColumn(state = lazyListState, flingBehavior = snapBehavior, contentPadding = PaddingValues(vertical = (halfVisible * itemHeight).dp), modifier = Modifier.height((visibleCount * itemHeight).dp))`
    - Items: `items(totalItems) { idx -> WheelCell(value = values[idx % values.size], isSelected = ..., itemHeight = itemHeight) }`
    - This replaces RecyclerView + LinearSnapHelper + scheduleApplyWheel() entirely
    - _Bug_Condition: isBugCondition(WheelRender{platform: "android", stutterMs: 48}) — chained post{} + 48ms delay_
    - _Expected_Behavior: LazyColumn renders at correct position on first frame; no post-attach jump_
    - _Requirements: 2.12, 3.9_

  - [x] 6.3 Wire onSettled via snapshotFlow { lazyListState.isScrollInProgress }
    - Add `LaunchedEffect(lazyListState) { snapshotFlow { lazyListState.isScrollInProgress }.filter { !it }.collect { val logical = snapIndex % values.size; commitSelection(logical, emitSettled = true) } }`
    - Wire onValueChange: emit only when logical index changes during scroll (snapshotFlow on firstVisibleItemIndex)
    - No DispatchQueue/Handler chains — settlement detection is purely reactive
    - _Requirements: 3.9_

  - [x] 6.4 Implement .graphicsLayer drum perspective on WheelCell
    - Apply `.graphicsLayer { val distance = (itemCenterY - containerCenterY) / itemHeightPx; rotationX = (-distance * 22f).coerceIn(-35.5f, 35.5f); scaleX = (1f - 0.125f * abs(distance).coerceAtMost(2.9f)).coerceAtLeast(0.76f); scaleY = scaleX; alpha = (1f - 0.34f * abs(distance).coerceAtMost(2.9f)).coerceAtLeast(0.22f); cameraDistance = 12f * density }` to each WheelCell
    - This replaces applyWheelPerspectiveToVisible() with declarative Compose rendering
    - _Requirements: 2.12_

- [x] 7. Verify bug condition exploration tests now pass
  - **Property 1: Expected Behavior** - Calendar & Wheel Rendering Defects Resolved
  - **IMPORTANT**: Re-run the SAME tests from task 1 — do NOT write new tests
  - The tests from task 1 encode the expected behavior
  - When these tests pass, it confirms the expected behavior is satisfied for all 6 bug conditions
  - Run all bug condition exploration tests from step 1 on FIXED code
  - **EXPECTED OUTCOME**: All tests PASS (confirms all 6 bugs are fixed)
  - Verify: cell width = floor(containerWidth/7) for any container width (Property 1 — Requirements 2.1, 2.2, 2.6)
  - Verify: mode transition animation ≤ 200 ms (Property 2 — Requirements 2.3, 2.10)
  - Verify: collapse animation = 250 ms ± 20 ms (Property 3 — Requirements 2.4, 2.9)
  - Verify: scroll offset tracks finger mid-gesture on both platforms (Property 4 — Requirements 2.7, 2.8)
  - Verify: wheel renders at correct position on first frame, no post-attach jump (Property 5 — Requirements 2.11, 2.12)
  - Verify: Android first attach triggers exactly one layout pass (Property 6 — Requirements 2.5)
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.12_

- [x] 8. Verify preservation tests still pass
  - **Property 2: Preservation** - Unchanged Behavior Baseline (Requirements 3.1–3.14)
  - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
  - Run all 14 preservation property tests from step 2 on FIXED code
  - **EXPECTED OUTCOME**: All 14 tests PASS (confirms no regressions)
  - Confirm all tests still pass after fix (no regressions across day selection, picker navigation, RTL, range enforcement, collapse state, wheel events, appearance updates, imperative methods, visible range events, scroll conflict)
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13, 3.14_

- [x] 9. Checkpoint — Ensure all tests pass
  - Ensure all bug condition exploration tests (task 7) pass
  - Ensure all preservation tests (task 8) pass
  - Verify no TypeScript/Nitro bridge regressions (HybridNitroCalendarSpec and HybridNitroWheelPickerViewSpec contracts unchanged — no Nitrogen regeneration needed)
  - Ask the user if any questions arise
