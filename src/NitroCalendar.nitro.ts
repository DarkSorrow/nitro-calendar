import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules';

export type CalendarViewMode = 'day' | 'month' | 'year' | 'week';

/** CSS font-weight numeric value; use 400, 500, 600, or 700 (avoids Nitro string-union → broken Swift enum codegen). */
export type FontWeight = number;

export type CalendarType =
  | 'gregorian'
  | 'islamic'
  | 'chinese'
  | 'indian'
  | 'japanese'
  | 'buddhist'
  | 'hebrew'
  | 'persian';

export type DayMarkerCompact = {
  timestampMs: number;
  dotIndices: number[];
};

export type CalendarAppearance = {
  backgroundColor: string;
  separatorColor?: string;
  headerBackgroundColor?: string;
  headerTitleColor: string;
  headerSubtitleColor?: string;
  headerButtonColor: string;
  headerTodayColor?: string;
  weekdayTextColor: string;
  weekdayFontSize?: number;
  weekdayFontWeight?: FontWeight;
  dayTextColor: string;
  dayOutsideMonthTextColor: string;
  selectedDayBackgroundColor: string;
  selectedDayTextColor: string;
  todayTextColor: string;
  todayIndicatorColor?: string;
  disabledDayTextColor: string;
  pickerCellBackgroundColor?: string;
  pickerCellSelectedBackgroundColor?: string;
  pickerCellTextColor?: string;
  pickerCellSelectedTextColor?: string;
  fontFamily?: string;
  fontSizeDay?: number;
  fontSizeHeader?: number;
  fontWeight?: FontWeight;
  dayCellSize?: number;
  rowHeight?: number;
  headerHeight?: number;
  spacing?: number;
  cornerRadius?: number;
  borderColor?: string;
  borderWidth?: number;
  markerPalette: string[];
  markerAccentColor?: string;
};

export type CalendarStrings = {
  monthNamesShort: string[];
  monthNamesFull?: string[];
  weekdayNamesMin: string[];
  headerToday: string;
  headerBack: string;
  labelShowWeekView?: string;
  labelShowMonthView?: string;
  accessibilityPrev?: string;
  accessibilityNext?: string;
};

export type DateChangeEvent = { timestampMs: number };
export type VisibleRangeChangeEvent = { startMs: number; endMs: number };
export type ViewModeChangeEvent = { mode: CalendarViewMode };

export interface NitroCalendarProps extends HybridViewProps {
  selectedTimestampMs: number;
  initialTimestampMs?: number;
  calendarType: CalendarType;
  isRTL: boolean;
  timeZoneId?: string;
  viewMode: CalendarViewMode;
  collapsedWeekMode: boolean;
  weekStartsOn: 0 | 1 | 2 | 3 | 4 | 5 | 6;
  uses24HourClock?: boolean;
  localeId?: string;
  appearance: CalendarAppearance;
  appearanceKey?: string;
  strings: CalendarStrings;
  stringsKey?: string;
  minTimestampMs?: number;
  maxTimestampMs?: number;
  onDateChange?: (event: DateChangeEvent) => void;
  onVisibleRangeChange?: (event: VisibleRangeChangeEvent) => void;
  onViewModeChange?: (event: ViewModeChangeEvent) => void;
}
export interface NitroCalendarMethods extends HybridViewMethods {
  goToToday(): void;
  goToMonth(monthIndex: number): void;
  goToYear(year: number): void;
  setCollapsedWeekModeEnabled(enabled: boolean): void;
  setMarkers(markers: DayMarkerCompact[]): void;
}

export type NitroCalendar = HybridView<
  NitroCalendarProps,
  NitroCalendarMethods
>;
