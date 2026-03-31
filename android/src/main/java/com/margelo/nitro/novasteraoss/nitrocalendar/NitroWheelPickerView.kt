package com.margelo.nitro.novasteraoss.nitrocalendar

import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import kotlin.math.max
import kotlin.math.min

@DoNotStrip
class HybridNitroWheelPickerView(private val context: ThemedReactContext) : HybridNitroWheelPickerViewSpec() {
  private val label = TextView(context).apply {
    gravity = Gravity.CENTER
    text = "--"
  }

  override val view: View = label

  override var values: Array<String> = emptyArray()
    set(value) {
      field = value
      updateLabel()
    }

  override var selectedIndex: Double = 0.0
    set(value) {
      field = value
      updateLabel()
    }

  override var loop: Boolean? = null
  override var visibleCount: Double? = 5.0
  override var itemHeight: Double = 36.0
  override var appearance: WheelPickerAppearance? = null

  override var onValueChange: ((event: WheelPickerValueChangeEvent) -> Unit)? = null
  override var onSettled: (event: WheelPickerValueChangeEvent) -> Unit = {}

  override fun scrollTo(index: Double) {
    selectedIndex = index
    emitSelectionEvents()
  }

  private fun updateLabel() {
    if (values.isEmpty()) {
      label.text = "--"
      return
    }
    val index = normalizeIndex(selectedIndex.toInt())
    label.text = values[index]
  }

  private fun emitSelectionEvents() {
    if (values.isEmpty()) return
    val index = normalizeIndex(selectedIndex.toInt())
    val event = WheelPickerValueChangeEvent(index.toDouble(), values[index])
    onValueChange?.invoke(event)
    onSettled(event)
  }

  private fun normalizeIndex(index: Int): Int {
    if (values.isEmpty()) return 0
    return if (loop == true) {
      ((index % values.size) + values.size) % values.size
    } else {
      min(values.size - 1, max(0, index))
    }
  }
}
