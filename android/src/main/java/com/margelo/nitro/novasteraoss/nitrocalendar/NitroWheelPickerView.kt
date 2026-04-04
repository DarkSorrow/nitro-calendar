package com.margelo.nitro.novasteraoss.nitrocalendar

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@DoNotStrip
class HybridNitroWheelPickerView(private val context: ThemedReactContext) :
  HybridNitroWheelPickerViewSpec() {

  // MARK: - Root view

  private val rootView = FrameLayout(context)
  override val view: View = rootView

  // MARK: - RecyclerView

  private val rvLayout = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
  private val snapHelper = LinearSnapHelper()

  // Fling-scaled RecyclerView for iOS-like deceleration feel
  private val recycler = object : RecyclerView(context) {
    override fun fling(velocityX: Int, velocityY: Int): Boolean =
      super.fling(velocityX, (velocityY * FLING_SCALE).toInt())
  }

  // MARK: - Selection indicator lines

  private val topLine = View(context)
  private val bottomLine = View(context)

  // MARK: - Adapter (inner class for direct access to outer state)

  private inner class WheelAdapter : RecyclerView.Adapter<WheelAdapter.VH>() {
    inner class VH(val row: FrameLayout, val tv: TextView) : RecyclerView.ViewHolder(row)

    override fun getItemCount(): Int = totalItems

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
      val tv = TextView(parent.context).apply {
        gravity = Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        )
      }
      val row = FrameLayout(parent.context).apply {
        clipChildren = false
        layoutParams = RecyclerView.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          itemHeightPx,
        )
        addView(tv)
      }
      return VH(row, tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
      val values = _values
      if (values.isEmpty()) {
        holder.tv.text = ""
        resetWheelTransforms(holder.row)
        return
      }
      val logical = position % values.size
      val isSelected = logical == currentIndex

      resetWheelTransforms(holder.row)
      holder.row.layoutParams =
        RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeightPx)
      holder.tv.text = values[logical]
      holder.tv.textSize = resolvedFontSizeSp
      holder.tv.typeface = resolvedTypeface
      holder.tv.setTextColor(if (isSelected) resolvedSelectedColor else resolvedNormalColor)
      holder.tv.alpha = 1f
      holder.tv.setBackgroundColor(
        if (isSelected && resolvedSelectedBg != null) resolvedSelectedBg!! else Color.TRANSPARENT)
    }

    /** Rebind only the currently visible items — cheap path for selection changes. */
    fun refreshVisible() {
      val first = rvLayout.findFirstVisibleItemPosition()
      val last = rvLayout.findLastVisibleItemPosition()
      if (first >= 0 && last >= first) notifyItemRangeChanged(first, last - first + 1)
    }

    /** Full rebind — used after values/loop/appearance change. */
    fun rebindAll() = notifyDataSetChanged()
  }

  private val adapter = WheelAdapter()

  // MARK: - Resolved appearance cache (rebuilt in applyAppearance)

  private var resolvedNormalColor: Int = Color.parseColor("#111827")
  private var resolvedSelectedColor: Int = Color.parseColor("#111827")
  private var resolvedSelectedBg: Int? = null
  private var resolvedFontSizeSp: Float = 17f
  private var resolvedTypeface: Typeface = Typeface.DEFAULT

  // MARK: - Private state

  private var isSyncingFromScroll = false
  private var currentIndex: Int = 0
  private var lastEmittedIndex: Int = -1

  // MARK: - Computed helpers

  private val isLoop: Boolean get() = loop == true
  private val visibleN: Int get() = max(1, (visibleCount ?: 5.0).toInt())
  private val halfVisible: Int get() = floor(visibleN / 2.0).toInt()
  private val itemHeightPx: Int get() = dpToPx(itemHeight)
  private val totalItems: Int
    get() {
      if (_values.isEmpty()) return 0
      return if (isLoop) _values.size * 1000 else _values.size
    }
  private val loopMidpoint: Int
    get() {
      if (_values.isEmpty()) return 0
      val total = totalItems
      return (total / 2 / _values.size) * _values.size
    }

  // Backing field to avoid property setter recursion in scroll listener
  private var _values: Array<String> = emptyArray()

  // MARK: - Spec properties

  override var values: Array<String>
    get() = _values
    set(v) {
      _values = v
      if (_values.isNotEmpty()) currentIndex = min(currentIndex, _values.size - 1)
      recycler.post {
        adapter.rebindAll()
        jumpToIndex(currentIndex, animated = false)
        scheduleApplyWheel()
      }
    }

  override var selectedIndex: Double = 0.0
    set(v) {
      field = v
      if (isSyncingFromScroll) return
      val idx = normalizeIndex(v.roundToInt())
      currentIndex = idx
      jumpToIndex(idx, animated = false)
    }

  override var loop: Boolean? = null
    set(v) {
      field = v
      adapter.rebindAll()
      jumpToIndex(currentIndex, animated = false)
    }

  override var visibleCount: Double? = null
    set(v) {
      field = v
      updatePaddingAndIndicator()
    }

  override var itemHeight: Double = 36.0
    set(v) {
      field = v
      updatePaddingAndIndicator()
      adapter.rebindAll()
    }

  override var appearance: WheelPickerAppearance? = null
    set(v) {
      field = v
      applyAppearance()
    }

  override var onValueChange: ((event: WheelPickerValueChangeEvent) -> Unit)? = null
  override var onSettled: (event: WheelPickerValueChangeEvent) -> Unit = {}

  // MARK: - Init

  init {
    rootView.clipChildren = true

    // RecyclerView
    recycler.layoutManager = rvLayout
    recycler.adapter = adapter
    recycler.itemAnimator = null
    recycler.clipToPadding = false
    recycler.clipChildren = false
    recycler.overScrollMode = View.OVER_SCROLL_NEVER
    snapHelper.attachToRecyclerView(recycler)

    rootView.addView(recycler, FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.MATCH_PARENT))

    // Indicator lines (1dp height)
    val lineH = dpToPx(1.0)
    for (line in listOf(topLine, bottomLine)) {
      line.setBackgroundColor(Color.LTGRAY)
      rootView.addView(line, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, lineH))
    }

    recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
      override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
          val snapView = snapHelper.findSnapView(rvLayout) ?: return
          val pos = rvLayout.getPosition(snapView)
          if (pos == RecyclerView.NO_POSITION || _values.isEmpty()) return
          val logical = pos % _values.size
          commitSelection(logical, emitSettled = true)
        }
      }

      override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
        if (_values.isEmpty()) return
        val snapView = snapHelper.findSnapView(rvLayout)
        if (snapView != null) {
          val pos = rvLayout.getPosition(snapView)
          if (pos != RecyclerView.NO_POSITION) {
            val logical = pos % _values.size
            if (logical != currentIndex) {
              currentIndex = logical
              updateVisibleItemAppearances()
              emitValueChange(logical)
            }
          }
        }
        applyWheelPerspectiveToVisible()
      }
    })

    updatePaddingAndIndicator()
    applyAppearance()

    val globalWheelLayoutListener =
      object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
          if (recycler.width <= 0 || recycler.height <= 0) return
          recycler.viewTreeObserver.removeOnGlobalLayoutListener(this)
          scheduleApplyWheel()
        }
      }
    recycler.viewTreeObserver.addOnGlobalLayoutListener(globalWheelLayoutListener)

    recycler.post { scheduleApplyWheel() }
  }

  // MARK: - Spec method

  override fun scrollTo(index: Double) {
    if (_values.isEmpty()) return
    val logical = normalizeIndex(index.roundToInt())
    // Post so adapter notification doesn't land during an in-progress layout pass
    recycler.post {
      commitSelection(logical, emitSettled = false)
      jumpToIndex(logical, animated = true)
      scheduleApplyWheel()
    }
  }

  // MARK: - Private helpers

  /** Run after layout so row heights / pivots are non-zero (first paint + Fabric). */
  private fun scheduleApplyWheel() {
    recycler.post {
      applyWheelPerspectiveToVisible()
      recycler.post {
        applyWheelPerspectiveToVisible()
        recycler.postDelayed({ applyWheelPerspectiveToVisible() }, 48)
      }
    }
  }

  private fun commitSelection(logical: Int, emitSettled: Boolean) {
    currentIndex = logical
    isSyncingFromScroll = true
    selectedIndex = logical.toDouble()
    isSyncingFromScroll = false
    // Post adapter notification — RecyclerView forbids notifyItem* calls during any
    // layout/scroll pass, including SCROLL_STATE_IDLE transitions inside
    // consumePendingUpdateOperations. Posting defers to the next looper iteration.
    recycler.post {
      adapter.refreshVisible()
      scheduleApplyWheel()
    }
    if (emitSettled) emitSettled(logical)
  }

  private fun resetWheelTransforms(v: View) {
    val ph = (if (v.height > 0) v.height else itemHeightPx) / 2f
    val pw = (if (v.width > 0) v.width else max(1, recycler.width)) / 2f
    v.cameraDistance = 12000f * v.resources.displayMetrics.density
    v.pivotY = ph
    v.pivotX = pw
    v.rotationX = 0f
    v.scaleX = 1f
    v.scaleY = 1f
    v.translationZ = 0f
  }

  /** iOS-style drum: distance from vertical center → rotationX, scale, alpha, translationZ. */
  private fun applyWheelPerspectiveToVisible() {
    if (_values.isEmpty() || itemHeightPx <= 0 || recycler.height <= 0) return
    val focal = recycler.height / 2f
    val camDist = 12000f * context.resources.displayMetrics.density
    for (i in 0 until rvLayout.childCount) {
      val child = rvLayout.getChildAt(i) ?: continue
      val h = if (child.height > 0) child.height else itemHeightPx
      val w = if (child.width > 0) child.width else max(1, recycler.width)
      val childMid = child.top + h / 2f
      val d = (childMid - focal) / itemHeightPx.toFloat()
      val ad = abs(d)
      val wheelAlpha = max(0.22f, 1f - 0.34f * min(ad, 2.9f))
      val scale = max(0.76f, 1f - 0.125f * min(ad, 2.9f))
      val rot = (-d * 22f).coerceIn(-35.5f, 35.5f)
      child.cameraDistance = camDist
      child.pivotY = h / 2f
      child.pivotX = w / 2f
      child.rotationX = rot
      child.scaleX = scale
      child.scaleY = scale
      val pos = rvLayout.getPosition(child)
      if (pos == RecyclerView.NO_POSITION) continue
      val logical = pos % _values.size
      val isSelected = logical == currentIndex
      val emphasis = if (isSelected) 1f else 0.88f
      child.alpha = wheelAlpha * emphasis
      child.translationZ = (8f - ad * 1.2f).coerceAtLeast(0f)
    }
  }

  /**
   * Directly update the text color / background of visible children (safe during scroll).
   * Alpha / 3D come from [applyWheelPerspectiveToVisible].
   */
  private fun updateVisibleItemAppearances() {
    for (i in 0 until rvLayout.childCount) {
      val row = rvLayout.getChildAt(i) as? FrameLayout ?: continue
      val child = row.getChildAt(0) as? TextView ?: continue
      val pos = rvLayout.getPosition(row)
      if (pos == RecyclerView.NO_POSITION || _values.isEmpty()) continue
      val childLogical = pos % _values.size
      val isSelected = childLogical == currentIndex
      child.setTextColor(if (isSelected) resolvedSelectedColor else resolvedNormalColor)
      child.setBackgroundColor(
        if (isSelected && resolvedSelectedBg != null) resolvedSelectedBg!! else Color.TRANSPARENT)
    }
  }

  private fun normalizeIndex(idx: Int): Int {
    if (_values.isEmpty()) return 0
    return if (isLoop) {
      ((idx % _values.size) + _values.size) % _values.size
    } else {
      min(_values.size - 1, max(0, idx))
    }
  }

  private fun rowForLogical(logical: Int): Int {
    if (_values.isEmpty()) return 0
    return if (isLoop) loopMidpoint + logical else logical
  }

  private fun jumpToIndex(logical: Int, animated: Boolean) {
    if (_values.isEmpty()) return
    val targetPos = rowForLogical(logical)
    if (animated) {
      val scroller = object : androidx.recyclerview.widget.LinearSmoothScroller(context) {
        // Align center of target view with center of RecyclerView
        override fun calculateDtToFit(
          viewStart: Int, viewEnd: Int,
          boxStart: Int, boxEnd: Int,
          snapPreference: Int
        ): Int = (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
      }
      scroller.targetPosition = targetPos
      rvLayout.startSmoothScroll(scroller)
    } else {
      rvLayout.scrollToPositionWithOffset(targetPos, halfVisible * itemHeightPx)
      scheduleApplyWheel()
    }
  }

  private fun updatePaddingAndIndicator() {
    val padPx = halfVisible * itemHeightPx
    recycler.setPadding(0, padPx, 0, padPx)

    val centerTop = halfVisible * itemHeightPx

    (topLine.layoutParams as? FrameLayout.LayoutParams)?.apply {
      topMargin = centerTop
      topLine.layoutParams = this
    }
    (bottomLine.layoutParams as? FrameLayout.LayoutParams)?.apply {
      topMargin = centerTop + itemHeightPx
      bottomLine.layoutParams = this
    }
    recycler.post { scheduleApplyWheel() }
  }

  private fun applyAppearance() {
    val ap = appearance
    resolvedNormalColor = parseColor(ap?.textColor) ?: Color.parseColor("#111827")
    resolvedSelectedColor = parseColor(ap?.selectedTextColor) ?: resolvedNormalColor
    resolvedSelectedBg = parseColor(ap?.selectedBackgroundColor)

    resolvedFontSizeSp = (ap?.fontSize ?: 17.0).toFloat()

    resolvedTypeface = resolveTypeface(ap)

    val bg = parseColor(ap?.backgroundColor) ?: Color.WHITE
    rootView.setBackgroundColor(bg)

    val divColor = parseColor(ap?.dividerColor) ?: Color.LTGRAY
    topLine.setBackgroundColor(divColor)
    bottomLine.setBackgroundColor(divColor)

    adapter.rebindAll()
    recycler.post { scheduleApplyWheel() }
  }

  private fun emitValueChange(logical: Int) {
    if (_values.isEmpty() || logical == lastEmittedIndex) return
    lastEmittedIndex = logical
    onValueChange?.invoke(WheelPickerValueChangeEvent(logical.toDouble(), _values[logical]))
  }

  private fun emitSettled(logical: Int) {
    if (_values.isEmpty()) return
    onSettled(WheelPickerValueChangeEvent(logical.toDouble(), _values[logical]))
  }

  private fun resolveTypeface(ap: WheelPickerAppearance?): Typeface {
    val w = ap?.fontWeight ?: 400.0
    val style = if (w >= 600.0) Typeface.BOLD else Typeface.NORMAL
    val family = ap?.fontFamily
    return if (!family.isNullOrBlank()) {
      try { Typeface.create(family, style) } catch (_: Exception) { Typeface.defaultFromStyle(style) }
    } else {
      Typeface.defaultFromStyle(style)
    }
  }

  private fun parseColor(hex: String?): Int? {
    val s = hex?.trim() ?: return null
    val normalized = if (s.startsWith("#")) s else "#$s"
    return try { Color.parseColor(normalized) } catch (_: Exception) { null }
  }

  private fun dpToPx(dp: Double): Int =
    (dp * context.resources.displayMetrics.density).roundToInt()

  private companion object {
    const val FLING_SCALE = 0.7f
  }
}
