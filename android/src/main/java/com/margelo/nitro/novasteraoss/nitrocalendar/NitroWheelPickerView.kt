package com.margelo.nitro.novasteraoss.nitrocalendar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A FrameLayout that intercepts touch events and tells any ancestor ScrollView
 * (including React Native's ScrollView) to stop intercepting once the user
 * starts a vertical drag inside this view.
 */
@SuppressLint("ViewConstructor")
private class ScrollInterceptFrameLayout(context: Context) : FrameLayout(context) {
  private var startX = 0f
  private var startY = 0f

  override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        startX = ev.x
        startY = ev.y
        // Always claim touch on DOWN so we can evaluate the gesture
        parent?.requestDisallowInterceptTouchEvent(true)
      }
      MotionEvent.ACTION_MOVE -> {
        val dx = abs(ev.x - startX)
        val dy = abs(ev.y - startY)
        if (dy > dx) {
          // Vertical drag — keep the lock so parent ScrollView can't steal it
          parent?.requestDisallowInterceptTouchEvent(true)
        } else {
          // Horizontal drag — release so parent can handle horizontal scroll if needed
          parent?.requestDisallowInterceptTouchEvent(false)
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        parent?.requestDisallowInterceptTouchEvent(false)
      }
    }
    return false // Let children (ComposeView) handle the event
  }
}

@DoNotStrip
class HybridNitroWheelPickerView(private val context: ThemedReactContext) :
  HybridNitroWheelPickerViewSpec() {

  // Wrap ComposeView in ScrollInterceptFrameLayout so vertical drags are
  // never stolen by a parent React Native ScrollView
  private val wrapper = ScrollInterceptFrameLayout(context)
  private val composeView = ComposeView(context)
  override val view: View = wrapper

  private var _values by mutableStateOf<Array<String>>(emptyArray())
  private var _selectedIndex by mutableStateOf(0)
  private var _loop by mutableStateOf(false)
  private var _visibleCount by mutableStateOf(5)
  private var _itemHeight by mutableStateOf(36.0)
  private var _appearance by mutableStateOf<WheelPickerAppearance?>(null)

  override var values: Array<String> = emptyArray()
    set(v) { field = v; _values = v; if (v.isNotEmpty()) _selectedIndex = min(_selectedIndex, v.size - 1) }
  override var selectedIndex: Double = 0.0
    set(v) { field = v; _selectedIndex = normalizeIndex(v.roundToInt()) }
  override var loop: Boolean? = null
    set(v) { field = v; _loop = v ?: false }
  override var visibleCount: Double? = null
    set(v) { field = v; _visibleCount = max(1, (v ?: 5.0).toInt()) }
  override var itemHeight: Double = 36.0
    set(v) { field = v; _itemHeight = v }
  override var appearance: WheelPickerAppearance? = null
    set(v) { field = v; _appearance = v }
  override var onValueChange: ((event: WheelPickerValueChangeEvent) -> Unit)? = null
  override var onSettled: (event: WheelPickerValueChangeEvent) -> Unit = {}

  init {
    wrapper.addView(
      composeView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    )
    composeView.setContent { WheelRoot() }
  }

  override fun scrollTo(index: Double) {
    val idx = normalizeIndex(index.roundToInt())
    _selectedIndex = idx
    selectedIndex = idx.toDouble()
    if (_values.isNotEmpty()) {
      onSettled(WheelPickerValueChangeEvent(idx.toDouble(), _values[idx]))
    }
  }

  private fun normalizeIndex(idx: Int): Int {
    if (_values.isEmpty()) return 0
    return if (_loop) ((idx % _values.size) + _values.size) % _values.size
    else min(_values.size - 1, max(0, idx))
  }

  @Composable
  private fun WheelRoot() {
    val vals = _values
    if (vals.isEmpty()) return
    val ap = _appearance
    val itemH = _itemHeight.dp
    val visN = _visibleCount
    val halfVis = floor(visN / 2.0).toInt()

    val dataCount = if (_loop) vals.size * 1000 else vals.size
    val loopMid = if (_loop) (dataCount / 2 / vals.size) * vals.size else 0
    val totalListItems = dataCount + halfVis * 2

    fun listToData(listIdx: Int) = listIdx - halfVis
    fun dataToList(dataIdx: Int) = dataIdx + halfVis

    val initDataPos = if (_loop) loopMid + _selectedIndex else _selectedIndex
    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = dataToList(initDataPos))
    val snapBehavior = rememberSnapFlingBehavior(lazyListState)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    LaunchedEffect(lazyListState) {
      snapshotFlow { lazyListState.isScrollInProgress }
        .filter { !it }
        .collect {
          val centeredListIdx = lazyListState.firstVisibleItemIndex
          val dataIdx = listToData(centeredListIdx).coerceIn(0, dataCount - 1)
          val logical = dataIdx % vals.size
          if (logical != _selectedIndex) {
            _selectedIndex = logical
            selectedIndex = logical.toDouble()
            onValueChange?.invoke(WheelPickerValueChangeEvent(logical.toDouble(), vals[logical]))
          }
          onSettled(WheelPickerValueChangeEvent(logical.toDouble(), vals[logical]))
        }
    }

    LaunchedEffect(_selectedIndex) {
      val targetDataPos = if (_loop) loopMid + _selectedIndex else _selectedIndex
      val targetListIdx = dataToList(targetDataPos)
      if (abs(lazyListState.firstVisibleItemIndex - targetListIdx) > 0) {
        scope.launch { lazyListState.animateScrollToItem(targetListIdx) }
      }
    }

    val bgColor = parseColor(ap?.backgroundColor ?: "#FFFFFF")
    val frameH = itemH * visN

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(frameH)
        .background(bgColor)
    ) {
      LazyColumn(
        state = lazyListState,
        flingBehavior = snapBehavior,
        modifier = Modifier.fillMaxWidth().height(frameH)
      ) {
        items(totalListItems) { listIdx ->
          val dataIdx = listToData(listIdx)
          if (dataIdx < 0 || dataIdx >= dataCount) {
            Box(modifier = Modifier.fillMaxWidth().height(itemH))
          } else {
            val logical = dataIdx % vals.size
            WheelCell(
              value = vals[logical],
              itemHeightDp = _itemHeight,
              appearance = ap,
              listState = lazyListState,
              listIndex = listIdx,
              densityVal = density.density
            )
          }
        }
      }

      val lineColor = parseColor(ap?.dividerColor ?: "#CCCCCC")
      Box(modifier = Modifier.fillMaxWidth().height(1.dp).offset(y = itemH * halfVis).background(lineColor))
      Box(modifier = Modifier.fillMaxWidth().height(1.dp).offset(y = itemH * (halfVis + 1)).background(lineColor))
    }
  }

  @Composable
  private fun WheelCell(
    value: String,
    itemHeightDp: Double,
    appearance: WheelPickerAppearance?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    listIndex: Int,
    densityVal: Float
  ) {
    val ap = appearance
    val itemH = itemHeightDp.dp
    val textColor = parseColor(ap?.textColor ?: "#111827")
    val fontSize = (ap?.fontSize ?: 17.0).sp
    val fontWeight = if ((ap?.fontWeight ?: 400.0) >= 600) FontWeight.SemiBold else FontWeight.Normal
    val layoutInfo = listState.layoutInfo

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(itemH)
        .graphicsLayer {
          val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == listIndex }
          if (itemInfo != null) {
            val viewportCenter = layoutInfo.viewportSize.height / 2f
            val itemCenter = itemInfo.offset + itemInfo.size / 2f
            val distance = (itemCenter - viewportCenter) / (itemHeightDp.toFloat() * densityVal)
            val ad = abs(distance)
            rotationX = (-distance * 22f).coerceIn(-35.5f, 35.5f)
            val s = (1f - 0.125f * min(ad, 2.9f)).coerceAtLeast(0.76f)
            scaleX = s; scaleY = s
            alpha = (1f - 0.34f * min(ad, 2.9f)).coerceAtLeast(0.22f)
            cameraDistance = 12f * densityVal
          }
        },
      contentAlignment = Alignment.Center
    ) {
      Text(text = value, color = textColor, fontSize = fontSize, fontWeight = fontWeight)
    }
  }

  private fun parseColor(hex: String): Color {
    return try { Color(AndroidColor.parseColor(if (hex.startsWith("#")) hex else "#$hex")) }
    catch (_: Throwable) { Color.Black }
  }
}
