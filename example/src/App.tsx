import { View, StyleSheet } from 'react-native';
import {
  NitroCalendarView,
  NitroWheelPickerView,
  type CalendarAppearance,
  type CalendarStrings,
} from '@novastera-oss/nitro-calendar';

const calendarAppearance: CalendarAppearance = {
  backgroundColor: '#ffffff',
  headerTitleColor: '#111827',
  headerButtonColor: '#2563eb',
  weekdayTextColor: '#6b7280',
  dayTextColor: '#111827',
  dayOutsideMonthTextColor: '#9ca3af',
  selectedDayBackgroundColor: '#2563eb',
  selectedDayTextColor: '#ffffff',
  todayTextColor: '#2563eb',
  disabledDayTextColor: '#d1d5db',
  markerPalette: ['#ef4444', '#10b981', '#3b82f6'],
};

const calendarStrings: CalendarStrings = {
  monthNamesShort: ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'],
  weekdayNamesMin: ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'],
  headerToday: 'Today',
  headerBack: 'Back',
};

const wheelValues = ['00', '15', '30', '45'];

export default function App() {
  const now = Date.now();

  return (
    <View style={styles.container}>
      <NitroCalendarView
        selectedTimestampMs={now}
        initialTimestampMs={now}
        calendarType="gregorian"
        isRTL={false}
        viewMode="day"
        collapsedWeekMode={false}
        weekStartsOn={1}
        uses24HourClock
        localeId="en-US"
        appearance={calendarAppearance}
        appearanceKey="theme-default-v1"
        strings={calendarStrings}
        stringsKey="en-US-v1"
        onDateChange={({ timestampMs }) => console.log(timestampMs)}
        onVisibleRangeChange={({ startMs, endMs }) => console.log(startMs, endMs)}
        onViewModeChange={({ mode }) => console.log(mode)}
        style={styles.calendar}
      />
      <NitroWheelPickerView
        values={wheelValues}
        selectedIndex={0}
        itemHeight={36}
        visibleCount={5}
        onSettled={({ index, value }) => console.log(index, value)}
        style={styles.wheel}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'flex-start',
    paddingTop: 64,
  },
  calendar: {
    width: 320,
    height: 300,
    marginVertical: 20,
  },
  wheel: {
    width: 220,
    height: 220,
    marginVertical: 20,
  },
});
