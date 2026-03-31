import { getHostComponent } from 'react-native-nitro-modules';
const NitroCalendarConfig = require('../nitrogen/generated/shared/json/NitroCalendarConfig.json');
const NitroWheelPickerViewConfig = require('../nitrogen/generated/shared/json/NitroWheelPickerViewConfig.json');
import type {
  CalendarAppearance,
  CalendarStrings,
  CalendarType,
  DayMarkerCompact,
  NitroCalendarMethods,
  NitroCalendarProps,
} from './NitroCalendar.nitro';
import type {
  NitroWheelPickerViewMethods,
  NitroWheelPickerViewProps,
  WheelPickerAppearance,
} from './NitroWheelPickerView.nitro';

export const NitroCalendarView = getHostComponent<
  NitroCalendarProps,
  NitroCalendarMethods
>('NitroCalendar', () => NitroCalendarConfig);

export const NitroWheelPickerView = getHostComponent<
  NitroWheelPickerViewProps,
  NitroWheelPickerViewMethods
>('NitroWheelPickerView', () => NitroWheelPickerViewConfig);

export type {
  CalendarAppearance,
  CalendarStrings,
  CalendarType,
  DayMarkerCompact,
  NitroCalendarMethods,
  NitroCalendarProps,
  NitroWheelPickerViewMethods,
  NitroWheelPickerViewProps,
  WheelPickerAppearance,
};
