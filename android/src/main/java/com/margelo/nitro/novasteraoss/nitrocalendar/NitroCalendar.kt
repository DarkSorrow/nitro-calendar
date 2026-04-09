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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    _weekIndex = 0  // today is always in the first visible week when we reset to today's month
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
    val pagerState = rememberPagerState(initialPage = _pageIndex) { 20_001 }

    // Derive the displayed month/year from pagerState.currentPage
    val displayedCal = remember(pagerState.currentPage) {
      val offset = pagerState.currentPage - 10_000
      Calendar.getInstance(resolveTimeZone()).apply { add(Calendar.MONTH, offset) }
    }

    // Sync pager → _pageIndex when user swipes
    LaunchedEffect(pagerState.currentPage) {
      if (_pageIndex != pagerState.currentPage) {
        _pageIndex = pagerState.currentPage
        emitVisibleRange(pagerState.currentPage)
      }
    }

    // Sync _pageIndex → pager when set externally (today button, greyed day tap, goToMonth etc.)
    LaunchedEffect(_pageIndex) {
      if (pagerState.currentPage != _pageIndex) {
        pagerState.animateScrollToPage(_pageIndex, animationSpec = tween(300))
      }
    }

    Column(
      modifier = Modifier.fillMaxWidth().background(parseColor(_appearance.backgroundColor))
    ) {
      CalendarHeader(
        displayedCal = displayedCal,
        onChevronPrev = {
          if (_collapsedWeekMode) {
            if (_weekIndex > 0) {
              _weekIndex--
            } else {
              // At week 0 — go to previous month, set weekIndex to its last week
              val prevPage = pagerState.currentPage + if (_isRTL) 1 else -1
              val prevOffset = prevPage - 10_000
              val nowCal = Calendar.getInstance(resolveTimeZone())
              val prevAnchor = (nowCal.clone() as Calendar).apply { add(Calendar.MONTH, prevOffset) }.startOfMonth()
              val prevItems = buildDayItems(prevAnchor)
              _weekIndex = (prevItems.size / 7) - 1
              scope.launch { pagerState.animateScrollToPage(prevPage, animationSpec = tween(300)) }
            }
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
            val currentOffset = pagerState.currentPage - 10_000
            val nowCal = Calendar.getInstance(resolveTimeZone())
            val anchor = (nowCal.clone() as Calendar).apply { add(Calendar.MONTH, currentOffset) }.startOfMonth()
            val items = buildDayItems(anchor)
            val totalWeeks = items.size / 7
            if (_weekIndex < totalWeeks - 1) {
              _weekIndex++
            } else {
              // At last week — go to next month, week 0
              _weekIndex = 0
              scope.launch {
                pagerState.animateScrollToPage(
                  pagerState.currentPage + if (_isRTL) -1 else 1,
                  animationSpec = tween(300)
                )
              }
            }
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
  private fun DayGridView(pagerState: androidx.compose.foundation.pager.PagerState) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
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
    val offset = pageIndex - 10_000
    val anchor = calendarFor(_selectedTimestampMs).apply { add(Calendar.MONTH, offset) }.startOfMonth()
    val prev = (anchor.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
    val nextBoundary = (anchor.clone() as Calendar).apply {
      add(Calendar.MONTH, 2); add(Calendar.MILLISECOND, -1)
    }
    onVisibleRangeChange?.invoke(VisibleRangeChangeEvent(prev.timeInMillis.toDouble(), nextBoundary.timeInMillis.toDouble()))
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
