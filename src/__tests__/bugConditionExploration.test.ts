/**
 * Bug Condition Exploration Tests — Task 1
 *
 * These tests encode the EXPECTED (correct) behavior for each of the 7 bug conditions.
 * They are run on UNFIXED code and are EXPECTED TO FAIL — failure confirms the bugs exist.
 *
 * DO NOT fix the tests or the implementation when they fail.
 * The tests will pass after the fix is applied (Task 7).
 *
 * Validates: Requirements 1.1, 1.2, 1.7, 1.8, 1.9, 1.12, 1.13, 1.5
 */

import * as fs from 'fs';
import * as path from 'path';

// ---------------------------------------------------------------------------
// Source file helpers
// ---------------------------------------------------------------------------

const ROOT = path.resolve(__dirname, '../../');

function readSource(relativePath: string): string {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8');
}

const iosCalendar = readSource('ios/NitroCalendar.swift');
const iosWheel = readSource('ios/NitroWheelPickerView.swift');
const androidCalendar = readSource(
  'android/src/main/java/com/margelo/nitro/novasteraoss/nitrocalendar/NitroCalendar.kt'
);
const androidWheel = readSource(
  'android/src/main/java/com/margelo/nitro/novasteraoss/nitrocalendar/NitroWheelPickerView.kt'
);

// ---------------------------------------------------------------------------
// isBugCondition helpers (mirrors the formal spec in design.md)
// ---------------------------------------------------------------------------

/** Returns true when the iOS calendar cell-width source is UIScreen (bug present). */
function isBugCondition_iOS_LayoutInput_sourceIsScreen(source: string): boolean {
  // Bug: UIScreen.main.bounds.width used as fallback for cell sizing
  return /UIScreen\.main\.bounds\.width/.test(source);
}

/** Returns true when iOS calendar uses UISwipeGestureRecognizer (non-interactive, bug present). */
function isBugCondition_iOS_MonthNav_notInteractive(source: string): boolean {
  // Bug: UISwipeGestureRecognizer fires only at gesture end — not continuous
  return /UISwipeGestureRecognizer/.test(source);
}

/** Returns true when iOS wheel uses UICollectionView drum (wrong primitive, bug present). */
function isBugCondition_iOS_WheelRender_UICollectionView(source: string): boolean {
  // Bug: UICollectionView used instead of Picker(.wheel) or ScrollView+scrollTargetBehavior
  return (
    /UICollectionView/.test(source) &&
    !/Picker\(.*\.wheel\)/.test(source) &&
    !/scrollTargetBehavior/.test(source)
  );
}

/** Returns true when Android calendar cell-width source is screen/hardcoded (bug present). */
function isBugCondition_Android_LayoutInput_sourceIsScreen(source: string): boolean {
  // Bug: No fillMaxWidth / measured width — uses hardcoded fallback or screen width
  // The current code uses RecyclerView with GridLayoutManager but no fillMaxWidth Compose cell sizing.
  // Expected fix: LazyVerticalGrid with GridCells.Fixed(7) + fillMaxWidth (no hardcoded width).
  // Bug indicator: uses RecyclerView + GridLayoutManager instead of Compose LazyVerticalGrid.
  return (
    /GridLayoutManager/.test(source) &&
    !/LazyVerticalGrid/.test(source)
  );
}

/** Returns true when Android calendar uses GestureDetector (non-interactive, bug present). */
function isBugCondition_Android_MonthNav_notInteractive(source: string): boolean {
  // Bug: GestureDetector fling fires only at gesture end — not continuous
  return (
    /GestureDetector/.test(source) &&
    !/HorizontalPager/.test(source)
  );
}

/** Returns true when Android calendar has double invalidation on first paint (bug present). */
function isBugCondition_Android_FirstPaint_doubleInvalidation(source: string): boolean {
  // Bug: lastDayGridLayoutSize = 0 to 0 posted in both afterUpdate and onViewAttachedToWindow
  // This causes multiple layout passes on first attach.
  const hasLastDayGridLayoutSize = /lastDayGridLayoutSize\s*=\s*0\s+to\s+0/.test(source);
  const hasDoublePost =
    /onViewAttachedToWindow[\s\S]{0,500}lastDayGridLayoutSize\s*=\s*0\s+to\s+0/.test(source) &&
    /afterUpdate[\s\S]{0,500}lastDayGridLayoutSize\s*=\s*0\s+to\s+0/.test(source);
  return hasLastDayGridLayoutSize && hasDoublePost;
}

/** Returns true when Android wheel has scheduleApplyWheel with postDelayed stutter (bug present). */
function isBugCondition_Android_WheelRender_stutter48ms(source: string): boolean {
  // Bug: scheduleApplyWheel() chains recycler.post{recycler.post{recycler.postDelayed(..., 48)}}
  return /postDelayed\s*\(/.test(source) && /scheduleApplyWheel/.test(source);
}

// ---------------------------------------------------------------------------
// Bug Condition Exploration Tests
// ---------------------------------------------------------------------------

describe('Bug Condition Exploration Tests (EXPECTED TO FAIL on unfixed code)', () => {

  /**
   * Test 1: iOS Calendar — Cell width source test
   *
   * isBugCondition(LayoutInput{sourceIsScreen: true})
   *
   * Expected behavior: cell width = floor(containerWidth / 7) from GeometryReader,
   * never UIScreen.main.bounds.
   *
   * Will FAIL on unfixed code — UIScreen.main.bounds.width is used as fallback.
   *
   * Validates: Requirements 1.1, 1.2
   */
  test('iOS Calendar: cell width must NOT use UIScreen.main.bounds (GeometryReader required)', () => {
    // Expected: isBugCondition returns false (no UIScreen.main.bounds usage)
    const bugPresent = isBugCondition_iOS_LayoutInput_sourceIsScreen(iosCalendar);

    // This assertion FAILS on unfixed code (bug is present)
    // Counterexample: UIScreen.main.bounds.width found in sizeForItemAt
    expect(bugPresent).toBe(false);
  });

  /**
   * Test 2: iOS Calendar — Interactive swipe test
   *
   * isBugCondition(MonthNav{interactive: false, pagerUsed: false})
   *
   * Expected behavior: TabView(.page) or ScrollView+scrollTargetBehavior tracks finger
   * continuously — no UISwipeGestureRecognizer.
   *
   * Will FAIL on unfixed code — UISwipeGestureRecognizer fires only at gesture end.
   *
   * Validates: Requirements 1.7, 1.8
   */
  test('iOS Calendar: month navigation must use TabView(.page) not UISwipeGestureRecognizer', () => {
    // Expected: isBugCondition returns false (no UISwipeGestureRecognizer)
    const bugPresent = isBugCondition_iOS_MonthNav_notInteractive(iosCalendar);

    // This assertion FAILS on unfixed code (bug is present)
    // Counterexample: UISwipeGestureRecognizer found — fires only at gesture end, no drag preview
    expect(bugPresent).toBe(false);
  });

  /**
   * Test 3: iOS Wheel — Primitive test
   *
   * isBugCondition(WheelRender{platform: "ios", primitiveUsed: "UICollectionView"})
   *
   * Expected behavior: Picker(.wheel) or ScrollView+scrollTargetBehavior used;
   * no UICollectionView drum.
   *
   * Will FAIL on unfixed code — UICollectionView drum is present.
   *
   * Validates: Requirements 1.13
   */
  test('iOS Wheel: must use Picker(.wheel) or ScrollView+scrollTargetBehavior, not UICollectionView', () => {
    // Expected: isBugCondition returns false (no UICollectionView drum)
    const bugPresent = isBugCondition_iOS_WheelRender_UICollectionView(iosWheel);

    // This assertion FAILS on unfixed code (bug is present)
    // Counterexample: UICollectionView found in HybridNitroWheelPickerView — missing platform scroll physics
    expect(bugPresent).toBe(false);
  });

  /**
   * Test 4: Android Calendar — Cell width source test
   *
   * isBugCondition(LayoutInput{sourceIsScreen: true})
   *
   * Expected behavior: LazyVerticalGrid with GridCells.Fixed(7) + fillMaxWidth distributes
   * cells from measured container width — no hardcoded fallback.
   *
   * Will FAIL on unfixed code — RecyclerView + GridLayoutManager used (no fillMaxWidth).
   *
   * Validates: Requirements 1.1, 1.2
   */
  test('Android Calendar: cell width must use fillMaxWidth (LazyVerticalGrid), not RecyclerView+GridLayoutManager', () => {
    // Expected: isBugCondition returns false (no GridLayoutManager, uses LazyVerticalGrid)
    const bugPresent = isBugCondition_Android_LayoutInput_sourceIsScreen(androidCalendar);

    // This assertion FAILS on unfixed code (bug is present)
    // Counterexample: GridLayoutManager found — cells sized from RecyclerView width, not measured container
    expect(bugPresent).toBe(false);
  });

  /**
   * Test 5: Android Calendar — Interactive swipe test
   *
   * isBugCondition(MonthNav{interactive: false, pagerUsed: false})
   *
   * Expected behavior: HorizontalPager tracks finger continuously — no GestureDetector.
   *
   * Will FAIL on unfixed code — GestureDetector fires only at gesture end.
   *
   * Validates: Requirements 1.7, 1.9
   */
  test('Android Calendar: month navigation must use HorizontalPager not GestureDetector', () => {
    // Expected: isBugCondition returns false (no GestureDetector, uses HorizontalPager)
    const bugPresent = isBugCondition_Android_MonthNav_notInteractive(androidCalendar);

    // This assertion FAILS on unfixed code (bug is present)
    // Counterexample: GestureDetector found — fires only at fling end, no mid-gesture offset
    expect(bugPresent).toBe(false);
  });

  /**
   * Test 6: Android Calendar — Double invalidation test
   *
   * isBugCondition(FirstPaint{layoutPassCount > 1})
   *
   * Expected behavior: exactly one layout pass on first attach — no redundant invalidations.
   *
   * Will FAIL on unfixed code — lastDayGridLayoutSize = 0 to 0 posted in both
   * afterUpdate and onViewAttachedToWindow.
   *
   * Validates: Requirements 1.5
   */
  test('Android Calendar: must NOT have lastDayGridLayoutSize=0 to 0 in both afterUpdate and onViewAttachedToWindow', () => {
    // Expected: isBugCondition returns false (no double invalidation)
    const bugPresent = isBugCondition_Android_FirstPaint_doubleInvalidation(androidCalendar);

    // This assertion FAILS on unfixed code (bug is present)
    // Counterexample: lastDayGridLayoutSize = 0 to 0 found in both afterUpdate and onViewAttachedToWindow
    // — causes 2+ layout passes on first attach, visible flash
    expect(bugPresent).toBe(false);
  });

  /**
   * Test 7: Android Wheel — Stutter test
   *
   * isBugCondition(WheelRender{platform: "android", stutterMs: 48})
   *
   * Expected behavior: LazyColumn + rememberSnapFlingBehavior renders at correct position
   * on first frame — no post{} chains with delay.
   *
   * Will FAIL on unfixed code — scheduleApplyWheel() chains
   * recycler.post{recycler.post{recycler.postDelayed(..., 48)}}.
   *
   * Validates: Requirements 1.12
   */
  test('Android Wheel: must NOT use postDelayed(48ms) in scheduleApplyWheel (LazyColumn required)', () => {
    // Expected: isBugCondition returns false (no postDelayed stutter)
    const bugPresent = isBugCondition_Android_WheelRender_stutter48ms(androidWheel);

    // This assertion FAILS on unfixed code (bug is present)
    // Counterexample: postDelayed(..., 48) found in scheduleApplyWheel() — causes 48ms stutter on attach
    expect(bugPresent).toBe(false);
  });

});

// ---------------------------------------------------------------------------
// Supplementary: document the counterexamples found
// ---------------------------------------------------------------------------

describe('Counterexample Documentation (informational — always passes)', () => {

  test('documents iOS Calendar cell-width counterexample', () => {
    const match = iosCalendar.match(/UIScreen\.main\.bounds\.width[^\n]*/);
    if (match) {
      console.log('[BUG 1] iOS Calendar cell-width counterexample:');
      console.log('  Found:', match[0].trim());
      console.log('  Expected: GeometryReader { geo in let cellW = geo.size.width / CGFloat(columns) }');
    }
    // This test always passes — it just documents the counterexample
    expect(true).toBe(true);
  });

  test('documents iOS Calendar swipe counterexample', () => {
    const match = iosCalendar.match(/UISwipeGestureRecognizer[^\n]*/);
    if (match) {
      console.log('[BUG 2] iOS Calendar swipe counterexample:');
      console.log('  Found:', match[0].trim());
      console.log('  Expected: TabView(.page) — contentOffset.x tracks finger continuously');
    }
    expect(true).toBe(true);
  });

  test('documents iOS Wheel primitive counterexample', () => {
    const match = iosWheel.match(/UICollectionView[^\n]*/);
    if (match) {
      console.log('[BUG 3] iOS Wheel primitive counterexample:');
      console.log('  Found:', match[0].trim());
      console.log('  Expected: Picker(.wheel) or ScrollView+scrollTargetBehavior(.viewAligned)');
    }
    expect(true).toBe(true);
  });

  test('documents Android Calendar cell-width counterexample', () => {
    const match = androidCalendar.match(/GridLayoutManager[^\n]*/);
    if (match) {
      console.log('[BUG 4] Android Calendar cell-width counterexample:');
      console.log('  Found:', match[0].trim());
      console.log('  Expected: LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.fillMaxWidth())');
    }
    expect(true).toBe(true);
  });

  test('documents Android Calendar swipe counterexample', () => {
    const match = androidCalendar.match(/GestureDetector[^\n]*/);
    if (match) {
      console.log('[BUG 5] Android Calendar swipe counterexample:');
      console.log('  Found:', match[0].trim());
      console.log('  Expected: HorizontalPager — pagerState.currentPageOffsetFraction non-zero mid-gesture');
    }
    expect(true).toBe(true);
  });

  test('documents Android Calendar double-invalidation counterexample', () => {
    const matches = androidCalendar.match(/lastDayGridLayoutSize\s*=\s*0\s+to\s+0/g);
    if (matches) {
      console.log('[BUG 6] Android Calendar double-invalidation counterexample:');
      console.log(`  Found ${matches.length} occurrence(s) of "lastDayGridLayoutSize = 0 to 0"`);
      console.log('  Expected: 0 occurrences — ComposeView performs single layout pass');
    }
    expect(true).toBe(true);
  });

  test('documents Android Wheel stutter counterexample', () => {
    const match = androidWheel.match(/postDelayed\s*\(\s*\{[^}]*\}\s*,\s*(\d+)\s*\)/);
    if (match) {
      console.log('[BUG 7] Android Wheel stutter counterexample:');
      console.log(`  Found: postDelayed(..., ${match[1]}ms) in scheduleApplyWheel()`);
      console.log('  Expected: LazyColumn + rememberSnapFlingBehavior — no post{} chains');
    }
    expect(true).toBe(true);
  });

});
