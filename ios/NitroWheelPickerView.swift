import Foundation
import UIKit
import NitroModules

// MARK: - Root (intrinsic height for Yoga + layout callback)

private final class WheelPickerRootView: UIView {
  var preferredHeight: CGFloat = 180 {
    didSet {
      if oldValue != preferredHeight {
        invalidateIntrinsicContentSize()
      }
    }
  }

  var onLayoutSubviews: (() -> Void)?

  override var intrinsicContentSize: CGSize {
    CGSize(width: UIView.noIntrinsicMetric, height: preferredHeight)
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    onLayoutSubviews?()
  }
}

private final class WheelTextCell: UICollectionViewCell {
  let title = UILabel()

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .clear
    contentView.backgroundColor = .clear
    title.textAlignment = .center
    title.numberOfLines = 1
    title.lineBreakMode = .byTruncatingTail
    title.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    title.frame = contentView.bounds
    contentView.addSubview(title)
    layer.zPosition = 0
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) has not been implemented")
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    title.frame = contentView.bounds
  }

  override func prepareForReuse() {
    super.prepareForReuse()
    contentView.layer.transform = CATransform3DIdentity
    contentView.alpha = 1
    layer.zPosition = 0
  }
}

// MARK: - Hybrid view (UICollectionView — mirrors Android RecyclerView wheel)

/// Vertical wheel: `UICollectionView` + insets + snap, with **3D row tilt / scale / falloff** so neighbors
/// read like a native picker (not a flat list). See ``WheelTextCell``.
class HybridNitroWheelPickerView: HybridNitroWheelPickerViewSpec {

  var view: UIView

  private let root = WheelPickerRootView()
  private let flow = UICollectionViewFlowLayout()
  fileprivate lazy var collection: UICollectionView = UICollectionView(frame: .zero, collectionViewLayout: flow)
  private let topLine = UIView()
  private let bottomLine = UIView()
  private lazy var coordinator = WheelPickerCoordinator(owner: self)

  private var lastLaidOutWidth: CGFloat = 0

  var values: [String] = [] {
    didSet {
      collection.reloadData()
      if !values.isEmpty {
        currentIndex = min(currentIndex, values.count - 1)
      }
      collection.layoutIfNeeded()
      jumpToLogicalIndex(currentIndex, animated: false)
    }
  }

  var selectedIndex: Double = 0 {
    didSet {
      guard !isSyncingFromScroll else { return }
      let idx = normalizeIndex(Int(selectedIndex.rounded()))
      currentIndex = idx
      jumpToLogicalIndex(idx, animated: false)
    }
  }

  var loop: Bool? {
    didSet {
      collection.reloadData()
      collection.layoutIfNeeded()
      jumpToLogicalIndex(currentIndex, animated: false)
    }
  }

  var visibleCount: Double? {
    didSet {
      updateChromeMetrics()
      collection.layoutIfNeeded()
      if !values.isEmpty {
        jumpToLogicalIndex(currentIndex, animated: false)
      }
    }
  }

  var itemHeight: Double = 36 {
    didSet {
      flow.invalidateLayout()
      collection.reloadData()
      updateChromeMetrics()
      collection.layoutIfNeeded()
      jumpToLogicalIndex(currentIndex, animated: false)
    }
  }

  var appearance: WheelPickerAppearance? {
    didSet { applyAppearance() }
  }

  var onValueChange: ((_ event: WheelPickerValueChangeEvent) -> Void)?
  var onSettled: (_ event: WheelPickerValueChangeEvent) -> Void = { _ in }

  fileprivate var isSyncingFromScroll = false
  fileprivate var currentIndex: Int = 0
  fileprivate var lastEmittedIndex: Int = -1

  fileprivate var itemH: CGFloat { max(32, CGFloat(itemHeight)) }
  fileprivate var visibleN: Int { max(1, Int(visibleCount ?? 5)) }
  fileprivate var halfVisible: Int { Int(floor(Double(visibleN) / 2.0)) }
  fileprivate var paddingTop: CGFloat { CGFloat(halfVisible) * itemH }
  fileprivate var isLoop: Bool { loop == true }

  fileprivate var totalItems: Int {
    guard !values.isEmpty else { return 0 }
    return isLoop ? values.count * 1000 : values.count
  }

  fileprivate var loopMidpoint: Int {
    guard !values.isEmpty else { return 0 }
    let total = totalItems
    return (total / 2 / values.count) * values.count
  }

  required override init() {
    view = root
    super.init()

    root.backgroundColor = .white
    root.clipsToBounds = true

    flow.scrollDirection = .vertical
    flow.minimumLineSpacing = 0
    flow.minimumInteritemSpacing = 0
    flow.sectionInset = .zero

    collection.backgroundColor = .clear
    collection.dataSource = coordinator
    collection.delegate = coordinator
    collection.showsVerticalScrollIndicator = false
    collection.showsHorizontalScrollIndicator = false
    collection.alwaysBounceVertical = false
    collection.decelerationRate = .fast
    collection.register(WheelTextCell.self, forCellWithReuseIdentifier: "cell")
    collection.translatesAutoresizingMaskIntoConstraints = false

    root.addSubview(collection)
    NSLayoutConstraint.activate([
      collection.topAnchor.constraint(equalTo: root.topAnchor),
      collection.leadingAnchor.constraint(equalTo: root.leadingAnchor),
      collection.trailingAnchor.constraint(equalTo: root.trailingAnchor),
      collection.bottomAnchor.constraint(equalTo: root.bottomAnchor),
    ])

    for line in [topLine, bottomLine] {
      line.translatesAutoresizingMaskIntoConstraints = true
      line.isUserInteractionEnabled = false
      line.backgroundColor = UIColor.separator
      root.addSubview(line)
    }
    root.bringSubviewToFront(collection)
    root.bringSubviewToFront(topLine)
    root.bringSubviewToFront(bottomLine)

    root.onLayoutSubviews = { [weak self] in
      guard let self else { return }
      self.relayoutIfWidthChanged()
      self.syncWheelPresentationWithContentOffset()
    }

    updateChromeMetrics()
    applyAppearance()
  }

  func scrollTo(index: Double) throws {
    let idx = normalizeIndex(Int(index.rounded()))
    currentIndex = idx
    isSyncingFromScroll = true
    selectedIndex = Double(idx)
    isSyncingFromScroll = false
    jumpToLogicalIndex(idx, animated: true)
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.emitSettled(index: self.currentIndex)
    }
  }

  fileprivate func normalizeIndex(_ idx: Int) -> Int {
    guard !values.isEmpty else { return 0 }
    if isLoop {
      return ((idx % values.count) + values.count) % values.count
    }
    return min(values.count - 1, max(0, idx))
  }

  fileprivate func logicalRow(from position: Int) -> Int {
    guard !values.isEmpty else { return 0 }
    return ((position % values.count) + values.count) % values.count
  }

  fileprivate func rowForLogical(_ logical: Int) -> Int {
    guard !values.isEmpty else { return 0 }
    if isLoop { return loopMidpoint + logical }
    return logical
  }

  fileprivate func offsetY(forPosition position: Int) -> CGFloat {
    CGFloat(position) * itemH - paddingTop
  }

  fileprivate func position(fromOffsetY y: CGFloat) -> Int {
    guard itemH > 0 else { return 0 }
    return Int(round((y + paddingTop) / itemH))
  }

  fileprivate func jumpToLogicalIndex(_ logical: Int, animated: Bool) {
    guard !values.isEmpty else { return }
    let pos = rowForLogical(logical)
    let y = offsetY(forPosition: pos)
    let clamped = clampOffsetY(y)
    collection.setContentOffset(CGPoint(x: 0, y: clamped), animated: animated)
    if animated {
      collection.layoutIfNeeded()
      syncWheelPresentationWithContentOffset()
    } else {
      collection.layoutIfNeeded()
      syncWheelPresentationWithContentOffset()
    }
  }

  fileprivate func clampOffsetY(_ y: CGFloat) -> CGFloat {
    collection.layoutIfNeeded()
    let minY = -collection.adjustedContentInset.top
    let maxY = max(minY, collection.contentSize.height - collection.bounds.height + collection.adjustedContentInset.bottom)
    return min(max(y, minY), maxY)
  }

  fileprivate func updateChromeMetrics() {
    let pad = paddingTop
    collection.contentInset = UIEdgeInsets(top: pad, left: 0, bottom: pad, right: 0)
    collection.scrollIndicatorInsets = collection.contentInset

    let lineH = 1.0 / UIScreen.main.scale
    let rw = root.bounds.width
    topLine.frame = CGRect(x: 0, y: pad, width: rw, height: lineH)
    bottomLine.frame = CGRect(x: 0, y: pad + itemH, width: rw, height: lineH)

    let wheelHeight = max(120, itemH * CGFloat(visibleN))
    root.preferredHeight = wheelHeight

    let hideLines = rw <= 0
    topLine.isHidden = hideLines
    bottomLine.isHidden = hideLines
  }

  fileprivate func relayoutIfWidthChanged() {
    let w = root.bounds.width
    guard abs(w - lastLaidOutWidth) > 0.5, w > 0 else {
      updateChromeMetrics()
      collection.layoutIfNeeded()
      syncWheelPresentationWithContentOffset()
      return
    }
    lastLaidOutWidth = w
    flow.invalidateLayout()
    collection.layoutIfNeeded()
    updateChromeMetrics()
    jumpToLogicalIndex(currentIndex, animated: false)
  }

  fileprivate func applyAppearance() {
    let bg = appearance?.backgroundColor.map(colorFromHex) ?? .white
    root.backgroundColor = bg
    collection.backgroundColor = bg
    let divider = appearance?.dividerColor.map(colorFromHex) ?? UIColor.separator
    topLine.backgroundColor = divider
    bottomLine.backgroundColor = divider
    collection.reloadData()
    collection.layoutIfNeeded()
    if !values.isEmpty {
      jumpToLogicalIndex(currentIndex, animated: false)
    } else {
      syncWheelPresentationWithContentOffset()
    }
  }

  fileprivate func emitValueChange(index: Int) {
    guard !values.isEmpty, index != lastEmittedIndex else { return }
    lastEmittedIndex = index
    onValueChange?(WheelPickerValueChangeEvent(index: Double(index), value: values[index]))
  }

  fileprivate func emitSettled(index: Int) {
    guard !values.isEmpty else { return }
    onSettled(WheelPickerValueChangeEvent(index: Double(index), value: values[index]))
  }

  fileprivate func cellFont() -> UIFont {
    let size = CGFloat(appearance?.fontSize ?? 17)
    let weight = uiFontWeight(appearance?.fontWeight)
    if let family = appearance?.fontFamily,
       let font = UIFont(name: family, size: size) {
      return font
    }
    return UIFont.systemFont(ofSize: size, weight: weight)
  }

  fileprivate func uiFontWeight(_ w: Double?) -> UIFont.Weight {
    guard let w else { return .regular }
    if w >= 700 { return .bold }
    if w >= 600 { return .semibold }
    if w >= 500 { return .medium }
    return .regular
  }

  fileprivate func colorFromHex(_ hex: String) -> UIColor {
    let s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
      .replacingOccurrences(of: "#", with: "")
    var v: UInt64 = 0
    Scanner(string: s).scanHexInt64(&v)
    switch s.count {
    case 6:
      return UIColor(red: CGFloat((v >> 16) & 0xFF) / 255,
                     green: CGFloat((v >> 8) & 0xFF) / 255,
                     blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    case 8:
      return UIColor(red: CGFloat((v >> 16) & 0xFF) / 255,
                     green: CGFloat((v >> 8) & 0xFF) / 255,
                     blue: CGFloat(v & 0xFF) / 255,
                     alpha: CGFloat((v >> 24) & 0xFF) / 255)
    default: return .black
    }
  }

  fileprivate func bind(cell: WheelTextCell, position: Int) {
    guard !values.isEmpty else {
      cell.title.text = ""
      return
    }
    let logical = logicalRow(from: position)
    let isSel = logical == currentIndex
    cell.title.text = values[logical]
    cell.title.font = cellFont()
    let normal = colorFromHex(appearance?.textColor ?? "#111827")
    let selected = appearance?.selectedTextColor.map { colorFromHex($0) } ?? normal
    cell.title.textColor = isSel ? selected : normal
    cell.title.alpha = 1
    if isSel, let selBg = appearance?.selectedBackgroundColor {
      cell.title.backgroundColor = colorFromHex(selBg)
    } else {
      cell.title.backgroundColor = .clear
    }
  }

  /// Re-bind visible rows (transforms are identity after reload / recycle) then apply 3D wheel styling.
  fileprivate func syncWheelPresentationWithContentOffset() {
    guard !values.isEmpty, itemH > 0, collection.bounds.height > 0 else { return }
    collection.layoutIfNeeded()
    for cell in collection.visibleCells {
      guard let c = cell as? WheelTextCell,
            let ip = collection.indexPath(for: cell) else { continue }
      bind(cell: c, position: ip.item)
    }
    applyWheelPerspectiveToVisibleCells()
  }

  /// Distances rows from the selection band (vertical center) using 3D rotation + scale + alpha like a drum wheel.
  fileprivate func applyWheelPerspectiveToVisibleCells() {
    guard itemH > 0, collection.bounds.height > 0 else { return }
    let focalY = collection.bounds.midY
    for case let cell as WheelTextCell in collection.visibleCells {
      applyWheelPerspective(cell: cell, focalY: focalY)
    }
  }

  fileprivate func applyWheelPerspective(cell: UICollectionViewCell, focalY: CGFloat? = nil) {
    guard let cell = cell as? WheelTextCell, itemH > 0 else { return }
    let focus = focalY ?? collection.bounds.midY
    let rect = cell.convert(cell.bounds, to: collection)
    let d = CGFloat((rect.midY - focus) / itemH)
    let ad = abs(d)

    let wheelAlpha = max(0.22, 1 - 0.34 * min(ad, 2.9))
    let scale = max(0.76, 1 - 0.125 * min(ad, 2.9))
    var t = CATransform3DIdentity
    t.m34 = -1.0 / 520
    let maxAngle: CGFloat = 0.62
    let angle = max(-maxAngle, min(maxAngle, -d * 0.38))
    t = CATransform3DRotate(t, angle, 1, 0, 0)
    t = CATransform3DScale(t, scale, scale, 1)

    guard let ip = collection.indexPath(for: cell) else { return }
    let logical = logicalRow(from: ip.item)
    let isSel = logical == currentIndex
    let emphasis: CGFloat = isSel ? 1 : 0.88

    CATransaction.begin()
    CATransaction.setDisableActions(true)
    cell.contentView.layer.transform = t
    cell.contentView.alpha = wheelAlpha * emphasis
    let z = 1000 - ad * 50
    cell.layer.zPosition = CGFloat(z)
    CATransaction.commit()
  }

  fileprivate func refreshVisibleCells() {
    syncWheelPresentationWithContentOffset()
  }

  fileprivate func commitSelection(logical: Int, emitSettled settled: Bool) {
    let lg = normalizeIndex(logical)
    currentIndex = lg
    isSyncingFromScroll = true
    selectedIndex = Double(lg)
    isSyncingFromScroll = false
    if settled {
      jumpToLogicalIndex(lg, animated: false)
    }
    refreshVisibleCells()
    if settled {
      emitSettled(index: lg)
    }
  }

  fileprivate func handleScrollPositionChanged() {
    guard !values.isEmpty else { return }
    let pos = position(fromOffsetY: collection.contentOffset.y)
    let bounded = max(0, min(pos, totalItems - 1))
    let logical = logicalRow(from: bounded)
    if logical != currentIndex {
      currentIndex = logical
      refreshVisibleCells()
      emitValueChange(index: logical)
    }
  }
}

// MARK: - Coordinator

private final class WheelPickerCoordinator: NSObject, UICollectionViewDataSource, UICollectionViewDelegate, UICollectionViewDelegateFlowLayout {

  private weak var owner: HybridNitroWheelPickerView?

  init(owner: HybridNitroWheelPickerView) {
    self.owner = owner
    super.init()
  }

  func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
    owner?.totalItems ?? 0
  }

  func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
    let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "cell", for: indexPath) as! WheelTextCell
    if let owner {
      owner.bind(cell: cell, position: indexPath.item)
    }
    return cell
  }

  func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
    guard let owner else { return }
    if let c = cell as? WheelTextCell {
      owner.bind(cell: c, position: indexPath.item)
    }
    owner.applyWheelPerspective(cell: cell)
  }

  func collectionView(
    _ collectionView: UICollectionView,
    layout collectionViewLayout: UICollectionViewLayout,
    sizeForItemAt indexPath: IndexPath
  ) -> CGSize {
    let w = max(collectionView.bounds.width, 1)
    let h = owner?.itemH ?? 36
    return CGSize(width: w, height: h)
  }

  func scrollViewWillEndDragging(
    _ scrollView: UIScrollView,
    withVelocity velocity: CGPoint,
    targetContentOffset: UnsafeMutablePointer<CGPoint>
  ) {
    guard let owner, !owner.values.isEmpty else { return }
    let rowH = owner.itemH
    let pad = owner.paddingTop
    let y = targetContentOffset.pointee.y
    let idx = round((y + pad) / rowH)
    let snapped = idx * rowH - pad
    targetContentOffset.pointee.y = owner.clampOffsetY(snapped)
  }

  func scrollViewDidScroll(_ scrollView: UIScrollView) {
    owner?.handleScrollPositionChanged()
    owner?.applyWheelPerspectiveToVisibleCells()
  }

  func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
    owner?.syncWheelPresentationWithContentOffset()
  }

  func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
    if !decelerate {
      scrollEnded()
    }
  }

  func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
    scrollEnded()
  }

  private func scrollEnded() {
    guard let owner, !owner.values.isEmpty else { return }
    let pos = owner.position(fromOffsetY: owner.collection.contentOffset.y)
    let bounded = max(0, min(pos, owner.totalItems - 1))
    let logical = owner.logicalRow(from: bounded)
    owner.commitSelection(logical: logical, emitSettled: true)
  }
}
