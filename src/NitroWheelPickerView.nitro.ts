import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules';
import type { FontWeight } from './NitroCalendar.nitro';

export type WheelPickerAppearance = {
  backgroundColor?: string;
  textColor: string;
  selectedTextColor?: string;
  selectedBackgroundColor?: string;
  fontFamily?: string;
  fontSize?: number;
  fontWeight?: FontWeight;
  dividerColor?: string;
};

export type WheelPickerValueChangeEvent = {
  index: number;
  value: string;
};

export interface NitroWheelPickerViewProps extends HybridViewProps {
  values: string[];
  selectedIndex: number;
  loop?: boolean;
  visibleCount?: number;
  itemHeight: number;
  appearance?: WheelPickerAppearance;
  onValueChange?: (event: WheelPickerValueChangeEvent) => void;
  onSettled: (event: WheelPickerValueChangeEvent) => void;
}

export interface NitroWheelPickerViewMethods extends HybridViewMethods {
  scrollTo(index: number): void;
}

export type NitroWheelPickerView = HybridView<
  NitroWheelPickerViewProps,
  NitroWheelPickerViewMethods
>;
