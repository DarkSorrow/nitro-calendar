import Foundation
import UIKit
import SwiftUI
import NitroModules

// MARK: - WheelViewModel

@MainActor
final class WheelViewModel: ObservableObject {
  @Published var values: [String] = []
  @Published var selectedIndex: Int = 0
  @Published var loop: Bool = false
  @Published var visibleCount: Int = 5
  @Published var itemHeight: Double = 36
  @Published var appearance: WheelPickerAppearance? = nil

  var onValueChange: ((WheelPickerValueChangeEvent) -> Void)?
  var onSettled: ((WheelPickerValueChangeEvent) -> Void)?
}

// MARK: - HybridNitroWheelPickerView

class HybridNitroWheelPickerView: HybridNitroWheelPickerViewSpec {
  var view: UIView

  private let container = UIView()
  private let vm = WheelViewModel()

  var values: [String] = [] {
    didSet { vm.values = values }
  }
  var selectedIndex: Double = 0 {
    didSet { vm.selectedIndex = max(0, min(values.count - 1, Int(selectedIndex.rounded()))) }
  }
  var loop: Bool? {
    didSet { vm.loop = loop ?? false }
  }
  var visibleCount: Double? {
    didSet { vm.visibleCount = max(1, Int(visibleCount ?? 5)) }
  }
  var itemHeight: Double = 36 {
    didSet { vm.itemHeight = itemHeight }
  }
  var appearance: WheelPickerAppearance? {
    didSet { vm.appearance = appearance }
  }
  var onValueChange: ((WheelPickerValueChangeEvent) -> Void)? {
    didSet { vm.onValueChange = onValueChange }
  }
  var onSettled: ((WheelPickerValueChangeEvent) -> Void)? {
    didSet { vm.onSettled = onSettled }
  }

  required override init() {
    container.translatesAutoresizingMaskIntoConstraints = false
    view = container
    super.init()

    let rootView = WheelRootView(vm: vm)
    let hc = UIHostingController(rootView: rootView)
    hc.view.translatesAutoresizingMaskIntoConstraints = false
    hc.view.backgroundColor = .clear
    container.addSubview(hc.view)
    NSLayoutConstraint.activate([
      hc.view.topAnchor.constraint(equalTo: container.topAnchor),
      hc.view.leadingAnchor.constraint(equalTo: container.leadingAnchor),
      hc.view.trailingAnchor.constraint(equalTo: container.trailingAnchor),
      hc.view.bottomAnchor.constraint(equalTo: container.bottomAnchor),
    ])
  }

  func scrollTo(index: Double) throws {
    let idx = max(0, min(vm.values.count - 1, Int(index.rounded())))
    vm.selectedIndex = idx
    if !vm.values.isEmpty {
      vm.onSettled?(WheelPickerValueChangeEvent(index: Double(idx), value: vm.values[idx]))
    }
  }
}

// MARK: - WheelRootView (Tasks 4.2 + 4.3)

struct WheelRootView: View {
  @ObservedObject var vm: WheelViewModel

  var body: some View {
    let useScrollView = vm.loop || vm.itemHeight != 36
    if useScrollView {
      LoopWheelView(vm: vm)
    } else {
      StandardWheelView(vm: vm)
    }
  }
}

// MARK: - StandardWheelView — Picker(.wheel) (Task 4.2)

struct StandardWheelView: View {
  @ObservedObject var vm: WheelViewModel
  @State private var lastIndex: Int = 0

  var body: some View {
    let ap = vm.appearance
    let frameH = CGFloat(vm.visibleCount) * CGFloat(vm.itemHeight)

    Picker("", selection: Binding(
      get: { vm.selectedIndex },
      set: { newIdx in
        vm.selectedIndex = newIdx
        guard !vm.values.isEmpty else { return }
        let safe = max(0, min(vm.values.count - 1, newIdx))
        if safe != lastIndex {
          lastIndex = safe
          vm.onValueChange?(WheelPickerValueChangeEvent(index: Double(safe), value: vm.values[safe]))
        }
      }
    )) {
      ForEach(vm.values.indices, id: \.self) { i in
        Text(vm.values[i])
          .font(wheelFont(ap))
          .foregroundColor(i == vm.selectedIndex
            ? Color(hex: ap?.selectedTextColor ?? ap?.textColor ?? "#111827")
            : Color(hex: ap?.textColor ?? "#111827"))
          .tag(i)
      }
    }
    .pickerStyle(.wheel)
    .frame(height: frameH)
    .clipped()
    .background(Color(hex: ap?.backgroundColor ?? "#FFFFFF"))
    .simultaneousGesture(DragGesture().onEnded { _ in
      guard !vm.values.isEmpty else { return }
      let safe = max(0, min(vm.values.count - 1, vm.selectedIndex))
      vm.onSettled?(WheelPickerValueChangeEvent(index: Double(safe), value: vm.values[safe]))
    })
  }

  private func wheelFont(_ ap: WheelPickerAppearance?) -> Font {
    let size = CGFloat(ap?.fontSize ?? 17)
    let weight: Font.Weight = (ap?.fontWeight ?? 400) >= 600 ? .semibold : .regular
    if let family = ap?.fontFamily {
      return .custom(family, size: size).weight(weight)
    }
    return .system(size: size, weight: weight)
  }
}

// MARK: - LoopWheelView — ScrollView+scrollTargetBehavior for loop/custom height (Task 4.3)

struct LoopWheelView: View {
  @ObservedObject var vm: WheelViewModel

  private let virtualCount = 10_000
  @State private var scrollID: Int? = nil

  var body: some View {
    guard !vm.values.isEmpty else { return AnyView(EmptyView()) }
    let ap = vm.appearance
    let itemH = CGFloat(vm.itemHeight)
    let frameH = CGFloat(vm.visibleCount) * itemH
    let midpoint = (virtualCount / 2 / vm.values.count) * vm.values.count

    return AnyView(
      ScrollViewReader { proxy in
        ScrollView(.vertical, showsIndicators: false) {
          LazyVStack(spacing: 0) {
            ForEach(0..<virtualCount, id: \.self) { i in
              let logical = i % vm.values.count
              let isSelected = logical == vm.selectedIndex
              Text(vm.values[logical])
                .font(loopFont(ap))
                .foregroundColor(isSelected
                  ? Color(hex: ap?.selectedTextColor ?? ap?.textColor ?? "#111827")
                  : Color(hex: ap?.textColor ?? "#111827"))
                .frame(maxWidth: .infinity)
                .frame(height: itemH)
                .background(isSelected && ap?.selectedBackgroundColor != nil
                  ? Color(hex: ap!.selectedBackgroundColor!)
                  : Color.clear)
                .scrollTransition { content, phase in
                  content
                    .opacity(phase.isIdentity ? 1 : 0.4)
                    .scaleEffect(phase.isIdentity ? 1 : 0.85)
                }
                .id(i)
                .onTapGesture {
                  let newIdx = logical
                  vm.selectedIndex = newIdx
                  vm.onValueChange?(WheelPickerValueChangeEvent(index: Double(newIdx), value: vm.values[newIdx]))
                  withAnimation { proxy.scrollTo(midpoint + newIdx, anchor: .center) }
                  vm.onSettled?(WheelPickerValueChangeEvent(index: Double(newIdx), value: vm.values[newIdx]))
                }
            }
          }
          .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned)
        .frame(height: frameH)
        .clipped()
        .background(Color(hex: ap?.backgroundColor ?? "#FFFFFF"))
        .onAppear {
          let startPos = midpoint + vm.selectedIndex
          proxy.scrollTo(startPos, anchor: .center)
          scrollID = startPos
        }
        .onChange(of: vm.selectedIndex) { newIdx in
          let pos = midpoint + newIdx
          withAnimation { proxy.scrollTo(pos, anchor: .center) }
        }
      }
    )
  }

  private func loopFont(_ ap: WheelPickerAppearance?) -> Font {
    let size = CGFloat(ap?.fontSize ?? 17)
    let weight: Font.Weight = (ap?.fontWeight ?? 400) >= 600 ? .semibold : .regular
    if let family = ap?.fontFamily {
      return .custom(family, size: size).weight(weight)
    }
    return .system(size: size, weight: weight)
  }
}
