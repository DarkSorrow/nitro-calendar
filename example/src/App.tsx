import { useMemo, useRef, useState } from 'react';
import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { callback } from 'react-native-nitro-modules';
import {
  NitroCalendarView,
  NitroWheelPickerView,
  type CalendarAppearance,
  type CalendarStrings,
  type NitroCalendarMethods,
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
  const [selectedTimestampMs, setSelectedTimestampMs] = useState(() => Date.now());
  const [collapsedWeekMode, setCollapsedWeekMode] = useState(false);
  const calendarRef = useRef<NitroCalendarMethods | null>(null);
  const selectedLabel = useMemo(
    () => new Date(selectedTimestampMs).toLocaleString('en-US', { dateStyle: 'full', timeStyle: 'short' }),
    [selectedTimestampMs],
  );

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Phase 2 Native Calendar Harness</Text>
      <Text style={styles.subtitle}>{selectedLabel}</Text>
      <View style={styles.actions}>
        <Pressable
          style={styles.actionButton}
          onPress={() => {
            calendarRef.current?.goToToday();
          }}
        >
          <Text style={styles.actionText}>Go To Today</Text>
        </Pressable>
        <Pressable
          style={styles.actionButton}
          onPress={() => {
            const next = !collapsedWeekMode;
            setCollapsedWeekMode(next);
            calendarRef.current?.setCollapsedWeekModeEnabled(next);
          }}
        >
          <Text style={styles.actionText}>{collapsedWeekMode ? 'Expand Month' : 'Collapse Week'}</Text>
        </Pressable>
      </View>
      <NitroCalendarView
        hybridRef={callback((ref: NitroCalendarMethods) => {
          calendarRef.current = ref;
        })}
        selectedTimestampMs={selectedTimestampMs}
        initialTimestampMs={selectedTimestampMs}
        calendarType="gregorian"
        isRTL={false}
        viewMode={collapsedWeekMode ? 'week' : 'day'}
        collapsedWeekMode={collapsedWeekMode}
        weekStartsOn={1}
        uses24HourClock
        localeId="en-US"
        appearance={calendarAppearance}
        appearanceKey="theme-default-v1"
        strings={calendarStrings}
        stringsKey="en-US-v1"
        onDateChange={callback(({ timestampMs }) => {
          setSelectedTimestampMs(timestampMs);
          console.log('onDateChange', timestampMs);
        })}
        onVisibleRangeChange={callback(({ startMs, endMs }) => console.log('onVisibleRangeChange', startMs, endMs))}
        onViewModeChange={callback(({ mode }) => console.log('onViewModeChange', mode))}
        style={styles.calendar}
      />
      <NitroWheelPickerView
        values={wheelValues}
        selectedIndex={0}
        itemHeight={36}
        visibleCount={5}
        onSettled={callback(({ index, value }) => console.log('onSettled', index, value))}
        style={styles.wheel}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    paddingTop: 56,
    paddingHorizontal: 16,
    gap: 8,
  },
  title: {
    fontSize: 20,
    fontWeight: '700',
    color: '#111827',
  },
  subtitle: {
    fontSize: 13,
    color: '#4b5563',
    marginBottom: 8,
  },
  actions: {
    flexDirection: 'row',
    gap: 8,
  },
  actionButton: {
    backgroundColor: '#2563eb',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  actionText: {
    color: '#ffffff',
    fontWeight: '600',
  },
  calendar: {
    width: '100%',
    height: 360,
    marginTop: 8,
  },
  wheel: {
    width: 220,
    height: 200,
    alignSelf: 'center',
    marginTop: 8,
  },
});
