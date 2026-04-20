package com.margelo.nitro.novasteraoss.nitrocalendar

import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.view.View
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

@DoNotStrip
class HybridNitroCalendar(private val context: ThemedReactContext) : HybridNitroCalendarSpec() {

  private val composeView = ComposeView(context)
  override val view: View = composeView

  private var _selectedTimestampMs by mutableStateOf(System.currentTimeMillis().toDouble())
  private var _renderedMode by mutableStateOf(CalendarViewMode.DAY)
  private var _collapsedWeekMode by mutableStateOf(false)
  // Week index within the current month page (0 = first week, increments independently of selection)
  private var _weekIndex by mutableStateOf(0)
  private var _isRTL by mutableStateOf(false)
  private var _appearance by mutableStateOf(defaultAppearance())
  private var _strings by mutableStateOf(defaultStrings())
  private var _pageIndex by mutableStateOf(10_000)
  private var _yearSliceStart by mutableStateOf(Calendar.getInstance().get(Calendar.YEAR) - 6)
  private var _minTimestampMs by mutableStateOf<Double?>(null)
  private var _maxTimestampMs by mutableStateOf<Double?>(null)
  private var _markers by mutableStateOf<Map<Long, IntArray>>(emptyMap())

  /** Prevents overlapping week navigations from rapid gestures. */
  private val navigatingWeek = AtomicBoolean(false)

  override var selectedTimestampMs: Double = System.currentTimeMillis().toDouble()
    set(value) { field = value; _selectedTimestampMs = value }
  override var initialTimestampMs: Double? = null
  override var calendarType: CalendarType = CalendarType.GREGORIAN
  override var isRTL: Boolean = false
    set(value) { field = value; _isRTL = value }
  override var timeZoneId: String? = null
  override var viewMode: CalendarViewMode = CalendarViewMode.DAY
    set(value) { field = value; _renderedMode = value; onViewModeChange?.invoke(ViewModeChangeEvent(value)) }
  override var collapsedWeekMode: Boolean = false
    set(value) { field = value; _collapsedWeekMode = value }
  override var weekStartsOn: Double = 1.0
  override var uses24HourClock: Boolean? = null
  override var localeId: String? = null
  override var appearance: CalendarAppearance = defaultAppearance()
    set(value) { field = value; _appearance = value }
  override var appearanceKey: String? = null
  override var strings: CalendarStrings = defaultStrings()
    set(value) { field = value; _strings = value }
  override var stringsKey: String? = null
  override var minTimestampMs: Double? = null
    set(value) { field = value; _minTimestampMs = value }
  override var maxTimestampMs: Double? = null
    set(value) { field = value; _maxTimestampMs = value }
  override var onDateChange: ((event: DateChangeEvent) -> Unit)? = null
  override var onVisibleRangeChange: ((event: VisibleRangeChangeEvent) -> Unit)? = null
  override var onViewModeChange: ((event: ViewModeChangeEvent) -> Unit)? = null

  init {
    composeView.setContent { CalendarRoot() }
  }

  override fun goToToday() {
    val now = Calendar.getInstance(resolveTimeZone())
    selectedTimestampMs = now.timeInMillis.toDouble()
    _selectedTimestampMs = selectedTimestampMs
    _pageIndex = 10_000
    syncWeekIndexToSelectionForPage(_pageIndex)
    if (_renderedMode == CalendarViewMode.MONTH || _renderedMode == CalendarViewMode.YEAR) {
      _renderedMode = if (_collapsedWeekMode) CalendarViewMode.WEEK else CalendarViewMode.DAY
    }
  }

  override fun goToMonth(monthIndex: Double) {
    val cal = calendarFor(_selectedTimestampMs)
    cal.set(Calendar.MONTH, min(11, max(0, monthIndex.toInt())))
    cal.set(Calendar.DAY_OF_MONTH, min(28, cal.get(Calendar.DAY_OF_MONTH)))
    selectedTimestampMs = cal.timeInMillis.toDouble()
    _selectedTimestampMs = selectedTimestampMs
    // Navigate pager to the selected month
    val todayCal = Calendar.getInstance(resolveTimeZone())
    val monthDiff = (cal.get(Calendar.YEAR) - todayCal.get(Calendar.YEAR)) * 12 +
      (cal.get(Calendar.MONTH) - todayCal.get(Calendar.MONTH))
    _pageIndex = 10_000 + monthDiff
    syncWeekIndexToSelectionForPage(_pageIndex)
    _renderedMode = if (_collapsedWeekMode) CalendarViewMode.WEEK else CalendarViewMode.DAY
    onViewModeChange?.invoke(ViewModeChangeEvent(_renderedMode))
  }

  override fun goToYear(year: Double) {
    val cal = calendarFor(_selectedTimestampMs)
    cal.set(Calendar.YEAR, year.toInt())
    cal.set(Calendar.DAY_OF_MONTH, min(28, cal.get(Calendar.DAY_OF_MONTH)))
    selectedTimestampMs = cal.timeInMillis.toDouble()
    _selectedTimestampMs = selectedTimestampMs
    _yearSliceStart = year.toInt() - 6
    // Navigate pager to the selected year/month
    val todayCal = Calendar.getInstance(resolveTimeZone())
    val monthDiff = (cal.get(Calendar.YEAR) - todayCal.get(Calendar.YEAR)) * 12 +
      (cal.get(Calendar.MONTH) - todayCal.get(Calendar.MONTH))
    _pageIndex = 10_000 + monthDiff
    syncWeekIndexToSelectionForPage(_pageIndex)
    _renderedMode = if (_collapsedWeekMode) CalendarViewMode.WEEK else CalendarViewMode.DAY
    onViewModeChange?.invoke(ViewModeChangeEvent(_renderedMode))
  }

  override fun setCollapsedWeekModeEnabled(enabled: Boolean) { collapsedWeekMode = enabled }
  override fun setMarkers(markers: Array<DayMarkerCompact>) {
    val map = mutableMapOf<Long, IntArray>()
    markers.forEach { m ->
      map[dayStartMs(m.timestampMs).toLong()] = m.dotIndices.map { it.toInt() }.toIntArray()
    }
    _markers = map
  }
  override fun beforeUpdate() {}
  override fun afterUpdate() {}

  // MARK: - Composables

  @Composable
  private fun CalendarRoot() {
    val scope = rememberCoroutineScope()
    // Page count is fixed (month index window); offset is relative to "today" month at index 10_000.
    val pagerState = rememberPagerState(
      initialPage = _pageIndex,
      initialPageOffsetFraction = 0f,
      pageCount = { 20_001 }
    )

    // Derive the displayed month/year from pagerState.currentPage
    val displayedCal = remember(pagerState.currentPage) {
      val offset = pagerState.currentPage - 10_000
      Calendar.getInstance(resolveTimeZone()).apply { add(Calendar.MONTH, offset) }
    }

    // Pager is the source of truth for visible month; keep _pageIndex in sync.
    LaunchedEffect(pagerState.currentPage) {
      _pageIndex = pagerState.currentPage
      if (!_collapsedWeekMode) {
        reconcileWeekIndexFromSelected(pagerState.currentPage)
      } else {
        val tw = weeksInMonthGrid(anchorCalendarForPage(pagerState.currentPage))
        if (tw > 0) {
          _weekIndex = _weekIndex.coerceIn(0, tw - 1)
        }
      }
    }

    // Visible range tracks pager month + week slice (not selection).
    LaunchedEffect(pagerState.currentPage, _collapsedWeekMode, _weekIndex) {
      emitVisibleRange(pagerState.currentPage)
    }

    // Sync _pageIndex → pager when set externally (today, goToMonth, etc.)
    LaunchedEffect(_pageIndex) {
      if (pagerState.currentPage != _pageIndex) {
        pagerState.animateScrollToPage(_pageIndex, animationSpec = tween(300))
      }
    }

    DisposableEffect(Unit) {
      onDispose {
        navigatingWeek.set(false)
      }
    }

    Column(
      modifier = Modifier.fillMaxWidth().background(parseColor(_appearance.backgroundColor))
    ) {
      CalendarHeader(
        displayedCal = displayedCal,
        onChevronPrev = {
          if (_collapsedWeekMode) {
            scope.launch { navigateWeek(pagerState, forward = false) }
          } else {
            scope.launch {
              pagerState.animateScrollToPage(
                pagerState.currentPage + if (_isRTL) 1 else -1,
                animationSpec = tween(300)
              )
            }
          }
        },
        onChevronNext = {
          if (_collapsedWeekMode) {
            scope.launch { navigateWeek(pagerState, forward = true) }
          } else {
            scope.launch {
              pagerState.animateScrollToPage(
                pagerState.currentPage + if (_isRTL) -1 else 1,
                animationSpec = tween(300)
              )
            }
          }
        }
      )

      Crossfade(
        targetState = _renderedMode,
        animationSpec = tween(200),
        modifier = Modifier.fillMaxWidth()
      ) { mode ->
        when (mode) {
          CalendarViewMode.DAY, CalendarViewMode.WEEK -> {
            Column {
              Row(modifier = Modifier.fillMaxWidth()) {
                _strings.weekdayNamesMin.forEach { name ->
                  Text(
                    text = name,
                    modifier = Modifier.weight(1f),
                    fontSize = (_appearance.weekdayFontSize ?: 12.0).sp,
                    color = parseColor(_appearance.weekdayTextColor),
                    textAlign = TextAlign.Center
                  )
                }
              }
              DayGridView(pagerState = pagerState)
              // Collapse toggle button — centered below grid, like legacy design
              val collapseLabel = if (_collapsedWeekMode)
                (_strings.labelShowMonthView ?: "Month view")
              else
                (_strings.labelShowWeekView ?: "Week view")
              TextButton(
                onClick = {
                  if (!_collapsedWeekMode) {
                    // Collapsing: find which week contains the selected date on the current page
                    val currentOffset = pagerState.currentPage - 10_000
                    val nowCal = Calendar.getInstance(resolveTimeZone())
                    val anchor = (nowCal.clone() as Calendar).apply { add(Calendar.MONTH, currentOffset) }.startOfMonth()
                    val items = buildDayItems(anchor)
                    val selectedCal = calendarFor(_selectedTimestampMs)
                    val foundIdx = items.indexOfFirst { sameDay(calendarFor(it.timestampMs), selectedCal) }
                    _weekIndex = if (foundIdx >= 0) foundIdx / 7 else 0
                  }
                  _collapsedWeekMode = !_collapsedWeekMode
                  collapsedWeekMode = _collapsedWeekMode
                  onViewModeChange?.invoke(ViewModeChangeEvent(if (_collapsedWeekMode) CalendarViewMode.WEEK else CalendarViewMode.DAY))
                },
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(
                  text = (if (_collapsedWeekMode) "⌄ " else "⌃ ") + collapseLabel,
                  color = parseColor(_appearance.headerButtonColor),
                  fontSize = 13.sp,
                  textAlign = TextAlign.Center
                )
              }
            }
          }
          CalendarViewMode.MONTH -> MonthPickerView()
          CalendarViewMode.YEAR -> YearPickerView()
        }
      }
    }
  }

  @Composable
  private fun CalendarHeader(
    displayedCal: Calendar,
    onChevronPrev: () -> Unit,
    onChevronNext: () -> Unit
  ) {
    val monthIndex = displayedCal.get(Calendar.MONTH)
    val year = displayedCal.get(Calendar.YEAR)
    val monthNames = _strings.monthNamesFull ?: _strings.monthNamesShort
    val monthName = monthNames.getOrNull(monthIndex) ?: (monthIndex + 1).toString()
    val inPicker = _renderedMode == CalendarViewMode.MONTH || _renderedMode == CalendarViewMode.YEAR

    val onPrev: () -> Unit = if (_renderedMode == CalendarViewMode.YEAR) {
      { _yearSliceStart -= 12 }
    } else onChevronPrev

    val onNext: () -> Unit = if (_renderedMode == CalendarViewMode.YEAR) {
      { _yearSliceStart += 12 }
    } else onChevronNext

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height((_appearance.headerHeight ?: 36.0).dp)
        .background(parseColor(_appearance.headerBackgroundColor ?: _appearance.backgroundColor)),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (_renderedMode != CalendarViewMode.MONTH) {
        TextButton(onClick = onPrev) {
          Text(if (_isRTL) "›" else "‹", color = parseColor(_appearance.headerButtonColor))
        }
      }
      TextButton(onClick = {
        _renderedMode = CalendarViewMode.MONTH
        onViewModeChange?.invoke(ViewModeChangeEvent(CalendarViewMode.MONTH))
      }) {
        Text(monthName, color = parseColor(_appearance.headerTitleColor))
      }
      TextButton(onClick = {
        _yearSliceStart = year - 6
        _renderedMode = CalendarViewMode.YEAR
        onViewModeChange?.invoke(ViewModeChangeEvent(CalendarViewMode.YEAR))
      }) {
        Text(year.toString(), color = parseColor(_appearance.headerTitleColor))
      }
      Spacer(modifier = Modifier.weight(1f))
      TextButton(onClick = {
        if (inPicker) {
          _renderedMode = if (_collapsedWeekMode) CalendarViewMode.WEEK else CalendarViewMode.DAY
          onViewModeChange?.invoke(ViewModeChangeEvent(_renderedMode))
        } else {
          goToToday()
        }
      }) {
        Text(
          if (inPicker) _strings.headerBack else _strings.headerToday,
          color = parseColor(_appearance.headerTodayColor ?: _appearance.headerButtonColor)
        )
      }
      if (!inPicker) {
        // Collapse button moved to below the grid — not in header
      }
      if (_renderedMode != CalendarViewMode.MONTH) {
        TextButton(onClick = onNext) {
          Text(if (_isRTL) "‹" else "›", color = parseColor(_appearance.headerButtonColor))
        }
      }
    }
  }

  @Composable
  private fun DayGridView(pagerState: PagerState) {
    val scope = rememberCoroutineScope()
    var totalDrag by remember { mutableFloatStateOf(0f) }
    val weekSwipeModifier =
      if (_collapsedWeekMode) {
        Modifier.pointerInput(_collapsedWeekMode, _isRTL) {
          detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onHorizontalDrag = { change, dragAmount ->
              change.consume()
              totalDrag += dragAmount
            },
            onDragEnd = {
              val threshold = size.width * 0.2f
              when {
                totalDrag < -threshold -> scope.launch { navigateWeek(pagerState, forward = true) }
                totalDrag > threshold -> scope.launch { navigateWeek(pagerState, forward = false) }
              }
              totalDrag = 0f
            },
            onDragCancel = { totalDrag = 0f }
          )
        }
      } else {
        Modifier
      }

    HorizontalPager(
      state = pagerState,
      userScrollEnabled = !_collapsedWeekMode,
      beyondViewportPageCount = 1,
      modifier = Modifier.fillMaxWidth().then(weekSwipeModifier)
    ) { page ->
      MonthGridPage(monthOffset = page - 10_000)
    }
  }

  @Composable
  private fun MonthGridPage(monthOffset: Int) {
    val nowCal = Calendar.getInstance(resolveTimeZone())
    val anchor = (nowCal.clone() as Calendar).apply { add(Calendar.MONTH, monthOffset) }.startOfMonth()
    val items = buildDayItems(anchor)

    val displayItems = if (_collapsedWeekMode) {
      val safeWeekIdx = _weekIndex.coerceIn(0, (items.size / 7) - 1)
      items.drop(safeWeekIdx * 7).take(7)
    } else {
      items
    }

    LazyVerticalGrid(
      columns = GridCells.Fixed(7),
      modifier = Modifier.fillMaxWidth(),
      userScrollEnabled = false
    ) {
      items(displayItems.size) { i -> DayCellView(item = displayItems[i]) }
    }
  }

  @Composable
  private fun DayCellView(item: DayItemModel) {
    val ap = _appearance
    val textColor = when {
      item.isDisabled -> parseColor(ap.disabledDayTextColor)
      !item.isCurrentMonth -> parseColor(ap.dayOutsideMonthTextColor)  // grey — checked before selected/today
      item.isSelected -> parseColor(ap.selectedDayTextColor)
      item.isToday -> parseColor(ap.todayTextColor)
      else -> parseColor(ap.dayTextColor)
    }
    val rowH = (ap.rowHeight ?: 40.0).dp
    val cornerR = (ap.cornerRadius ?: 8.0).dp

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(rowH)
        .clip(RoundedCornerShape(cornerR))
        .background(
          if (item.isSelected && item.isCurrentMonth) parseColor(ap.selectedDayBackgroundColor)
          else Color.Transparent
        )
        .alpha(if (item.isDisabled) 0.4f else 1f)
        // Grey (out-of-month) days are not tappable — user must swipe to that month first
        .then(
          if (!item.isDisabled && item.isCurrentMonth)
            Modifier.clickable {
              _selectedTimestampMs = item.timestampMs
              selectedTimestampMs = item.timestampMs
              onDateChange?.invoke(DateChangeEvent(item.timestampMs))
            }
          else Modifier
        ),
      contentAlignment = Alignment.Center
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = item.label, color = textColor, fontSize = (ap.fontSizeDay ?: 14.0).sp)
        if (item.dotCount > 0 && item.isCurrentMonth) {
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(item.dotCount) {
              Box(
                modifier = Modifier
                  .size(4.dp)
                  .clip(RoundedCornerShape(2.dp))
                  .background(parseColor(ap.markerAccentColor ?: ap.todayTextColor))
              )
            }
          }
        }
      }
    }
  }

  @Composable
  private fun MonthPickerView() {
    val ap = _appearance
    val rawNames = _strings.monthNamesFull ?: _strings.monthNamesShort
    val names: List<String> = if (rawNames.size >= 12) rawNames.take(12)
                              else (0..11).map { i -> rawNames.getOrNull(i) ?: (i + 1).toString() }
    val currentMonth = calendarFor(_selectedTimestampMs).get(Calendar.MONTH)

    LazyVerticalGrid(
      columns = GridCells.Fixed(3),
      modifier = Modifier.fillMaxWidth().padding(8.dp)
    ) {
      items(names.size) { i ->
        val isSelected = i == currentMonth
        Box(
          modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape((ap.cornerRadius ?: 8.0).dp))
            .background(
              if (isSelected) parseColor(ap.pickerCellSelectedBackgroundColor ?: ap.selectedDayBackgroundColor)
              else parseColor(ap.pickerCellBackgroundColor ?: ap.backgroundColor)
            )
            .clickable { goToMonth(i.toDouble()) }
            .padding(vertical = 10.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = names[i],
            color = if (isSelected) parseColor(ap.pickerCellSelectedTextColor ?: ap.selectedDayTextColor)
                    else parseColor(ap.pickerCellTextColor ?: ap.dayTextColor),
            fontSize = (ap.fontSizeDay ?: 14.0).sp
          )
        }
      }
    }
  }

  @Composable
  private fun YearPickerView() {
    val ap = _appearance
    val currentYear = calendarFor(_selectedTimestampMs).get(Calendar.YEAR)
    val years = (0..11).map { _yearSliceStart + it }

    LazyVerticalGrid(
      columns = GridCells.Fixed(3),
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
        .pointerInput(Unit) {
          var totalDrag = 0f
          detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onDragEnd = {
              if (totalDrag > 50f) _yearSliceStart -= 12
              else if (totalDrag < -50f) _yearSliceStart += 12
            },
            onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount }
          )
        }
    ) {
      items(years.size) { i ->
        val year = years[i]
        val isSelected = year == currentYear
        Box(
          modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape((ap.cornerRadius ?: 8.0).dp))
            .background(
              if (isSelected) parseColor(ap.pickerCellSelectedBackgroundColor ?: ap.selectedDayBackgroundColor)
              else parseColor(ap.pickerCellBackgroundColor ?: ap.backgroundColor)
            )
            .clickable { goToYear(year.toDouble()) }
            .padding(vertical = 10.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = year.toString(),
            color = if (isSelected) parseColor(ap.pickerCellSelectedTextColor ?: ap.selectedDayTextColor)
                    else parseColor(ap.pickerCellTextColor ?: ap.dayTextColor),
            fontSize = (ap.fontSizeDay ?: 14.0).sp
          )
        }
      }
    }
  }

  // MARK: - Helpers
  // Visible state contract: (pager month from pageIndex, _weekIndex, firstDayOfWeek via weekStartsOn only).

  private fun firstDayOfWeekCalendarConstant(): Int =
    weekStartsOn.toInt().coerceIn(0, 6) + 1

  /** Month anchor for a pager page: "today" + offset months, start of month (matches grid / iOS). */
  private fun anchorCalendarForPage(pageIndex: Int): Calendar {
    val offset = pageIndex - 10_000
    return (Calendar.getInstance(resolveTimeZone()) as Calendar).apply {
      add(Calendar.MONTH, offset)
    }.startOfMonth()
  }

  /** Same row count as [buildDayItems] / canonical week grid. */
  private fun weeksInMonthGrid(anchor: Calendar): Int {
    val a = anchor.clone() as Calendar
    a.firstDayOfWeek = firstDayOfWeekCalendarConstant()
    val daysInMonth = a.getActualMaximum(Calendar.DAY_OF_MONTH)
    val leading = (a.get(Calendar.DAY_OF_WEEK) - a.firstDayOfWeek + 7) % 7
    val totalCells = ((leading + daysInMonth + 6) / 7) * 7
    return totalCells / 7
  }

  private fun reconcileWeekIndexFromSelected(pageIndex: Int) {
    val anchor = anchorCalendarForPage(pageIndex)
    val items = buildDayItems(anchor)
    if (items.isEmpty()) return
    val selectedCal = calendarFor(_selectedTimestampMs)
    val foundIdx = items.indexOfFirst { sameDay(calendarFor(it.timestampMs), selectedCal) }
    val maxW = max(0, items.size / 7 - 1)
    _weekIndex = (if (foundIdx >= 0) foundIdx / 7 else 0).coerceIn(0, maxW)
  }

  private fun syncWeekIndexToSelectionForPage(pageIndex: Int) {
    reconcileWeekIndexFromSelected(pageIndex)
  }

  private suspend fun navigateWeek(pagerState: PagerState, forward: Boolean) {
    if (!navigatingWeek.compareAndSet(false, true)) return
    try {
      val currentPage = pagerState.currentPage
      val anchor = anchorCalendarForPage(currentPage)
      var tw = weeksInMonthGrid(anchor).coerceAtLeast(1)
      _weekIndex = _weekIndex.coerceIn(0, tw - 1)

      val pageDeltaNext = if (_isRTL) -1 else 1
      val pageDeltaPrev = if (_isRTL) 1 else -1

      if (forward) {
        if (_weekIndex < tw - 1) {
          _weekIndex++
        } else {
          pagerState.scrollToPage(currentPage + pageDeltaNext)
          val na = anchorCalendarForPage(pagerState.currentPage)
          tw = weeksInMonthGrid(na).coerceAtLeast(1)
          _weekIndex = 0.coerceAtMost(tw - 1)
        }
      } else {
        if (_weekIndex > 0) {
          _weekIndex--
        } else {
          pagerState.scrollToPage(currentPage + pageDeltaPrev)
          val na = anchorCalendarForPage(pagerState.currentPage)
          tw = weeksInMonthGrid(na).coerceAtLeast(1)
          _weekIndex = max(0, tw - 1)
        }
      }
    } finally {
      navigatingWeek.set(false)
    }
  }

  private fun buildDayItems(anchor: Calendar): List<DayItemModel> {
    val items = mutableListOf<DayItemModel>()
    val firstDay = (weekStartsOn.toInt().coerceIn(0, 6) + 1)
    anchor.firstDayOfWeek = firstDay
    val daysInMonth = anchor.getActualMaximum(Calendar.DAY_OF_MONTH)
    val leading = (anchor.get(Calendar.DAY_OF_WEEK) - anchor.firstDayOfWeek + 7) % 7
    val cursor = anchor.clone() as Calendar
    cursor.add(Calendar.DAY_OF_MONTH, -leading)
    val count = ((leading + daysInMonth + 6) / 7) * 7
    val todayCal = Calendar.getInstance(resolveTimeZone())
    val selectedCal = calendarFor(_selectedTimestampMs)

    // isCurrentMonth is always relative to the page anchor month (the displayed month),
    // same in both week and month view. Grey days are those outside the anchor month.
    // Tapping a grey day in week view just selects it — it stays grey (same as legacy).
    repeat(count) {
      val ts = cursor.timeInMillis.toDouble()
      val inMonth = cursor.get(Calendar.MONTH) == anchor.get(Calendar.MONTH)
                 && cursor.get(Calendar.YEAR) == anchor.get(Calendar.YEAR)
      val isToday = sameDay(cursor, todayCal)
      val isSelected = sameDay(cursor, selectedCal)
      val isDisabled = (_minTimestampMs?.let { ts < it } ?: false) || (_maxTimestampMs?.let { ts > it } ?: false)
      val dayKey = dayStartMs(ts).toLong()
      val dots = min(3, _markers[dayKey]?.size ?: 0)
      items.add(DayItemModel(ts, cursor.get(Calendar.DAY_OF_MONTH).toString(), inMonth, isToday, isSelected, isDisabled, dots))
      cursor.add(Calendar.DAY_OF_MONTH, 1)
    }
    return items
  }

  private fun emitVisibleRange(pageIndex: Int) {
    val anchor = anchorCalendarForPage(pageIndex)
    if (_collapsedWeekMode) {
      val items = buildDayItems(anchor)
      if (items.isEmpty()) return
      val tw = max(1, items.size / 7)
      val idx = _weekIndex.coerceIn(0, tw - 1)
      val startMs = items[idx * 7].timestampMs
      val endMs = items[idx * 7 + 6].timestampMs
      val endCal = calendarFor(endMs).apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
      }
      onVisibleRangeChange?.invoke(
        VisibleRangeChangeEvent(startMs, endCal.timeInMillis.toDouble())
      )
    } else {
      val start = anchor.timeInMillis.toDouble()
      val endCal = (anchor.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
      }
      onVisibleRangeChange?.invoke(
        VisibleRangeChangeEvent(start, endCal.timeInMillis.toDouble())
      )
    }
  }

  private fun calendarFor(ms: Double): Calendar =
    Calendar.getInstance(resolveTimeZone()).apply {
      firstDayOfWeek = weekStartsOn.toInt().coerceIn(0, 6) + 1
      timeInMillis = ms.toLong()
    }

  private fun dayStartMs(ms: Double): Double {
    val cal = calendarFor(ms)
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis.toDouble()
  }

  private fun sameDay(a: Calendar, b: Calendar) =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

  private fun resolveTimeZone(): TimeZone =
    if (!timeZoneId.isNullOrBlank()) TimeZone.getTimeZone(timeZoneId) else TimeZone.getDefault()

  private fun parseColor(hex: String): Color {
    return try { Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")) }
    catch (_: Throwable) { Color.Black }
  }

  private fun Calendar.startOfMonth(): Calendar {
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    return this
  }

  companion object {
    fun defaultStrings() = CalendarStrings(
      monthNamesShort = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"),
      monthNamesFull = null,
      weekdayNamesMin = arrayOf("Mo","Tu","We","Th","Fr","Sa","Su"),
      headerToday = "Today", headerBack = "Back",
      labelShowWeekView = null, labelShowMonthView = null,
      accessibilityPrev = null, accessibilityNext = null
    )

    fun defaultAppearance() = CalendarAppearance(
      backgroundColor = "#FFFFFF", separatorColor = null, headerBackgroundColor = null,
      headerTitleColor = "#111827", headerSubtitleColor = null, headerButtonColor = "#2563EB",
      headerTodayColor = null, weekdayTextColor = "#6B7280", weekdayFontSize = 12.0,
      weekdayFontWeight = null, dayTextColor = "#111827", dayOutsideMonthTextColor = "#9CA3AF",
      selectedDayBackgroundColor = "#2563EB", selectedDayTextColor = "#FFFFFF",
      todayTextColor = "#2563EB", todayIndicatorColor = null, disabledDayTextColor = "#D1D5DB",
      pickerCellBackgroundColor = null, pickerCellSelectedBackgroundColor = null,
      pickerCellTextColor = null, pickerCellSelectedTextColor = null, fontFamily = null,
      fontSizeDay = 14.0, fontSizeHeader = 14.0, fontWeight = null, dayCellSize = null,
      rowHeight = 40.0, headerHeight = 36.0, spacing = 0.0, cornerRadius = 8.0,
      borderColor = null, borderWidth = null, markerPalette = arrayOf("#2563EB"), markerAccentColor = null
    )
  }
}

private data class DayItemModel(
  val timestampMs: Double,
  val label: String,
  val isCurrentMonth: Boolean,
  val isToday: Boolean,
  val isSelected: Boolean,
  val isDisabled: Boolean,
  val dotCount: Int
)
