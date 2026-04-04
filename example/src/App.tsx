import { useMemo, useRef, useState } from 'react';
import React from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { callback } from 'react-native-nitro-modules';
import {
  NitroCalendarView,
  NitroWheelPickerView,
  type CalendarAppearance,
  type CalendarStrings,
  type NitroCalendarMethods,
  type NitroWheelPickerViewMethods,
  type WheelPickerAppearance,
} from '@novastera-oss/nitro-calendar';

// ── Shared appearance objects (stable references) ──────────────────────────

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
  monthNamesShort: [
    'Jan',
    'Feb',
    'Mar',
    'Apr',
    'May',
    'Jun',
    'Jul',
    'Aug',
    'Sep',
    'Oct',
    'Nov',
    'Dec',
  ],
  weekdayNamesMin: ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'],
  headerToday: 'Today',
  headerBack: 'Back',
};

const wheelAppearance: WheelPickerAppearance = {
  backgroundColor: '#ffffff',
  textColor: '#111827',
  selectedTextColor: '#2563eb',
  dividerColor: '#d1d5db',
  fontSize: 17,
};

// ── Value arrays ────────────────────────────────────────────────────────────

const HOURS_24 = Array.from({ length: 24 }, (_, i) =>
  String(i).padStart(2, '0')
);
const HOURS_12 = Array.from({ length: 12 }, (_, i) =>
  String(i === 0 ? 12 : i).padStart(2, '0')
);
const MINUTES = Array.from({ length: 60 }, (_, i) =>
  String(i).padStart(2, '0')
);
const PERIOD = ['AM', 'PM'];
const STANDALONE_VALUES = ['00', '15', '30', '45'];

// ── Main app ────────────────────────────────────────────────────────────────

export default function App() {
  const [selectedTimestampMs, setSelectedTimestampMs] = useState(() =>
    Date.now()
  );
  const [collapsedWeekMode, setCollapsedWeekMode] = useState(false);
  const [use24h, setUse24h] = useState(true);
  const [settledLog, setSettledLog] = useState<string[]>([]);

  const calendarRef = useRef<NitroCalendarMethods | null>(null);
  const hourRef = useRef<NitroWheelPickerViewMethods | null>(null);
  const minuteRef = useRef<NitroWheelPickerViewMethods | null>(null);

  const selectedLabel = useMemo(
    () =>
      new Date(selectedTimestampMs).toLocaleString('en-US', {
        dateStyle: 'full',
        timeStyle: 'short',
      }),
    [selectedTimestampMs]
  );

  const now = new Date();
  const initialHour = use24h ? now.getHours() : (now.getHours() % 12 || 12) - 1;
  const initialMinute = now.getMinutes();
  const initialPeriod = now.getHours() < 12 ? 0 : 1;

  function addLog(msg: string) {
    setSettledLog((prev) => [msg, ...prev].slice(0, 6));
  }

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container}>
      {/* ── Calendar section ── */}
      <Text style={styles.sectionTitle}>Calendar (Phase 2)</Text>
      <Text style={styles.subtitle}>{selectedLabel}</Text>

      <View style={styles.actions}>
        <Pressable
          style={styles.btn}
          onPress={() => calendarRef.current?.goToToday()}
        >
          <Text style={styles.btnText}>Today</Text>
        </Pressable>
        <Pressable
          style={styles.btn}
          onPress={() => {
            const next = !collapsedWeekMode;
            setCollapsedWeekMode(next);
            calendarRef.current?.setCollapsedWeekModeEnabled(next);
          }}
        >
          <Text style={styles.btnText}>
            {collapsedWeekMode ? 'Expand' : 'Week'}
          </Text>
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
        uses24HourClock={use24h}
        localeId="en-US"
        appearance={calendarAppearance}
        appearanceKey="theme-default-v1"
        strings={calendarStrings}
        stringsKey="en-US-v1"
        onDateChange={callback(({ timestampMs }) =>
          setSelectedTimestampMs(timestampMs)
        )}
        onVisibleRangeChange={callback(() => {})}
        onViewModeChange={callback(() => {})}
        style={styles.calendar}
      />

      {/* ── Time picker section (Phase 3) ── */}
      <Text style={styles.sectionTitle}>Time Picker (Phase 3)</Text>

      <View style={styles.actions}>
        <Pressable
          style={[styles.btn, !use24h && styles.btnOutline]}
          onPress={() => setUse24h(true)}
        >
          <Text style={[styles.btnText, !use24h && styles.btnTextOutline]}>
            24h
          </Text>
        </Pressable>
        <Pressable
          style={[styles.btn, use24h && styles.btnOutline]}
          onPress={() => setUse24h(false)}
        >
          <Text style={[styles.btnText, use24h && styles.btnTextOutline]}>
            12h
          </Text>
        </Pressable>
        <Pressable
          style={styles.btn}
          onPress={() => {
            hourRef.current?.scrollTo(0);
            minuteRef.current?.scrollTo(0);
          }}
        >
          <Text style={styles.btnText}>Reset</Text>
        </Pressable>
      </View>

      {/* Composed time wheels */}
      <View style={styles.wheelRow}>
        {/* Hour wheel */}
        <NitroWheelPickerView
          hybridRef={callback((ref: NitroWheelPickerViewMethods) => {
            hourRef.current = ref;
          })}
          values={use24h ? HOURS_24 : HOURS_12}
          selectedIndex={initialHour}
          loop
          visibleCount={5}
          itemHeight={44}
          appearance={wheelAppearance}
          onSettled={callback(({ index, value }) =>
            addLog(`hour settled → ${value} (idx ${index})`)
          )}
          onValueChange={callback(({ value }) =>
            console.log('hour scrolling', value)
          )}
          style={styles.wheel}
        />

        <Text style={styles.colon}>:</Text>

        {/* Minute wheel */}
        <NitroWheelPickerView
          hybridRef={callback((ref: NitroWheelPickerViewMethods) => {
            minuteRef.current = ref;
          })}
          values={MINUTES}
          selectedIndex={initialMinute}
          loop
          visibleCount={5}
          itemHeight={44}
          appearance={wheelAppearance}
          onSettled={callback(({ index, value }) =>
            addLog(`min settled → ${value} (idx ${index})`)
          )}
          style={styles.wheel}
        />

        {/* AM/PM wheel (12h only) */}
        {!use24h && (
          <NitroWheelPickerView
            values={PERIOD}
            selectedIndex={initialPeriod}
            loop={false}
            visibleCount={5}
            itemHeight={44}
            appearance={wheelAppearance}
            onSettled={callback(({ value }) =>
              addLog(`period settled → ${value}`)
            )}
            style={styles.wheelPeriod}
          />
        )}
      </View>

      {/* ── Standalone wheel (loop on/off test) ── */}
      <Text style={styles.sectionTitle}>Standalone Wheel — loop test</Text>
      <View style={styles.wheelRow}>
        <View style={styles.wheelLabelCol}>
          <Text style={styles.label}>loop=true</Text>
          <NitroWheelPickerView
            values={STANDALONE_VALUES}
            selectedIndex={0}
            loop
            visibleCount={5}
            itemHeight={40}
            appearance={wheelAppearance}
            onSettled={callback(({ index, value }) =>
              addLog(`loop settled → ${value} (idx ${index})`)
            )}
            style={styles.wheelSmall}
          />
        </View>
        <View style={styles.wheelLabelCol}>
          <Text style={styles.label}>loop=false</Text>
          <NitroWheelPickerView
            values={STANDALONE_VALUES}
            selectedIndex={0}
            loop={false}
            visibleCount={5}
            itemHeight={40}
            appearance={wheelAppearance}
            onSettled={callback(({ index, value }) =>
              addLog(`no-loop settled → ${value} (idx ${index})`)
            )}
            style={styles.wheelSmall}
          />
        </View>
      </View>

      {/* ── Event log ── */}
      {settledLog.length > 0 && (
        <View style={styles.logBox}>
          <Text style={styles.logTitle}>onSettled log</Text>
          {settledLog.map((line, i) => (
            <Text key={i} style={styles.logLine}>
              {line}
            </Text>
          ))}
        </View>
      )}
    </ScrollView>
  );
}

// ── Styles ──────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  scroll: { flex: 1 },
  container: {
    paddingTop: 56,
    paddingHorizontal: 16,
    paddingBottom: 40,
    gap: 8,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: '#111827',
    marginTop: 12,
  },
  subtitle: {
    fontSize: 13,
    color: '#4b5563',
  },
  actions: {
    flexDirection: 'row',
    gap: 8,
    flexWrap: 'wrap',
  },
  btn: {
    backgroundColor: '#2563eb',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  btnOutline: {
    backgroundColor: 'transparent',
    borderWidth: 1,
    borderColor: '#2563eb',
  },
  btnText: {
    color: '#ffffff',
    fontWeight: '600',
    fontSize: 14,
  },
  btnTextOutline: {
    color: '#2563eb',
  },
  calendar: {
    width: '100%',
    height: 360,
    marginTop: 4,
  },
  wheelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
    marginTop: 4,
  },
  wheel: {
    width: 80,
    height: 220,
  },
  wheelPeriod: {
    width: 64,
    height: 220,
  },
  wheelSmall: {
    width: 80,
    height: 200,
  },
  wheelLabelCol: {
    alignItems: 'center',
    gap: 4,
  },
  colon: {
    fontSize: 24,
    fontWeight: '700',
    color: '#111827',
    paddingBottom: 4,
  },
  label: {
    fontSize: 12,
    color: '#6b7280',
  },
  logBox: {
    backgroundColor: '#f9fafb',
    borderRadius: 8,
    padding: 12,
    marginTop: 8,
    gap: 2,
  },
  logTitle: {
    fontSize: 12,
    fontWeight: '600',
    color: '#374151',
    marginBottom: 4,
  },
  logLine: {
    fontSize: 12,
    color: '#4b5563',
    fontFamily: 'monospace',
  },
});
