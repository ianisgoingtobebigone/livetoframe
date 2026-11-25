import Photos
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @StateObject private var model = LibraryModel()
    @State private var dayView = true
    @State private var sceneFilter: PhotoScene?
    @State private var previewAsset: PHAsset?
    @State private var onlySelected = false
    @State private var excludeFavorites = true
    @State private var filterDraft = FilterDraft()
    @State private var showGalleryHint = true
    @State private var panel: Panel?
    @State private var afterPanel: (() -> Void)?
    @State private var screen: Screen = .library
    @State private var didViewResults = false
    @State private var removalSelection = Set<String>()
    @State private var removalSelectionWasEdited = false
    @AccessibilityFocusState private var resultFocused: Bool
    @AccessibilityFocusState private var panelFocused: Bool
    @AccessibilityFocusState private var filterFocused: Bool
    @ScaledMetric(relativeTo: .title2) private var libraryTitleSize = 21.0
    @ScaledMetric(relativeTo: .title2) private var resultTitleSize = 24.0
    @ScaledMetric(relativeTo: .body) private var actionSize = 16.0
    @ScaledMetric(relativeTo: .subheadline) private var controlSize = 14.0
    @ScaledMetric(relativeTo: .footnote) private var utilitySize = 13.0
    @ScaledMetric(relativeTo: .title3) private var panelTitleSize = 20.0

    private struct FilterDraft {
        var excludeFavorites = true
        var scene: PhotoScene?
    }

    private enum Screen { case library, results, copies }
    private enum Panel: String, Identifiable {
        case filters, selection, removal
        var id: String { rawValue }
    }

    private var columns: [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: 4), count: 5)
    }

    var body: some View {
        Group {
            if let progress = model.saveProgress {
                SaveProgressPage(progress: progress)
            } else {
                switch screen {
                case .library: libraryScreen
                case .results: resultsScreen
                case .copies:
                    CopyGallery(results: confirmedResults, authorization: model.authorization, manageAccess: openSettings) {
                        previewAsset = $0
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .safeAreaInset(edge: .top, spacing: 0) {
            navigationBar.background(LibraryAppearance.canvas, ignoresSafeAreaEdges: .top)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if model.saveProgress == nil { bottomBar }
        }
        .background(LibraryAppearance.canvas.ignoresSafeArea())
        .foregroundStyle(LibraryAppearance.ink)
        .buttonStyle(FlatPressStyle())
        .tint(LibraryAppearance.link)
        .accessibilityHidden(previewAsset != nil)
        .task { model.refresh() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { model.refresh() } }
        .onChange(of: model.readyToRemove) { _, identifiers in
            if panel == .removal && !removalSelectionWasEdited {
                removalSelection = Set(identifiers)
            }
        }
        .onChange(of: model.selection.isEmpty) { _, empty in
            if empty { onlySelected = false } else { showGalleryHint = false }
        }
        .onChange(of: previewAsset?.localIdentifier) { _, identifier in
            if identifier != nil { showGalleryHint = false }
        }
        .accessibilityAction(.escape) {
            if previewAsset != nil { previewAsset = nil } else { navigateBack() }
        }
        .simultaneousGesture(DragGesture(minimumDistance: 28).onEnded { gesture in
            guard gesture.startLocation.x < 22, gesture.translation.width > 64,
                  abs(gesture.translation.height) < 40, previewAsset == nil else { return }
            navigateBack()
        }, including: screen == .library ? .none : .gesture)
        .sheet(item: $panel, onDismiss: runAfterPanel) { panel in
            Group {
                switch panel {
                case .filters: filterSheet
                case .selection: selectionSheet
                case .removal: removalSheet
                }
            }
            .foregroundStyle(LibraryAppearance.ink)
            .buttonStyle(FlatPressStyle())
            .presentationBackground(LibraryAppearance.canvas)
            .presentationCornerRadius(25)
            .presentationDetents(presentationDetents(for: panel))
            .presentationDragIndicator(.hidden)
        }
        .overlay {
            ZStack {
                if let asset = previewAsset {
                    Group {
                        if screen == .copies {
                            StillPhotoPreviewOverlay(asset: asset, name: photoName(asset)) { previewAsset = nil }
                        } else {
                            PhotoPreviewOverlay(asset: asset, playsLive: true) { previewAsset = nil }
                        }
                    }
                    .transition(.opacity)
                }
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.22), value: previewAsset?.localIdentifier)
        }
        .alert("操作结果", isPresented: Binding(
            get: { model.notice != nil },
            set: { if !$0 { model.notice = nil } }
        )) {
            Button("知道了") { model.notice = nil }
        } message: {
            Text(model.notice ?? "")
        }
        .fileExporter(isPresented: $model.showExporter, documents: model.exportDocuments,
                      contentTypes: [.image], onCompletion: model.finishExport, onCancellation: {})
    }

    @ViewBuilder
    private var bottomBar: some View {
        switch screen {
        case .library:
            if model.canBrowse && !model.selection.isEmpty { actionBar }
        case .results, .copies: EmptyView()
        }
    }

    private var navigationBar: some View {
        HStack(spacing: 12) {
            if model.saveProgress != nil {
                Label("实况转静态", systemImage: "livephoto")
                    .font(.system(size: utilitySize, weight: .semibold))
                    .foregroundStyle(LibraryAppearance.muted)
                Spacer()
            } else {
                switch screen {
            case .library:
                Text("实况转静态")
                    .font(.system(size: libraryTitleSize, weight: .semibold))
                    .foregroundStyle(LibraryAppearance.ink)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
            case .results:
                backButton
                Spacer()
                Text("本次结果").foregroundStyle(LibraryAppearance.ink).fontWeight(.semibold)
            case .copies:
                backButton
                Spacer()
                Text("静态副本").foregroundStyle(LibraryAppearance.ink).fontWeight(.semibold)
                }
            }
        }
        .font(.system(size: controlSize))
        .foregroundStyle(LibraryAppearance.link)
        .frame(minHeight: 52)
        .padding(.horizontal, 16)
    }

    private var backButton: some View {
        Button(action: navigateBack) {
            Label("返回", systemImage: "chevron.left").frame(minWidth: 44, minHeight: 44)
        }
        .disabled(model.progress != nil)
    }

    private func navigateBack() {
        guard model.progress == nil else { return }
        switch screen {
        case .library: break
        case .results: screen = .library
        case .copies: screen = .results
        }
    }

    @ViewBuilder
    private var libraryScreen: some View {
        if !model.canBrowse {
            ContentUnavailableView {
                Label("选择要访问的照片", systemImage: "photo.on.rectangle")
            } description: {
                Text("授权后，这里只显示可访问的实况照片。你也可以只开放几张。")
            } actions: {
                Button(model.authorization == .notDetermined ? "选择照片权限" : "打开设置") {
                    if model.authorization == .notDetermined {
                        Task { await model.requestAccess() }
                    } else {
                        openSettings()
                    }
                }.buttonStyle(FilledActionStyle())
            }
        } else if model.assets.isEmpty && (model.isRefreshing || !model.hasLoadedLibrary) {
            GalleryLoadingView(byDay: dayView)
        } else if model.assets.isEmpty {
            ContentUnavailableView {
                Label("没有可用的实况照片", systemImage: "livephoto")
            } description: {
                Text("拍摄新的实况照片，或检查照片访问范围。")
            } actions: {
                Button("管理照片权限", action: openSettings)
                    .foregroundStyle(LibraryAppearance.link)
            }
        } else {
            let gallery = visibleGallery
            let hiddenCount = hiddenSelectedCount
            galleryScroll(gallery, hiddenCount: hiddenCount)
        }
    }

    private func galleryScroll(_ gallery: PhotoGalleryContent, hiddenCount: Int) -> some View {
        let transitionOpacity = reduceMotion ? 1.0 : 0.78
        let rows = dayView ? gallery.rowsByDay : gallery.rowsAll
        return VStack(spacing: 0) {
            galleryControls(visibleCount: gallery.assets.count)
            Rectangle().fill(LibraryAppearance.separatorSoft).frame(height: 1)
            PhotoGalleryScrollArea(gallery: gallery, rows: rows, selection: model.selection,
                                   selectionOrder: model.selectedIDs(excludingFavorites: false),
                                   rangeEnabled: !onlySelected && model.progress == nil,
                                   selectRange: model.selectRange) {
                VStack(alignment: .leading, spacing: 10) {
                    if !didViewResults && !model.results.isEmpty {
                        Button("查看上次中断的保存") {
                            didViewResults = true
                            screen = .results
                        }.font(.system(size: controlSize)).foregroundStyle(LibraryAppearance.link)
                            .frame(minHeight: 44)
                    }
                    if hiddenCount > 0 {
                        Text("筛选藏起了 \(hiddenCount) 张已选照片，保存前可核对。")
                            .font(.caption).foregroundStyle(LibraryAppearance.link)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12).padding(.vertical, 9)
                            .background(LibraryAppearance.blueTint, in: RoundedRectangle(cornerRadius: 11))
                    }
                    if gallery.assets.isEmpty {
                        ContentUnavailableView {
                            Label(onlySelected ? "这里没有已选照片" : "没有符合筛选的照片", systemImage: "livephoto")
                        } description: {
                            Text("调整筛选可查看其他实况照片。")
                        } actions: {
                            Button("清除筛选") {
                                sceneFilter = nil
                                onlySelected = false
                            }.foregroundStyle(LibraryAppearance.link)
                        }
                    } else {
                        LazyVStack(spacing: 4) {
                            ForEach(rows.rows) { row in
                                if let group = row.group {
                                    dayHeader(group)
                                        .padding(.top, row.id == rows.rows.first?.id ? 0 : 6)
                                } else {
                                    GalleryPhotoRowLayout {
                                        ForEach(row.assets, id: \.localIdentifier) { photoButton($0) }
                                    }
                                }
                            }
                        }
                        .scrollTargetLayout()
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 3)
                .padding(.bottom, 12)
            }
            .keyframeAnimator(initialValue: 1.0,
                          trigger: GalleryLayout(columns: 5, byDay: dayView)) { view, opacity in
                view.opacity(opacity)
            } keyframes: { _ in
                MoveKeyframe(transitionOpacity)
                CubicKeyframe(1, duration: 0.18)
            }
        }
    }

    private func galleryControls(visibleCount: Int) -> some View {
        VStack(spacing: 3) {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(spacing: 4) {
                    gallerySegments
                    HStack(spacing: 8) { onlySelectedButton; Spacer(minLength: 0); filterButton }
                }
            } else {
                HStack(spacing: 8) {
                    gallerySegments.frame(maxWidth: 156)
                    Spacer(minLength: 0)
                    onlySelectedButton
                    filterButton
                }
            }
            ViewThatFits(in: .horizontal) {
                HStack {
                    visibleCountLabel(visibleCount)
                    Spacer(minLength: 8)
                    if model.authorization == .limited { managePhotoAccess } else if showGalleryHint { Text("横划多选 · 长按预览") }
                }
                VStack(alignment: .leading, spacing: 2) {
                    visibleCountLabel(visibleCount)
                    if model.authorization == .limited { managePhotoAccess }
                }
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 19)
            .font(.caption)
            .foregroundStyle(LibraryAppearance.muted)
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
        .padding(.bottom, 8)
    }

    private var gallerySegments: some View {
        HStack(spacing: 0) {
            daySegment("按日", isDay: true)
            daySegment("全部", isDay: false)
        }
        .padding(3)
        .background(LibraryAppearance.segmentTrack, in: RoundedRectangle(cornerRadius: 11))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("相册显示方式")
    }

    private var onlySelectedButton: some View {
        Button {
            onlySelected.toggle()
        } label: {
            Text("只看已选")
                .font(.system(size: utilitySize, weight: onlySelected ? .semibold : .regular))
                .foregroundStyle(model.selection.isEmpty ? LibraryAppearance.disabledText : LibraryAppearance.link)
                .padding(.horizontal, 7).frame(minWidth: 44, minHeight: 44)
                .background(onlySelected ? LibraryAppearance.blueTint : .clear, in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
        }
        .disabled(model.selection.isEmpty)
        .accessibilityAddTraits(onlySelected ? .isSelected : [])
        .accessibilityValue(onlySelected ? "已开启" : "已关闭")
    }

    private var filterButton: some View {
        Button {
            filterDraft = FilterDraft(excludeFavorites: excludeFavorites, scene: sceneFilter)
            panel = .filters
        } label: {
            Label("筛选", systemImage: "line.3.horizontal.decrease")
                .font(.system(size: utilitySize, weight: .semibold))
                .padding(.horizontal, 11).frame(minWidth: 44, minHeight: 44)
                .foregroundStyle(filterActive ? LibraryAppearance.link : LibraryAppearance.ink)
                .background(filterActive ? LibraryAppearance.blueTint : LibraryAppearance.paper,
                            in: RoundedRectangle(cornerRadius: 12))
        }
        .accessibilityLabel("筛选照片")
        .accessibilityValue(filterActive ? "筛选已启用" : "默认筛选")
        .accessibilityFocused($filterFocused)
    }

    private var managePhotoAccess: some View {
        Button("管理照片权限", action: openSettings)
            .foregroundStyle(LibraryAppearance.link).frame(minHeight: 44)
    }

    private func visibleCountLabel(_ count: Int) -> some View {
        let category = sceneFilter.map { " · \($0.label)" } ?? ""
        let favorites = excludeFavorites && !model.favoriteIDs.isEmpty ? " · \(model.favoriteIDs.count) 张收藏已保护" : ""
        return Text("\(count) 张\(category)\(favorites)")
    }

    private func daySegment(_ title: String, isDay: Bool) -> some View {
        let selected = dayView == isDay
        return Button { dayView = isDay } label: {
            Text(title)
                .font(.system(size: controlSize, weight: .medium))
                .padding(.vertical, 7)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background {
                    if selected {
                        RoundedRectangle(cornerRadius: 8)
                            .fill(LibraryAppearance.segmentThumb)
                            .shadow(color: .black.opacity(0.13), radius: 2, y: 1)
                    }
                }
                .contentShape(Rectangle())
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func dayHeader(_ group: PhotoDateGroupViewData) -> some View {
        let selected = group.assets.lazy.filter { model.selection.contains($0.localIdentifier) }.count
        return Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(group.title).font(.headline).layoutPriority(1)
                        Spacer(minLength: 8)
                        daySelectionButton(group, selected: selected)
                    }
                    Text(selected > 0 ? "\(selected) 已选 · 共 \(group.assets.count) 张" : "共 \(group.assets.count) 张")
                        .font(.caption).foregroundStyle(LibraryAppearance.muted)
                }
            } else {
                HStack {
                    Text(group.title).font(.headline)
                        .lineLimit(1).minimumScaleFactor(0.8)
                    Text(selected > 0 ? "\(selected) 已选 · \(group.assets.count) 张" : "\(group.assets.count) 张")
                        .font(.caption).foregroundStyle(LibraryAppearance.muted)
                        .lineLimit(1).minimumScaleFactor(0.75)
                    Spacer()
                    daySelectionButton(group, selected: selected)
                }
            }
        }
        .frame(minHeight: dynamicTypeSize.isAccessibilitySize ? 64 : 44,
               maxHeight: dynamicTypeSize.isAccessibilitySize ? nil : 44)
    }

    private func daySelectionButton(_ group: PhotoDateGroupViewData, selected: Int) -> some View {
        let all = selected == group.assets.count
        return Button {
            withTransaction(Transaction(animation: nil)) {
                model.toggle(group.assets.map(\.localIdentifier), selecting: !all)
            }
        } label: {
            ZStack {
                RoundedRectangle(cornerRadius: 6)
                    .fill(all ? LibraryAppearance.blue : selected > 0 ? LibraryAppearance.blueTint : .clear)
                RoundedRectangle(cornerRadius: 6)
                    .strokeBorder(selected > 0 ? LibraryAppearance.selected : LibraryAppearance.disabledText,
                                  lineWidth: 1.5)
                if selected > 0 {
                    Image(systemName: all ? "checkmark" : "minus")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(all ? .white : LibraryAppearance.link)
                }
            }
            .frame(width: 20, height: 20)
            .frame(width: 44, height: 44, alignment: .trailing)
            .contentShape(Rectangle())
        }
        .accessibilityLabel((all ? "取消当天全选，" : "全选当天，") + group.title)
        .accessibilityValue(all ? "全选" : selected > 0 ? "部分选中" : "未选")
        .disabled(model.progress != nil)
        .buttonStyle(.plain)
    }

    private func photoButton(_ asset: PHAsset) -> some View {
        SelectablePhotoTile(asset: asset, model: model) { previewAsset = asset }
            .anchorPreference(key: GalleryTileBounds.self, value: .bounds) { [asset.localIdentifier: $0] }
    }

    private var actionBar: some View {
        let selected = model.selectedIDs(excludingFavorites: excludeFavorites)
        let needsReview = hiddenSelectedCount > 0
        let buttons = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 8))
            : AnyLayout(SelectionButtonsLayout())
        return FlatDock(verticalPadding: 8, spacing: 4) {
            if !selected.isEmpty {
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        selectionCount(selected.count)
                        Spacer(minLength: 0)
                        Text("保存后，原件仍在相册").font(.caption).foregroundStyle(LibraryAppearance.muted)
                            .fixedSize()
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        selectionCount(selected.count)
                        Text("保存后，原件仍在相册").font(.caption).foregroundStyle(LibraryAppearance.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                StorageEstimateLine(estimate: model.storageEstimate(for: selected), count: selected.count)
                buttons {
                    Button("取消选择") { model.clearSelection() }
                        .buttonStyle(FilledActionStyle(secondary: true))
                        .accessibilityHint("清空所有已选照片，包括当前筛选隐藏的照片。")
                    Button(needsReview ? "核对后保存" : "保存静态副本") {
                        if needsReview { panel = .selection } else { saveSelection() }
                    }
                    .buttonStyle(FilledActionStyle())
                    .accessibilityHint("保存静态副本后，相册中的原件仍会保留。")
                }
                .padding(.top, 4)
                .disabled(model.progress != nil)
            }
        }
    }

    private func selectionCount(_ count: Int) -> some View {
        Text("已选 \(count) 张").font(.system(size: actionSize, weight: .semibold)).monospacedDigit().fixedSize()
    }

    private var filterPanelHeight: CGFloat {
        if dynamicTypeSize.isAccessibilitySize { return 380 }
        let classification: CGFloat = model.sceneResults.isEmpty && model.classificationProgress == nil ? 0 : 76
        let permission: CGFloat = model.authorization == .limited ? 48 : 0
        let favoriteNotice: CGFloat = filterDraft.excludeFavorites && !model.selection.isDisjoint(with: model.favoriteIDs) ? 40 : 0
        return 260 + classification + permission + favoriteNotice
    }

    private func presentationDetents(for panel: Panel) -> Set<PresentationDetent> {
        switch panel {
        case .filters:
            return [.height(filterPanelHeight), .large]
        case .selection:
            return [.medium, .large]
        case .removal:
            if dynamicTypeSize.isAccessibilitySize { return [.large] }
            let rows = CGFloat(min(4, max(1, model.readyToRemove.count)))
            return [.height(286 + 64 * rows), .large]
        }
    }

    private var filterSheet: some View {
        let protected = filterDraft.excludeFavorites ? model.selection.intersection(model.favoriteIDs) : []
        let draftCount = gallery(excludingFavorites: filterDraft.excludeFavorites, scene: filterDraft.scene,
                                 onlySelected: onlySelected && model.selection.count > protected.count).assets.count
        let hasResults = model.assets.contains { model.sceneResults[$0.localIdentifier] != nil }
        let needsClassification = model.assets.contains { asset in
            guard let result = model.sceneResults[asset.localIdentifier] else { return true }
            return result.modifiedAt != asset.modificationDate
        }
        return VStack(spacing: 0) {
            panelHeader("筛选照片")
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    Toggle(isOn: $filterDraft.excludeFavorites) {
                        filterLabel("保护收藏", detail: "排除收藏照片")
                    }.toggleStyle(FilterSwitchStyle()).frame(minHeight: 52)
                    Divider()
                    if !protected.isEmpty {
                        Text("应用后，将撤选 \(protected.count) 张收藏照片。")
                            .font(.footnote).foregroundStyle(LibraryAppearance.link)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .background(LibraryAppearance.blueTint, in: RoundedRectangle(cornerRadius: 8))
                    }
                    HStack {
                        Text("按内容筛选").font(.subheadline.weight(.semibold))
                        Spacer()
                        if needsClassification && model.classificationProgress == nil {
                            Button(hasResults ? "继续识别" : "识别分类") {
                                model.startClassification()
                            }.font(.subheadline).foregroundStyle(LibraryAppearance.link)
                                .disabled(model.isRefreshing)
                        }
                    }.frame(minHeight: 38)
                    if let progress = model.classificationProgress {
                        HStack(alignment: .center, spacing: 10) {
                            ProgressView(progress).font(.caption)
                            Button(progress == "正在停止识别…" ? "正在停止" : "停止") {
                                model.cancelClassification()
                            }
                                .font(.caption).foregroundStyle(LibraryAppearance.link)
                                .fixedSize().disabled(progress == "正在停止识别…")
                        }
                    }
                    if hasResults {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 88), spacing: 6)], spacing: 6) {
                            filterChip(nil)
                            ForEach(PhotoScene.allCases, id: \.self) { filterChip($0) }
                        }
                    } else {
                        Text("识别后可按人物、风景、文字等查找。")
                            .font(.caption).foregroundStyle(LibraryAppearance.muted)
                    }
                    if model.authorization == .limited {
                        Button("管理照片访问", action: openSettings)
                            .font(.subheadline).foregroundStyle(LibraryAppearance.link).frame(minHeight: 44)
                    }
                }.padding(.horizontal, 16).padding(.bottom, 12)
            }
            FlatDock(verticalPadding: 9) {
                Button("显示 \(draftCount) 张照片") {
                    excludeFavorites = filterDraft.excludeFavorites
                    sceneFilter = filterDraft.scene
                    if !protected.isEmpty {
                        model.toggle(Array(protected), selecting: false)
                    }
                    panel = nil
                }
                .buttonStyle(FilledActionStyle())
            }
        }
        .padding(.top, 7)
    }

    private func filterLabel(_ title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.subheadline.weight(.semibold))
            Text(detail).font(.caption).foregroundStyle(LibraryAppearance.muted)
        }
    }

    private func filterChip(_ category: PhotoScene?) -> some View {
        let selected = filterDraft.scene == category
        return Button { filterDraft.scene = category } label: {
            Text(category?.label ?? "全部类别")
                .font(.footnote.weight(selected ? .semibold : .regular))
                .foregroundStyle(selected ? LibraryAppearance.link : LibraryAppearance.ink)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(selected ? LibraryAppearance.blueTint : LibraryAppearance.paper, in: Capsule())
                .contentShape(Capsule())
        }.accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var selectionSheet: some View {
        let selected = model.selectedAssets(excludingFavorites: excludeFavorites)
        let hidden = hiddenSelectedCount
        return VStack(spacing: 0) {
            panelHeader("已选 \(selected.count) 张")
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if !selected.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(model.estimateText(for: selected.map(\.localIdentifier), title: "移除后预计节省"))
                            if hidden > 0 { Text("清单包含当前筛选隐藏的 \(hidden) 张照片。") }
                        }.font(.footnote)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(LibraryAppearance.paper, in: RoundedRectangle(cornerRadius: 11))
                            .padding(.bottom, 8)
                    }
                    ForEach(selected, id: \.localIdentifier) { asset in
                        let name = photoName(asset)
                        CompactPhotoRow(id: asset.localIdentifier, name: name, date: asset.creationDate) {
                            Button { model.toggle(asset.localIdentifier) } label: {
                                Image(systemName: "minus")
                                    .frame(width: 44, height: 44)
                                    .foregroundStyle(LibraryAppearance.link)
                                    .background(LibraryAppearance.blueTint, in: Circle())
                            }.accessibilityLabel("撤选 \(name)")
                        }
                    }
                }.padding(.horizontal, 17).padding(.bottom, 12)
            }
            FlatDock {
                if selected.isEmpty {
                    Button("返回照片") { panel = nil }.buttonStyle(FilledActionStyle())
                } else {
                    Button("保存 \(selected.count) 张静态副本") {
                        dismissPanel(then: saveSelection)
                    }.buttonStyle(FilledActionStyle()).disabled(model.progress != nil)
                    Button("清空本次选择") {
                        model.clearSelection()
                        panel = nil
                    }.font(.subheadline).foregroundStyle(LibraryAppearance.link).frame(minHeight: 44)
                }
            }
        }.padding(.top, 7)
    }

    private var confirmedResults: [ConversionOutcome] {
        model.results.filter { [.verified, .previewOnly, .retained, .removed].contains($0.state) }
    }

    private var resultsScreen: some View {
        let confirmed = confirmedResults.count
        let removed = model.results.lazy.filter { $0.state == .removed }.count
        let candidates = model.readyToRemove.count
        let needsHelp = model.results.contains { $0.state == .failed || $0.state == .pending }
        let diagnostics = model.diagnostics
        let successful = confirmed > 0
        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(resultHeadline(confirmed: confirmed))
                        .font(.system(size: resultTitleSize, weight: .semibold)).tracking(-0.8)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityFocused($resultFocused)
                    Text(resultMessage(removed: removed))
                        .font(.subheadline).foregroundStyle(LibraryAppearance.muted)
                        .padding(.top, 8)
                    if let progress = model.progress {
                        ProgressView(progress).font(.footnote).padding(.top, 12)
                    }
                }
                .padding(.top, 12).padding(.bottom, 16)
                .modifier(ResultSummaryEntrance())

                if successful {
                    Button { screen = .copies } label: {
                        Label("查看静态副本", systemImage: "photo")
                    }
                    .buttonStyle(FilledActionStyle())
                    .disabled(model.progress != nil)
                }

                if candidates > 0 {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("原件处理").font(.system(size: controlSize, weight: .semibold))
                            .accessibilityAddTraits(.isHeader)
                            .padding(.top, 12)
                        resultAction("移除原件前核对", subtitle: "\(candidates) 张原件可复核", icon: "trash",
                                     destructive: true) {
                            removalSelection = Set(model.readyToRemove)
                            removalSelectionWasEdited = false
                            panel = .removal
                        }
                    }
                    .padding(.horizontal, 12).padding(.bottom, 4)
                    .background(LibraryAppearance.paper, in: RoundedRectangle(cornerRadius: 12))
                    .padding(.top, 20)
                    .disabled(model.progress != nil)
                }

                LazyVStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("逐张结果").font(.system(size: actionSize, weight: .semibold))
                        Text("\(model.results.count) 张").font(.system(size: utilitySize))
                            .foregroundStyle(LibraryAppearance.muted)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.bottom, 4)
                    ForEach(model.results) { ConversionResultRow(result: $0) }
                }.padding(.top, 20)

                if needsHelp {
                    DisclosureGroup("无法核验的照片怎么处理？") {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("可把可读的封面导出到「文件」。导出不会改变核验状态。")
                            Button("导出可读封面") { Task { await model.exportUnverifiedCovers() } }
                                .foregroundStyle(LibraryAppearance.link).frame(minHeight: 44)
                                .disabled(model.progress != nil)
                            if model.authorization == .limited {
                                Button("管理照片访问", action: openSettings)
                                    .foregroundStyle(LibraryAppearance.link).frame(minHeight: 44)
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }.font(.caption).foregroundStyle(LibraryAppearance.muted).padding(.top, 13)
                }
                if model.conversionDuration != nil || !diagnostics.isEmpty {
                    DisclosureGroup("处理详情") {
                        VStack(alignment: .leading, spacing: 6) {
                            if let duration = model.conversionDuration { Text(duration) }
                            if !diagnostics.isEmpty {
                                Text(diagnostics).font(.caption2.monospaced()).textSelection(.enabled)
                                Button("复制错误码") { UIPasteboard.general.string = diagnostics }
                                    .foregroundStyle(LibraryAppearance.link).frame(minHeight: 44)
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }.font(.caption).foregroundStyle(LibraryAppearance.muted).padding(.top, 12)
                }
            }.padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 16)
        }
        .scrollIndicators(.hidden)
        .onAppear { resultFocused = true }
    }

    private func resultAction(_ title: String, subtitle: String, icon: String, destructive: Bool = false,
                              action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon).font(.system(size: 19)).frame(width: 20)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.system(size: controlSize, weight: .semibold))
                    Text(subtitle).font(.caption).foregroundStyle(LibraryAppearance.muted)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .medium))
                    .foregroundStyle(LibraryAppearance.muted)
                    .accessibilityHidden(true)
            }
            .foregroundStyle(destructive ? LibraryAppearance.danger : LibraryAppearance.link)
            .padding(.vertical, 8)
            .frame(minHeight: 55)
            .contentShape(Rectangle())
            .accessibilityElement(children: .combine)
        }
    }

    private var removalSheet: some View {
        let candidateIDs = model.readyToRemove
        let candidates = Set(candidateIDs)
        let selectedIDs = candidateIDs.filter { !removalSelectionWasEdited || removalSelection.contains($0) }
        let selected = Set(selectedIDs)
        return VStack(spacing: 0) {
            panelHeader("移除原件前核对")
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    Text("仅列出副本已核验、可提交复核的原件。取消勾选可保留对应原件。")
                        .font(.subheadline).foregroundStyle(LibraryAppearance.muted)
                    let summary = "选中 \(selectedIDs.count) 张原件"
                    let estimate = selectedIDs.isEmpty ? "" : " · " + model.estimateText(for: selectedIDs, title: "移除后预计节省")
                    Text(summary + estimate)
                        .font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12).background(LibraryAppearance.paper, in: RoundedRectangle(cornerRadius: 11))
                    ForEach(model.results.filter { candidates.contains($0.id) }) { result in
                        CompactPhotoRow(id: result.id, name: result.photoName, date: result.capturedAt) {
                            let selected = !removalSelectionWasEdited || removalSelection.contains(result.id)
                            Button {
                                if selected { removalSelection.remove(result.id) } else { removalSelection.insert(result.id) }
                                removalSelectionWasEdited = true
                            } label: {
                                Image(systemName: selected ? "checkmark.square.fill" : "square")
                                    .foregroundStyle(selected ? LibraryAppearance.link : LibraryAppearance.muted)
                                    .font(.system(size: 22))
                                    .frame(width: 44, height: 44)
                                    .contentShape(Rectangle())
                            }
                            .accessibilityLabel("处理 \(result.photoName)")
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                    Text("开启 iCloud 照片时，移除会同步到其他设备。实际腾出的空间取决于存储状态。")
                        .font(.footnote).foregroundStyle(LibraryAppearance.warning)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(LibraryAppearance.warningTint, in: RoundedRectangle(cornerRadius: 8))
                }.padding(.horizontal, 17).padding(.bottom, 12)
            }
            FlatDock(verticalPadding: 9) {
                Button(selectedIDs.isEmpty ? "请选择原件" : "复核并继续移除（\(selectedIDs.count) 张）", role: .destructive) {
                    dismissPanel { Task { await model.removeOriginals(ids: selected) } }
                }.buttonStyle(FilledActionStyle(destructive: true))
                    .disabled(selectedIDs.isEmpty || model.progress != nil)
            }
        }
        .onAppear {
            guard !removalSelectionWasEdited else { return }
            removalSelection = Set(candidateIDs)
        }
        .task(id: candidateIDs) {
            guard !removalSelectionWasEdited else { return }
            removalSelection = Set(candidateIDs)
        }
        .padding(.top, 7)
    }

    private func panelHeader(_ title: String) -> some View {
        HStack {
            Text(title)
                .font(.system(size: panelTitleSize, weight: .semibold))
                .accessibilityAddTraits(.isHeader)
                .accessibilityFocused($panelFocused)
            Spacer()
            Button { panel = nil } label: {
                Image(systemName: "xmark")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(LibraryAppearance.ink)
                    .frame(width: 44, height: 44)
                    .background(LibraryAppearance.paper, in: Circle())
            }.accessibilityLabel("关闭")
        }
        .padding(.horizontal, 16).frame(minHeight: 60)
        .onAppear { panelFocused = true }
    }

    private func dismissPanel(then action: @escaping () -> Void) {
        afterPanel = action
        panel = nil
    }

    private func runAfterPanel() {
        let action = afterPanel
        afterPanel = nil
        if let action { action() } else if screen == .library { filterFocused = true }
    }

    private func saveSelection() {
        Task {
            if await model.convertSelection(excludingFavorites: excludeFavorites) {
                didViewResults = true
                screen = .results
            }
        }
    }

    private var visibleGallery: PhotoGalleryContent {
        gallery(excludingFavorites: excludeFavorites, scene: sceneFilter, onlySelected: onlySelected)
    }

    private func gallery(excludingFavorites: Bool, scene: PhotoScene?, onlySelected: Bool) -> PhotoGalleryContent {
        let gallery = model.gallery(excludingFavorites: excludingFavorites)
        guard onlySelected || scene != nil else { return gallery }
        if onlySelected {
            let assets = model.selectedAssets(excludingFavorites: excludeFavorites)
                .filter { scene == nil || model.sceneResults[$0.localIdentifier]?.scene == scene }
                .sorted { ($0.creationDate ?? .distantPast) > ($1.creationDate ?? .distantPast) }
            return PhotoGalleryContent(assets: assets, dateGroups: PhotoDateGroupViewData.groups(from: assets))
        }
        return gallery.filtered { model.sceneResults[$0.localIdentifier]?.scene == scene }
    }

    private var hiddenSelectedCount: Int {
        guard let sceneFilter else { return 0 }
        return model.selectedIDs(excludingFavorites: excludeFavorites).lazy
            .filter { model.sceneResults[$0]?.scene != sceneFilter }.count
    }

    private var filterActive: Bool { sceneFilter != nil || !excludeFavorites }

    private func resultHeadline(confirmed: Int) -> String {
        return confirmed > 0 ? "已保存 \(confirmed) 张静态副本" : "本次未确认静态副本"
    }

    private func resultMessage(removed: Int) -> String {
        let pendingRemoval = model.results.lazy.filter { $0.state == .removalPending }.count
        if pendingRemoval > 0 {
            let pending = "\(pendingRemoval) 张原件的移除结果待核对。"
            return removed > 0 ? "\(removed) 张原件已移除，" + pending : pending
        }
        if removed > 0 {
            let kept = model.results.count - removed
            return "\(removed) 张原件已移除" + (kept > 0 ? "，\(kept) 张原件保留。" : "。")
        }
        return "本批实况原件仍在相册。"
    }

    private func photoName(_ asset: PHAsset) -> String {
        PHAssetResource.assetResources(for: asset).first(where: { $0.type == .photo })?.originalFilename ?? "未命名照片"
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        openURL(url)
    }
}

private enum LibraryAppearance {
    static let canvas = Color(uiColor: .systemBackground)
    static let ink = adaptive(0x1D1D1F, 0xF5F5F7)
    static let muted = adaptive(0x66666C, 0xA8A8AE)
    static let paper = adaptive(0xF5F5F7, 0x1C1C1E)
    static let divider = adaptive(0xE0E0E0, 0x38383A)
    static let separatorSoft = adaptive(0xF0F0F0, 0x262628)
    static let segmentTrack = adaptive(0xEDEDF0, 0x262628)
    static let segmentThumb = adaptive(0xFFFFFF, 0x48484A)
    static let blue = adaptive(0x0066CC, 0x0066CC)
    static let selected = adaptive(0x0071E3, 0x70B5FF)
    static let link = adaptive(0x0066CC, 0x70B5FF)
    static let blueTint = adaptive(0xEAF3FF, 0x142C47)
    static let estimateLabel = adaptive(0x345C7E, 0xA7C7E4)
    static let estimateValue = adaptive(0x135389, 0xA9D5FF)
    static let progressTrack = adaptive(0xE8E8ED, 0x38383A)
    static let disabledFill = adaptive(0xEEEEF1, 0x2C2C2E)
    static let disabledText = adaptive(0x75757C, 0xA8A8AE)
    static let placeholder = adaptive(0xE4E8EB, 0x282A2E)
    static let danger = adaptive(0xB4232D, 0xFF9CA1)
    static let dangerTint = adaptive(0xFFF0F1, 0x412125)
    static let good = adaptive(0x216242, 0x8DE3AF)
    static let goodTint = adaptive(0xE9F7EE, 0x193A27)
    static let warning = adaptive(0x79500B, 0xFFD18D)
    static let warningTint = adaptive(0xFFF3DF, 0x3C2C16)

    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255,
                           green: CGFloat((value >> 8) & 255) / 255,
                           blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }
}

private struct SaveProgressPage: View {
    let progress: SaveProgress
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .title2) private var titleSize = 26.0
    @ScaledMetric(relativeTo: .subheadline) private var detailSize = 14.0

    var body: some View {
        GeometryReader { page in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Image(systemName: "photo")
                        .font(.system(size: 25, weight: .medium))
                        .foregroundStyle(LibraryAppearance.link)
                        .frame(width: 49, height: 49)
                        .background(LibraryAppearance.blueTint, in: RoundedRectangle(cornerRadius: 15))
                        .accessibilityHidden(true)
                    Text("正在保存副本")
                        .font(.system(size: titleSize, weight: .bold))
                        .accessibilityAddTraits(.isHeader)
                        .padding(.top, 13).padding(.bottom, 7)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(stageLabel)
                        if let filename = progress.filename { Text(filename).padding(.top, 3) }
                        GeometryReader { track in
                            Capsule().fill(LibraryAppearance.progressTrack)
                                .overlay(alignment: .leading) {
                                    Capsule().fill(LibraryAppearance.blue)
                                        .frame(width: track.size.width * progress.fractionCompleted)
                                }
                        }
                        .frame(height: 5)
                        .animation(reduceMotion ? nil : .easeInOut(duration: 0.26), value: progress.fractionCompleted)
                        .padding(.top, 19).padding(.bottom, 10)
                        .accessibilityHidden(true)
                        Text("已完成 \(progress.current - 1) / \(progress.total) 张")
                            .padding(.bottom, 4)
                        Text("先完成保存和核验，实况原件仍在。")
                    }
                    .font(.system(size: detailSize))
                    .foregroundStyle(LibraryAppearance.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
                .frame(minHeight: page.size.height)
            }.scrollIndicators(.hidden)
        }
    }

    private var stageLabel: String {
        let title: String
        switch progress.stage {
        case .preparing: title = "准备读取实况照片"
        case .writing: title = "写入静态副本"
        case .verifying: title = "回读核验静态副本"
        }
        return "\(title) · 第 \(progress.current) / \(progress.total) 张"
    }
}

private struct FlatPressStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(isEnabled ? (configuration.isPressed ? 0.82 : 1) : 0.56)
    }
}

private struct FilledActionStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    @ScaledMetric(relativeTo: .body) private var buttonSize = 16.0
    var destructive = false
    var secondary = false

    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: 12)
        let foreground = destructive ? LibraryAppearance.danger : secondary ? LibraryAppearance.ink : .white
        let background = destructive ? LibraryAppearance.dangerTint : secondary ? LibraryAppearance.paper : LibraryAppearance.blue
        return configuration.label
            .font(.system(size: buttonSize, weight: .semibold))
            .multilineTextAlignment(.center)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 46)
            .foregroundStyle(isEnabled ? foreground : LibraryAppearance.disabledText)
            .background(isEnabled ? background : LibraryAppearance.disabledFill, in: shape)
            .opacity(configuration.isPressed ? 0.72 : 1)
            .contentShape(shape)
    }
}

private struct SelectionButtonsLayout: Layout {
    private let spacing: CGFloat = 8

    private func widths(for width: CGFloat) -> (CGFloat, CGFloat) {
        let available = max(0, width - spacing)
        return (available * 0.4, available * 0.6)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let idealWidth = subviews.reduce(spacing) { $0 + $1.sizeThatFits(.unspecified).width }
        let width = proposal.width ?? idealWidth
        let (clear, save) = widths(for: width)
        let height = max(subviews[0].sizeThatFits(.init(width: clear, height: nil)).height,
                         subviews[1].sizeThatFits(.init(width: save, height: nil)).height)
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let (clear, save) = widths(for: bounds.width)
        subviews[0].place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading,
                          proposal: .init(width: clear, height: bounds.height))
        subviews[1].place(at: CGPoint(x: bounds.minX + clear + spacing, y: bounds.midY), anchor: .leading,
                          proposal: .init(width: save, height: bounds.height))
    }
}

private struct FilterSwitchStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 12) {
            configuration.label
            Spacer(minLength: 0)
            Capsule().fill(configuration.isOn ? LibraryAppearance.blue : LibraryAppearance.disabledFill)
                .frame(width: 43, height: 26)
                .overlay(alignment: configuration.isOn ? .trailing : .leading) {
                    Circle().fill(.white).frame(width: 22, height: 22)
                        .shadow(color: .black.opacity(0.12), radius: 1, y: 1).padding(2)
                }
        }
        .frame(minHeight: 52)
        .contentShape(Rectangle())
        .onTapGesture { configuration.isOn.toggle() }
        .accessibilityRepresentation {
            Toggle(isOn: configuration.$isOn) { configuration.label }.toggleStyle(.switch)
        }
    }
}

private struct FlatDock<Content: View>: View {
    var verticalPadding: CGFloat = 6
    var spacing: CGFloat = 5
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(LibraryAppearance.divider).frame(height: 0.5)
            VStack(spacing: spacing) { content }.padding(.horizontal, 16).padding(.vertical, verticalPadding)
        }
        .background(LibraryAppearance.canvas, ignoresSafeAreaEdges: .bottom)
    }
}

private struct StorageEstimateLine: View {
    let estimate: StorageEstimate?
    let count: Int
    @ScaledMetric(relativeTo: .footnote) private var labelSize = 13.0

    var body: some View {
        ZStack(alignment: .leading) {
            // Reserve the space required by a partial estimate before its value arrives.
            sentence(for: .available(number: "999.9", unit: "MB", unknownCount: count))
                .monospacedDigit().hidden().accessibilityHidden(true)
            if let estimate {
                if case .estimating = estimate {
                    HStack(spacing: 5) {
                        Text("正在估算可节省空间")
                            .font(.system(size: labelSize)).foregroundStyle(LibraryAppearance.estimateLabel)
                        ProgressView()
                            .controlSize(.small)
                            .tint(LibraryAppearance.estimateValue)
                            .accessibilityLabel("正在估算可节省空间")
                    }
                } else {
                    sentence(for: estimate).monospacedDigit()
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var label: Text {
        Text("移除原件后预计节省")
            .font(.system(size: labelSize))
            .foregroundColor(LibraryAppearance.estimateLabel)
    }

    private func sentence(for estimate: StorageEstimate) -> Text {
        let small = Font.system(size: labelSize, weight: .semibold)
        switch estimate {
        case .estimating:
            return label
        case .unavailable:
            return Text("可节省的空间暂未知").font(small)
                .foregroundColor(LibraryAppearance.estimateLabel)
        case let .available(number, unit, unknownCount):
            let qualifier = Text(unknownCount > 0 ? "至少" : "约").font(small)
                .foregroundColor(LibraryAppearance.estimateValue)
            let digits = Text(verbatim: number).font(small)
                .foregroundColor(LibraryAppearance.estimateValue)
            let units = Text(verbatim: unit).font(small).foregroundColor(LibraryAppearance.estimateValue)
            let unknown = Text(unknownCount > 0 ? " · \(unknownCount) 张未知" : "").font(small)
                .foregroundColor(LibraryAppearance.estimateLabel)
            return Text("\(label) \(qualifier) \(digits) \(units)\(unknown)")
        }
    }
}

private struct ResultSummaryEntrance: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var visible = false

    func body(content: Content) -> some View {
        content.opacity(visible || reduceMotion ? 1 : 0.78)
            .onAppear {
                withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) { visible = true }
            }
    }
}

private struct GalleryTileBounds: PreferenceKey {
    static let defaultValue: [String: Anchor<CGRect>] = [:]
    static func reduce(value: inout [String: Anchor<CGRect>], nextValue: () -> [String: Anchor<CGRect>]) {
        value.merge(nextValue(), uniquingKeysWith: { _, next in next })
    }
}

private struct GalleryLoadingView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false
    let byDay: Bool

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: 11).frame(height: 44)
                    Capsule().frame(width: 92, height: 44)
                }
                .foregroundStyle(LibraryAppearance.paper)
                .frame(height: 50)
                .accessibilityHidden(true)
                Text("正在读取相册…")
                    .font(.caption)
                    .foregroundStyle(LibraryAppearance.muted)
                    .frame(minHeight: 19)
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 8)

            Rectangle().fill(LibraryAppearance.separatorSoft).frame(height: 1)

            GeometryReader { viewport in
                VStack(spacing: 4) {
                    ForEach(0..<4) { section in
                        if byDay {
                            Capsule().frame(width: 96, height: 12)
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        }
                        ForEach(0..<(section == 0 ? 3 : 2)) { _ in
                            GalleryPhotoRowLayout {
                                ForEach(0..<PhotoGalleryRow.columns) { _ in
                                    RoundedRectangle(cornerRadius: 3)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 3)
                .frame(height: viewport.size.height, alignment: .top)
                .clipped()
                .foregroundStyle(LibraryAppearance.placeholder)
                .opacity(reduceMotion || pulse ? 0.94 : 0.57)
                .accessibilityHidden(true)
            }
        }
        .accessibilityIdentifier("galleryLoading")
        .onAppear {
            if !reduceMotion {
                withAnimation(.easeInOut(duration: 0.98).repeatForever(autoreverses: true)) {
                    pulse = true
                }
            }
        }
    }
}

private struct GalleryPhotoRowLayout: Layout {
    private let spacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 0
        return CGSize(width: width, height: tileWidth(width))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let side = tileWidth(bounds.width)
        for (index, tile) in subviews.enumerated() {
            tile.place(at: CGPoint(x: bounds.minX + CGFloat(index) * (side + spacing), y: bounds.minY),
                       anchor: .topLeading, proposal: ProposedViewSize(width: side, height: side))
        }
    }

    private func tileWidth(_ width: CGFloat) -> CGFloat {
        let count = CGFloat(PhotoGalleryRow.columns)
        return max(0, (width - (count - 1) * spacing) / count)
    }
}

private struct PhotoGalleryScrollArea<Content: View>: View {
    let gallery: PhotoGalleryContent
    let rows: PhotoGalleryRows
    let selection: Set<String>
    let selectionOrder: [String]
    let rangeEnabled: Bool
    let selectRange: ([String], [String], Bool) -> Void
    @ViewBuilder let content: () -> Content
    @State private var firstRowID: PhotoGalleryRow.ID?
    @State private var scrollable = false
    @State private var atEnd = false
    @State private var previousOffset: Int?
    @State private var showHandle = false
    @State private var scrubbing = false
    @State private var hideHandle: Task<Void, Never>?
    @State private var seekTask: Task<Void, Never>?
    @State private var pendingIndex: Int?
    @State private var requestedRow: PhotoGalleryRow.ID?
    @State private var preheatedRange: Range<Int> = 0..<0
    @State private var preheatedAssets: [PHAsset] = []
    @State private var preheatBucket: Int?

    var body: some View {
        GeometryReader { viewport in
            ScrollView {
                content()
                .backgroundPreferenceValue(GalleryTileBounds.self) { anchors in
                    GeometryReader { geometry in
                        GalleryRangeGesture(enabled: rangeEnabled, selection: selectionOrder,
                            isSelected: { index in
                                gallery.assets.indices.contains(index) && selection.contains(gallery.assets[index].localIdentifier)
                            },
                            locate: { point in
                                anchors.first { geometry[$0.value].contains(point) }
                                    .flatMap { gallery.indexByID[$0.key] }
                            },
                            updateRange: { base, start, end, selecting in
                                guard gallery.assets.indices.contains(start), gallery.assets.indices.contains(end) else { return }
                                let range = min(start, end)...max(start, end)
                                selectRange(base, gallery.assets[range].map(\.localIdentifier), selecting)
                            })
                    }
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                }
                .onGeometryChange(for: Bool.self) { geometry in
                    geometry.size.height > viewport.size.height + 1
                } action: { scrollable = $0 }
                .onGeometryChange(for: Bool.self) { geometry in
                    geometry.frame(in: .named("libraryPhotoViewport")).maxY <= viewport.size.height + 1
                } action: { atEnd = $0 }
                .onGeometryChange(for: Int.self) { geometry in
                    Int((geometry.frame(in: .named("libraryPhotoViewport")).minY / 28).rounded())
                } action: { offset in
                    if let previousOffset, previousOffset != offset, !scrubbing { revealHandle() }
                    previousOffset = offset
                }
            }
            .coordinateSpace(name: "libraryPhotoViewport")
            .scrollPosition(id: $firstRowID, anchor: .top)
            .scrollIndicators(.hidden)
            .accessibilityIdentifier("photoGalleryScroll")
            .onAppear { updateThumbnailPreheat(visibleIndex) }
            .onChange(of: visibleIndex) { _, index in updateThumbnailPreheat(index) }
            .onChange(of: gallery.assets.first?.localIdentifier) { _, _ in
                preheatBucket = nil
                updateThumbnailPreheat(visibleIndex)
            }
            .overlay(alignment: .trailing) {
                if (scrollable || scrubbing) && gallery.assets.count > 1 {
                    GalleryDateScrollbar(assets: gallery.assets, visibleIndex: atEnd ? gallery.assets.count - 1 : visibleIndex,
                                         shown: showHandle, scrubbing: $scrubbing, activity: revealHandle) { index, finished in
                        requestJump(to: index, finished: finished)
                    }
                }
            }
        }
        .onChange(of: gallery.assets.count) { _, _ in cancelSeek() }
        .onChange(of: rows.rows.first?.id) { _, _ in cancelSeek() }
        .onDisappear {
            hideHandle?.cancel()
            cancelSeek()
            stopThumbnailPreheat()
        }
    }

    private func cancelSeek() {
        seekTask?.cancel()
        seekTask = nil
        pendingIndex = nil
        requestedRow = nil
    }

    private func requestJump(to index: Int, finished: Bool) {
        guard gallery.assets.indices.contains(index) else { return }
        if finished {
            cancelSeek()
            jump(to: index)
            return
        }

        pendingIndex = index
        guard seekTask == nil else { return }
        seekTask = Task { @MainActor in
            while !Task.isCancelled, let next = pendingIndex {
                pendingIndex = nil
                let row = rows.target(at: next)
                if requestedRow != row {
                    requestedRow = row
                    jump(to: next)
                }
                try? await Task.sleep(for: .milliseconds(75))
            }
            if !Task.isCancelled { seekTask = nil }
        }
    }

    private func jump(to index: Int) {
        // Use the same scroll position for tracking the visible row and requesting a jump.
        withTransaction(Transaction(animation: nil)) {
            firstRowID = rows.target(at: index)
        }
    }

    private var visibleIndex: Int {
        firstRowID.flatMap { rows.indexByRowID[$0] } ?? 0
    }

    private func updateThumbnailPreheat(_ index: Int) {
        guard !scrubbing, gallery.assets.indices.contains(index) else { return }
        let bucket = index / 50
        guard preheatBucket != bucket else { return }
        stopThumbnailPreheat()
        let lower = max(0, index - 30)
        let upper = min(gallery.assets.count, index + 90)
        guard lower < upper else { return }
        preheatedRange = lower..<upper
        preheatedAssets = Array(gallery.assets[preheatedRange])
        preheatBucket = bucket
        PhotoThumbnailCache.manager.startCachingImages(
            for: preheatedAssets, targetSize: CGSize(width: 180, height: 180),
            contentMode: .aspectFill, options: nil
        )
    }

    private func stopThumbnailPreheat() {
        guard !preheatedRange.isEmpty else { return }
        PhotoThumbnailCache.manager.stopCachingImages(
            for: preheatedAssets, targetSize: CGSize(width: 180, height: 180),
            contentMode: .aspectFill, options: nil
        )
        preheatedRange = 0..<0
        preheatedAssets = []
        preheatBucket = nil
    }

    private func revealHandle() {
        showHandle = true
        hideHandle?.cancel()
        hideHandle = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(1350))
            if !Task.isCancelled && !scrubbing { showHandle = false }
        }
    }
}

private struct GalleryDateScrollbar: View {
    let assets: [PHAsset]
    let visibleIndex: Int
    let shown: Bool
    @Binding var scrubbing: Bool
    let activity: () -> Void
    let seek: (Int, Bool) -> Void
    @State private var initialFraction: CGFloat?
    @State private var dragFraction: CGFloat?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var lastIndex: Int { assets.count - 1 }
    private var visibleFraction: CGFloat { CGFloat(visibleIndex) / CGFloat(lastIndex) }

    var body: some View {
        GeometryReader { geometry in
            let travel = max(1, geometry.size.height - 56)
            let fraction = dragFraction ?? visibleFraction
            let dateIndex = Int((fraction * CGFloat(lastIndex)).rounded()).clamped(to: 0...lastIndex)
            let dateTitle = assets[dateIndex].creationDate?
                .formatted(.dateTime.year().month().day().locale(Locale(identifier: "zh_CN"))) ?? "日期未知"
            let top = 4 + travel * fraction
            ZStack(alignment: .topTrailing) {
                if scrubbing, dragFraction != nil {
                    Text(dateTitle)
                        .font(.system(size: 13, weight: .semibold)).foregroundStyle(LibraryAppearance.ink)
                        .fixedSize(horizontal: true, vertical: false)
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(LibraryAppearance.canvas, in: Capsule())
                        .shadow(color: .black.opacity(0.15), radius: 5, y: 2)
                        .frame(width: 0, alignment: .trailing)
                        .offset(x: -46, y: top + 8)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
                VStack(spacing: 2) {
                    Image(systemName: "triangle.fill")
                    Image(systemName: "triangle.fill").rotationEffect(.degrees(180))
                }
                .font(.system(size: 9, weight: .heavy)).foregroundStyle(LibraryAppearance.ink)
                .frame(width: 33, height: 46)
                .background(LibraryAppearance.canvas, in: Capsule())
                .shadow(color: .black.opacity(0.22), radius: 5, y: 2)
                .frame(width: 39, height: 48)
                .contentShape(Rectangle())
                .offset(x: -3, y: top)
                .accessibilityElement(children: .ignore)
                .accessibilityIdentifier("galleryDateScrollbar")
                .accessibilityLabel("按日期快滚")
                .accessibilityValue(dateTitle)
                .accessibilityAdjustableAction { direction in
                    let step = max(1, lastIndex / 20)
                    switch direction {
                    case .increment: seek(min(lastIndex, visibleIndex + step), true)
                    case .decrement: seek(max(0, visibleIndex - step), true)
                    @unknown default: break
                    }
                    activity()
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height, alignment: .topTrailing)
            .overlay {
                // Move the thumb, while the recognizer stays in the same coordinate space.
                FastScrollPan(handleTop: top) { state, distance in
                    switch state {
                    case .began, .changed:
                        let start = initialFraction ?? visibleFraction
                        initialFraction = start
                        scrubbing = true
                        let progress = (start + distance / travel).clamped(to: 0...1)
                        dragFraction = progress
                        seek(Int((progress * CGFloat(lastIndex)).rounded()), false)
                    case .ended, .cancelled, .failed:
                        if let dragFraction {
                            seek(Int((dragFraction * CGFloat(lastIndex)).rounded()), true)
                        }
                        resetDrag()
                    default: break
                    }
                }
                .accessibilityHidden(true)
            }
        }
        .frame(width: 43)
        .opacity(shown || scrubbing ? 1 : 0)
        .allowsHitTesting(shown || scrubbing)
        .accessibilityHidden(!shown && !scrubbing)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: shown)
    }

    private func resetDrag() {
        scrubbing = false
        initialFraction = nil
        dragFraction = nil
        activity()
    }
}

private struct FastScrollPan: UIViewRepresentable {
    let handleTop: CGFloat
    let change: (UIGestureRecognizer.State, CGFloat) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(change) }
    func makeUIView(context: Context) -> TrackView {
        let view = TrackView()
        view.backgroundColor = .clear
        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.pan))
        pan.maximumNumberOfTouches = 1
        view.addGestureRecognizer(pan)
        return view
    }
    func updateUIView(_ view: TrackView, context: Context) {
        view.handleTop = handleTop
        context.coordinator.change = change
    }

    final class TrackView: UIView {
        var handleTop: CGFloat = 0

        override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
            super.point(inside: point, with: event) && (handleTop...(handleTop + 48)).contains(point.y)
        }
    }

    final class Coordinator: NSObject {
        var change: (UIGestureRecognizer.State, CGFloat) -> Void
        init(_ change: @escaping (UIGestureRecognizer.State, CGFloat) -> Void) { self.change = change }
        @objc func pan(_ gesture: UIPanGestureRecognizer) {
            change(gesture.state, gesture.translation(in: gesture.view).y)
        }
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(range.upperBound, max(range.lowerBound, self))
    }
}

private struct GalleryRangeGesture: UIViewRepresentable {
    let enabled: Bool
    let selection: [String]
    let isSelected: (Int) -> Bool
    let locate: (CGPoint) -> Int?
    let updateRange: ([String], Int, Int, Bool) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> Probe {
        let view = Probe()
        view.isUserInteractionEnabled = false
        view.onMount = { [weak coordinator = context.coordinator, weak view] in
            if let view { coordinator?.attach(to: view) }
        }
        return view
    }

    func updateUIView(_ view: Probe, context: Context) {
        context.coordinator.configuration = self
        context.coordinator.attach(to: view)
    }

    static func dismantleUIView(_ view: Probe, coordinator: Coordinator) {
        coordinator.scrollView?.removeGestureRecognizer(coordinator.pan)
    }

    final class Probe: UIView {
        var onMount: (() -> Void)?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            onMount?()
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var configuration: GalleryRangeGesture
        weak var scrollView: UIScrollView?
        private weak var probe: Probe?
        private var start: Int?
        private var endpoint: Int?
        private var base: [String] = []
        private var selecting = true
        let pan = UIPanGestureRecognizer()

        init(_ configuration: GalleryRangeGesture) {
            self.configuration = configuration
            super.init()
            pan.delegate = self
            pan.maximumNumberOfTouches = 1
            pan.addTarget(self, action: #selector(changed))
        }

        func attach(to probe: Probe) {
            self.probe = probe
            var parent = probe.superview
            while let view = parent, !(view is UIScrollView) { parent = view.superview }
            guard let candidate = parent as? UIScrollView, scrollView !== candidate else { return }
            scrollView?.removeGestureRecognizer(pan)
            scrollView = candidate
            candidate.addGestureRecognizer(pan)
            candidate.panGestureRecognizer.require(toFail: pan)
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard configuration.enabled, let probe else { return false }
            let movement = pan.translation(in: probe)
            guard abs(movement.x) > abs(movement.y) * 1.2 else { return false }
            let point = pan.location(in: probe)
            start = configuration.locate(CGPoint(x: point.x - movement.x, y: point.y - movement.y))
            base = configuration.selection
            selecting = start.map { !configuration.isSelected($0) } ?? true
            endpoint = nil
            return start != nil
        }

        @objc private func changed() {
            guard let start, let probe else { return }
            switch pan.state {
            case .began, .changed, .ended:
                let end = configuration.locate(pan.location(in: probe)) ?? endpoint ?? start
                if end != endpoint {
                    endpoint = end
                    configuration.updateRange(base, start, end, selecting)
                }
            default: break
            }
        }
    }
}

private struct PhotoPreviewOverlay: View {
    let asset: PHAsset
    let playsLive: Bool
    let close: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var presented = false

    var body: some View {
        GeometryReader { geometry in
            let ratio = CGFloat(max(asset.pixelWidth, 1)) / CGFloat(max(asset.pixelHeight, 1))
            let width = min(max(1, geometry.size.width - 34), max(1, geometry.size.height - 68) * ratio)
            ZStack {
                Color.black.opacity(0.82).ignoresSafeArea().onTapGesture(perform: dismiss)
                    .accessibilityHidden(true)
                    .opacity(presented ? 1 : 0)
                LiveContextPreview(asset: asset, playsLive: playsLive)
                    .id(asset.localIdentifier)
                    .frame(width: width, height: width / ratio)
                    .clipShape(.rect(cornerRadius: 18))
                    .overlay(alignment: .topLeading) {
                        if playsLive {
                            Label("实况", systemImage: "livephoto")
                                .font(.caption2).foregroundStyle(.white)
                                .padding(.horizontal, 8).padding(.vertical, 5)
                                .background(.black.opacity(0.55), in: Capsule()).padding(10)
                                .accessibilityHidden(true)
                        }
                    }
                    .accessibilityLabel(playsLive ? "实况预览" : "静态副本")
                    .accessibilityAction(named: Text("关闭预览"), dismiss)
                    .accessibilityAction(.escape, dismiss)
                    .scaleEffect(reduceMotion || presented ? 1 : 0.94)
                    .opacity(presented ? 1 : 0)
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .onAppear { withAnimation(.easeOut(duration: 0.22)) { presented = true } }
    }

    private func dismiss() {
        guard presented else { return }
        withAnimation(.easeIn(duration: 0.18), completionCriteria: .logicallyComplete) {
            presented = false
        } completion: {
            close()
        }
    }
}

private struct StillPhotoPreviewOverlay: View {
    let asset: PHAsset
    let name: String
    let close: () -> Void

    var body: some View {
        GeometryReader { geometry in
            let ratio = CGFloat(max(asset.pixelWidth, 1)) / CGFloat(max(asset.pixelHeight, 1))
            let width = max(1, geometry.size.width - 24)
            let mediaHeight = max(1, min(width / ratio, geometry.size.height * 0.55))
            ZStack {
                Color.black.opacity(0.82).ignoresSafeArea().onTapGesture(perform: close)
                    .accessibilityHidden(true)
                VStack(spacing: 0) {
                    HStack(spacing: 8) {
                        Text(name).font(.subheadline.weight(.semibold))
                            .lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                        Button(action: close) {
                            Image(systemName: "xmark").font(.subheadline.weight(.medium))
                                .frame(width: 44, height: 44)
                        }
                        .accessibilityLabel("关闭预览")
                    }
                    .padding(.leading, 12)
                    LiveContextPreview(asset: asset, playsLive: false)
                        .frame(height: mediaHeight)
                        .accessibilityLabel("静态副本")
                    Text("静态副本").font(.caption).foregroundStyle(.white.opacity(0.8))
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .padding(.horizontal, 12)
                }
                .foregroundStyle(.white)
                .frame(width: width)
                .background(Color(white: 0.16))
                .clipShape(.rect(cornerRadius: 18))
                .accessibilityAction(.escape, close)
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

private struct CopyGallery: View {
    let results: [ConversionOutcome]
    let authorization: PHAuthorizationStatus
    let manageAccess: () -> Void
    let showPreview: (PHAsset) -> Void
    @State private var assets: [PHAsset] = []
    @State private var loaded = false
    @ScaledMetric(relativeTo: .largeTitle) private var titleSize = 34.0
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 4), count: 3)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 15) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("本次保存 \(results.count) 张")
                        .font(.system(size: titleSize, weight: .semibold)).accessibilityAddTraits(.isHeader)
                    Text("照片 App → 相簿 → 实况转静态")
                        .font(.subheadline).foregroundStyle(LibraryAppearance.muted)
                }
                if !loaded {
                    ProgressView("正在读取静态副本…").frame(maxWidth: .infinity, minHeight: 140)
                } else {
                    if !assets.isEmpty {
                        LazyVGrid(columns: columns, spacing: 4) {
                            ForEach(assets, id: \.localIdentifier) { asset in
                                Button { showPreview(asset) } label: {
                                    CopyThumbnail(asset: asset)
                                        .aspectRatio(1, contentMode: .fit)
                                        .contentShape(Rectangle())
                                }
                                .accessibilityLabel("查看静态副本")
                            }
                        }.clipShape(.rect(cornerRadius: 11))
                    }
                    if assets.count < results.count {
                        Text("部分副本暂不可读取。可在系统「照片」的「最近存储」中核对。")
                            .font(.subheadline).foregroundStyle(LibraryAppearance.muted)
                        if authorization == .limited {
                            Button("管理照片访问", action: manageAccess)
                                .font(.subheadline).foregroundStyle(LibraryAppearance.link).frame(minHeight: 44)
                        }
                    }
                }
            }.padding(.horizontal, 16).padding(.vertical, 8)
        }
        .task(id: authorization) { await loadCopies() }
    }

    private func loadCopies() async {
        let ids = results.compactMap(\.replacementID)
        let batch = await Task.detached(priority: .userInitiated) { CopyAssets(ids: ids) }.value
        guard !Task.isCancelled else { return }
        assets = batch.assets
        loaded = true
    }
}

private struct CopyAssets: @unchecked Sendable {
    let assets: [PHAsset]

    init(ids: [String]) {
        let matches = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
        var byID: [String: PHAsset] = [:]
        matches.enumerateObjects { asset, _, _ in
            if asset.mediaType == .image && !asset.mediaSubtypes.contains(.photoLive) {
                byID[asset.localIdentifier] = asset
            }
        }
        assets = ids.compactMap { byID[$0] }
    }
}

private struct CopyThumbnail: View {
    let asset: PHAsset
    @StateObject private var thumbnail = PhotoThumbnail()

    var body: some View {
        LibraryAppearance.paper.overlay {
            if let image = thumbnail.image { Image(uiImage: image).resizable().scaledToFill() }
        }.clipped()
            .onAppear { thumbnail.load(asset) }
            .onDisappear { thumbnail.cancel() }
    }
}

private struct GalleryLayout: Equatable {
    let columns: Int
    let byDay: Bool
}

private struct SelectablePhotoTile: View {
    let asset: PHAsset
    @ObservedObject var model: LibraryModel
    let preview: () -> Void

    private var selected: Bool { model.selection.contains(asset.localIdentifier) }

    var body: some View {
        PhotoTile(asset: asset, compact: true)
            .overlay {
                if selected {
                    Rectangle().fill(.white.opacity(0.26))
                }
            }
            .overlay(alignment: .bottomTrailing) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, LibraryAppearance.blue)
                    .font(.system(size: 15, weight: .semibold))
                    .padding(3)
                    .shadow(color: .black.opacity(0.5), radius: 2)
            }
            .clipShape(.rect(cornerRadius: 3))
            .contentShape(Rectangle())
            .gesture(LongPressGesture(minimumDuration: 0.45, maximumDistance: 18)
                .exclusively(before: TapGesture()).onEnded { result in
                    guard model.progress == nil else { return }
                    switch result {
                    case .first: preview()
                    case .second: model.toggle(asset.localIdentifier)
                    }
                })
            .accessibilityElement()
            .accessibilityIdentifier("galleryPhotoTile-\(asset.localIdentifier)")
            .accessibilityLabel("实况照片，\(asset.creationDate?.formatted(date: .abbreviated, time: .shortened) ?? "拍摄时间未知")")
            .accessibilityValue(selected ? "已选中" : "未选中")
            .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
            .accessibilityAction { model.toggle(asset.localIdentifier) }
            .accessibilityAction(named: Text("预览实况")) { preview() }
            .disabled(model.progress != nil)
    }
}

private struct PhotoTile: View {
    let asset: PHAsset
    let compact: Bool

    @StateObject private var thumbnail = PhotoThumbnail()
    private var badgeSize: CGFloat { compact ? 15 : 22 }
    private var badgeInset: CGFloat { compact ? 3 : 6 }

    var body: some View {
        LibraryAppearance.placeholder
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                GeometryReader { geometry in
                    if let image = thumbnail.image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                            .frame(width: geometry.size.width, height: geometry.size.height)
                    } else {
                        Image(systemName: "livephoto")
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .clipped()
            }
            .overlay(alignment: .topLeading) {
                Image(systemName: "livephoto")
                    .font(.system(size: compact ? 9 : 12, weight: .medium))
                    .foregroundStyle(.white)
                    .frame(width: badgeSize, height: badgeSize)
                    .background(.black.opacity(0.42), in: Circle())
                    .padding(badgeInset)
            }
            .overlay(alignment: .bottomLeading) {
                if asset.isFavorite {
                    Image(systemName: "heart.fill")
                        .font(.system(size: compact ? 8 : 10, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: badgeSize, height: badgeSize)
                        .background(.black.opacity(0.42), in: Circle())
                        .padding(badgeInset)
                }
            }
            .contentShape(Rectangle())
            .onAppear { thumbnail.load(asset) }
            .onDisappear { thumbnail.cancel() }
    }
}

private struct ConversionResultRow: View {
    let result: ConversionOutcome
    @ScaledMetric(relativeTo: .caption2) private var statusFontSize = 12.0

    var body: some View {
        CompactPhotoRow(id: result.id, name: result.photoName, date: result.capturedAt, detail: statusDetail) {
            Text(statusTitle)
                .font(.system(size: statusFontSize, weight: .semibold))
                .foregroundStyle(statusColor)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(statusFill, in: Capsule())
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: 95)
        }
        .accessibilityElement(children: .combine)
    }

    private var statusTitle: String {
        switch result.state {
        case .verified: "已核验"
        case .previewOnly: "保留原件"
        case .pending: "待核验"
        case .skipped: "已跳过"
        case .failed: "保存失败"
        case .retained: "原件已保留"
        case .removalPending: "移除待核对"
        case .removed: "原件已移除"
        }
    }

    private var statusDetail: String {
        switch result.state {
        case .verified: "相册已读回静态副本"
        case .previewOnly: "已保存副本，原件不参与移除"
        case .pending: "暂未读回可验证副本"
        case .skipped, .failed: result.detail.isEmpty ? "原件已保留" : result.detail
        case .retained: result.detail.isEmpty ? "可重新核对" : result.detail
        case .removalPending: result.detail
        case .removed: "静态副本已保留"
        }
    }

    private var statusColor: Color {
        switch result.state {
        case .verified, .removed: LibraryAppearance.good
        case .pending, .skipped, .failed, .retained, .removalPending: LibraryAppearance.warning
        case .previewOnly: LibraryAppearance.muted
        }
    }

    private var statusFill: Color {
        switch result.state {
        case .verified, .removed: LibraryAppearance.goodTint
        case .pending, .skipped, .failed, .retained, .removalPending: LibraryAppearance.warningTint
        case .previewOnly: LibraryAppearance.paper
        }
    }
}

private struct CompactPhotoRow<Trailing: View>: View {
    let name: String
    let date: Date?
    let detail: String?
    let trailing: Trailing
    @Environment(\.dynamicTypeSize) private var dynamicType
    @ScaledMetric(relativeTo: .footnote) private var nameFontSize = 13.0
    @ScaledMetric(relativeTo: .caption) private var detailFontSize = 12.0
    @StateObject private var thumbnail: ResultThumbnail

    init(id: String, name: String, date: Date?, detail: String? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.name = name
        self.date = date
        self.detail = detail
        self.trailing = trailing()
        _thumbnail = StateObject(wrappedValue: ResultThumbnail(assetID: id))
    }

    var body: some View {
        let textLayout = dynamicType.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6))
            : AnyLayout(HStackLayout(spacing: 10))
        HStack(alignment: dynamicType.isAccessibilitySize ? .top : .center, spacing: 10) {
            LibraryAppearance.paper
                .overlay {
                    if let image = thumbnail.image {
                        Image(uiImage: image).resizable().scaledToFill()
                    } else {
                        Image(systemName: "photo").font(.footnote).foregroundStyle(LibraryAppearance.muted)
                    }
                }
                .frame(width: 43, height: 43)
                .clipShape(.rect(cornerRadius: 8))
                .accessibilityHidden(true)

            textLayout {
                VStack(alignment: .leading, spacing: 4) {
                    Text(name)
                        .font(.system(size: nameFontSize, weight: .semibold))
                        .lineLimit(dynamicType.isAccessibilitySize ? nil : 2)
                    Text(dateLabel + (detail.map { " · \($0)" } ?? ""))
                        .font(.system(size: detailFontSize))
                        .foregroundStyle(LibraryAppearance.muted)
                        .lineLimit(dynamicType.isAccessibilitySize ? nil : 2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                trailing
            }
        }
        .padding(.vertical, 7)
        .frame(minHeight: 59)
        .overlay(alignment: .bottom) {
            Rectangle().fill(LibraryAppearance.divider).frame(height: 0.5)
        }
        .task { thumbnail.load() }
    }

    private var dateLabel: String {
        date?.formatted(.dateTime.month().day().hour().minute().locale(Locale(identifier: "zh_CN")))
            ?? "拍摄时间未知"
    }
}

@MainActor
private final class ResultThumbnail: ObservableObject {
    @Published private(set) var image: UIImage?
    let assetID: String
    private var requestID: PHImageRequestID?
    init(assetID: String) { self.assetID = assetID }
    func load() {
        let cacheKey = assetID as NSString
        if let cached = PhotoThumbnailCache.images.object(forKey: cacheKey) {
            image = cached
            return
        }
        guard image == nil, requestID == nil,
              let asset = PhotoConverter.fetch(assetID) else { return }
        let options = PHImageRequestOptions()
        options.deliveryMode = .opportunistic
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = false
        requestID = PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 160, height: 160),
                                                          contentMode: .aspectFill, options: options) { [weak self] image, info in
            guard let image else { return }
            DispatchQueue.main.async {
                guard let self else { return }
                self.image = image
                if (info?[PHImageResultIsDegradedKey] as? Bool) != true {
                    PhotoThumbnailCache.store(image, forKey: cacheKey)
                }
            }
        }
    }
}

struct PhotoDateGroupViewData: Identifiable {
    let id: Date?
    let assets: [PHAsset]
    var title: String {
        guard let id else { return "日期未知" }
        let format = Date.FormatStyle.dateTime.month().day().locale(Locale(identifier: "zh_CN"))
        let currentYear = Calendar.current.isDate(id, equalTo: .now, toGranularity: .year)
        return id.formatted(currentYear ? format : format.year())
    }

    static func groups(from assets: [PHAsset]) -> [PhotoDateGroupViewData] {
        let calendar = Calendar.current
        let buckets = Dictionary(grouping: assets) { asset -> Date? in
            asset.creationDate.map { calendar.startOfDay(for: $0) }
        }
        return buckets.map { PhotoDateGroupViewData(id: $0.key, assets: $0.value) }
            .sorted { lhs, rhs in
                switch (lhs.id, rhs.id) {
                case let (a?, b?): return a > b
                case (_?, nil): return true
                default: return false
                }
            }
    }
}

private struct LiveContextPreview: View {
    let asset: PHAsset
    let playsLive: Bool
    @StateObject private var loader = ContextLivePhotoLoader()

    var body: some View {
        ZStack {
            Color.black
            if let photo = loader.photo {
                ContextLivePhotoPlayer(photo: photo)
            } else if let cover = loader.cover {
                Image(uiImage: cover).resizable().scaledToFit()
            }
            if loader.loading {
                ProgressView().tint(.white)
            } else if loader.photo == nil && loader.cover == nil {
                Text("暂时无法读取照片").font(.footnote).foregroundStyle(.white)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .onAppear { loader.load(asset, playsLive: playsLive) }
        .onDisappear { loader.cancel() }
    }

}

@MainActor
private final class ContextLivePhotoLoader: ObservableObject {
    @Published private(set) var photo: PHLivePhoto?
    @Published private(set) var cover: UIImage?
    @Published private(set) var loading = true
    private var requests: [PHImageRequestID] = []
    private var generation = 0

    func load(_ asset: PHAsset, playsLive: Bool) {
        let current = generation
        let manager = PHImageManager.default()
        let coverSize: CGFloat = playsLive ? 720 : 1200
        let imageOptions = PHImageRequestOptions()
        imageOptions.deliveryMode = .opportunistic
        imageOptions.isNetworkAccessAllowed = true
        requests.append(manager.requestImage(for: asset, targetSize: CGSize(width: coverSize, height: coverSize),
                                             contentMode: .aspectFit, options: imageOptions) { [weak self] image, info in
            let isDegraded = (info?[PHImageResultIsDegradedKey] as? Bool) == true
            DispatchQueue.main.async {
                guard let self, self.generation == current else { return }
                if let image { self.cover = image }
                if !playsLive && !isDegraded { self.loading = false }
            }
        })
        guard playsLive else { return }
        let liveOptions = PHLivePhotoRequestOptions()
        liveOptions.deliveryMode = .highQualityFormat
        liveOptions.isNetworkAccessAllowed = true
        requests.append(manager.requestLivePhoto(for: asset, targetSize: CGSize(width: 720, height: 720),
                                                 contentMode: .aspectFit, options: liveOptions) { [weak self] photo, info in
            guard (info?[PHImageResultIsDegradedKey] as? Bool) != true else { return }
            DispatchQueue.main.async {
                guard let self, self.generation == current else { return }
                self.photo = photo
                self.loading = false
            }
        })
    }

    func cancel() {
        generation += 1
        requests.forEach { PHImageManager.default().cancelImageRequest($0) }
        requests.removeAll()
        photo = nil
        cover = nil
        loading = true
    }
}

private struct ContextLivePhotoPlayer: UIViewRepresentable {
    let photo: PHLivePhoto
    func makeUIView(context: Context) -> PHLivePhotoView { PHLivePhotoView() }
    func updateUIView(_ view: PHLivePhotoView, context: Context) {
        guard view.livePhoto !== photo else { return }
        view.contentMode = .scaleAspectFill
        view.livePhoto = photo
        DispatchQueue.main.async { [weak view] in
            guard view?.livePhoto === photo else { return }
            view?.startPlayback(with: .full)
        }
    }
    static func dismantleUIView(_ view: PHLivePhotoView, coordinator: Coordinator) {
        view.stopPlayback()
        view.livePhoto = nil
    }
}

@MainActor
private final class PhotoThumbnail: ObservableObject {
    @Published private(set) var image: UIImage?
    private var requestID: PHImageRequestID?
    private var cloudRequest: Task<Void, Never>?
    private var generation = 0

    func load(_ asset: PHAsset) {
        guard image == nil, requestID == nil else { return }
        let cacheKey = asset.localIdentifier as NSString
        if let cached = PhotoThumbnailCache.images.object(forKey: cacheKey) {
            image = cached
            return
        }
        request(asset, allowNetwork: false)
    }

    private func request(_ asset: PHAsset, allowNetwork: Bool) {
        let generation = self.generation
        let cacheKey = asset.localIdentifier as NSString
        let options = PHImageRequestOptions()
        options.deliveryMode = .opportunistic
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = allowNetwork
        requestID = PhotoThumbnailCache.manager.requestImage(
            for: asset, targetSize: CGSize(width: 180, height: 180),
            contentMode: .aspectFill, options: options
        ) { [weak self] result, info in
            let isDegraded = (info?[PHImageResultIsDegradedKey] as? Bool) == true
            let isInCloud = (info?[PHImageResultIsInCloudKey] as? Bool) == true
            let isCancelled = (info?[PHImageCancelledKey] as? Bool) == true
            DispatchQueue.main.async {
                guard let self, self.generation == generation, !isCancelled else { return }
                if let result {
                    self.image = result
                    if !isDegraded { PhotoThumbnailCache.store(result, forKey: cacheKey) }
                }
                if !isDegraded { self.requestID = nil }
                if !allowNetwork && isInCloud && (result == nil || isDegraded) {
                    self.scheduleCloudRequest(for: asset, generation: generation)
                } else if result != nil && !isDegraded {
                    self.cloudRequest?.cancel()
                    self.cloudRequest = nil
                }
            }
        }
    }

    private func scheduleCloudRequest(for asset: PHAsset, generation: Int) {
        guard cloudRequest == nil else { return }
        cloudRequest = Task { [weak self] in
            do { try await Task.sleep(for: .milliseconds(300)) } catch { return }
            guard let self, self.generation == generation else { return }
            self.cloudRequest = nil
            if let requestID = self.requestID {
                PhotoThumbnailCache.manager.cancelImageRequest(requestID)
            }
            self.generation += 1
            self.request(asset, allowNetwork: true)
        }
    }

    func cancel() {
        generation += 1
        cloudRequest?.cancel()
        cloudRequest = nil
        if let requestID { PhotoThumbnailCache.manager.cancelImageRequest(requestID) }
        requestID = nil
        image = nil
    }
}

private enum PhotoThumbnailCache {
    static let manager: PHCachingImageManager = {
        let manager = PHCachingImageManager()
        manager.allowsCachingHighQualityImages = false
        return manager
    }()
    static let images: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 384
        cache.totalCostLimit = 48 * 1024 * 1024
        return cache
    }()

    static func store(_ image: UIImage, forKey key: NSString) {
        let cost = image.cgImage.map { $0.bytesPerRow * $0.height } ?? 180 * 180 * 4
        images.setObject(image, forKey: key, cost: cost)
    }
}
