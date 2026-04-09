import Foundation
import UIKit
import SwiftUI
import NitroModules

// MARK: - CalendarViewModel

@MainActor
final class CalendarViewModel: ObservableObject {
  @Published var selectedTimestampMs: Double = Date().timeIntervalSince1970 * 1000
  @Published var mode: CalendarViewMode = .day
  @Published var collapsedWeekMode: Bool = false
  @Published var isRTL: Bool = false
  @Published var appearance: CalendarAppearance = HybridNitroCalendar.defaultAppearance()
  @Published var strings: CalendarStrings = HybridNitroCalendar.defaultStrings()
  @Published var pageIndex: Int = 10_000
  @Published var yearSliceStart: Int = Calendar.current.component(.year, from: Date()) - 6
  @Published var weekIndex: Int = 0  // week row index within the current month page
  @Published var minTimestampMs: Double? = nil
  @Published var maxTimestampMs: Double? = nil
  @Published var weekStartsOn: Int = 1
  @Published var markers: [Int64: [Int]] = [:]
  var calendarEngine: Foundation.Calendar = {
    var c = Foundation.Calendar(identifier: .gregorian)
    c.firstWeekday = 2
    return c
  }()

  var onDateChange: ((DateChangeEvent) -> Void)?
  var onVisibleRangeChange: ((VisibleRangeChangeEvent) -> Void)?
  var onViewModeChange: ((ViewModeChangeEvent) -> Void)?
}

// MARK: - HybridNitroCalendar

class HybridNitroCalendar: HybridNitroCalendarSpec {
  var view: UIView

  private let container = UIView()
  private let vm = CalendarViewModel()
  private var hostingController: UIViewController?

  var selectedTimestampMs: Double = Date().timeIntervalSince1970 * 1000 {
    didSet { vm.selectedTimestampMs = selectedTimestampMs }
  }
  var initialTimestampMs: Double?
  var calendarType: CalendarType = .gregorian
  var isRTL: Bool = false { didSet { vm.isRTL = isRTL } }
  var timeZoneId: String? { didSet { updateCalendarEngine() } }
  var viewMode: CalendarViewMode = .day { didSet { vm.mode = viewMode; vm.onViewModeChange?(ViewModeChangeEvent(mode: viewMode)) } }
  var collapsedWeekMode: Bool = false { didSet { vm.collapsedWeekMode = collapsedWeekMode } }
  var weekStartsOn: Double = 1 {
    didSet {
      vm.weekStartsOn = max(0, min(6, Int(weekStartsOn)))
      updateCalendarEngine()
    }
  }
  var uses24HourClock: Bool?
  var localeId: String? { didSet { updateCalendarEngine() } }
  var appearance: CalendarAppearance = HybridNitroCalendar.defaultAppearance() { didSet { vm.appearance = appearance } }
  var appearanceKey: String?
  var strings: CalendarStrings = HybridNitroCalendar.defaultStrings() { didSet { vm.strings = strings } }
  var stringsKey: String?
  var minTimestampMs: Double? { didSet { vm.minTimestampMs = minTimestampMs } }
  var maxTimestampMs: Double? { didSet { vm.maxTimestampMs = maxTimestampMs } }
  var onDateChange: ((_ event: DateChangeEvent) -> Void)? { didSet { vm.onDateChange = onDateChange } }
  var onVisibleRangeChange: ((_ event: VisibleRangeChangeEvent) -> Void)? { didSet { vm.onVisibleRangeChange = onVisibleRangeChange } }
  var onViewModeChange: ((_ event: ViewModeChangeEvent) -> Void)? { didSet { vm.onViewModeChange = onViewModeChange } }

  required override init() {
    container.translatesAutoresizingMaskIntoConstraints = false
    view = container
    super.init()

    let rootView = CalendarRootView(vm: vm)
    let hc = UIHostingController(rootView: rootView)
    hc.view.translatesAutoresizingMaskIntoConstraints = false
    hc.view.backgroundColor = .clear
    container.addSubview(hc.view)
    NSLayoutConstraint.activate([
      hc.view.topAnchor.constraint(equalTo: container.topAnchor),
      hc.view.leadingAnchor.constraint(equalTo: container.leadingAnchor),
      hc.view.trailingAnchor.constraint(equalTo: container.trailingAnchor),
      hc.view.bottomAnchor.constraint(equalTo: container.bottomAnchor),
    ])
    hostingController = hc

    vm.selectedTimestampMs = selectedTimestampMs
    vm.yearSliceStart = Foundation.Calendar.current.component(.year, from: Date()) - 6
  }

  func goToToday() throws {
    let now = Date()
    selectedTimestampMs = now.timeIntervalSince1970 * 1000
    vm.selectedTimestampMs = selectedTimestampMs
    vm.pageIndex = 10_000
    vm.weekIndex = 0
    if vm.mode == .month || vm.mode == .year {
      vm.mode = vm.collapsedWeekMode ? .week : .day
      vm.onViewModeChange?(ViewModeChangeEvent(mode: vm.mode))
    }
    vm.onDateChange?(DateChangeEvent(timestampMs: vm.selectedTimestampMs))
  }

  func goToMonth(monthIndex: Double) throws {
    var comps = vm.calendarEngine.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: vm.selectedTimestampMs / 1000))
    comps.month = max(1, min(12, Int(monthIndex) + 1))
    comps.day = min(comps.day ?? 1, 28)
    if let updated = vm.calendarEngine.date(from: comps) {
      let ms = updated.timeIntervalSince1970 * 1000
      selectedTimestampMs = ms
      vm.selectedTimestampMs = ms
      // Navigate pager to the selected month
      let todayComps = vm.calendarEngine.dateComponents([.year, .month], from: Date())
      let updatedComps = vm.calendarEngine.dateComponents([.year, .month], from: updated)
      let monthDiff = (updatedComps.year! - todayComps.year!) * 12 + (updatedComps.month! - todayComps.month!)
      vm.pageIndex = 10_000 + monthDiff
      vm.mode = vm.collapsedWeekMode ? .week : .day
    }
  }

  func goToYear(year: Double) throws {
    var comps = vm.calendarEngine.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: vm.selectedTimestampMs / 1000))
    comps.year = Int(year)
    comps.day = min(comps.day ?? 1, 28)
    if let updated = vm.calendarEngine.date(from: comps) {
      let ms = updated.timeIntervalSince1970 * 1000
      selectedTimestampMs = ms
      vm.selectedTimestampMs = ms
      vm.yearSliceStart = Int(year) - 6
      // Navigate pager to the selected year/month
      let todayComps = vm.calendarEngine.dateComponents([.year, .month], from: Date())
      let updatedComps = vm.calendarEngine.dateComponents([.year, .month], from: updated)
      let monthDiff = (updatedComps.year! - todayComps.year!) * 12 + (updatedComps.month! - todayComps.month!)
      vm.pageIndex = 10_000 + monthDiff
      vm.mode = vm.collapsedWeekMode ? .week : .day
    }
  }

  func setCollapsedWeekModeEnabled(enabled: Bool) throws {
    collapsedWeekMode = enabled
  }

  func setMarkers(markers: [DayMarkerCompact]) throws {
    var dict: [Int64: [Int]] = [:]
    for m in markers {
      let key = Int64(dayStartMs(m.timestampMs))
      dict[key] = m.dotIndices.map { Int($0) }
    }
    vm.markers = dict
  }

  func beforeUpdate() {}
  func afterUpdate() {}

  private func updateCalendarEngine() {
    var cal = Foundation.Calendar(identifier: .gregorian)
    if let localeId, !localeId.isEmpty { cal.locale = Locale(identifier: localeId) }
    if let timeZoneId, let tz = TimeZone(identifier: timeZoneId) { cal.timeZone = tz }
    cal.firstWeekday = max(1, min(7, Int(weekStartsOn) + 1))
    vm.calendarEngine = cal
  }

  private func dayStartMs(_ ms: Double) -> Double {
    let date = Date(timeIntervalSince1970: ms / 1000)
    let start = vm.calendarEngine.startOfDay(for: date)
    return start.timeIntervalSince1970 * 1000
  }

  static func defaultAppearance() -> CalendarAppearance {
    return CalendarAppearance(
      backgroundColor: "#FFFFFF", separatorColor: nil, headerBackgroundColor: nil,
      headerTitleColor: "#111827", headerSubtitleColor: nil, headerButtonColor: "#2563EB",
      headerTodayColor: nil, weekdayTextColor: "#6B7280", weekdayFontSize: 12,
      weekdayFontWeight: nil, dayTextColor: "#111827", dayOutsideMonthTextColor: "#9CA3AF",
      selectedDayBackgroundColor: "#2563EB", selectedDayTextColor: "#FFFFFF",
      todayTextColor: "#2563EB", todayIndicatorColor: nil, disabledDayTextColor: "#D1D5DB",
      pickerCellBackgroundColor: nil, pickerCellSelectedBackgroundColor: nil,
      pickerCellTextColor: nil, pickerCellSelectedTextColor: nil, fontFamily: nil,
      fontSizeDay: 14, fontSizeHeader: 14, fontWeight: nil, dayCellSize: nil,
      rowHeight: 40, headerHeight: 36, spacing: 0, cornerRadius: 8,
      borderColor: nil, borderWidth: nil, markerPalette: ["#2563EB"], markerAccentColor: nil
    )
  }

  static func defaultStrings() -> CalendarStrings {
    return CalendarStrings(
      monthNamesShort: ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"],
      monthNamesFull: nil,
      weekdayNamesMin: ["Mo","Tu","We","Th","Fr","Sa","Su"],
      headerToday: "Today", headerBack: "Back",
      labelShowWeekView: nil, labelShowMonthView: nil,
      accessibilityPrev: nil, accessibilityNext: nil
    )
  }
}

// MARK: - CalendarRootView (Tasks 3.2 + 3.3 + 3.4 + 3.5 + 3.6)

struct CalendarRootView: View {
  @ObservedObject var vm: CalendarViewModel

  // Derive displayed month/year from pageIndex so header updates on swipe
  private var displayedDate: Date {
    let offset = vm.pageIndex - 10_000
    return vm.calendarEngine.date(byAdding: .month, value: offset, to: {
      let comps = vm.calendarEngine.dateComponents([.year, .month], from: Date())
      return vm.calendarEngine.date(from: comps) ?? Date()
    }()) ?? Date()
  }

  var body: some View {
    VStack(spacing: 0) {
      CalendarHeaderView(vm: vm, displayedDate: displayedDate)
      if vm.mode != .month && vm.mode != .year {
        WeekdayRowView(vm: vm)
      }
      ZStack {
        if vm.mode == .day || vm.mode == .week {
          VStack(spacing: 0) {
            DayGridView(vm: vm)
            // Collapse toggle — centered below grid, like legacy design
            let collapseLabel = vm.collapsedWeekMode
              ? (vm.strings.labelShowMonthView ?? "Month view")
              : (vm.strings.labelShowWeekView ?? "Week view")
            Button(action: {
              if !vm.collapsedWeekMode {
                // Collapsing: find which week contains the selected date on the current page
                let cal = vm.calendarEngine
                let offset = vm.pageIndex - 10_000
                let todayComps = cal.dateComponents([.year, .month], from: Date())
                if let todayStart = cal.date(from: DateComponents(year: todayComps.year, month: todayComps.month, day: 1)),
                   let anchor = cal.date(byAdding: .month, value: offset, to: todayStart) {
                  var c = cal.dateComponents([.year, .month], from: anchor); c.day = 1
                  if let monthStart = cal.date(from: c),
                     let monthRange = cal.range(of: .day, in: .month, for: monthStart) {
                    let firstWeekday = cal.component(.weekday, from: monthStart)
                    let leading = (firstWeekday - cal.firstWeekday + 7) % 7
                    let selectedDate = Date(timeIntervalSince1970: vm.selectedTimestampMs / 1000)
                    var found = 0
                    let totalCells = ((leading + monthRange.count + 6) / 7) * 7
                    for i in 0..<totalCells {
                      if let date = cal.date(byAdding: .day, value: i - leading, to: monthStart),
                         cal.isDate(date, inSameDayAs: selectedDate) {
                        found = i / 7
                        break
                      }
                    }
                    vm.weekIndex = found
                  }
                }
              }
              withAnimation(.easeInOut(duration: 0.25)) { vm.collapsedWeekMode.toggle() }
              vm.onViewModeChange?(ViewModeChangeEvent(mode: vm.collapsedWeekMode ? .week : .day))
            }) {
              HStack(spacing: 4) {
                Text(vm.collapsedWeekMode ? "⌄" : "⌃")
                Text(collapseLabel)
              }
              .font(.system(size: 13))
              .foregroundColor(Color(hex: vm.appearance.headerButtonColor))
              .frame(maxWidth: .infinity)
              .padding(.vertical, 6)
            }
          }
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
    }
    .background(Color(hex: vm.appearance.backgroundColor))
  }
}

// MARK: - CalendarHeaderView (Task 3.2)

struct CalendarHeaderView: View {
  @ObservedObject var vm: CalendarViewModel
  let displayedDate: Date

  var body: some View {
    HStack(spacing: 4) {
      if vm.mode != .month {
        Button(vm.isRTL ? "›" : "‹") {
          if vm.mode == .year {
            vm.yearSliceStart -= 12
          } else if vm.collapsedWeekMode {
            navigateWeek(forward: vm.isRTL)
          } else {
            withAnimation(.easeInOut(duration: 0.3)) { vm.pageIndex += vm.isRTL ? 1 : -1 }
          }
        }
        .foregroundColor(Color(hex: vm.appearance.headerButtonColor))
      }
      Button(monthName) {
        vm.mode = .month
        vm.onViewModeChange?(ViewModeChangeEvent(mode: .month))
      }
      .foregroundColor(Color(hex: vm.appearance.headerTitleColor))
      Button(yearName) {
        vm.yearSliceStart = vm.calendarEngine.component(.year, from: displayedDate) - 6
        vm.mode = .year
        vm.onViewModeChange?(ViewModeChangeEvent(mode: .year))
      }
      .foregroundColor(Color(hex: vm.appearance.headerTitleColor))
      Spacer()
      Button(vm.mode == .month || vm.mode == .year ? vm.strings.headerBack : vm.strings.headerToday) {
        if vm.mode == .month || vm.mode == .year {
          vm.mode = vm.collapsedWeekMode ? .week : .day
          vm.onViewModeChange?(ViewModeChangeEvent(mode: vm.mode))
        } else {
          let now = Date()
          vm.selectedTimestampMs = now.timeIntervalSince1970 * 1000
          vm.pageIndex = 10_000
          vm.onDateChange?(DateChangeEvent(timestampMs: vm.selectedTimestampMs))
        }
      }
      .foregroundColor(Color(hex: vm.appearance.headerTodayColor ?? vm.appearance.headerButtonColor))
      if vm.mode != .month {
        Button(vm.isRTL ? "‹" : "›") {
          if vm.mode == .year {
            vm.yearSliceStart += 12
          } else if vm.collapsedWeekMode {
            navigateWeek(forward: !vm.isRTL)
          } else {
            withAnimation(.easeInOut(duration: 0.3)) { vm.pageIndex += vm.isRTL ? -1 : 1 }
          }
        }
        .foregroundColor(Color(hex: vm.appearance.headerButtonColor))
      }
    }
    .padding(.horizontal, 8)
    .frame(height: CGFloat(vm.appearance.headerHeight ?? 36))
    .background(Color(hex: vm.appearance.headerBackgroundColor ?? vm.appearance.backgroundColor))
  }

  /// Move one week forward or backward — purely index-based, no date searching.
  private func navigateWeek(forward: Bool) {
    let cal = vm.calendarEngine
    let offset = vm.pageIndex - 10_000
    let todayComps = cal.dateComponents([.year, .month], from: Date())
    guard let todayMonthStart = cal.date(from: DateComponents(year: todayComps.year, month: todayComps.month, day: 1)),
          let anchorMonth = cal.date(byAdding: .month, value: offset, to: todayMonthStart) else { return }
    var c = cal.dateComponents([.year, .month], from: anchorMonth); c.day = 1
    guard let monthStart = cal.date(from: c),
          let monthRange = cal.range(of: .day, in: .month, for: monthStart) else { return }
    let firstWeekday = cal.component(.weekday, from: monthStart)
    let leading = (firstWeekday - cal.firstWeekday + 7) % 7
    let totalCells = ((leading + monthRange.count + 6) / 7) * 7
    let totalWeeks = totalCells / 7

    if forward {
      if vm.weekIndex < totalWeeks - 1 {
        vm.weekIndex += 1
      } else {
        vm.weekIndex = 0
        vm.pageIndex += 1
      }
    } else {
      if vm.weekIndex > 0 {
        vm.weekIndex -= 1
      } else {
        // Go to previous month — set weekIndex to its last week
        let prevOffset = offset - 1
        guard let prevAnchor = cal.date(byAdding: .month, value: prevOffset, to: todayMonthStart) else { return }
        var pc = cal.dateComponents([.year, .month], from: prevAnchor); pc.day = 1
        guard let prevMonthStart = cal.date(from: pc),
              let prevRange = cal.range(of: .day, in: .month, for: prevMonthStart) else { return }
        let prevFirstWeekday = cal.component(.weekday, from: prevMonthStart)
        let prevLeading = (prevFirstWeekday - cal.firstWeekday + 7) % 7
        let prevTotalCells = ((prevLeading + prevRange.count + 6) / 7) * 7
        vm.weekIndex = (prevTotalCells / 7) - 1
        vm.pageIndex -= 1
      }
    }
  }

  private var monthName: String {
    let monthIndex = vm.calendarEngine.component(.month, from: displayedDate) - 1
    let names = vm.strings.monthNamesFull ?? vm.strings.monthNamesShort
    return names.indices.contains(monthIndex) ? names[monthIndex] : "\(monthIndex + 1)"
  }

  private var yearName: String {
    "\(vm.calendarEngine.component(.year, from: displayedDate))"
  }
}

// MARK: - WeekdayRowView

struct WeekdayRowView: View {
  @ObservedObject var vm: CalendarViewModel

  var body: some View {
    HStack(spacing: 0) {
      ForEach(vm.strings.weekdayNamesMin.indices, id: \.self) { i in
        Text(vm.strings.weekdayNamesMin[i])
          .frame(maxWidth: .infinity)
          .font(.system(size: CGFloat(vm.appearance.weekdayFontSize ?? 12), weight: .medium))
          .foregroundColor(Color(hex: vm.appearance.weekdayTextColor))
      }
    }
    .frame(height: 24)
  }
}

// MARK: - DayGridView (Tasks 3.4 + 3.5)

struct DayGridView: View {
  @ObservedObject var vm: CalendarViewModel

  var body: some View {
    TabView(selection: Binding(
      get: { vm.pageIndex },
      set: { newPage in
        vm.pageIndex = newPage
        emitVisibleRange(for: newPage)
      }
    )) {
      ForEach(0..<20_001, id: \.self) { idx in
        MonthGridPage(monthOffset: idx - 10_000, vm: vm)
          .tag(idx)
      }
    }
    .tabViewStyle(.page(indexDisplayMode: .never))
    .onChange(of: vm.pageIndex) { _ in }
  }

  private func emitVisibleRange(for pageIndex: Int) {
    let offset = pageIndex - 10_000
    let anchor = vm.calendarEngine.date(byAdding: .month, value: offset, to: startOfCurrentMonth()) ?? Date()
    guard
      let prev = vm.calendarEngine.date(byAdding: .month, value: -1, to: anchor),
      let next = vm.calendarEngine.date(byAdding: .month, value: 2, to: anchor),
      let nextEnd = vm.calendarEngine.date(byAdding: .second, value: -1, to: next)
    else { return }
    vm.onVisibleRangeChange?(VisibleRangeChangeEvent(
      startMs: prev.timeIntervalSince1970 * 1000,
      endMs: nextEnd.timeIntervalSince1970 * 1000
    ))
  }

  private func startOfCurrentMonth() -> Date {
    let comps = vm.calendarEngine.dateComponents([.year, .month], from: Date())
    return vm.calendarEngine.date(from: comps) ?? Date()
  }
}

// MARK: - MonthGridPage (Task 3.4 — GeometryReader cell sizing, no UIScreen.main.bounds)

struct MonthGridPage: View {
  let monthOffset: Int
  @ObservedObject var vm: CalendarViewModel

  var body: some View {
    GeometryReader { geo in
      let columns = 7
      let cellW = geo.size.width / CGFloat(columns)
      let allItems = buildDayItems()

      // In collapsed week mode: show the week at vm.weekIndex
      let items: [DayItemModel] = {
        if vm.collapsedWeekMode {
          let totalWeeks = allItems.count / 7
          let safeIdx = min(vm.weekIndex, totalWeeks - 1)
          return Array(allItems.dropFirst(safeIdx * 7).prefix(7))
        }
        return allItems
      }()

      let rowCount = items.count / 7

      // Task 3.5: collapse animation — rowCount drives height
      LazyVGrid(
        columns: Array(repeating: GridItem(.fixed(cellW), spacing: 0), count: columns),
        spacing: 0
      ) {
        ForEach(0..<(rowCount * columns), id: \.self) { i in
          if i < items.count {
            DayCellView(item: items[i], vm: vm, cellWidth: cellW)
          } else {
            Color.clear.frame(width: cellW, height: CGFloat(vm.appearance.rowHeight ?? 40))
          }
        }
      }
      .frame(width: geo.size.width)
      .animation(.easeInOut(duration: 0.25), value: rowCount)
    }
  }

  private func buildDayItems() -> [DayItemModel] {
    let cal = vm.calendarEngine
    // Anchor from today's month + offset, not from selectedTimestampMs
    let todayComps = cal.dateComponents([.year, .month], from: Date())
    let todayMonthStart = cal.date(from: todayComps) ?? Date()
    guard let anchor = cal.date(byAdding: .month, value: monthOffset, to: todayMonthStart) else { return [] }
    let monthStart = startOfMonth(anchor)
    guard let monthRange = cal.range(of: .day, in: .month, for: monthStart) else { return [] }
    let firstWeekday = cal.component(.weekday, from: monthStart)
    let leading = (firstWeekday - cal.firstWeekday + 7) % 7
    var items: [DayItemModel] = []
    let total = ((leading + monthRange.count + 6) / 7) * 7
    let selectedDate = Date(timeIntervalSince1970: vm.selectedTimestampMs / 1000)
    for i in 0..<total {
      guard let date = cal.date(byAdding: .day, value: i - leading, to: monthStart) else { continue }
      let ms = date.timeIntervalSince1970 * 1000
      // isCurrentMonth is always relative to the page anchor month (the displayed month).
      // Grey days are those outside the anchor month, same in both week and month view.
      // Tapping a grey day in week view just selects it — it stays grey (same as legacy).
      let inMonth = cal.component(.month, from: date) == cal.component(.month, from: monthStart)
                 && cal.component(.year, from: date) == cal.component(.year, from: monthStart)
      let isToday = cal.isDateInToday(date)
      let isSelected = cal.isDate(date, inSameDayAs: selectedDate)
      let isDisabled = (vm.minTimestampMs.map { ms < $0 } ?? false) || (vm.maxTimestampMs.map { ms > $0 } ?? false)
      let dayKey = Int64(cal.startOfDay(for: date).timeIntervalSince1970 * 1000)
      let dots = min(3, vm.markers[dayKey]?.count ?? 0)
      items.append(DayItemModel(date: date, label: "\(cal.component(.day, from: date))",
        isCurrentMonth: inMonth, isToday: isToday, isSelected: isSelected,
        isDisabled: isDisabled, dotCount: dots))
    }
    return items
  }

  private func startOfMonth(_ date: Date) -> Date {
    let comps = vm.calendarEngine.dateComponents([.year, .month], from: date)
    return vm.calendarEngine.date(from: comps) ?? date
  }
}

// MARK: - DayCellView

struct DayCellView: View {
  let item: DayItemModel
  @ObservedObject var vm: CalendarViewModel
  let cellWidth: CGFloat

  var body: some View {
    let rowH = CGFloat(vm.appearance.rowHeight ?? 40)
    let textColor: Color = {
      if item.isDisabled { return Color(hex: vm.appearance.disabledDayTextColor) }
      if !item.isCurrentMonth { return Color(hex: vm.appearance.dayOutsideMonthTextColor) }
      if item.isSelected { return Color(hex: vm.appearance.selectedDayTextColor) }
      if item.isToday { return Color(hex: vm.appearance.todayTextColor) }
      return Color(hex: vm.appearance.dayTextColor)
    }()

    VStack(spacing: 2) {
      Text(item.label)
        .font(.system(size: CGFloat(vm.appearance.fontSizeDay ?? 14)))
        .foregroundColor(textColor)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
      if item.dotCount > 0 && item.isCurrentMonth {
        HStack(spacing: 2) {
          ForEach(0..<item.dotCount, id: \.self) { _ in
            Circle()
              .fill(Color(hex: vm.appearance.markerAccentColor ?? vm.appearance.todayTextColor))
              .frame(width: 4, height: 4)
          }
        }
      }
    }
    .frame(width: cellWidth, height: rowH)
    .background(item.isSelected && item.isCurrentMonth ? Color(hex: vm.appearance.selectedDayBackgroundColor) : Color.clear)
    .cornerRadius(CGFloat(vm.appearance.cornerRadius ?? 8))
    .opacity(item.isDisabled ? 0.4 : 1.0)
    .onTapGesture {
      // Grey (out-of-month) and disabled days are not tappable
      guard !item.isDisabled && item.isCurrentMonth else { return }
      let ms = item.date.timeIntervalSince1970 * 1000
      vm.selectedTimestampMs = ms
      vm.onDateChange?(DateChangeEvent(timestampMs: ms))
    }
  }
}

struct DayItemModel {
  let date: Date
  let label: String
  let isCurrentMonth: Bool
  let isToday: Bool
  let isSelected: Bool
  let isDisabled: Bool
  let dotCount: Int
}

// MARK: - MonthPickerView (Task 3.6)

struct MonthPickerView: View {
  @ObservedObject var vm: CalendarViewModel

  var body: some View {
    let names: [String] = {
      let full = vm.strings.monthNamesFull ?? vm.strings.monthNamesShort
      return full.count >= 12 ? Array(full.prefix(12)) : (0..<12).map { full.indices.contains($0) ? full[$0] : "\($0+1)" }
    }()
    let currentMonth = vm.calendarEngine.component(.month, from: Date(timeIntervalSince1970: vm.selectedTimestampMs / 1000)) - 1

    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 0), count: 3), spacing: 0) {
      ForEach(0..<12, id: \.self) { i in
        let isSelected = i == currentMonth
        Text(names[i])
          .font(.system(size: CGFloat(vm.appearance.fontSizeDay ?? 14)))
          .foregroundColor(Color(hex: isSelected
            ? (vm.appearance.pickerCellSelectedTextColor ?? vm.appearance.selectedDayTextColor)
            : (vm.appearance.pickerCellTextColor ?? vm.appearance.dayTextColor)))
          .frame(maxWidth: .infinity)
          .padding(.vertical, 10)
          .background(Color(hex: isSelected
            ? (vm.appearance.pickerCellSelectedBackgroundColor ?? vm.appearance.selectedDayBackgroundColor)
            : (vm.appearance.pickerCellBackgroundColor ?? vm.appearance.backgroundColor)))
          .cornerRadius(CGFloat(vm.appearance.cornerRadius ?? 8))
          .onTapGesture {
            try? vm.onDateChange.map { _ in }
            var comps = vm.calendarEngine.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: vm.selectedTimestampMs / 1000))
            comps.month = i + 1
            comps.day = min(comps.day ?? 1, 28)
            if let updated = vm.calendarEngine.date(from: comps) {
              vm.selectedTimestampMs = updated.timeIntervalSince1970 * 1000
            }
            vm.mode = vm.collapsedWeekMode ? .week : .day
            vm.onViewModeChange?(ViewModeChangeEvent(mode: vm.mode))
          }
      }
    }
    .padding(8)
  }
}

// MARK: - YearPickerView (Task 3.6)

struct YearPickerView: View {
  @ObservedObject var vm: CalendarViewModel

  var body: some View {
    let currentYear = vm.calendarEngine.component(.year, from: Date(timeIntervalSince1970: vm.selectedTimestampMs / 1000))

    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 0), count: 3), spacing: 0) {
      ForEach(0..<12, id: \.self) { i in
        let year = vm.yearSliceStart + i
        let isSelected = year == currentYear
        Text("\(year)")
          .font(.system(size: CGFloat(vm.appearance.fontSizeDay ?? 14)))
          .foregroundColor(Color(hex: isSelected
            ? (vm.appearance.pickerCellSelectedTextColor ?? vm.appearance.selectedDayTextColor)
            : (vm.appearance.pickerCellTextColor ?? vm.appearance.dayTextColor)))
          .frame(maxWidth: .infinity)
          .padding(.vertical, 10)
          .background(Color(hex: isSelected
            ? (vm.appearance.pickerCellSelectedBackgroundColor ?? vm.appearance.selectedDayBackgroundColor)
            : (vm.appearance.pickerCellBackgroundColor ?? vm.appearance.backgroundColor)))
          .cornerRadius(CGFloat(vm.appearance.cornerRadius ?? 8))
          .onTapGesture {
            var comps = vm.calendarEngine.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: vm.selectedTimestampMs / 1000))
            comps.year = year
            comps.day = min(comps.day ?? 1, 28)
            if let updated = vm.calendarEngine.date(from: comps) {
              vm.selectedTimestampMs = updated.timeIntervalSince1970 * 1000
            }
            vm.yearSliceStart = year - 6
            vm.mode = vm.collapsedWeekMode ? .week : .day
            vm.onViewModeChange?(ViewModeChangeEvent(mode: vm.mode))
          }
      }
    }
    .padding(8)
  }
}

// MARK: - Color(hex:) helper

extension Color {
  init(hex: String) {
    let s = hex.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "#", with: "")
    var v: UInt64 = 0
    Scanner(string: s).scanHexInt64(&v)
    let r, g, b: Double
    switch s.count {
    case 6:
      r = Double((v >> 16) & 0xFF) / 255
      g = Double((v >> 8) & 0xFF) / 255
      b = Double(v & 0xFF) / 255
    default:
      r = 0; g = 0; b = 0
    }
    self.init(red: r, green: g, blue: b)
  }
}
