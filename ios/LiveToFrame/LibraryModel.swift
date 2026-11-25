import os
import Photos
import SwiftUI
import UniformTypeIdentifiers

struct CoverDocument: FileDocument {
    static let readableContentTypes: [UTType] = [.image]
    let data: Data
    let name: String

    init(data: Data, name: String) {
        self.data = data
        self.name = name
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.data = data
        name = configuration.file.preferredFilename ?? "Photo"
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        let wrapper = FileWrapper(regularFileWithContents: data)
        wrapper.preferredFilename = name
        return wrapper
    }
}

struct PhotoGalleryRow: Identifiable {
    static let columns = 5

    enum ID: Hashable {
        case day(Date?)
        case photos(String)
    }

    let id: ID
    let startIndex: Int
    let group: PhotoDateGroupViewData?
    let assets: ArraySlice<PHAsset>
}

struct PhotoGalleryRows {
    let rows: [PhotoGalleryRow]
    let indexByRowID: [PhotoGalleryRow.ID: Int]
    private let rowByAssetIndex: [PhotoGalleryRow.ID]

    init(assets: [PHAsset], groups: [PhotoDateGroupViewData]?, indexByID: [String: Int]) {
        var rows: [PhotoGalleryRow] = []
        var rowByAssetIndex = Array(repeating: PhotoGalleryRow.ID.day(nil), count: assets.count)

        func appendPhotos(_ photos: [PHAsset]) {
            for start in stride(from: 0, to: photos.count, by: PhotoGalleryRow.columns) {
                let row = photos[start..<min(start + PhotoGalleryRow.columns, photos.count)]
                let id = PhotoGalleryRow.ID.photos(photos[start].localIdentifier)
                let index = indexByID[photos[start].localIdentifier] ?? start
                rows.append(PhotoGalleryRow(id: id, startIndex: index, group: nil, assets: row))
                for asset in row {
                    if let assetIndex = indexByID[asset.localIdentifier] { rowByAssetIndex[assetIndex] = id }
                }
            }
        }

        if let groups {
            for group in groups {
                guard let first = group.assets.first, let index = indexByID[first.localIdentifier] else { continue }
                rows.append(PhotoGalleryRow(id: .day(group.id), startIndex: index, group: group, assets: []))
                appendPhotos(group.assets)
                rowByAssetIndex[index] = .day(group.id)
            }
        } else {
            appendPhotos(assets)
        }

        self.rows = rows
        self.rowByAssetIndex = rowByAssetIndex
        indexByRowID = Dictionary(uniqueKeysWithValues: rows.map { ($0.id, $0.startIndex) })
    }

    func target(at assetIndex: Int) -> PhotoGalleryRow.ID {
        rowByAssetIndex[assetIndex]
    }
}

struct PhotoGalleryContent {
    let assets: [PHAsset]
    let dateGroups: [PhotoDateGroupViewData]

    let indexByID: [String: Int]
    let rowsByDay: PhotoGalleryRows
    let rowsAll: PhotoGalleryRows

    init(assets: [PHAsset], dateGroups: [PhotoDateGroupViewData]) {
        self.assets = assets
        self.dateGroups = dateGroups
        let indices = Dictionary(uniqueKeysWithValues: assets.enumerated().map { ($0.element.localIdentifier, $0.offset) })
        indexByID = indices
        rowsByDay = PhotoGalleryRows(assets: assets, groups: dateGroups, indexByID: indices)
        rowsAll = PhotoGalleryRows(assets: assets, groups: nil, indexByID: indices)
    }

    func filtered(where includes: (PHAsset) -> Bool) -> PhotoGalleryContent {
        let groups = dateGroups.compactMap { group -> PhotoDateGroupViewData? in
            let assets = group.assets.filter(includes)
            return assets.isEmpty ? nil : PhotoDateGroupViewData(id: group.id, assets: assets)
        }
        return PhotoGalleryContent(assets: groups.flatMap(\.assets), dateGroups: groups)
    }
}

private struct PhotoAssetBatch: @unchecked Sendable {
    let all: PhotoGalleryContent
    let withoutFavorites: PhotoGalleryContent
    let assetsByID: [String: PHAsset]
    let favoriteIDs: Set<String>

    init(assets: [PHAsset]) {
        assetsByID = Dictionary(uniqueKeysWithValues: assets.map { ($0.localIdentifier, $0) })
        let favorites = Set(assets.lazy.filter(\.isFavorite).map(\.localIdentifier))
        favoriteIDs = favorites
        all = PhotoGalleryContent(assets: assets, dateGroups: PhotoDateGroupViewData.groups(from: assets))
        withoutFavorites = favorites.isEmpty ? all : all.filtered { !favorites.contains($0.localIdentifier) }
    }
}

struct ConversionOutcome: Identifiable {
    enum State {
        case verified, previewOnly, pending, skipped, failed, retained, removalPending, removed
    }

    let id: String
    let title: String
    let state: State
    let detail: String
    var photoName: String = "未命名照片"
    var capturedAt: Date? = nil
    var diagnostic: String? = nil
    var replacementID: String? = nil
    var operationID: String? = nil
}

struct SaveProgress {
    let current: Int
    let total: Int
    var filename: String? = nil
    var stage: ConversionStage = .preparing
    var fractionCompleted: Double {
        let stepsPerPhoto = ConversionStage.allCases.count
        return Double((current - 1) * stepsPerPhoto + stage.rawValue) / Double(total * stepsPerPhoto)
    }
}

enum StorageEstimate {
    case estimating
    case unavailable
    case available(number: String, unit: String, unknownCount: Int)
}

@MainActor
final class LibraryModel: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    @Published private(set) var authorization = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    @Published private var library = PhotoAssetBatch(assets: [])
    @Published private(set) var isRefreshing = false
    @Published private(set) var hasLoadedLibrary = false
    @Published private(set) var sceneResults: [String: SceneResult] = [:]
    @Published private(set) var classificationProgress: String?
    @Published private(set) var reclaimableSizes: [String: Int] = [:]
    @Published private(set) var actionEstimateLoading = false
    @Published private(set) var resolvedEstimateIDs: Set<String> = []
    @Published private(set) var selection: Set<String> = []
    @Published private(set) var results: [ConversionOutcome] = []
    @Published private(set) var progress: String?
    @Published private(set) var saveProgress: SaveProgress?
    @Published var notice: String?
    @Published var showResults = false
    @Published private(set) var removalDiagnostic: String?
    @Published private(set) var exportDocuments: [CoverDocument] = []
    @Published var showExporter = false
    @Published private(set) var conversionDuration: String?

    private let logger = Logger(subsystem: "app.livetoframe.personal", category: "PhotoOperations")
    private var sequence: [String] = []
    private var journal: ConversionJournal?
    private var estimateGeneration = 0
    private var estimateTask: Task<Void, Never>?
    private var refreshTask: Task<Void, Never>?
    private var classificationTask: Task<Void, Never>?
    private var refreshRequestedWhileLoading = false
    private var didRestoreJournal = false

    override init() {
        super.init()
        UserDefaults.standard.removeObject(forKey: "selectedLivePhotoIdentifiers")
        PHPhotoLibrary.shared().register(self)
    }

    deinit {
        PHPhotoLibrary.shared().unregisterChangeObserver(self)
    }

    var canBrowse: Bool { authorization == .authorized || authorization == .limited }
    var assets: [PHAsset] { library.all.assets }
    var favoriteIDs: Set<String> { library.favoriteIDs }

    func gallery(excludingFavorites: Bool) -> PhotoGalleryContent {
        excludingFavorites ? library.withoutFavorites : library.all
    }

    func selectedIDs(excludingFavorites: Bool) -> [String] {
        ConversionSafetyPolicy.conversionIDs(sequence, favoriteIDs: favoriteIDs,
                                             excludingFavorites: excludingFavorites)
    }

    func selectedAssets(excludingFavorites: Bool) -> [PHAsset] {
        selectedIDs(excludingFavorites: excludingFavorites).compactMap { library.assetsByID[$0] }
    }

    var readyToRemove: [String] {
        results.filter {
            ($0.state == .verified || $0.state == .retained) && $0.operationID != nil && $0.replacementID != nil
        }.map(\.id)
    }

    func storageEstimate(for identifiers: [String]) -> StorageEstimate {
        if actionEstimateLoading || identifiers.contains(where: { !resolvedEstimateIDs.contains($0) }) {
            return .estimating
        }
        let known = identifiers.compactMap { reclaimableSizes[$0] }
        guard !known.isEmpty else { return .unavailable }
        let total = Double(known.reduce(0, +))
        let unknownCount = identifiers.count - known.count
        if total >= 1_000_000_000 {
            return .available(number: String(format: "%.2f", total / 1_000_000_000),
                              unit: "GB", unknownCount: unknownCount)
        }
        return .available(number: String(format: "%.1f", total / 1_000_000),
                          unit: "MB", unknownCount: unknownCount)
    }

    func estimateText(for identifiers: [String], title: String) -> String {
        switch storageEstimate(for: identifiers) {
        case .estimating:
            return "正在估算\(title)…"
        case .unavailable:
            return "\(title)：照片大小未知"
        case let .available(number, unit, unknownCount):
            let amount = "\(number) \(unit)"
            return unknownCount > 0
                ? "\(title)至少 \(amount)，另有 \(unknownCount) 张大小未知"
                : "\(title)约 \(amount)"
        }
    }

    var resultSummary: String {
        let confirmed = results.filter { [.verified, .previewOnly, .retained, .removed].contains($0.state) }.count
        let pending = results.filter { $0.state == .pending }.count
        let skipped = results.filter { $0.state == .skipped }.count
        let failed = results.filter { $0.state == .failed }.count
        return ["已确认静态 \(confirmed) 张",
                pending > 0 ? "待核验 \(pending) 张" : nil,
                skipped > 0 ? "跳过 \(skipped) 张" : nil,
                failed > 0 ? "失败 \(failed) 张" : nil].compactMap { $0 }.joined(separator: " · ")
    }

    var diagnostics: String {
        let itemDiagnostics = results.enumerated().compactMap { index, item in
            item.diagnostic.map { "第 \(index + 1) 张 · \($0)" }
        }
        return (itemDiagnostics + [removalDiagnostic].compactMap { $0 }).joined(separator: "\n")
    }

    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor [weak self] in
            self?.refresh()
        }
    }

    func requestAccess() async {
        _ = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        refresh()
    }

    func refresh() {
        guard progress == nil else { return }
        guard refreshTask == nil else {
            refreshRequestedWhileLoading = true
            return
        }
        isRefreshing = true
        refreshTask = Task { [weak self] in
            guard let self else { return }
            let refreshStarted = ProcessInfo.processInfo.systemUptime
            if let task = classificationTask {
                classificationProgress = "正在停止识别…"
                task.cancel()
                await task.value
            }
            authorization = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            let batch: PhotoAssetBatch
            if canBrowse {
                batch = await Task.detached(priority: .userInitiated) {
                    Self.loadLivePhotos()
                }.value
            } else {
                batch = PhotoAssetBatch(assets: [])
            }
            sceneResults = sceneResults.filter { entry in
                guard let currentAsset = batch.assetsByID[entry.key] else { return false }
                return entry.value.modifiedAt == currentAsset.modificationDate
            }
            let availableSelection = Set(selection.filter { batch.assetsByID[$0] != nil })
            if availableSelection != selection {
                selection = availableSelection
                sequence.removeAll { !availableSelection.contains($0) }
            }
            library = batch
            hasLoadedLibrary = canBrowse
            restoreJournalIfNeeded()
            refreshActionEstimates()
            let totalDuration = ProcessInfo.processInfo.systemUptime - refreshStarted
            logger.notice("Library refresh \(batch.all.assets.count) assets · total \(totalDuration, format: .fixed(precision: 2))s")
            refreshTask = nil
            isRefreshing = false
            if refreshRequestedWhileLoading {
                refreshRequestedWhileLoading = false
                refresh()
            }
        }
    }

    private func restoreJournalIfNeeded() {
        guard !didRestoreJournal, canBrowse, progress == nil, let converter = makeConverter() else { return }
        didRestoreJournal = true
        let journal = converter.journal
        let byOriginal = Dictionary(grouping: journal.unfinishedRecords, by: \.originalID)
        results = byOriginal.values.compactMap { records in
            guard records.count == 1, let record = records.first else { return nil }
            let state: ConversionOutcome.State = record.state == .written ? .pending : .failed
            return ConversionOutcome(id: record.originalID,
                                     title: record.originalModifiedAt?.formatted(date: .abbreviated, time: .shortened) ?? "待核验照片",
                                     state: state,
                                     detail: record.state == .written ? "发现上次中断的静态副本，正在重新核验。" : "上次操作中断在写入阶段，请先检查系统相册。",
                                     diagnostic: "恢复任务 \(record.operationID)", operationID: record.operationID)
        }
        guard !results.isEmpty else { return }
        Task { [weak self] in
            guard let self else { return }
            let outcomes = await converter.recoverUnfinishedRecords(operationIDs: Set(results.compactMap(\.operationID)))
            for outcome in outcomes {
                guard let index = results.firstIndex(where: { $0.operationID == outcome.operationID }) else { continue }
                let existing = results[index]
                if let record = outcome.record, let original = PhotoConverter.fetch(record.originalID) {
                    results[index] = ConversionOutcome(id: record.originalID, title: existing.title,
                                                       state: record.usedCurrentRendition == true ? .previewOnly : .verified, detail: "已恢复并通过副本核验。",
                                                       photoName: record.replacementFilename ?? "未命名照片",
                                                       capturedAt: original.creationDate, replacementID: record.replacementID, operationID: record.operationID)
                } else if let failure = outcome.failure {
                    logger.error("Recovery: \(failure.diagnostic, privacy: .public)")
                    results[index] = ConversionOutcome(id: existing.id, title: existing.title,
                                                       state: .failed, detail: failure.localizedDescription,
                                                       photoName: existing.photoName, capturedAt: existing.capturedAt,
                                                       diagnostic: failure.diagnostic,
                                                       replacementID: existing.replacementID, operationID: existing.operationID)
                }
            }
        }
    }

    func startClassification() {
        guard classificationTask == nil, !isRefreshing else { return }
        classificationTask = Task { [weak self] in
            guard let self else { return }
            await classifyAssets()
            classificationTask = nil
        }
    }

    func cancelClassification() {
        guard classificationTask != nil else { return }
        classificationProgress = "正在停止识别…"
        classificationTask?.cancel()
    }

    private func classifyAssets() async {
        let pending = assets.filter { asset in
            guard let result = sceneResults[asset.localIdentifier] else { return true }
            return result.modifiedAt != asset.modificationDate
        }
        guard !pending.isEmpty else {
            classificationProgress = nil
            return
        }

        var completed: [String: SceneResult] = [:]
        var publishedCount = 0
        var lastPublishedAt = ProcessInfo.processInfo.systemUptime
        classificationProgress = "正在分析 0 / \(pending.count) 张（仅读取本机照片）"

        for (index, asset) in pending.enumerated() {
            if Task.isCancelled { break }
            if let result = await LocalPhotoClassifier.classify(asset) {
                completed[asset.localIdentifier] = SceneResult(modifiedAt: asset.modificationDate, scene: result)
            } else {
                completed[asset.localIdentifier] = SceneResult(modifiedAt: asset.modificationDate, scene: .unknown)
            }
            if Task.isCancelled { break }

            let now = ProcessInfo.processInfo.systemUptime
            let batchReady = completed.count >= 16 || now - lastPublishedAt >= 0.4
            let isLastAsset = index == pending.count - 1
            guard batchReady || isLastAsset else { continue }

            sceneResults.merge(completed) { _, latest in latest }
            completed.removeAll(keepingCapacity: true)
            publishedCount = index + 1
            classificationProgress = "正在分析 \(publishedCount) / \(pending.count) 张（仅读取本机照片）"
            lastPublishedAt = now
            await Task.yield()
        }

        if !completed.isEmpty {
            sceneResults.merge(completed) { _, latest in latest }
        }
        classificationProgress = nil
    }

    private func refreshActionEstimates() {
        estimateGeneration += 1
        let generation = estimateGeneration
        estimateTask?.cancel()
        let ids = selection.union(readyToRemove).filter { !resolvedEstimateIDs.contains($0) }
        guard !ids.isEmpty else {
            actionEstimateLoading = false
            return
        }
        let candidates = ids.compactMap { library.assetsByID[$0] }
        actionEstimateLoading = true
        estimateTask = Task { [weak self] in
            do { try await Task.sleep(for: .milliseconds(120)) } catch { return }
            let worker = Task.detached(priority: .utility) {
                var measured: [String: Int] = [:]
                if #available(iOS 27.0, *) {
                    for asset in candidates {
                        if Task.isCancelled { return measured }
                        let videos = PHAssetResource.assetResources(for: asset).filter { $0.type == .pairedVideo }
                        guard !videos.isEmpty, videos.allSatisfy({ $0.dataSize != nil }) else { continue }
                        measured[asset.localIdentifier] = videos.reduce(0) { $0 + ($1.dataSize ?? 0) }
                    }
                }
                return measured
            }
            let sizes = await withTaskCancellationHandler {
                await worker.value
            } onCancel: {
                worker.cancel()
            }
            guard let self, self.estimateGeneration == generation else { return }
            self.reclaimableSizes.merge(sizes) { _, new in new }
            self.resolvedEstimateIDs.formUnion(ids)
            self.actionEstimateLoading = false
        }
    }

    nonisolated private static func loadLivePhotos() -> PhotoAssetBatch {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "(mediaSubtype & %d) != 0", Int(PHAssetMediaSubtype.photoLive.rawValue))
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.includeAssetSourceTypes = .typeUserLibrary
        let matches = PHAsset.fetchAssets(with: .image, options: options)
        return PhotoAssetBatch(assets: (0..<matches.count).map { matches.object(at: $0) })
    }

    func toggle(_ identifier: String) {
        guard progress == nil else { return }
        var updated = selection
        if updated.remove(identifier) != nil {
            sequence.removeAll { $0 == identifier }
        } else {
            updated.insert(identifier)
            sequence.append(identifier)
        }
        selection = updated
        refreshActionEstimates()
    }

    func toggle(_ identifiers: [String], selecting: Bool) {
        guard progress == nil else { return }
        var updated = selection
        if selecting {
            for identifier in identifiers where updated.insert(identifier).inserted {
                sequence.append(identifier)
            }
        } else {
            let removed = Set(identifiers)
            updated.subtract(removed)
            sequence.removeAll { removed.contains($0) }
        }
        guard updated != selection else { return }
        selection = updated
        refreshActionEstimates()
    }

    func selectRange(_ base: [String], _ identifiers: [String], selecting: Bool) {
        guard progress == nil else { return }
        let start = Set(base)
        let range = Set(identifiers)
        let updated = selecting ? start.union(range) : start.subtracting(range)
        guard updated != selection else { return }
        sequence = selecting ? base + identifiers.filter { !start.contains($0) }
            : base.filter { !range.contains($0) }
        selection = updated
        refreshActionEstimates()
    }

    func clearSelection() {
        selection = []
        sequence.removeAll()
        refreshActionEstimates()
    }

    func exportUnverifiedCovers() async {
        let failed = results.filter { $0.state == .failed || $0.state == .pending }
        guard progress == nil, !failed.isEmpty, let converter = makeConverter() else { return }
        progress = "正在准备原始封面文件"
        defer {
            progress = nil
            refresh()
            refreshActionEstimates()
        }
        var documents: [CoverDocument] = []
        for (index, item) in failed.enumerated() {
            do {
                guard let asset = PhotoConverter.fetch(item.id) else { throw ConversionProblem.unavailable }
                documents.append(try await converter.exportCover(asset, index: index + 1))
            } catch {
                let failure = (error as? PhotoFailure) ?? PhotoFailure("导出静态封面", error)
                logger.error("Export: \(failure.diagnostic, privacy: .public)")
            }
        }
        guard !documents.isEmpty else {
            notice = "未能读取这些照片的原尺寸静态封面。"
            return
        }
        exportDocuments = documents
        showExporter = true
    }

    func finishExport(_ result: Result<[URL], Error>) {
        exportDocuments.removeAll()
        switch result {
        case .success(let files):
            notice = "已导出 \(files.count) 个静态封面文件。实况原件仍在系统相册。"
        case .failure(let error):
            notice = "导出失败。\(error.localizedDescription)"
        }
    }

    func convertSelection(excludingFavorites: Bool) async -> Bool {
        let ids = selectedIDs(excludingFavorites: excludingFavorites)
        guard progress == nil, !ids.isEmpty, let converter = makeConverter() else { return false }
        results.removeAll()
        removalDiagnostic = nil
        conversionDuration = nil
        progress = "正在保存副本"
        saveProgress = SaveProgress(current: 1, total: ids.count)
        clearSelection()
        var timing = ConversionTiming()
        defer {
            saveProgress = nil
            progress = nil
            refresh()
            refreshActionEstimates()
        }

        let fetched = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
        let selectedAssets = Dictionary(uniqueKeysWithValues: (0..<fetched.count).map { index in
            let asset = fetched.object(at: index)
            return (asset.localIdentifier, asset)
        })
        for (offset, id) in ids.enumerated() {
            if offset > 0 { saveProgress = SaveProgress(current: offset + 1, total: ids.count) }
            let title = library.assetsByID[id]?.creationDate?.formatted(date: .abbreviated, time: .shortened)
                ?? "第 \(offset + 1) 张"
            let sourceAsset = selectedAssets[id]
            let captureDate = sourceAsset?.creationDate
            let photoName = sourceAsset.flatMap { asset in
                PHAssetResource.assetResources(for: asset).first(where: { $0.type == .photo })?.originalFilename
            } ?? "未命名照片"
            saveProgress?.filename = photoName
            do {
                guard let asset = sourceAsset else { throw ConversionProblem.unavailable }
                let record = try await converter.convert(asset, timing: &timing, onStage: { stage in
                    saveProgress?.stage = stage
                })
                if record.state == .written {
                    let hint = if authorization == .limited {
                        "可在设置调整照片访问范围后复核，或导出封面文件。"
                    } else if record.albumIdentifier != nil {
                        "可到系统相册的「实况转静态」核对。"
                    } else {
                        "可到系统相册的「最近存储」核对，或导出封面文件。"
                    }
                    let diagnostic = "保存后回读待确认 · \(authorization == .limited ? "有限照片权限" : "相册暂未返回副本")"
                    logger.notice("Conversion item \(offset + 1): \(diagnostic, privacy: .public)")
                    results.append(.init(id: id, title: title, state: .pending,
                                         detail: "系统已接受保存请求，暂未读到可验证的副本。\(hint)",
                                         photoName: photoName, capturedAt: captureDate,
                                         diagnostic: diagnostic, replacementID: record.replacementID, operationID: record.operationID))
                } else if record.usedCurrentRendition == true {
                    logger.notice("Conversion item \(offset + 1): static copy confirmed from current rendition")
                    results.append(.init(id: id, title: title, state: .previewOnly,
                                         detail: "", photoName: photoName, capturedAt: captureDate, replacementID: record.replacementID, operationID: record.operationID))
                } else {
                    logger.notice("Conversion item \(offset + 1): static copy verified")
                    results.append(.init(id: id, title: title, state: .verified, detail: "",
                                         photoName: photoName, capturedAt: captureDate, replacementID: record.replacementID, operationID: record.operationID))
                }
            } catch {
                let failure = (error as? PhotoFailure) ?? PhotoFailure("转换照片", error)
                let state: ConversionOutcome.State = failure.isSkipped ? .skipped : .failed
                logger.error("Conversion: \(failure.diagnostic, privacy: .public)")
                results.append(.init(id: id, title: title, state: state,
                                     detail: failure.localizedDescription, photoName: photoName, capturedAt: captureDate,
                                     diagnostic: failure.diagnostic))
            }
        }
        conversionDuration = timing.summary
        logger.notice("Batch \(ids.count) photos · \(timing.summary, privacy: .public)")
        return true
    }

    func removeOriginals(ids selected: Set<String>) async {
        let ids = selected.intersection(readyToRemove)
        let candidates = results.filter { ids.contains($0.id) }
        guard progress == nil, !candidates.isEmpty, let converter = makeConverter() else { return }
        progress = "正在复核实况原件与静态副本"
        removalDiagnostic = nil
        defer {
            progress = nil
            refresh()
        }
        let preflightStarted = ProcessInfo.processInfo.systemUptime
        let preparation = await converter.prepareRemoval(candidates)
        logger.notice("Removal prompt ready after \(ProcessInfo.processInfo.systemUptime - preflightStarted, format: .fixed(precision: 2))s · selected \(candidates.count) · eligible \(preparation.items.count) · blocked \(preparation.blocked.count)")
        results = results.map { item in
            guard let failure = preparation.blocked[item.id] else { return item }
            logger.error("Removal eligibility: \(failure.diagnostic, privacy: .public)")
            return .init(id: item.id, title: item.title, state: .retained,
                         detail: failure.localizedDescription, photoName: item.photoName, capturedAt: item.capturedAt,
                         diagnostic: failure.diagnostic, replacementID: item.replacementID, operationID: item.operationID)
        }
        guard !preparation.items.isEmpty else {
            notice = "这批照片没有通过删除前复核，原件仍在。原因已标在对应照片下方。"
            return
        }

        progress = "请在系统弹窗中确认移除"
        let submitted = Set(preparation.items.map { $0.record.originalID })
        do {
            logger.notice("Submitting native removal confirmation for \(submitted.count) photos")
            let removed = try await converter.removeVerifiedOriginals(preparation.items)
            logger.notice("Photos confirmed \(removed.count) of \(submitted.count) originals no longer visible")
            results = results.map { item in
                if removed.contains(item.id) {
                    return .init(id: item.id, title: item.title, state: .removed, detail: "",
                                 photoName: item.photoName, capturedAt: item.capturedAt,
                                 replacementID: item.replacementID, operationID: item.operationID)
                }
                if submitted.contains(item.id) {
                    return .init(id: item.id, title: item.title, state: .removalPending,
                                 detail: "请在系统相册核对原件与静态副本。",
                                 photoName: item.photoName, capturedAt: item.capturedAt,
                                 diagnostic: "系统删除后复查 · 无法确认移除结果", replacementID: item.replacementID, operationID: item.operationID)
                }
                return item
            }
        } catch {
            let failure = (error as? PhotoFailure) ?? PhotoFailure("移除原件", error)
            removalDiagnostic = failure.diagnostic
            logger.error("Removal: \(failure.diagnostic, privacy: .public)")
            notice = failure.wasCancelled
                ? "已取消系统删除，静态副本仍在，可以重试。"
                : "\(failure.localizedDescription)\n\(failure.diagnostic)"
        }
    }

    private func makeConverter() -> PhotoConverter? {
        do {
            let journal = try journal ?? ConversionJournal()
            self.journal = journal
            return PhotoConverter(journal: journal)
        } catch {
            notice = "无法读取本机的转换记录。\n" + error.localizedDescription
            return nil
        }
    }
}
