import Foundation
import UIKit
import NitroModules

class HybridNitroCalendar: HybridNitroCalendarSpec {
  var view: UIView

  private let calendarView = CalendarRootView()
  private let headerContainer = UIStackView()
  private let leftButton = UIButton(type: .system)
  private let monthButton = UIButton(type: .system)
  private let yearButton = UIButton(type: .system)
  private let centerSpacer = UIView()
  private let todayBackButton = UIButton(type: .system)
  private let rightButton = UIButton(type: .system)
  private let collapseButton = UIButton(type: .system)
  private let weekdayStack = UIStackView()
  private let layout = UICollectionViewFlowLayout()
  private lazy var collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
  private lazy var coordinator = CalendarCoordinator(owner: self)
  private var collectionMinHeightConstraint: NSLayoutConstraint?

  private var renderedMode: CalendarViewMode = .day
  private var selectedDate = Date()
  private var displayedMonthAnchor = Date()
  private var yearSliceStart = 2000
  private var dayItems: [DayItem] = []
  private var pickerItems: [String] = []
  private var markerByDayStartMs: [Int64: [Int]] = [:]
  private var lastVisibleRange: (Double, Double)?
  private var isBatchUpdating = false
  private var pendingRefresh = false
  private var refreshScheduled = false
  private var lastLaidOutSize: CGSize = .zero
  private var calendarEngine: Calendar = {
    var calendar = Calendar(identifier: .gregorian)
    calendar.firstWeekday = 2
    return calendar
  }()

  var selectedTimestampMs: Double = Date().timeIntervalSince1970 * 1000 {
    didSet {
      selectedDate = dateFromMs(selectedTimestampMs)
      if renderedMode == .day || renderedMode == .week {
        displayedMonthAnchor = startOfMonth(for: selectedDate)
      }
      requestRefresh()
    }
  }
  var initialTimestampMs: Double?
  var calendarType: CalendarType = .gregorian
  var isRTL: Bool = false { didSet { refreshHeader() } }
  var timeZoneId: String? { didSet { updateCalendarConfig(); requestRefresh() } }
  var viewMode: CalendarViewMode = .day { didSet { applyViewMode(viewMode, emit: true) } }
  var collapsedWeekMode: Bool = false { didSet { applyCollapsedMode(emit: true) } }
  var weekStartsOn: Double = 1 {
    didSet {
      let normalized = max(0, min(6, Int(weekStartsOn)))
      calendarEngine.firstWeekday = normalized + 1
      refreshWeekdayLabels()
      requestRefresh()
    }
  }
  var uses24HourClock: Bool?
  var localeId: String? { didSet { updateCalendarConfig(); requestRefresh() } }
  var appearance: CalendarAppearance = HybridNitroCalendar.defaultAppearance() { didSet { applyAppearance() } }
  var appearanceKey: String?
  var strings: CalendarStrings = HybridNitroCalendar.defaultStrings() { didSet { refreshWeekdayLabels(); refreshHeader(); requestRefresh() } }
  var stringsKey: String?
  var minTimestampMs: Double? { didSet { requestRefresh() } }
  var maxTimestampMs: Double? { didSet { requestRefresh() } }
  var onDateChange: ((_ event: DateChangeEvent) -> Void)?
  var onVisibleRangeChange: ((_ event: VisibleRangeChangeEvent) -> Void)?
  var onViewModeChange: ((_ event: ViewModeChangeEvent) -> Void)?

  required override init() {
    layout.minimumLineSpacing = 0
    layout.minimumInteritemSpacing = 0
    calendarView.translatesAutoresizingMaskIntoConstraints = false
    view = calendarView
    super.init()
    calendarView.onLayout = { [weak self] size in
      guard let self else { return }
      guard size.width > 0, size.height > 0 else { return }
      if self.lastLaidOutSize != size {
        self.lastLaidOutSize = size
        self.performRefreshNow()
      }
    }
    setupViewHierarchy()
    setupCollection()
    updateCalendarConfig()
    selectedDate = Date()
    displayedMonthAnchor = startOfMonth(for: selectedDate)
    yearSliceStart = computeYearSliceStart(for: selectedDate)
    applyAppearance()
    refreshWeekdayLabels()
    requestRefresh()
  }

  func goToToday() throws {
    selectedDate = Date()
    selectedTimestampMs = msFromDate(selectedDate)
    displayedMonthAnchor = startOfMonth(for: selectedDate)
    if renderedMode == .month || renderedMode == .year {
      applyViewMode(.day, emit: true)
    }
    requestRefresh()
  }

  func goToMonth(monthIndex: Double) throws {
    var components = calendarEngine.dateComponents([.year, .month, .day], from: selectedDate)
    components.month = max(1, min(12, Int(monthIndex) + 1))
    components.day = min(components.day ?? 1, 28)
    if let updated = calendarEngine.date(from: components) {
      selectedDate = updated
      selectedTimestampMs = msFromDate(updated)
      displayedMonthAnchor = startOfMonth(for: updated)
      applyViewMode(collapsedWeekMode ? .week : .day, emit: true)
      requestRefresh()
    }
  }

  func goToYear(year: Double) throws {
    var components = calendarEngine.dateComponents([.year, .month, .day], from: selectedDate)
    components.year = Int(year)
    components.day = min(components.day ?? 1, 28)
    if let updated = calendarEngine.date(from: components) {
      selectedDate = updated
      selectedTimestampMs = msFromDate(updated)
      displayedMonthAnchor = startOfMonth(for: updated)
      yearSliceStart = computeYearSliceStart(for: updated)
      applyViewMode(collapsedWeekMode ? .week : .day, emit: true)
      requestRefresh()
    }
  }

  func setCollapsedWeekModeEnabled(enabled: Bool) throws {
    collapsedWeekMode = enabled
  }

  func setMarkers(markers: [DayMarkerCompact]) throws {
    markerByDayStartMs.removeAll(keepingCapacity: true)
    for marker in markers {
      let key = Int64(dayStartMs(marker.timestampMs))
      markerByDayStartMs[key] = marker.dotIndices.map { Int($0) }
    }
    requestRefresh()
  }

  func beforeUpdate() {
    isBatchUpdating = true
  }

  func afterUpdate() {
    isBatchUpdating = false
    if pendingRefresh {
      pendingRefresh = false
      requestRefresh()
    }
  }

  private func setupViewHierarchy() {
    calendarView.addSubview(headerContainer)
    calendarView.addSubview(weekdayStack)
    calendarView.addSubview(collectionView)

    headerContainer.axis = .horizontal
    headerContainer.distribution = .fill
    headerContainer.alignment = .center
    headerContainer.spacing = 8
    headerContainer.translatesAutoresizingMaskIntoConstraints = false

    [leftButton, monthButton, yearButton, centerSpacer, todayBackButton, rightButton, collapseButton].forEach { item in
      if item === centerSpacer {
        centerSpacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        centerSpacer.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
      } else if let button = item as? UIButton {
        button.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .semibold)
        if #available(iOS 15.0, *) {
          var config = button.configuration ?? UIButton.Configuration.plain()
          config.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 8, bottom: 6, trailing: 8)
          button.configuration = config
        } else {
          button.contentEdgeInsets = UIEdgeInsets(top: 6, left: 8, bottom: 6, right: 8)
        }
      }
      headerContainer.addArrangedSubview(item)
    }

    weekdayStack.axis = .horizontal
    weekdayStack.distribution = .fillEqually
    weekdayStack.alignment = .fill
    weekdayStack.translatesAutoresizingMaskIntoConstraints = false
    for _ in 0..<7 {
      let label = UILabel()
      label.textAlignment = .center
      weekdayStack.addArrangedSubview(label)
    }

    collectionView.translatesAutoresizingMaskIntoConstraints = false
    collectionView.backgroundColor = .clear

    NSLayoutConstraint.activate([
      headerContainer.topAnchor.constraint(equalTo: calendarView.topAnchor),
      headerContainer.leadingAnchor.constraint(equalTo: calendarView.leadingAnchor, constant: 8),
      headerContainer.trailingAnchor.constraint(equalTo: calendarView.trailingAnchor, constant: -8),

      weekdayStack.topAnchor.constraint(equalTo: headerContainer.bottomAnchor, constant: 8),
      weekdayStack.leadingAnchor.constraint(equalTo: calendarView.leadingAnchor, constant: 8),
      weekdayStack.trailingAnchor.constraint(equalTo: calendarView.trailingAnchor, constant: -8),
      weekdayStack.heightAnchor.constraint(equalToConstant: 24),

      collectionView.topAnchor.constraint(equalTo: weekdayStack.bottomAnchor, constant: 6),
      collectionView.leadingAnchor.constraint(equalTo: calendarView.leadingAnchor, constant: 8),
      collectionView.trailingAnchor.constraint(equalTo: calendarView.trailingAnchor, constant: -8),
      collectionView.bottomAnchor.constraint(equalTo: calendarView.bottomAnchor, constant: -8)
    ])
    let minHeight = collectionView.heightAnchor.constraint(greaterThanOrEqualToConstant: 180)
    minHeight.isActive = true
    collectionMinHeightConstraint = minHeight

    leftButton.addTarget(coordinator, action: #selector(CalendarCoordinator.onPressPrev), for: .touchUpInside)
    rightButton.addTarget(coordinator, action: #selector(CalendarCoordinator.onPressNext), for: .touchUpInside)
    monthButton.addTarget(coordinator, action: #selector(CalendarCoordinator.onPressMonth), for: .touchUpInside)
    yearButton.addTarget(coordinator, action: #selector(CalendarCoordinator.onPressYear), for: .touchUpInside)
    todayBackButton.addTarget(coordinator, action: #selector(CalendarCoordinator.onPressTodayBack), for: .touchUpInside)
    collapseButton.addTarget(coordinator, action: #selector(CalendarCoordinator.onPressCollapse), for: .touchUpInside)
  }

  private func setupCollection() {
    collectionView.register(CalendarGridCell.self, forCellWithReuseIdentifier: "cell")
    collectionView.dataSource = coordinator
    collectionView.delegate = coordinator

    let leftSwipe = UISwipeGestureRecognizer(target: coordinator, action: #selector(CalendarCoordinator.onSwipe(_:)))
    leftSwipe.direction = .left
    collectionView.addGestureRecognizer(leftSwipe)

    let rightSwipe = UISwipeGestureRecognizer(target: coordinator, action: #selector(CalendarCoordinator.onSwipe(_:)))
    rightSwipe.direction = .right
    collectionView.addGestureRecognizer(rightSwipe)
  }

  private func updateCalendarConfig() {
    var calendar = Calendar(identifier: .gregorian)
    if let localeId, !localeId.isEmpty {
      calendar.locale = Locale(identifier: localeId)
    }
    if let timeZoneId, let timeZone = TimeZone(identifier: timeZoneId) {
      calendar.timeZone = timeZone
    }
    calendar.firstWeekday = max(1, min(7, Int(weekStartsOn) + 1))
    calendarEngine = calendar
  }

  private func applyAppearance() {
    calendarView.backgroundColor = colorFromHex(appearance.backgroundColor)
    let headerColor = appearance.headerBackgroundColor ?? appearance.backgroundColor
    headerContainer.backgroundColor = colorFromHex(headerColor)
    refreshWeekdayLabels()
    refreshHeader()
    collectionView.reloadData()
  }

  private func refreshWeekdayLabels() {
    let names = strings.weekdayNamesMin
    for (index, view) in weekdayStack.arrangedSubviews.enumerated() {
      guard let label = view as? UILabel else { continue }
      let sourceIndex = index % max(1, names.count)
      label.text = names.isEmpty ? "" : names[sourceIndex]
      label.textColor = colorFromHex(appearance.weekdayTextColor)
      label.font = UIFont.systemFont(
        ofSize: CGFloat(appearance.weekdayFontSize ?? 12),
        weight: .medium
      )
    }
  }

  private func requestRefresh() {
    if isBatchUpdating {
      pendingRefresh = true
      return
    }
    if refreshScheduled {
      return
    }
    refreshScheduled = true
    DispatchQueue.main.async {
      self.refreshScheduled = false
      self.performRefreshNow()
    }
  }

  private func performRefreshNow() {
    rebuildData()
    refreshHeader()
    // Ensure collection bounds are up-to-date before reloading so cells can size correctly.
    calendarView.layoutIfNeeded()
    collectionView.collectionViewLayout.invalidateLayout()
    collectionView.reloadData()
    emitVisibleRangeIfChanged()
  }

  private func rebuildData() {
    if renderedMode == .month {
      weekdayStack.isHidden = true
      pickerItems = strings.monthNamesFull ?? strings.monthNamesShort
      if pickerItems.count < 12 {
        let fallback = strings.monthNamesShort
        pickerItems = (0..<12).map { index in
          let i = index % max(1, fallback.count)
          return fallback.isEmpty ? "\(index + 1)" : fallback[i]
        }
      } else {
        pickerItems = Array(pickerItems.prefix(12))
      }
      dayItems = []
      return
    }

    if renderedMode == .year {
      weekdayStack.isHidden = true
      pickerItems = (0..<12).map { "\(yearSliceStart + $0)" }
      dayItems = []
      return
    }

    weekdayStack.isHidden = false
    pickerItems = []

    if renderedMode == .week {
      buildWeekItems()
    } else {
      buildMonthItems()
    }
  }

  private func buildMonthItems() {
    dayItems.removeAll(keepingCapacity: true)
    let monthStart = startOfMonth(for: displayedMonthAnchor)
    guard let monthRange = calendarEngine.range(of: .day, in: .month, for: monthStart) else {
      buildFallbackMonthItems(from: monthStart)
      return
    }

    let firstWeekdayIndex = calendarEngine.component(.weekday, from: monthStart)
    let leading = (firstWeekdayIndex - calendarEngine.firstWeekday + 7) % 7

    for index in 0..<(monthRange.count + leading) {
      if let date = calendarEngine.date(byAdding: .day, value: index - leading, to: monthStart) {
        dayItems.append(makeDayItem(for: date, inVisibleMonth: true))
      }
    }
    while dayItems.count % 7 != 0 {
      if let last = dayItems.last?.date, let next = calendarEngine.date(byAdding: .day, value: 1, to: last) {
        dayItems.append(makeDayItem(for: next, inVisibleMonth: true))
      } else {
        break
      }
    }
    if dayItems.isEmpty {
      buildFallbackMonthItems(from: monthStart)
    }
  }

  private func buildFallbackMonthItems(from monthStart: Date) {
    for offset in 0..<42 {
      guard let date = calendarEngine.date(byAdding: .day, value: offset, to: monthStart) else { continue }
      dayItems.append(makeDayItem(for: date, inVisibleMonth: true))
    }
  }

  private func buildWeekItems() {
    dayItems.removeAll(keepingCapacity: true)
    guard let weekStart = calendarEngine.dateInterval(of: .weekOfYear, for: selectedDate)?.start else { return }
    for offset in 0..<7 {
      guard let date = calendarEngine.date(byAdding: .day, value: offset, to: weekStart) else { continue }
      dayItems.append(makeDayItem(for: date, inVisibleMonth: false))
    }
  }

  private func makeDayItem(for date: Date, inVisibleMonth: Bool) -> DayItem {
    let monthOfDate = calendarEngine.component(.month, from: date)
    let monthOfAnchor = calendarEngine.component(.month, from: displayedMonthAnchor)
    let sameMonth = monthOfDate == monthOfAnchor || !inVisibleMonth
    let isToday = calendarEngine.isDateInToday(date)
    let isSelected = calendarEngine.isDate(date, inSameDayAs: selectedDate)
    let timestampMs = msFromDate(date)
    let isDisabled = isOutOfRange(timestampMs)
    let dots = markerByDayStartMs[Int64(dayStartMs(timestampMs))] ?? []
    return DayItem(
      date: date,
      dayLabel: "\(calendarEngine.component(.day, from: date))",
      isCurrentMonth: sameMonth,
      isToday: isToday,
      isSelected: isSelected,
      isDisabled: isDisabled,
      dotCount: min(3, dots.count)
    )
  }

  private func refreshHeader() {
    leftButton.setTitle("‹", for: .normal)
    rightButton.setTitle("›", for: .normal)
    collapseButton.setTitle(renderedMode == .week ? "⌄" : "⌃", for: .normal)
    collapseButton.tintColor = colorFromHex(appearance.headerButtonColor)

    let monthIndex = calendarEngine.component(.month, from: displayedMonthAnchor) - 1
    let monthNames = strings.monthNamesFull ?? strings.monthNamesShort
    let monthName = monthNames.indices.contains(monthIndex) ? monthNames[monthIndex] : "\(monthIndex + 1)"
    monthButton.setTitle(monthName, for: .normal)
    monthButton.tintColor = colorFromHex(appearance.headerTitleColor)

    let year = calendarEngine.component(.year, from: displayedMonthAnchor)
    yearButton.setTitle("\(year)", for: .normal)
    yearButton.tintColor = colorFromHex(appearance.headerTitleColor)

    let isPicker = renderedMode == .month || renderedMode == .year
    todayBackButton.setTitle(isPicker ? strings.headerBack : strings.headerToday, for: .normal)
    todayBackButton.tintColor = colorFromHex(appearance.headerTodayColor ?? appearance.headerButtonColor)

    let chevronsVisible = renderedMode != .month
    leftButton.isHidden = !chevronsVisible
    rightButton.isHidden = !chevronsVisible
    weekdayStack.isHidden = renderedMode == .month || renderedMode == .year
  }

  private func applyViewMode(_ mode: CalendarViewMode, emit: Bool) {
    renderedMode = mode
    requestRefresh()
    if emit {
      onViewModeChange?(ViewModeChangeEvent(mode: renderedMode))
    }
  }

  private func applyCollapsedMode(emit: Bool) {
    let target: CalendarViewMode = collapsedWeekMode ? .week : .day
    if renderedMode != .month && renderedMode != .year {
      renderedMode = target
    }
    requestRefresh()
    if emit {
      onViewModeChange?(ViewModeChangeEvent(mode: target))
    }
  }

  private func emitVisibleRangeIfChanged() {
    guard renderedMode == .day || renderedMode == .week else { return }
    let monthStart = startOfMonth(for: displayedMonthAnchor)
    guard
      let prev = calendarEngine.date(byAdding: .month, value: -1, to: monthStart),
      let next = calendarEngine.date(byAdding: .month, value: 2, to: monthStart),
      let nextEnd = calendarEngine.date(byAdding: .second, value: -1, to: next)
    else { return }

    let range: (Double, Double) = (msFromDate(prev), msFromDate(nextEnd))
    if let lastVisibleRange,
       Swift.abs(lastVisibleRange.0 - range.0) < 1,
       Swift.abs(lastVisibleRange.1 - range.1) < 1 {
      return
    }
    lastVisibleRange = range
    onVisibleRangeChange?(VisibleRangeChangeEvent(startMs: range.0, endMs: range.1))
  }

  private func shiftTimeline(forward: Bool) {
    let forwardValue = forward ? 1 : -1
    if renderedMode == .year {
      yearSliceStart += forwardValue * 12
    } else if renderedMode == .week {
      if let next = calendarEngine.date(byAdding: .weekOfYear, value: forwardValue, to: selectedDate) {
        selectedDate = next
        selectedTimestampMs = msFromDate(next)
        displayedMonthAnchor = startOfMonth(for: next)
      }
    } else {
      if let next = calendarEngine.date(byAdding: .month, value: forwardValue, to: displayedMonthAnchor) {
        displayedMonthAnchor = startOfMonth(for: next)
      }
    }
    requestRefresh()
  }

  fileprivate func onPressPrev() {
    let forward = isRTL
    shiftTimeline(forward: forward)
  }

  fileprivate func onPressNext() {
    let forward = !isRTL
    shiftTimeline(forward: forward)
  }

  fileprivate func onPressMonth() {
    applyViewMode(.month, emit: true)
  }

  fileprivate func onPressYear() {
    yearSliceStart = computeYearSliceStart(for: displayedMonthAnchor)
    applyViewMode(.year, emit: true)
  }

  fileprivate func onPressTodayBack() {
    if renderedMode == .month || renderedMode == .year {
      applyViewMode(collapsedWeekMode ? .week : .day, emit: true)
      return
    }
    try? goToToday()
  }

  fileprivate func onPressCollapse() {
    collapsedWeekMode.toggle()
  }

  fileprivate func onSwipe(_ recognizer: UISwipeGestureRecognizer) {
    let isForwardSwipe = recognizer.direction == .left
    let forward = isRTL ? !isForwardSwipe : isForwardSwipe
    shiftTimeline(forward: forward)
  }

  fileprivate func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
    if renderedMode == .month || renderedMode == .year {
      return pickerItems.count
    }
    return dayItems.count
  }

  fileprivate func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
    guard let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "cell", for: indexPath) as? CalendarGridCell else {
      return UICollectionViewCell()
    }

    if renderedMode == .month || renderedMode == .year {
      let value = pickerItems[indexPath.item]
      let selected = (renderedMode == .month && indexPath.item == calendarEngine.component(.month, from: selectedDate) - 1)
        || (renderedMode == .year && Int(value) == calendarEngine.component(.year, from: selectedDate))
      cell.configurePicker(
        title: value,
        isSelected: selected,
        textColor: colorFromHex(selected ? (appearance.pickerCellSelectedTextColor ?? appearance.selectedDayTextColor) : (appearance.pickerCellTextColor ?? appearance.dayTextColor)),
        selectedBackground: colorFromHex(appearance.pickerCellSelectedBackgroundColor ?? appearance.selectedDayBackgroundColor),
        normalBackground: colorFromHex(appearance.pickerCellBackgroundColor ?? appearance.backgroundColor)
      )
      return cell
    }

    let item = dayItems[indexPath.item]
    let textColor: UIColor
    if item.isDisabled {
      textColor = colorFromHex(appearance.disabledDayTextColor)
    } else if item.isSelected {
      textColor = colorFromHex(appearance.selectedDayTextColor)
    } else if item.isToday {
      textColor = colorFromHex(appearance.todayTextColor)
    } else if !item.isCurrentMonth {
      textColor = colorFromHex(appearance.dayOutsideMonthTextColor)
    } else {
      textColor = colorFromHex(appearance.dayTextColor)
    }

    cell.configureDay(
      title: item.dayLabel,
      isSelected: item.isSelected,
      isDisabled: item.isDisabled,
      dotCount: item.dotCount,
      textColor: textColor,
      selectedBackground: colorFromHex(appearance.selectedDayBackgroundColor),
      normalBackground: .clear,
      dotColor: colorFromHex(appearance.markerAccentColor ?? appearance.todayIndicatorColor ?? appearance.todayTextColor)
    )
    return cell
  }

  fileprivate func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
    if renderedMode == .month {
      let month = indexPath.item + 1
      try? goToMonth(monthIndex: Double(month - 1))
      return
    }
    if renderedMode == .year {
      let year = yearSliceStart + indexPath.item
      try? goToYear(year: Double(year))
      return
    }

    let item = dayItems[indexPath.item]
    guard !item.isDisabled else { return }
    selectedDate = item.date
    selectedTimestampMs = msFromDate(item.date)
    onDateChange?(DateChangeEvent(timestampMs: selectedTimestampMs))
    if renderedMode == .day {
      displayedMonthAnchor = startOfMonth(for: item.date)
    }
    requestRefresh()
  }

  fileprivate func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
    let spacing: CGFloat = CGFloat(appearance.spacing ?? 0)
    let fallbackWidth = max(0, UIScreen.main.bounds.width - 32)
    let measuredWidth = max(collectionView.bounds.width, max(0, calendarView.bounds.width - 16))
    let width = measuredWidth > 1 ? measuredWidth : fallbackWidth
    let columns: CGFloat = (renderedMode == .month || renderedMode == .year) ? 3 : 7
    let rawWidth = (width - (columns - 1) * spacing) / columns
    let itemWidth = max(1, floor(rawWidth))
    let height: CGFloat
    if renderedMode == .month || renderedMode == .year {
      height = max(36, CGFloat(appearance.rowHeight ?? 44))
    } else {
      height = max(34, CGFloat(appearance.rowHeight ?? 40))
    }
    return CGSize(width: itemWidth, height: height)
  }

  private func startOfMonth(for date: Date) -> Date {
    let components = calendarEngine.dateComponents([.year, .month], from: date)
    return calendarEngine.date(from: components) ?? date
  }

  private func dayStartMs(_ timestampMs: Double) -> Double {
    let start = calendarEngine.startOfDay(for: dateFromMs(timestampMs))
    return msFromDate(start)
  }

  private func dateFromMs(_ timestampMs: Double) -> Date {
    return Date(timeIntervalSince1970: timestampMs / 1000.0)
  }

  private func msFromDate(_ date: Date) -> Double {
    return date.timeIntervalSince1970 * 1000.0
  }

  private func isOutOfRange(_ timestampMs: Double) -> Bool {
    if let minTimestampMs, timestampMs < minTimestampMs { return true }
    if let maxTimestampMs, timestampMs > maxTimestampMs { return true }
    return false
  }

  private func computeYearSliceStart(for date: Date) -> Int {
    let year = calendarEngine.component(.year, from: date)
    return year - 6
  }

  private func colorFromHex(_ hex: String) -> UIColor {
    let sanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "#", with: "")
    var value: UInt64 = 0
    Scanner(string: sanitized).scanHexInt64(&value)
    let r, g, b: CGFloat
    switch sanitized.count {
    case 6:
      r = CGFloat((value >> 16) & 0xFF) / 255.0
      g = CGFloat((value >> 8) & 0xFF) / 255.0
      b = CGFloat(value & 0xFF) / 255.0
    default:
      r = 0.0
      g = 0.0
      b = 0.0
    }
    return UIColor(red: r, green: g, blue: b, alpha: 1.0)
  }

  private static func defaultAppearance() -> CalendarAppearance {
    return CalendarAppearance(
      backgroundColor: "#FFFFFF",
      separatorColor: nil,
      headerBackgroundColor: nil,
      headerTitleColor: "#111827",
      headerSubtitleColor: nil,
      headerButtonColor: "#2563EB",
      headerTodayColor: nil,
      weekdayTextColor: "#6B7280",
      weekdayFontSize: 12,
      weekdayFontWeight: nil,
      dayTextColor: "#111827",
      dayOutsideMonthTextColor: "#9CA3AF",
      selectedDayBackgroundColor: "#2563EB",
      selectedDayTextColor: "#FFFFFF",
      todayTextColor: "#2563EB",
      todayIndicatorColor: nil,
      disabledDayTextColor: "#D1D5DB",
      pickerCellBackgroundColor: nil,
      pickerCellSelectedBackgroundColor: nil,
      pickerCellTextColor: nil,
      pickerCellSelectedTextColor: nil,
      fontFamily: nil,
      fontSizeDay: 14,
      fontSizeHeader: 14,
      fontWeight: nil,
      dayCellSize: nil,
      rowHeight: 40,
      headerHeight: 36,
      spacing: 0,
      cornerRadius: 8,
      borderColor: nil,
      borderWidth: nil,
      markerPalette: ["#2563EB"],
      markerAccentColor: nil
    )
  }

  private static func defaultStrings() -> CalendarStrings {
    return CalendarStrings(
      monthNamesShort: ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"],
      monthNamesFull: nil,
      weekdayNamesMin: ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"],
      headerToday: "Today",
      headerBack: "Back",
      labelShowWeekView: nil,
      labelShowMonthView: nil,
      accessibilityPrev: nil,
      accessibilityNext: nil
    )
  }
}

private final class CalendarRootView: UIView {
  var onLayout: ((CGSize) -> Void)?

  override func layoutSubviews() {
    super.layoutSubviews()
    onLayout?(bounds.size)
  }
}

private final class CalendarCoordinator: NSObject, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
  weak var owner: HybridNitroCalendar?

  init(owner: HybridNitroCalendar) {
    self.owner = owner
    super.init()
  }

  @objc func onPressPrev() {
    owner?.onPressPrev()
  }

  @objc func onPressNext() {
    owner?.onPressNext()
  }

  @objc func onPressMonth() {
    owner?.onPressMonth()
  }

  @objc func onPressYear() {
    owner?.onPressYear()
  }

  @objc func onPressTodayBack() {
    owner?.onPressTodayBack()
  }

  @objc func onPressCollapse() {
    owner?.onPressCollapse()
  }

  @objc func onSwipe(_ recognizer: UISwipeGestureRecognizer) {
    owner?.onSwipe(recognizer)
  }

  func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
    return owner?.collectionView(collectionView, numberOfItemsInSection: section) ?? 0
  }

  func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
    return owner?.collectionView(collectionView, cellForItemAt: indexPath) ?? UICollectionViewCell()
  }

  func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
    owner?.collectionView(collectionView, didSelectItemAt: indexPath)
  }

  func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
    return owner?.collectionView(collectionView, layout: collectionViewLayout, sizeForItemAt: indexPath) ?? .zero
  }
}

private struct DayItem {
  let date: Date
  let dayLabel: String
  let isCurrentMonth: Bool
  let isToday: Bool
  let isSelected: Bool
  let isDisabled: Bool
  let dotCount: Int
}

private final class CalendarGridCell: UICollectionViewCell {
  private let label = UILabel()
  private let dotsContainer = UIStackView()
  private let backgroundPill = UIView()

  override init(frame: CGRect) {
    super.init(frame: frame)
    contentView.addSubview(backgroundPill)
    backgroundPill.translatesAutoresizingMaskIntoConstraints = false
    backgroundPill.layer.cornerRadius = 8
    backgroundPill.clipsToBounds = true

    label.translatesAutoresizingMaskIntoConstraints = false
    label.textAlignment = .center
    backgroundPill.addSubview(label)

    dotsContainer.axis = .horizontal
    dotsContainer.spacing = 2
    dotsContainer.alignment = .center
    dotsContainer.distribution = .fillEqually
    dotsContainer.translatesAutoresizingMaskIntoConstraints = false
    backgroundPill.addSubview(dotsContainer)

    NSLayoutConstraint.activate([
      backgroundPill.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 2),
      backgroundPill.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 2),
      backgroundPill.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -2),
      backgroundPill.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -2),

      label.topAnchor.constraint(equalTo: backgroundPill.topAnchor, constant: 6),
      label.leadingAnchor.constraint(equalTo: backgroundPill.leadingAnchor, constant: 4),
      label.trailingAnchor.constraint(equalTo: backgroundPill.trailingAnchor, constant: -4),

      dotsContainer.topAnchor.constraint(greaterThanOrEqualTo: label.bottomAnchor, constant: 2),
      dotsContainer.bottomAnchor.constraint(equalTo: backgroundPill.bottomAnchor, constant: -4),
      dotsContainer.centerXAnchor.constraint(equalTo: backgroundPill.centerXAnchor),
      dotsContainer.heightAnchor.constraint(equalToConstant: 6),
      dotsContainer.widthAnchor.constraint(equalToConstant: 20)
    ])
  }

  required init?(coder: NSCoder) {
    fatalError("init(coder:) has not been implemented")
  }

  func configureDay(title: String, isSelected: Bool, isDisabled: Bool, dotCount: Int, textColor: UIColor, selectedBackground: UIColor, normalBackground: UIColor, dotColor: UIColor) {
    label.text = title
    label.textColor = textColor
    label.alpha = isDisabled ? 0.7 : 1.0
    backgroundPill.backgroundColor = isSelected ? selectedBackground : normalBackground
    updateDots(count: dotCount, color: dotColor)
  }

  func configurePicker(title: String, isSelected: Bool, textColor: UIColor, selectedBackground: UIColor, normalBackground: UIColor) {
    label.text = title
    label.textColor = textColor
    label.alpha = 1
    backgroundPill.backgroundColor = isSelected ? selectedBackground : normalBackground
    updateDots(count: 0, color: .clear)
  }

  private func updateDots(count: Int, color: UIColor) {
    dotsContainer.arrangedSubviews.forEach { view in
      dotsContainer.removeArrangedSubview(view)
      view.removeFromSuperview()
    }
    guard count > 0 else { return }
    for _ in 0..<count {
      let dot = UIView()
      dot.backgroundColor = color
      dot.layer.cornerRadius = 2
      dot.translatesAutoresizingMaskIntoConstraints = false
      dot.widthAnchor.constraint(equalToConstant: 4).isActive = true
      dot.heightAnchor.constraint(equalToConstant: 4).isActive = true
      dotsContainer.addArrangedSubview(dot)
    }
  }
}
