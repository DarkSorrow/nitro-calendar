package com.margelo.nitro.novasteraoss.nitrocalendar

import android.graphics.Color
import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@DoNotStrip
class HybridNitroCalendar(private val context: ThemedReactContext) : HybridNitroCalendarSpec() {

  private val root = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
  }
  private val header = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
  }
  private val prevButton = Button(context)
  private val monthButton = Button(context)
  private val yearButton = Button(context)
  private val todayBackButton = Button(context)
  private val nextButton = Button(context)
  private val collapseButton = Button(context)
  private val weekdayRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
  private val recycler = RecyclerView(context)
  private val dayLayoutManager = GridLayoutManager(context, 7)
  private val pickerLayoutManager = GridLayoutManager(context, 3)

  override val view: View = root

  private val dayAdapter = DayAdapter(::onDayCellPressed)
  private val pickerAdapter = PickerAdapter(::onPickerCellPressed)

  private var renderedMode: CalendarViewMode = CalendarViewMode.DAY
  private var selectedCalendar = Calendar.getInstance(TimeZone.getDefault()).apply { firstDayOfWeek = Calendar.MONDAY }
  private var displayedMonthAnchor = (selectedCalendar.clone() as Calendar).startOfMonth()
  private var yearSliceStart: Int = selectedCalendar.get(Calendar.YEAR) - 6
  private var dayItems: List<DayItem> = emptyList()
  private var pickerItems: List<String> = emptyList()
  private var markersByDayStart = mutableMapOf<Long, IntArray>()
  private var lastVisibleRange: Pair<Double, Double>? = null
  private var touchStartX = 0f
  private var isBatchUpdating = false
  private var pendingRefresh = false
  private var refreshScheduled = false

  override var selectedTimestampMs: Double = System.currentTimeMillis().toDouble()
    set(value) {
      field = value
      selectedCalendar = calendarFor(value)
      if (renderedMode == CalendarViewMode.DAY || renderedMode == CalendarViewMode.WEEK) {
        displayedMonthAnchor = (selectedCalendar.clone() as Calendar).startOfMonth()
      }
      requestRefresh()
    }

  override var initialTimestampMs: Double? = null
  override var calendarType: CalendarType = CalendarType.GREGORIAN
  override var isRTL: Boolean = false
    set(value) {
      field = value
      refreshHeader()
    }
  override var timeZoneId: String? = null
    set(value) {
      field = value
      requestRefresh()
    }
  override var viewMode: CalendarViewMode = CalendarViewMode.DAY
    set(value) {
      field = value
      applyViewMode(value, true)
    }
  override var collapsedWeekMode: Boolean = false
    set(value) {
      field = value
      applyCollapsedMode(true)
    }
  override var weekStartsOn: Double = 1.0
    set(value) {
      field = value
      requestRefresh()
    }
  override var uses24HourClock: Boolean? = null
  override var localeId: String? = null
  override var appearance: CalendarAppearance = defaultAppearance()
    set(value) {
      field = value
      applyAppearance()
    }
  override var appearanceKey: String? = null
  override var strings: CalendarStrings = defaultStrings()
    set(value) {
      field = value
      refreshWeekdayRow()
      requestRefresh()
    }
  override var stringsKey: String? = null
  override var minTimestampMs: Double? = null
    set(value) {
      field = value
      requestRefresh()
    }
  override var maxTimestampMs: Double? = null
    set(value) {
      field = value
      requestRefresh()
    }
  override var onDateChange: ((event: DateChangeEvent) -> Unit)? = null
  override var onVisibleRangeChange: ((event: VisibleRangeChangeEvent) -> Unit)? = null
  override var onViewModeChange: ((event: ViewModeChangeEvent) -> Unit)? = null

  init {
    setupView()
    applyAppearance()
    refreshWeekdayRow()
    requestRefresh()
  }

  override fun goToToday() {
    val now = Calendar.getInstance(resolveTimeZone())
    selectedCalendar = now
    selectedTimestampMs = now.timeInMillis.toDouble()
    displayedMonthAnchor = (now.clone() as Calendar).startOfMonth()
    if (renderedMode == CalendarViewMode.MONTH || renderedMode == CalendarViewMode.YEAR) {
      applyViewMode(if (collapsedWeekMode) CalendarViewMode.WEEK else CalendarViewMode.DAY, true)
    }
    requestRefresh()
  }

  override fun goToMonth(monthIndex: Double) {
    val month = min(11, max(0, monthIndex.toInt()))
    val updated = selectedCalendar.clone() as Calendar
    updated.set(Calendar.MONTH, month)
    updated.set(Calendar.DAY_OF_MONTH, min(28, updated.get(Calendar.DAY_OF_MONTH)))
    selectedCalendar = updated
    selectedTimestampMs = updated.timeInMillis.toDouble()
    displayedMonthAnchor = (updated.clone() as Calendar).startOfMonth()
    applyViewMode(if (collapsedWeekMode) CalendarViewMode.WEEK else CalendarViewMode.DAY, true)
  }

  override fun goToYear(year: Double) {
    val updated = selectedCalendar.clone() as Calendar
    updated.set(Calendar.YEAR, year.toInt())
    updated.set(Calendar.DAY_OF_MONTH, min(28, updated.get(Calendar.DAY_OF_MONTH)))
    selectedCalendar = updated
    selectedTimestampMs = updated.timeInMillis.toDouble()
    displayedMonthAnchor = (updated.clone() as Calendar).startOfMonth()
    yearSliceStart = updated.get(Calendar.YEAR) - 6
    applyViewMode(if (collapsedWeekMode) CalendarViewMode.WEEK else CalendarViewMode.DAY, true)
  }

  override fun setCollapsedWeekModeEnabled(enabled: Boolean) {
    collapsedWeekMode = enabled
  }

  override fun setMarkers(markers: Array<DayMarkerCompact>) {
    markersByDayStart.clear()
    markers.forEach { marker ->
      val dayKey = dayStartMs(marker.timestampMs).toLong()
      markersByDayStart[dayKey] = marker.dotIndices.map { it.toInt() }.toIntArray()
    }
    requestRefresh()
  }

  override fun beforeUpdate() {
    isBatchUpdating = true
  }

  override fun afterUpdate() {
    isBatchUpdating = false
    if (pendingRefresh) {
      pendingRefresh = false
      requestRefresh()
    }
  }

  private fun setupView() {
    root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    root.addView(weekdayRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24.dp()))
    root.addView(recycler, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

    listOf(prevButton, monthButton, yearButton, todayBackButton, nextButton, collapseButton).forEach {
      it.isAllCaps = false
      header.addView(it)
    }

    prevButton.text = "‹"
    nextButton.text = "›"
    collapseButton.text = "⌃"

    prevButton.setOnClickListener { shiftTimeline(forward = isRTL) }
    nextButton.setOnClickListener { shiftTimeline(forward = !isRTL) }
    monthButton.setOnClickListener { applyViewMode(CalendarViewMode.MONTH, true) }
    yearButton.setOnClickListener {
      yearSliceStart = displayedMonthAnchor.get(Calendar.YEAR) - 6
      applyViewMode(CalendarViewMode.YEAR, true)
    }
    todayBackButton.setOnClickListener {
      if (renderedMode == CalendarViewMode.MONTH || renderedMode == CalendarViewMode.YEAR) {
        applyViewMode(if (collapsedWeekMode) CalendarViewMode.WEEK else CalendarViewMode.DAY, true)
      } else {
        goToToday()
      }
    }
    collapseButton.setOnClickListener { collapsedWeekMode = !collapsedWeekMode }

    recycler.layoutManager = dayLayoutManager
    recycler.adapter = dayAdapter
    recycler.itemAnimator = null
    recycler.setHasFixedSize(true)
    recycler.overScrollMode = View.OVER_SCROLL_NEVER
    recycler.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> touchStartX = event.x
        MotionEvent.ACTION_UP -> {
          val dx = event.x - touchStartX
          if (abs(dx) > 20.dp().toFloat()) {
            val swipeForward = dx < 0
            shiftTimeline(forward = if (isRTL) !swipeForward else swipeForward)
          }
        }
      }
      false
    }

    repeat(7) {
      val label = TextView(context)
      label.gravity = Gravity.CENTER
      weekdayRow.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    }
  }

  private fun applyAppearance() {
    root.setBackgroundColor(parseColorSafe(appearance.backgroundColor))
    val headerColor = appearance.headerBackgroundColor ?: appearance.backgroundColor
    header.setBackgroundColor(parseColorSafe(headerColor))
    val buttonColor = parseColorSafe(appearance.headerButtonColor)
    listOf(prevButton, nextButton, collapseButton, todayBackButton).forEach { it.setTextColor(buttonColor) }
    monthButton.setTextColor(parseColorSafe(appearance.headerTitleColor))
    yearButton.setTextColor(parseColorSafe(appearance.headerTitleColor))
    refreshWeekdayRow()
    requestRefresh()
  }

  private fun refreshWeekdayRow() {
    weekdayRow.visibility = if (renderedMode == CalendarViewMode.MONTH || renderedMode == CalendarViewMode.YEAR) View.GONE else View.VISIBLE
    val names = strings.weekdayNamesMin
    weekdayRow.childrenIndexed().forEach { (index, view) ->
      (view as? TextView)?.apply {
        text = if (names.isEmpty()) "" else names[index % names.size]
        setTextColor(parseColorSafe(appearance.weekdayTextColor))
        textSize = (appearance.weekdayFontSize ?: 12.0).toFloat()
      }
    }
  }

  private fun requestRefresh() {
    if (isBatchUpdating) {
      pendingRefresh = true
      return
    }
    if (refreshScheduled) {
      return
    }
    refreshScheduled = true
    recycler.post {
      refreshScheduled = false
      refreshUi()
    }
  }

  private fun refreshUi() {
    val firstDay = (weekStartsOn.toInt().coerceIn(0, 6) + 1)
    selectedCalendar.firstDayOfWeek = firstDay
    displayedMonthAnchor.firstDayOfWeek = firstDay
    rebuildData()
    refreshHeader()
    if (renderedMode == CalendarViewMode.MONTH || renderedMode == CalendarViewMode.YEAR) {
      if (recycler.layoutManager !== pickerLayoutManager) {
        recycler.layoutManager = pickerLayoutManager
      }
      if (recycler.adapter !== pickerAdapter) {
        recycler.adapter = pickerAdapter
      }
      pickerAdapter.submitItems(pickerItems, renderedMode, selectedCalendar.get(Calendar.YEAR), selectedCalendar.get(Calendar.MONTH))
    } else {
      if (recycler.layoutManager !== dayLayoutManager) {
        recycler.layoutManager = dayLayoutManager
      }
      if (recycler.adapter !== dayAdapter) {
        recycler.adapter = dayAdapter
      }
      dayAdapter.submitItems(dayItems, appearance, strings)
      emitVisibleRangeIfNeeded()
    }
    refreshWeekdayRow()
  }

  private fun refreshHeader() {
    val monthIndex = displayedMonthAnchor.get(Calendar.MONTH)
    val monthNames = strings.monthNamesFull ?: strings.monthNamesShort
    monthButton.text = monthNames.getOrNull(monthIndex) ?: (monthIndex + 1).toString()
    yearButton.text = displayedMonthAnchor.get(Calendar.YEAR).toString()
    val inPicker = renderedMode == CalendarViewMode.MONTH || renderedMode == CalendarViewMode.YEAR
    todayBackButton.text = if (inPicker) strings.headerBack else strings.headerToday
    prevButton.visibility = if (renderedMode == CalendarViewMode.MONTH) View.GONE else View.VISIBLE
    nextButton.visibility = if (renderedMode == CalendarViewMode.MONTH) View.GONE else View.VISIBLE
    collapseButton.text = if (renderedMode == CalendarViewMode.WEEK) "⌄" else "⌃"
  }

  private fun applyViewMode(mode: CalendarViewMode, emit: Boolean) {
    renderedMode = mode
    requestRefresh()
    if (emit) {
      onViewModeChange?.invoke(ViewModeChangeEvent(mode))
    }
  }

  private fun applyCollapsedMode(emit: Boolean) {
    if (renderedMode != CalendarViewMode.MONTH && renderedMode != CalendarViewMode.YEAR) {
      renderedMode = if (collapsedWeekMode) CalendarViewMode.WEEK else CalendarViewMode.DAY
      requestRefresh()
      if (emit) {
        onViewModeChange?.invoke(ViewModeChangeEvent(renderedMode))
      }
    }
  }

  private fun rebuildData() {
    when (renderedMode) {
      CalendarViewMode.MONTH -> {
        val source = strings.monthNamesFull ?: strings.monthNamesShort
        pickerItems = if (source.size >= 12) source.take(12) else (0..11).map { source.getOrNull(it) ?: (it + 1).toString() }
      }
      CalendarViewMode.YEAR -> {
        pickerItems = (0..11).map { (yearSliceStart + it).toString() }
      }
      CalendarViewMode.WEEK -> {
        dayItems = buildWeekItems()
      }
      CalendarViewMode.DAY -> {
        dayItems = buildMonthItems()
      }
    }
  }

  private fun buildMonthItems(): List<DayItem> {
    val items = mutableListOf<DayItem>()
    val monthStart = (displayedMonthAnchor.clone() as Calendar).startOfMonth()
    val daysInMonth = monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
    val leading = (monthStart.get(Calendar.DAY_OF_WEEK) - monthStart.firstDayOfWeek + 7) % 7
    val cursor = monthStart.clone() as Calendar
    cursor.add(Calendar.DAY_OF_MONTH, -leading)
    val count = ((leading + daysInMonth + 6) / 7) * 7
    repeat(count) {
      items.add(buildDayItem(cursor))
      cursor.add(Calendar.DAY_OF_MONTH, 1)
    }
    return items
  }

  private fun buildWeekItems(): List<DayItem> {
    val items = mutableListOf<DayItem>()
    val weekStart = (selectedCalendar.clone() as Calendar).apply {
      set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
    }
    repeat(7) {
      items.add(buildDayItem(weekStart))
      weekStart.add(Calendar.DAY_OF_MONTH, 1)
    }
    return items
  }

  private fun buildDayItem(cal: Calendar): DayItem {
    val timestamp = cal.timeInMillis.toDouble()
    val selected = sameDay(cal, selectedCalendar)
    val today = sameDay(cal, Calendar.getInstance(resolveTimeZone()))
    val currentMonth = cal.get(Calendar.MONTH) == displayedMonthAnchor.get(Calendar.MONTH)
    val disabled = isOutOfRange(timestamp)
    val dots = markersByDayStart[dayStartMs(timestamp).toLong()]?.size ?: 0
    return DayItem(
      timestampMs = timestamp,
      dayLabel = cal.get(Calendar.DAY_OF_MONTH).toString(),
      isSelected = selected,
      isToday = today,
      isCurrentMonth = currentMonth || renderedMode == CalendarViewMode.WEEK,
      isDisabled = disabled,
      dotCount = min(3, dots)
    )
  }

  private fun onDayCellPressed(item: DayItem) {
    if (item.isDisabled) return
    selectedTimestampMs = item.timestampMs
    selectedCalendar = calendarFor(item.timestampMs)
    if (renderedMode == CalendarViewMode.DAY) {
      displayedMonthAnchor = (selectedCalendar.clone() as Calendar).startOfMonth()
    }
    onDateChange?.invoke(DateChangeEvent(item.timestampMs))
    requestRefresh()
  }

  private fun onPickerCellPressed(value: String, index: Int) {
    if (renderedMode == CalendarViewMode.MONTH) {
      goToMonth(index.toDouble())
      return
    }
    if (renderedMode == CalendarViewMode.YEAR) {
      goToYear((value.toIntOrNull() ?: selectedCalendar.get(Calendar.YEAR)).toDouble())
    }
  }

  private fun shiftTimeline(forward: Boolean) {
    val delta = if (forward) 1 else -1
    when (renderedMode) {
      CalendarViewMode.YEAR -> yearSliceStart += 12 * delta
      CalendarViewMode.WEEK -> {
        selectedCalendar.add(Calendar.WEEK_OF_YEAR, delta)
        selectedTimestampMs = selectedCalendar.timeInMillis.toDouble()
        displayedMonthAnchor = (selectedCalendar.clone() as Calendar).startOfMonth()
      }
      else -> {
        displayedMonthAnchor.add(Calendar.MONTH, delta)
        displayedMonthAnchor = (displayedMonthAnchor.clone() as Calendar).startOfMonth()
      }
    }
    requestRefresh()
  }

  private fun emitVisibleRangeIfNeeded() {
    val prev = (displayedMonthAnchor.clone() as Calendar).apply { add(Calendar.MONTH, -1); set(Calendar.DAY_OF_MONTH, 1) }
    val nextBoundary = (displayedMonthAnchor.clone() as Calendar).apply {
      add(Calendar.MONTH, 2)
      set(Calendar.DAY_OF_MONTH, 1)
      add(Calendar.MILLISECOND, -1)
    }
    val pair = prev.timeInMillis.toDouble() to nextBoundary.timeInMillis.toDouble()
    val previous = lastVisibleRange
    if (previous != null && abs(previous.first - pair.first) < 1.0 && abs(previous.second - pair.second) < 1.0) {
      return
    }
    lastVisibleRange = pair
    onVisibleRangeChange?.invoke(VisibleRangeChangeEvent(pair.first, pair.second))
  }

  private fun calendarFor(timestampMs: Double): Calendar {
    return Calendar.getInstance(resolveTimeZone()).apply {
      firstDayOfWeek = weekStartsOn.toInt().coerceIn(0, 6) + 1
      timeInMillis = timestampMs.toLong()
    }
  }

  private fun dayStartMs(timestampMs: Double): Double {
    val cal = calendarFor(timestampMs)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis.toDouble()
  }

  private fun isOutOfRange(timestampMs: Double): Boolean {
    if (minTimestampMs != null && timestampMs < minTimestampMs!!) return true
    if (maxTimestampMs != null && timestampMs > maxTimestampMs!!) return true
    return false
  }

  private fun sameDay(first: Calendar, second: Calendar): Boolean {
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
      first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
  }

  private fun resolveTimeZone(): TimeZone {
    val id = timeZoneId
    return if (!id.isNullOrBlank()) TimeZone.getTimeZone(id) else TimeZone.getDefault()
  }

  private fun parseColorSafe(value: String): Int {
    return try {
      value.toColorInt()
    } catch (_: Throwable) {
      Color.BLACK
    }
  }

  private fun Int.dp(): Int {
    return floor(this * context.resources.displayMetrics.density).toInt()
  }

  private fun LinearLayout.childrenIndexed(): List<Pair<Int, View>> {
    val list = mutableListOf<Pair<Int, View>>()
    for (index in 0 until childCount) {
      list.add(index to getChildAt(index))
    }
    return list
  }

  companion object {
    private fun defaultStrings(): CalendarStrings {
      return CalendarStrings(
        monthNamesShort = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
        monthNamesFull = null,
        weekdayNamesMin = arrayOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"),
        headerToday = "Today",
        headerBack = "Back",
        labelShowWeekView = null,
        labelShowMonthView = null,
        accessibilityPrev = null,
        accessibilityNext = null
      )
    }

    private fun defaultAppearance(): CalendarAppearance {
      return CalendarAppearance(
        backgroundColor = "#FFFFFF",
        separatorColor = null,
        headerBackgroundColor = null,
        headerTitleColor = "#111827",
        headerSubtitleColor = null,
        headerButtonColor = "#2563EB",
        headerTodayColor = null,
        weekdayTextColor = "#6B7280",
        weekdayFontSize = 12.0,
        weekdayFontWeight = null,
        dayTextColor = "#111827",
        dayOutsideMonthTextColor = "#9CA3AF",
        selectedDayBackgroundColor = "#2563EB",
        selectedDayTextColor = "#FFFFFF",
        todayTextColor = "#2563EB",
        todayIndicatorColor = null,
        disabledDayTextColor = "#D1D5DB",
        pickerCellBackgroundColor = null,
        pickerCellSelectedBackgroundColor = null,
        pickerCellTextColor = null,
        pickerCellSelectedTextColor = null,
        fontFamily = null,
        fontSizeDay = 14.0,
        fontSizeHeader = 14.0,
        fontWeight = null,
        dayCellSize = null,
        rowHeight = 40.0,
        headerHeight = 36.0,
        spacing = 0.0,
        cornerRadius = 8.0,
        borderColor = null,
        borderWidth = null,
        markerPalette = arrayOf("#2563EB"),
        markerAccentColor = null
      )
    }
  }
}

private data class DayItem(
  val timestampMs: Double,
  val dayLabel: String,
  val isSelected: Boolean,
  val isToday: Boolean,
  val isCurrentMonth: Boolean,
  val isDisabled: Boolean,
  val dotCount: Int
)

private class DayAdapter(
  private val onPress: (DayItem) -> Unit
) : RecyclerView.Adapter<DayViewHolder>() {
  private var items: List<DayItem> = emptyList()
  private var appearance: CalendarAppearance? = null

  fun submitItems(items: List<DayItem>, appearance: CalendarAppearance, strings: CalendarStrings) {
    this.items = items
    this.appearance = appearance
    notifyDataSetChanged()
  }

  override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DayViewHolder {
    return DayViewHolder.create(parent)
  }

  override fun getItemCount(): Int = items.size

  override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
    val item = items[position]
    holder.bind(item, appearance ?: return)
    holder.itemView.setOnClickListener { onPress(item) }
  }
}

private class DayViewHolder private constructor(view: View) : RecyclerView.ViewHolder(view) {
  private val container = view as LinearLayout
  private val title = container.getChildAt(0) as TextView
  private val dotsRow = container.getChildAt(1) as LinearLayout

  fun bind(item: DayItem, appearance: CalendarAppearance) {
    val textColor = when {
      item.isDisabled -> appearance.disabledDayTextColor
      item.isSelected -> appearance.selectedDayTextColor
      item.isToday -> appearance.todayTextColor
      !item.isCurrentMonth -> appearance.dayOutsideMonthTextColor
      else -> appearance.dayTextColor
    }
    title.text = item.dayLabel
    title.setTextColor(textColor.toColorInt())
    itemView.setBackgroundColor(if (item.isSelected) appearance.selectedDayBackgroundColor.toColorInt() else Color.TRANSPARENT)
    dotsRow.removeAllViews()
    repeat(item.dotCount) {
      val dot = View(itemView.context)
      val size = 4 * itemView.context.resources.displayMetrics.density
      val params = LinearLayout.LayoutParams(size.toInt(), size.toInt())
      params.marginEnd = 2
      dot.layoutParams = params
      dot.setBackgroundColor((appearance.markerAccentColor ?: appearance.todayTextColor).toColorInt())
      dotsRow.addView(dot)
    }
  }

  companion object {
    fun create(parent: android.view.ViewGroup): DayViewHolder {
      val context = parent.context
      val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = (40 * context.resources.displayMetrics.density).toInt()
      }
      val title = TextView(context).apply { gravity = Gravity.CENTER }
      val dots = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
      }
      column.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
      column.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
      return DayViewHolder(column)
    }
  }
}

private class PickerAdapter(
  private val onPress: (String, Int) -> Unit
) : RecyclerView.Adapter<PickerViewHolder>() {
  private var values: List<String> = emptyList()
  private var mode: CalendarViewMode = CalendarViewMode.MONTH
  private var selectedYear: Int = 0
  private var selectedMonth: Int = 0

  fun submitItems(values: List<String>, mode: CalendarViewMode, selectedYear: Int, selectedMonth: Int) {
    this.values = values
    this.mode = mode
    this.selectedYear = selectedYear
    this.selectedMonth = selectedMonth
    notifyDataSetChanged()
  }

  override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PickerViewHolder {
    return PickerViewHolder(TextView(parent.context).apply {
      gravity = Gravity.CENTER
      minHeight = (44 * resources.displayMetrics.density).toInt()
    })
  }

  override fun getItemCount(): Int = values.size

  override fun onBindViewHolder(holder: PickerViewHolder, position: Int) {
    val value = values[position]
    val selected = when (mode) {
      CalendarViewMode.MONTH -> position == selectedMonth
      CalendarViewMode.YEAR -> value.toIntOrNull() == selectedYear
      else -> false
    }
    holder.bind(value, selected)
    holder.itemView.setOnClickListener { onPress(value, position) }
  }
}

private class PickerViewHolder(private val label: TextView) : RecyclerView.ViewHolder(label) {
  fun bind(value: String, selected: Boolean) {
    label.text = value
    label.setBackgroundColor(if (selected) "#2563EB".toColorInt() else Color.TRANSPARENT)
    label.setTextColor(if (selected) Color.WHITE else "#111827".toColorInt())
  }
}

private fun Calendar.startOfMonth(): Calendar {
  set(Calendar.DAY_OF_MONTH, 1)
  set(Calendar.HOUR_OF_DAY, 0)
  set(Calendar.MINUTE, 0)
  set(Calendar.SECOND, 0)
  set(Calendar.MILLISECOND, 0)
  return this
}
