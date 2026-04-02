import Foundation
import UIKit
import NitroModules

// MARK: - WheelCell

private final class WheelCell: UICollectionViewCell {
  static let reuseID = "WheelCell"
  let label = UILabel()

  override init(frame: CGRect) {
    super.init(frame: frame)
    label.textAlignment = .center
    label.adjustsFontSizeToFitWidth = false
    label.translatesAutoresizingMaskIntoConstraints = false
    contentView.addSubview(label)
    NSLayoutConstraint.activate([
      label.topAnchor.constraint(equalTo: contentView.topAnchor),
      label.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
      label.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
      label.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
    ])
  }

  required init?(coder: NSCoder) { fatalError() }
}

// MARK: - WheelContainerView

/// Calls back on layoutSubviews so item sizes can be updated when bounds change.
private final class WheelContainerView: UIView {
  var onBoundsChange: (() -> Void)?
  override func layoutSubviews() {
    super.layoutSubviews()
    onBoundsChange?()
  }
}

// MARK: - HybridNitroWheelPickerView

class HybridNitroWheelPickerView: HybridNitroWheelPickerViewSpec {

  // MARK: HybridView
  var view: UIView { containerView }

  // MARK: Private views
  private let containerView = WheelContainerView()
  private lazy var collectionView: UICollectionView = {
    let layout = UICollectionViewFlowLayout()
    layout.scrollDirection = .vertical
    layout.minimumLineSpacing = 0
    layout.minimumInteritemSpacing = 0
    let cv = UICollectionView(frame: .zero, collectionViewLayout: layout)
    cv.translatesAutoresizingMaskIntoConstraints = false
    cv.showsVerticalScrollIndicator = false
    cv.decelerationRate = .fast
    cv.backgroundColor = .clear
    cv.clipsToBounds = true
    cv.register(WheelCell.self, forCellWithReuseIdentifier: WheelCell.reuseID)
    cv.dataSource = self
    cv.delegate = self
    return cv
  }()
  // Selection indicator lines positioned at center-item edges
  private let topLine = UIView()
  private let bottomLine = UIView()
  private var topLineConstraint: NSLayoutConstraint?
  private var bottomLineConstraint: NSLayoutConstraint?

  // MARK: Spec properties

  var values: [String] = [] {
    didSet {
      collectionView.reloadData()
      if !values.isEmpty {
        currentIndex = min(currentIndex, values.count - 1)
      }
      jumpToIndex(currentIndex, animated: false)
    }
  }

  var selectedIndex: Double = 0 {
    didSet {
      guard !isSyncingFromScroll else { return }
      let idx = normalizeIndex(Int(selectedIndex.rounded()))
      currentIndex = idx
      jumpToIndex(idx, animated: false)
    }
  }

  var loop: Bool? {
    didSet {
      collectionView.reloadData()
      jumpToIndex(currentIndex, animated: false)
    }
  }

  var visibleCount: Double? {
    didSet { updateInsetAndIndicator() }
  }

  var itemHeight: Double = 36 {
    didSet {
      updateInsetAndIndicator()
      if let layout = collectionView.collectionViewLayout as? UICollectionViewFlowLayout {
        layout.invalidateLayout()
      }
    }
  }

  var appearance: WheelPickerAppearance? {
    didSet { applyAppearance() }
  }

  var onValueChange: ((_ event: WheelPickerValueChangeEvent) -> Void)?
  var onSettled: (_ event: WheelPickerValueChangeEvent) -> Void = { _ in }

  // MARK: Private state
  private var isSyncingFromScroll = false
  private var currentIndex: Int = 0
  private var lastEmittedIndex: Int = -1

  // MARK: Computed
  private var itemH: CGFloat { max(24, CGFloat(itemHeight)) }
  private var visibleN: Int { max(1, Int(visibleCount ?? 5)) }
  private var isLoop: Bool { loop == true }
  private var halfVisible: CGFloat { floor(CGFloat(visibleN) / 2.0) }

  private var totalRows: Int {
    guard !values.isEmpty else { return 0 }
    return isLoop ? values.count * 1000 : values.count
  }

  // MARK: Init

  required override init() {
    super.init()
    containerView.backgroundColor = .white
    containerView.clipsToBounds = true
    containerView.translatesAutoresizingMaskIntoConstraints = false

    containerView.addSubview(collectionView)
    NSLayoutConstraint.activate([
      collectionView.topAnchor.constraint(equalTo: containerView.topAnchor),
      collectionView.leadingAnchor.constraint(equalTo: containerView.leadingAnchor),
      collectionView.trailingAnchor.constraint(equalTo: containerView.trailingAnchor),
      collectionView.bottomAnchor.constraint(equalTo: containerView.bottomAnchor),
    ])

    for line in [topLine, bottomLine] {
      line.translatesAutoresizingMaskIntoConstraints = false
      line.isUserInteractionEnabled = false
      line.backgroundColor = UIColor.separator
      containerView.addSubview(line)
      NSLayoutConstraint.activate([
        line.leadingAnchor.constraint(equalTo: containerView.leadingAnchor),
        line.trailingAnchor.constraint(equalTo: containerView.trailingAnchor),
        line.heightAnchor.constraint(equalToConstant: 1.0 / UIScreen.main.scale),
      ])
    }
    topLineConstraint = topLine.topAnchor.constraint(
      equalTo: containerView.topAnchor, constant: halfVisible * itemH)
    bottomLineConstraint = bottomLine.topAnchor.constraint(
      equalTo: containerView.topAnchor, constant: (halfVisible + 1) * itemH)
    topLineConstraint?.isActive = true
    bottomLineConstraint?.isActive = true

    containerView.onBoundsChange = { [weak self] in
      guard let self else { return }
      if let layout = self.collectionView.collectionViewLayout as? UICollectionViewFlowLayout {
        layout.invalidateLayout()
      }
    }

    updateInsetAndIndicator()
  }

  // MARK: Spec method

  func scrollTo(index: Double) throws {
    let idx = normalizeIndex(Int(index.rounded()))
    currentIndex = idx
    isSyncingFromScroll = true
    selectedIndex = Double(idx)
    isSyncingFromScroll = false
    jumpToIndex(idx, animated: true)
    // onSettled fires via scrollViewDidEndScrollingAnimation
  }

  // MARK: Private helpers

  private func normalizeIndex(_ idx: Int) -> Int {
    guard !values.isEmpty else { return 0 }
    if isLoop {
      return ((idx % values.count) + values.count) % values.count
    }
    return min(values.count - 1, max(0, idx))
  }

  private func logicalIndex(for row: Int) -> Int {
    guard !values.isEmpty else { return 0 }
    return row % values.count
  }

  private func rowForLogical(_ logical: Int) -> Int {
    guard !values.isEmpty else { return 0 }
    if isLoop {
      let mid = (totalRows / 2 / values.count) * values.count
      return mid + logical
    }
    return logical
  }

  private func jumpToIndex(_ logical: Int, animated: Bool) {
    guard !values.isEmpty else { return }
    let row = rowForLogical(logical)
    let inset = collectionView.contentInset.top
    let offsetY = CGFloat(row) * itemH - inset
    collectionView.setContentOffset(CGPoint(x: 0, y: offsetY), animated: animated)
  }

  private func updateInsetAndIndicator() {
    let inset = halfVisible * itemH
    collectionView.contentInset = UIEdgeInsets(top: inset, left: 0, bottom: inset, right: 0)
    topLineConstraint?.constant = halfVisible * itemH
    bottomLineConstraint?.constant = (halfVisible + 1) * itemH
  }

  private func applyAppearance() {
    let bg = appearance?.backgroundColor.map(colorFromHex) ?? .white
    containerView.backgroundColor = bg
    let divider = appearance?.dividerColor.map(colorFromHex) ?? UIColor.separator
    topLine.backgroundColor = divider
    bottomLine.backgroundColor = divider
    collectionView.reloadData()
  }

  private func emitValueChange(index: Int) {
    guard !values.isEmpty, index != lastEmittedIndex else { return }
    lastEmittedIndex = index
    onValueChange?(WheelPickerValueChangeEvent(index: Double(index), value: values[index]))
  }

  private func emitSettled(index: Int) {
    guard !values.isEmpty else { return }
    onSettled(WheelPickerValueChangeEvent(index: Double(index), value: values[index]))
  }

  private func cellFont() -> UIFont {
    let size = CGFloat(appearance?.fontSize ?? 17)
    let weight = uiFontWeight(appearance?.fontWeight)
    if let family = appearance?.fontFamily,
       let font = UIFont(name: family, size: size) {
      return font
    }
    return UIFont.systemFont(ofSize: size, weight: weight)
  }

  private func uiFontWeight(_ fw: FontWeight?) -> UIFont.Weight {
    guard let fw else { return .regular }
    switch fw.stringValue {
    case "700": return .bold
    case "600": return .semibold
    case "500": return .medium
    default: return .regular
    }
  }

  private func colorFromHex(_ hex: String) -> UIColor {
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
}

// MARK: - UICollectionViewDataSource

extension HybridNitroWheelPickerView: UICollectionViewDataSource {

  func collectionView(_ collectionView: UICollectionView,
                      numberOfItemsInSection section: Int) -> Int {
    totalRows
  }

  func collectionView(_ collectionView: UICollectionView,
                      cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
    let cell = collectionView.dequeueReusableCell(
      withReuseIdentifier: WheelCell.reuseID, for: indexPath) as! WheelCell
    guard !values.isEmpty else { return cell }

    let logical = logicalIndex(for: indexPath.item)
    let isSelected = logical == currentIndex

    cell.label.text = values[logical]
    cell.label.font = cellFont()

    let normalColor = colorFromHex(appearance?.textColor ?? "#111827")
    let selectedColor = appearance?.selectedTextColor.map(colorFromHex) ?? normalColor
    cell.label.textColor = isSelected ? selectedColor : normalColor
    cell.label.alpha = isSelected ? 1.0 : 0.5

    if isSelected, let selBg = appearance?.selectedBackgroundColor {
      cell.contentView.backgroundColor = colorFromHex(selBg)
    } else {
      cell.contentView.backgroundColor = .clear
    }

    return cell
  }
}

// MARK: - UICollectionViewDelegateFlowLayout

extension HybridNitroWheelPickerView: UICollectionViewDelegateFlowLayout {

  func collectionView(_ collectionView: UICollectionView,
                      layout collectionViewLayout: UICollectionViewLayout,
                      sizeForItemAt indexPath: IndexPath) -> CGSize {
    CGSize(width: max(1, collectionView.bounds.width), height: itemH)
  }
}

// MARK: - UIScrollViewDelegate

extension HybridNitroWheelPickerView: UIScrollViewDelegate {

  func scrollViewWillEndDragging(_ scrollView: UIScrollView,
                                 withVelocity velocity: CGPoint,
                                 targetContentOffset: UnsafeMutablePointer<CGPoint>) {
    guard !values.isEmpty else { return }
    let inset = scrollView.contentInset.top
    let rawOffset = targetContentOffset.pointee.y + inset
    let nearestRow = max(0, min(totalRows - 1, Int((rawOffset / itemH).rounded())))
    targetContentOffset.pointee.y = CGFloat(nearestRow) * itemH - inset
  }

  func scrollViewDidScroll(_ scrollView: UIScrollView) {
    guard !values.isEmpty else { return }
    let inset = scrollView.contentInset.top
    let rawOffset = scrollView.contentOffset.y + inset
    let row = max(0, min(totalRows - 1, Int((rawOffset / itemH).rounded())))
    let logical = logicalIndex(for: row)

    if logical != currentIndex {
      currentIndex = logical
      isSyncingFromScroll = true
      selectedIndex = Double(logical)
      isSyncingFromScroll = false
      refreshVisibleCellsAppearance()
      emitValueChange(index: logical)
    }
  }

  func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
    if !decelerate {
      finalSnap(scrollView)
    }
  }

  func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
    finalSnap(scrollView)
  }

  func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
    emitSettled(index: currentIndex)
  }

  private func finalSnap(_ scrollView: UIScrollView) {
    guard !values.isEmpty else { return }
    let inset = scrollView.contentInset.top
    let rawOffset = scrollView.contentOffset.y + inset
    let row = max(0, min(totalRows - 1, Int((rawOffset / itemH).rounded())))
    let snapOffset = CGFloat(row) * itemH - inset
    if abs(scrollView.contentOffset.y - snapOffset) > 0.5 {
      // Correction snap — onSettled fires from scrollViewDidEndScrollingAnimation
      scrollView.setContentOffset(CGPoint(x: 0, y: snapOffset), animated: true)
    } else {
      emitSettled(index: currentIndex)
    }
  }

  private func refreshVisibleCellsAppearance() {
    let normalColor = colorFromHex(appearance?.textColor ?? "#111827")
    let selectedColor = appearance?.selectedTextColor.map(colorFromHex) ?? normalColor
    for case let cell as WheelCell in collectionView.visibleCells {
      guard let ip = collectionView.indexPath(for: cell), !values.isEmpty else { continue }
      let isSelected = logicalIndex(for: ip.item) == currentIndex
      cell.label.textColor = isSelected ? selectedColor : normalColor
      cell.label.alpha = isSelected ? 1.0 : 0.5
      if isSelected, let selBg = appearance?.selectedBackgroundColor {
        cell.contentView.backgroundColor = colorFromHex(selBg)
      } else {
        cell.contentView.backgroundColor = .clear
      }
    }
  }
}
