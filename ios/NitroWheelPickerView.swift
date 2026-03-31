import Foundation
import UIKit
import NitroModules

class HybridNitroWheelPickerView: HybridNitroWheelPickerViewSpec {
  var view: UIView

  private let containerView = UIView()
  private let pickerView = UIPickerView()
  private lazy var coordinator = WheelPickerCoordinator(owner: self)

  var values: [String] = [] {
    didSet {
      pickerView.reloadAllComponents()
      selectCurrentIndex(animated: false)
    }
  }
  var selectedIndex: Double = 0 {
    didSet {
      guard !isSynchronizingSelection else { return }
      selectCurrentIndex(animated: false)
    }
  }
  var loop: Bool?
  var visibleCount: Double?
  var itemHeight: Double = 36 {
    didSet {
      pickerView.reloadAllComponents()
    }
  }
  var appearance: WheelPickerAppearance? {
    didSet {
      applyAppearance()
      pickerView.reloadAllComponents()
    }
  }
  var onValueChange: ((_ event: WheelPickerValueChangeEvent) -> Void)?
  var onSettled: (_ event: WheelPickerValueChangeEvent) -> Void = { _ in }
  private var isSynchronizingSelection = false

  required override init() {
    view = containerView
    super.init()

    containerView.translatesAutoresizingMaskIntoConstraints = false
    pickerView.translatesAutoresizingMaskIntoConstraints = false
    pickerView.dataSource = coordinator
    pickerView.delegate = coordinator

    containerView.addSubview(pickerView)
    NSLayoutConstraint.activate([
      pickerView.topAnchor.constraint(equalTo: containerView.topAnchor),
      pickerView.leadingAnchor.constraint(equalTo: containerView.leadingAnchor),
      pickerView.trailingAnchor.constraint(equalTo: containerView.trailingAnchor),
      pickerView.bottomAnchor.constraint(equalTo: containerView.bottomAnchor)
    ])

    applyAppearance()
    selectCurrentIndex(animated: false)
  }

  func scrollTo(index: Double) throws {
    selectedIndex = index
    selectCurrentIndex(animated: true)
    emitChange(settled: true)
  }

  fileprivate func numberOfRows() -> Int {
    guard !values.isEmpty else { return 0 }
    if loop == true {
      return values.count * 1000
    }
    return values.count
  }

  fileprivate func title(for row: Int) -> String {
    guard !values.isEmpty else { return "" }
    let normalized = row % values.count
    return values[normalized]
  }

  fileprivate func selectRow(_ row: Int) {
    guard !values.isEmpty else { return }
    let normalized = row % values.count
    if Int(selectedIndex.rounded()) != normalized {
      isSynchronizingSelection = true
      selectedIndex = Double(normalized)
      isSynchronizingSelection = false
    }
    emitChange(settled: false)
    onSettled(WheelPickerValueChangeEvent(index: selectedIndex, value: values[normalized]))
  }

  fileprivate func rowHeight() -> CGFloat {
    return max(24, CGFloat(itemHeight))
  }

  fileprivate func textColor(isSelected: Bool) -> UIColor {
    let selected = appearance?.selectedTextColor
    let normal = appearance?.textColor ?? "#111827"
    let resolved = isSelected ? (selected ?? normal) : normal
    return colorFromHex(resolved)
  }

  private func emitChange(settled: Bool) {
    guard !values.isEmpty else { return }
    let index = max(0, min(values.count - 1, Int(selectedIndex.rounded())))
    let event = WheelPickerValueChangeEvent(index: Double(index), value: values[index])
    onValueChange?(event)
    if settled {
      onSettled(event)
    }
  }

  private func selectCurrentIndex(animated: Bool) {
    guard !values.isEmpty else { return }
    let clamped = max(0, min(values.count - 1, Int(selectedIndex.rounded())))
    if Int(selectedIndex.rounded()) != clamped {
      isSynchronizingSelection = true
      selectedIndex = Double(clamped)
      isSynchronizingSelection = false
    }

    if loop == true {
      let base = (numberOfRows() / 2 / values.count) * values.count
      pickerView.selectRow(base + clamped, inComponent: 0, animated: animated)
    } else {
      pickerView.selectRow(clamped, inComponent: 0, animated: animated)
    }
  }

  private func applyAppearance() {
    let backgroundHex = appearance?.backgroundColor ?? "#FFFFFF"
    containerView.backgroundColor = colorFromHex(backgroundHex)
    pickerView.backgroundColor = .clear
  }

  private func colorFromHex(_ hex: String) -> UIColor {
    let sanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "#", with: "")
    var value: UInt64 = 0
    Scanner(string: sanitized).scanHexInt64(&value)
    let r, g, b: CGFloat
    switch sanitized.count {
    case 6:
      r = CGFloat((value >> 16) & 0xFF) / 255.0
      g = CGFloat((value >> 8) & 0xFF) / 255.0
      b = CGFloat(value & 0xFF) / 255.0
    default:
      r = 0
      g = 0
      b = 0
    }
    return UIColor(red: r, green: g, blue: b, alpha: 1)
  }
}

private final class WheelPickerCoordinator: NSObject, UIPickerViewDataSource, UIPickerViewDelegate {
  weak var owner: HybridNitroWheelPickerView?

  init(owner: HybridNitroWheelPickerView) {
    self.owner = owner
    super.init()
  }

  func numberOfComponents(in pickerView: UIPickerView) -> Int {
    return 1
  }

  func pickerView(_ pickerView: UIPickerView, numberOfRowsInComponent component: Int) -> Int {
    return owner?.numberOfRows() ?? 0
  }

  func pickerView(_ pickerView: UIPickerView, rowHeightForComponent component: Int) -> CGFloat {
    return owner?.rowHeight() ?? 36
  }

  func pickerView(_ pickerView: UIPickerView, titleForRow row: Int, forComponent component: Int) -> String? {
    return owner?.title(for: row)
  }

  func pickerView(_ pickerView: UIPickerView, didSelectRow row: Int, inComponent component: Int) {
    owner?.selectRow(row)
  }
}
