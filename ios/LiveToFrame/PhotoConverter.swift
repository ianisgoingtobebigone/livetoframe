import CryptoKit
import Foundation
import ImageIO
import os
import Photos
import UniformTypeIdentifiers

enum ConversionProblem: LocalizedError {
    case unavailable, edited, unknownFormat, ambiguousWrite, changed, unverifiable, notDeletable, fallbackCover, replacementUnavailable, replacementIsLive, albumMembership

    var errorDescription: String? {
        switch self {
        case .unavailable: "读不到这张照片的原始资源。请检查相册权限或 iCloud 连接。"
        case .edited: "封面或照片经过编辑，无法确认能原样保留。原件已保留。"
        case .unknownFormat: "这张照片的资源结构暂不支持。原件已保留。"
        case .ambiguousWrite: "本次写入没有返回新的副本标识，请到系统相册核对。实况原件仍在。"
        case .changed: "照片在处理期间有变更。请在系统相册核对。"
        case .unverifiable: "静态副本未能通过校验，请在系统相册核对。原件已保留。"
        case .notDeletable: "系统相册不允许移除这张原件。静态副本仍在。"
        case .fallbackCover: "静态副本使用系统提供的原尺寸封面。请先对照系统相册，实况原件已保留。"
        case .replacementUnavailable: "系统相册还未返回可复核的静态副本。实况原件仍在。"
        case .albumMembership: "静态副本未能加入原件的全部相册，请重新核对。原件已保留。"
        case .replacementIsLive: "系统读回的副本仍含实况视频，不能算转换成功。实况原件仍在。"
        }
    }

    var isSkipped: Bool {
        switch self {
        case .edited, .unknownFormat: true
        default: false
        }
    }
}

struct PhotoFailure: LocalizedError {
    let stage: String
    let underlying: Error
    let context: String

    init(_ stage: String, _ underlying: Error, context: String = "") {
        self.stage = stage
        self.underlying = underlying
        self.context = context
    }

    var errorDescription: String? { underlying.localizedDescription }

    var isSkipped: Bool { (underlying as? ConversionProblem)?.isSkipped == true }
    var isMissingReplacement: Bool { (underlying as? ConversionProblem) == .replacementUnavailable }

    var wasCancelled: Bool {
        let error = underlying as NSError
        return (error.domain == PHPhotosError.errorDomain && error.code == PHPhotosError.Code.userCancelled.rawValue)
            || (error.domain == NSCocoaErrorDomain && error.code == NSUserCancelledError)
    }

    var diagnostic: String {
        var codes: [String] = []
        var current: NSError? = underlying as NSError
        while let error = current, codes.count < 3 {
            codes.append("\(error.domain) \(error.code)")
            current = error.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return ([stage, codes.joined(separator: " → ")] + (context.isEmpty ? [] : [context]))
            .joined(separator: " · ")
    }
}

struct RemovalPreparation {
    struct Item {
        let record: ConversionRecord
        let source: PHAsset
        let replacement: PHAsset
    }
    var items: [Item] = []
    var blocked: [String: PhotoFailure] = [:]
}

struct ConversionRecoveryOutcome {
    let operationID: String
    let record: ConversionRecord?
    let failure: PhotoFailure?
}

enum ConversionStage: Int, CaseIterable {
    case preparing, writing, verifying
}

struct ConversionTiming {
    var preparation: TimeInterval = 0
    var saving: TimeInterval = 0
    var verification: TimeInterval = 0

    var summary: String {
        let total = preparation + saving + verification
        return String(format: "共 %.1f 秒 · 准备 %.1f · 保存 %.1f · 核验 %.1f", total, preparation, saving, verification)
    }
}

@MainActor
struct PhotoConverter {
    let journal: ConversionJournal
    static let outputAlbumTitle = "实况转静态"
    private static let outputAlbumKey = "LiveToFrame.outputAlbum"

    static func fetch(_ identifier: String) -> PHAsset? {
        PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil).firstObject
    }

    func exportCover(_ asset: PHAsset, index: Int) async throws -> CoverDocument {
        let source = try originalStill(asset)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        let cover = try await selectCover(asset, resource: source, directory: directory)
        let originalName = Self.cameraFilename(for: asset, cover: source)
        let cleanName = Self.staticFilename(originalName, type: cover.type, fallbackExtension: cover.fileExtension)
        let stem = (cleanName as NSString).deletingPathExtension
        let name = "\(stem)-静态-\(index).\(cover.fileExtension)"
        let stillData = try await Task.detached(priority: .utility) {
            try Self.removeApplePairing(from: cover.data)
        }.value
        return CoverDocument(data: stillData, name: name)
    }

    func convert(_ asset: PHAsset, timing: inout ConversionTiming,
                 onStage: (ConversionStage) -> Void) async throws -> ConversionRecord {
        let started = ProcessInfo.processInfo.systemUptime
        var saveStarted: TimeInterval?
        var verificationStarted: TimeInterval?
        defer {
            let finished = ProcessInfo.processInfo.systemUptime
            timing.preparation += (saveStarted ?? finished) - started
            if let saveStarted { timing.saving += (verificationStarted ?? finished) - saveStarted }
            if let verificationStarted { timing.verification += finished - verificationStarted }
        }
        let originalID = asset.localIdentifier
        let previousReplacement = journal.latestRecord(for: originalID)?.replacementID
        let source = try originalStill(asset)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }

        let cover = try await selectCover(asset, resource: source, directory: directory)
        let digest = Self.digest(cover.data)
        let stillData = try await Task.detached(priority: .utility) {
            try Self.removeApplePairing(from: cover.data)
        }.value

        var record = ConversionRecord(
            originalID: originalID, originalModifiedAt: asset.modificationDate,
            digest: digest, state: .writing, usedCurrentRendition: cover.usedCurrentRendition,
            savedStillDigest: Self.digest(stillData)
        )
        let sourceAlbums = Self.userAlbums(containing: asset)
        record.requiredAlbumIDs = sourceAlbums.map(\.localIdentifier)
        _ = try await verifiedOriginal(record)
        try journal.upsert(record)

        let filename = Self.staticFilename(
            Self.cameraFilename(for: asset, cover: source), type: cover.type, fallbackExtension: cover.fileExtension
        )
        let staged = directory.appendingPathComponent(filename)
        do {
            try stillData.write(to: staged)
        } catch {
            throw PhotoFailure("准备写入静态副本", error)
        }
        let placeInAlbum = PHPhotoLibrary.authorizationStatus(for: .readWrite) == .authorized
        let outputAlbum = placeInAlbum ? Self.outputAlbum() : nil
        let createAlbum = placeInAlbum && UserDefaults.standard.string(forKey: Self.outputAlbumKey) == nil
        let created = OSAllocatedUnfairLock(initialState: (asset: String?.none, album: String?.none, migrated: [String]()))
        onStage(.writing)
        saveStarted = ProcessInfo.processInfo.systemUptime
        do {
            try await PHPhotoLibrary.shared().performChanges {
                let creation = PHAssetCreationRequest.forAsset()
                let options = PHAssetResourceCreationOptions()
                options.originalFilename = filename
                options.uniformTypeIdentifier = cover.type.identifier
                creation.addResource(with: .photo, fileURL: staged, options: options)
                creation.creationDate = asset.creationDate
                creation.location = asset.location
                creation.isFavorite = asset.isFavorite
                guard let placeholder = creation.placeholderForCreatedAsset else { return }
                let albumIdentifier: String?
                if let outputAlbum, let change = PHAssetCollectionChangeRequest(for: outputAlbum) {
                    change.addAssets([placeholder] as NSArray)
                    albumIdentifier = outputAlbum.localIdentifier
                } else if createAlbum {
                    let change = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(
                        withTitle: Self.outputAlbumTitle
                    )
                    change.addAssets([placeholder] as NSArray)
                    albumIdentifier = change.placeholderForCreatedAssetCollection.localIdentifier
                } else {
                    albumIdentifier = nil
                }
                var migratedAlbumIDs: [String] = []
                for collection in sourceAlbums {
                    guard let change = PHAssetCollectionChangeRequest(for: collection) else { continue }
                    change.addAssets([placeholder] as NSArray)
                    migratedAlbumIDs.append(collection.localIdentifier)
                }
                let completedMigratedAlbumIDs = migratedAlbumIDs
                created.withLock { $0 = (placeholder.localIdentifier, albumIdentifier, completedMigratedAlbumIDs) }
            }
        } catch {
            throw PhotoFailure("写入静态副本", error)
        }

        let identifiers = created.withLock { $0 }
        guard let replacementID = identifiers.asset,
              Self.assetKey(replacementID) != Self.assetKey(originalID),
              previousReplacement.map(Self.assetKey) != Self.assetKey(replacementID) else {
            throw ConversionProblem.ambiguousWrite
        }
        if let albumIdentifier = identifiers.album {
            UserDefaults.standard.set(albumIdentifier, forKey: Self.outputAlbumKey)
        }
        record.replacementID = replacementID
        record.albumIdentifier = identifiers.album
        record.migratedAlbumIDs = identifiers.migrated
        record.state = .written
        try journal.upsert(record)
        onStage(.verifying)
        verificationStarted = ProcessInfo.processInfo.systemUptime
        do {
            let verified = try await verifyReplacement(
                record, waitingForVisibility: true, expectedCover: stillData
            )
            record.replacementModifiedAt = verified.asset.modificationDate
            record.matchedImageContent = verified.imageContentOnly
            record.replacementFilename = verified.filename
        } catch let failure as PhotoFailure where failure.isMissingReplacement {
            if let recovered = try await findNewCopyInAlbum(for: asset, record: record, expectedCover: stillData) {
                return recovered
            }
            return record
        }
        _ = try await verifiedOriginal(record)

        record.state = .verified
        record.verified = true
        try journal.upsert(record)
        return record
    }

    private static func outputAlbum() -> PHAssetCollection? {
        guard let identifier = UserDefaults.standard.string(forKey: outputAlbumKey) else { return nil }
        return PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [identifier], options: nil).firstObject
    }

    private static func userAlbums(containing asset: PHAsset) -> [PHAssetCollection] {
        let collections = PHAssetCollection.fetchAssetCollectionsContaining(asset, with: .album, options: nil)
        return (0..<collections.count).map { collections.object(at: $0) }
    }

    private func findNewCopyInAlbum(
        for original: PHAsset, record: ConversionRecord, expectedCover: Data
    ) async throws -> ConversionRecord? {
        guard let identifier = record.albumIdentifier, let replacementID = record.replacementID,
              let album = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [identifier], options: nil).firstObject
        else { return nil }
        let replacementKey = Self.assetKey(replacementID)
        let options = PHFetchOptions()
        if let date = original.creationDate {
            options.predicate = NSPredicate(
                format: "creationDate >= %@ AND creationDate <= %@",
                date.addingTimeInterval(-1) as NSDate, date.addingTimeInterval(1) as NSDate
            )
        }
        let candidates = PHAsset.fetchAssets(in: album, options: options)
        for index in 0..<candidates.count {
            let candidate = candidates.object(at: index)
            guard Self.assetKey(candidate.localIdentifier) == replacementKey else { continue }
            var found = record
            found.replacementID = candidate.localIdentifier
            let result = try await verifyReplacement(found, expectedCover: expectedCover, visibleAsset: candidate)
            _ = try await verifiedOriginal(found)
            guard await Self.sameMetadataOffMain(original, result.asset) else {
                throw PhotoFailure("检查本次副本的拍摄信息", ConversionProblem.changed)
            }
            found.state = .verified
            found.verified = true
            found.matchedImageContent = result.imageContentOnly
            found.replacementFilename = result.filename
            try journal.upsert(found)
            return found
        }
        return nil
    }

    func prepareRemoval(_ candidates: [ConversionOutcome]) async -> RemovalPreparation {
        let started = ProcessInfo.processInfo.systemUptime
        var preparation = RemovalPreparation()
        var firstPassDuration: TimeInterval = 0
        for candidate in candidates {
            let itemStarted = ProcessInfo.processInfo.systemUptime
            do {
                guard PHPhotoLibrary.authorizationStatus(for: .readWrite) == .authorized else {
                    throw PhotoFailure("复核相册权限", ConversionProblem.notDeletable,
                                       context: "需要完整照片访问来确认原相册归属")
                }
                guard let operationID = candidate.operationID, let replacementID = candidate.replacementID,
                      var record = journal.recordForRemoval(operationID: operationID, originalID: candidate.id,
                                                          replacementID: replacementID) else {
                    throw PhotoFailure("复核本次转换记录", ConversionProblem.unverifiable)
                }
                guard record.usedCurrentRendition != true else {
                    throw PhotoFailure("复核备用封面来源", ConversionProblem.fallbackCover)
                }
                let original = try await verifiedOriginal(record)
                var replacement = try await verifyRemovalReplacement(record, original: original)
                guard original.canPerform(.delete) else {
                    throw PhotoFailure("检查删除权限", ConversionProblem.notDeletable)
                }
                guard await Self.sameMetadataOffMain(original, replacement) else {
                    throw PhotoFailure("复核日期、位置和收藏", ConversionProblem.changed)
                }
                let required = Self.userAlbums(containing: original)
                let existingAlbumIDs = Self.albumIDs(containing: replacement)
                let missing = required.filter { !existingAlbumIDs.contains($0.localIdentifier) }
                if !missing.isEmpty {
                    try await addVerifiedCopy(replacement, to: missing)
                    guard let updated = Self.fetch(replacementID) else {
                        throw PhotoFailure("读回静态副本", ConversionProblem.replacementUnavailable)
                    }
                    replacement = updated
                }
                let requiredIDs = Set(required.map(\.localIdentifier))
                guard requiredIDs.isSubset(of: Self.albumIDs(containing: replacement)) else {
                    throw PhotoFailure("复核原相册归属", ConversionProblem.albumMembership)
                }
                if Set(record.requiredAlbumIDs) != requiredIDs || Set(record.migratedAlbumIDs) != requiredIDs {
                    record.requiredAlbumIDs = requiredIDs.sorted()
                    record.migratedAlbumIDs = record.requiredAlbumIDs
                    try journal.upsert(record)
                }
                preparation.items.append(.init(record: record, source: original, replacement: replacement))
            } catch {
                preparation.blocked[candidate.id] = (error as? PhotoFailure) ?? PhotoFailure("删除前检查", error)
            }
            firstPassDuration += ProcessInfo.processInfo.systemUptime - itemStarted
        }

        // Earlier members of a batch may have changed while later members downloaded.
        var revalidatedItems: [RemovalPreparation.Item] = []
        let secondPassStarted = ProcessInfo.processInfo.systemUptime
        for item in preparation.items {
            guard PHPhotoLibrary.authorizationStatus(for: .readWrite) == .authorized,
                  let source = Self.fetch(item.source.localIdentifier),
                  source.modificationDate == item.source.modificationDate,
                  source.canPerform(.delete),
                  let replacement = Self.fetch(item.replacement.localIdentifier),
                  replacement.modificationDate == item.replacement.modificationDate,
                  await Self.sameMetadataOffMain(source, replacement),
                  (try? originalStill(source)) != nil,
                  removalCopyIsUnchanged(item.record, replacement: replacement) else {
                preparation.blocked[item.record.originalID] = PhotoFailure("提交前复核", ConversionProblem.changed)
                continue
            }
            guard Self.albumIDs(containing: source).isSubset(of: Self.albumIDs(containing: replacement)) else {
                preparation.blocked[item.record.originalID] = PhotoFailure("提交前复核原相册归属", ConversionProblem.albumMembership)
                continue
            }
            revalidatedItems.append(.init(record: item.record, source: source, replacement: replacement))
        }
        preparation.items = revalidatedItems
        let finished = ProcessInfo.processInfo.systemUptime
        Logger(subsystem: "app.livetoframe.personal", category: "PhotoOperations").notice(
            "Removal preflight \(candidates.count) photos · identity and copy check \(firstPassDuration, format: .fixed(precision: 2))s · final recheck \(finished - secondPassStarted, format: .fixed(precision: 2))s · total \(finished - started, format: .fixed(precision: 2))s · eligible \(revalidatedItems.count) · blocked \(preparation.blocked.count)"
        )
        return preparation
    }

    func removeVerifiedOriginals(_ items: [RemovalPreparation.Item]) async throws -> Set<String> {
        guard !items.isEmpty else { return [] }
        let assetsToDelete = NSArray(array: items.map(\.source))
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.deleteAssets(assetsToDelete)
            }
        } catch {
            throw PhotoFailure("系统删除请求", error)
        }

        guard PHPhotoLibrary.authorizationStatus(for: .readWrite) == .authorized else { return [] }
        var removed: [ConversionRecord] = []
        for item in items where Self.fetch(item.record.originalID) == nil
            && Self.fetch(item.replacement.localIdentifier) != nil {
            var record = item.record
            record.state = .removed
            removed.append(record)
        }
        if !removed.isEmpty {
            do {
                try journal.save(removed)
            } catch {
                throw PhotoFailure("记录已移除的原件", error)
            }
        }
        return Set(removed.map(\.originalID))
    }

    private func verifiedOriginal(_ record: ConversionRecord) async throws -> PHAsset {
        guard let original = Self.fetch(record.originalID) else {
            throw PhotoFailure("重新查找实况原件", ConversionProblem.unavailable)
        }
        let source = try originalStill(original)
        // Photos can change the modification date while updating its library.
        // If that happened, verify the actual current cover before proceeding.
        if original.modificationDate != record.originalModifiedAt {
            do {
                let current = try await currentStill(original)
                guard Self.digest(current) == record.digest,
                      let fresh = Self.fetch(record.originalID),
                      fresh.modificationDate == original.modificationDate else {
                    throw ConversionProblem.changed
                }
            } catch {
                throw PhotoFailure("复核实况原件封面", error)
            }
        }
        if source?.type == .fullSizePhoto && record.usedCurrentRendition != true {
            throw PhotoFailure("复核实况原件封面", ConversionProblem.changed)
        }
        return original
    }

    private static func albumIDs(containing asset: PHAsset) -> Set<String> {
        Set(Self.userAlbums(containing: asset).map(\.localIdentifier))
    }

    private func addVerifiedCopy(_ replacement: PHAsset, to albums: [PHAssetCollection]) async throws {
        guard albums.allSatisfy({ $0.canPerform(.addContent) }) else {
            throw PhotoFailure("复核原相册写入权限", ConversionProblem.albumMembership)
        }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                for album in albums {
                    PHAssetCollectionChangeRequest(for: album)?.addAssets([replacement] as NSArray)
                }
            }
        } catch {
            throw PhotoFailure("补齐静态副本的原相册归属", error)
        }
    }

    private func originalStill(_ asset: PHAsset) throws -> PHAssetResource? {
        guard asset.mediaType == .image, asset.mediaSubtypes.contains(.photoLive),
              asset.sourceType == .typeUserLibrary, !asset.isHidden else {
            throw PhotoFailure("检查实况原件类型", ConversionProblem.unknownFormat)
        }
        let resources = PHAssetResource.assetResources(for: asset)
        guard !resources.contains(where: { resource in
            switch resource.type {
            case .adjustmentData,
                 .adjustmentBasePhoto, .adjustmentBasePairedVideo, .adjustmentBaseVideo: true
            default: false
            }
        }) else {
            throw PhotoFailure("检查相册编辑记录", ConversionProblem.edited)
        }
        let currentPhotos = resources.filter { $0.type == .fullSizePhoto }
        if !currentPhotos.isEmpty { return currentPhotos.count == 1 ? currentPhotos.first : nil }
        let photos = resources.filter { $0.type == .photo }
        return photos.count == 1 ? photos.first : nil
    }

    private func verifyReplacement(
        _ record: ConversionRecord,
        waitingForVisibility: Bool = false,
        expectedCover: Data? = nil,
        matchingCoverOf original: PHAsset? = nil,
        visibleAsset: PHAsset? = nil
    ) async throws -> (asset: PHAsset, imageContentOnly: Bool, filename: String) {
        guard let identifier = record.replacementID,
              identifier != record.originalID else {
            throw PhotoFailure("查找写入的静态副本", ConversionProblem.unverifiable)
        }
        var visible = visibleAsset ?? Self.fetch(identifier)
        if waitingForVisibility {
            for delay in [150, 400, 850] where visible == nil {
                try await Task.sleep(for: .milliseconds(delay))
                visible = Self.fetch(identifier)
            }
        }
        guard let replacement = visible else {
            let access = PHPhotoLibrary.authorizationStatus(for: .readWrite) == .limited ? "有限照片权限" : "相册写入记录仍在"
            throw PhotoFailure("查找写入的静态副本", ConversionProblem.replacementUnavailable, context: access)
        }
        let resources = PHAssetResource.assetResources(for: replacement)
        let hasMotion = resources.contains {
            [.pairedVideo, .fullSizePairedVideo, .adjustmentBasePairedVideo, .video, .fullSizeVideo].contains($0.type)
        }
        if hasMotion || replacement.mediaSubtypes.contains(.photoLive)
            || Self.assetKey(replacement.localIdentifier) == Self.assetKey(record.originalID) {
            throw PhotoFailure("检查副本是否仍是实况", ConversionProblem.replacementIsLive,
                               context: "资源类型 " + resources.map { String($0.type.rawValue) }.joined(separator: ","))
        }
        let photos = resources.filter { $0.type == .photo }
        guard replacement.mediaType == .image, photos.count == 1, let photo = photos.first,
              resources.allSatisfy({ $0.type == .photo || $0.type == .photoProxy }) else {
            let types = resources.map { String($0.type.rawValue) }.joined(separator: ",")
            throw PhotoFailure("检查副本资源", ConversionProblem.unverifiable, context: "资源类型 \(types)")
        }

        let destination = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: destination) }
        let bytes: Data
        do {
            try await export(photo, to: destination)
            bytes = try Data(contentsOf: destination, options: .mappedIfSafe)
        } catch {
            throw PhotoFailure("读取静态副本原始资源", error)
        }
        if Self.digest(bytes) == (record.savedStillDigest ?? record.digest) {
            var reference = expectedCover
            if reference == nil, let original {
                let source = try originalStill(original)
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
                defer { try? FileManager.default.removeItem(at: directory) }
                reference = try await selectCover(original, resource: source, directory: directory).data
            }
            guard let reference else {
                throw PhotoFailure("复核静态副本元数据", ConversionProblem.unverifiable,
                                   context: "当前任务缺少封面核验数据，原件保留")
            }
            guard Self.requiredMetadataMatches(reference, bytes) else {
                throw PhotoFailure("复核静态副本元数据", ConversionProblem.unverifiable,
                                   context: "关键 EXIF/XMP 信息不同")
            }
            return (replacement, false, photo.originalFilename)
        }

        var reference = expectedCover
        if reference == nil, let original {
            let source = try originalStill(original)
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
            defer { try? FileManager.default.removeItem(at: directory) }
            let cover = try await selectCover(original, resource: source, directory: directory)
            if Self.digest(cover.data) == record.digest { reference = cover.data }
        }
        guard let reference else {
            throw PhotoFailure("比对原件与副本文件", ConversionProblem.unverifiable,
                               context: "字节不同，无法重新确认此前使用的封面")
        }
        let difference = await Task.detached(priority: .utility) {
            Self.imageContentDifference(reference, bytes)
        }.value
        if let difference {
            throw PhotoFailure("比对封面像素及 HDR", ConversionProblem.unverifiable,
                               context: "\(difference) · 源文件 \(reference.count) B，副本 \(bytes.count) B")
        }
        guard Self.requiredMetadataMatches(reference, bytes) else {
            throw PhotoFailure("复核静态副本元数据", ConversionProblem.unverifiable,
                               context: "关键 EXIF/XMP 信息不同")
        }
        return (replacement, true, photo.originalFilename)
    }

    func recoverUnfinishedRecords(operationIDs: Set<String>) async -> [ConversionRecoveryOutcome] {
        var outcomes: [ConversionRecoveryOutcome] = []
        for var record in journal.unfinishedRecords where operationIDs.contains(record.operationID) {
            guard record.state == .written else { continue }
            guard let original = Self.fetch(record.originalID) else {
                outcomes.append(.init(operationID: record.operationID, record: nil,
                                      failure: PhotoFailure("恢复中断记录", ConversionProblem.unavailable)))
                continue
            }
            do {
                _ = try await verifiedOriginal(record)
                var lastFailure: PhotoFailure?
                var recoveredResult: (asset: PHAsset, imageContentOnly: Bool, filename: String)?
                var recoveredReplacementID: String?

                for candidate in recoveryReplacementCandidates(for: record, original: original) {
                    var candidateRecord = record
                    candidateRecord.replacementID = candidate.localIdentifier
                    do {
                        let result = try await verifyReplacement(candidateRecord,
                                                                 matchingCoverOf: original,
                                                                 visibleAsset: candidate)
                        guard await Self.sameMetadataOffMain(original, result.asset) else {
                            throw PhotoFailure("恢复静态副本信息", ConversionProblem.changed)
                        }

                        // Older journal entries may contain a stale placeholder ID
                        // and no album data. Rebuild the source album requirement and
                        // prove the candidate is present in every required album.
                        let requiredAlbumIDs = Set(record.requiredAlbumIDs)
                            .union(Self.userAlbums(containing: original).map(\.localIdentifier))
                        let replacementAlbumIDs = Set(Self.userAlbums(containing: result.asset).map(\.localIdentifier))
                        guard ConversionSafetyPolicy.albumMembershipsSatisfied(
                            required: requiredAlbumIDs, replacement: replacementAlbumIDs
                        ) else {
                            throw PhotoFailure("复核原相册归属", ConversionProblem.unverifiable,
                                               context: "静态副本尚未加入原照片所在相册")
                        }
                        recoveredResult = result
                        recoveredReplacementID = candidate.localIdentifier
                        record.requiredAlbumIDs = requiredAlbumIDs.sorted()
                        record.migratedAlbumIDs = requiredAlbumIDs.sorted()
                        break
                    } catch {
                        lastFailure = (error as? PhotoFailure) ?? PhotoFailure("恢复静态副本", error)
                    }
                }

                guard let result = recoveredResult, let replacementID = recoveredReplacementID else {
                    throw lastFailure ?? PhotoFailure("恢复静态副本", ConversionProblem.unverifiable,
                                                      context: "没有找到可核验的静态副本")
                }
                record.replacementID = replacementID
                record.state = .verified
                record.verified = true
                record.replacementFilename = result.filename
                record.matchedImageContent = result.imageContentOnly
                try journal.upsert(record)
                outcomes.append(.init(operationID: record.operationID, record: record, failure: nil))
            } catch {
                let failure = (error as? PhotoFailure) ?? PhotoFailure("恢复转换记录", error)
                outcomes.append(.init(operationID: record.operationID, record: nil, failure: failure))
            }
        }
        return outcomes
    }

    private func recoveryReplacementCandidates(for record: ConversionRecord, original: PHAsset) -> [PHAsset] {
        var candidates: [PHAsset] = []
        var seen = Set<String>()
        func append(_ asset: PHAsset) {
            guard asset.localIdentifier != original.localIdentifier,
                  !asset.isHidden, !asset.mediaSubtypes.contains(.photoLive),
                  seen.insert(asset.localIdentifier).inserted else { return }
            candidates.append(asset)
        }

        if let replacementID = record.replacementID, let direct = Self.fetch(replacementID) {
            append(direct)
        }

        guard let date = original.creationDate else { return candidates }
        let sourceResource = Self.originalPhotoResource(original)
        guard let expectedFilename = record.replacementFilename ?? sourceResource?.originalFilename else {
            return candidates
        }
        guard let recordedID = record.replacementID else { return candidates }
        let options = PHFetchOptions()
        options.predicate = NSPredicate(
            format: "creationDate >= %@ AND creationDate <= %@",
            date.addingTimeInterval(-1) as NSDate, date.addingTimeInterval(1) as NSDate
        )
        options.includeAssetSourceTypes = .typeUserLibrary
        let nearbyAssets = PHAsset.fetchAssets(with: .image, options: options)
        for index in 0..<nearbyAssets.count {
            let asset = nearbyAssets.object(at: index)
            guard Self.assetKey(asset.localIdentifier) == Self.assetKey(recordedID),
                  asset.localIdentifier != original.localIdentifier,
                  !asset.isHidden, !asset.mediaSubtypes.contains(.photoLive) else { continue }
            let filename = Self.originalPhotoResource(asset)?.originalFilename
            guard filename?.caseInsensitiveCompare(expectedFilename) == .orderedSame else { continue }
            append(asset)
        }
        return candidates
    }

    private static func originalPhotoResource(_ asset: PHAsset) -> PHAssetResource? {
        PHAssetResource.assetResources(for: asset).first { $0.type == .photo }
    }

    private static func assetKey(_ identifier: String) -> String {
        identifier.split(separator: "/", maxSplits: 1).first.map { $0.lowercased() } ?? identifier.lowercased()
    }

    private static func cameraFilename(for asset: PHAsset, cover: PHAssetResource?) -> String? {
        if cover?.type == .photo { return cover?.originalFilename }
        return PHAssetResource.assetResources(for: asset).first { $0.type == .photo }?.originalFilename
            ?? cover?.originalFilename
    }

    nonisolated static func staticFilename(_ original: String?, type: UTType, fallbackExtension: String) -> String {
        guard let original, !original.isEmpty else { return "静态照片.\(fallbackExtension)" }
        let name = (original as NSString).lastPathComponent as NSString
        let ext = name.pathExtension.lowercased()
        let originalType: UTType? = switch ext {
        case "jpg", "jpeg", "jpe": .jpeg
        case "heic": .heic
        case "heif", "hif": .heif
        case "png": .png
        default: nil
        }
        let generatedSuffix = #"(?:-still-(?:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}|[a-f0-9]{8}))+$"#
        let stem = name.deletingPathExtension.replacingOccurrences(
            of: generatedSuffix, with: "", options: [.regularExpression, .caseInsensitive]
        )
        guard !stem.isEmpty, stem != ".", stem != ".." else { return "静态照片.\(fallbackExtension)" }
        let hasCompatibleExtension = ext == fallbackExtension.lowercased()
            || originalType.map({ type.conforms(to: $0) }) == true
        return "\(stem).\(hasCompatibleExtension ? name.pathExtension : fallbackExtension)"
    }

    nonisolated static func removeApplePairing(from data: Data) throws -> Data {
        guard let identifier = applePairingID(in: data), !identifier.isEmpty else { return data }
        let signature = Data("Apple iOS\u{0}".utf8)
        var cursor = data.startIndex
        while let header = data.range(of: signature, in: cursor..<data.endIndex) {
            cursor = header.upperBound
            if let result = removePairingEntry(from: data, header: header.lowerBound, identifier: identifier),
               applePairingID(in: result) == nil,
               imageContentDifference(data, result) == nil {
                return result
            }
        }
        throw PhotoFailure("移除实况配对信息", ConversionProblem.unverifiable,
                           context: "无法安全保留像素和 HDR")
    }

    nonisolated private static func applePairingID(in data: Data) -> String? {
        guard let image = CGImageSourceCreateWithData(data as CFData, nil),
              let metadata = CGImageSourceCopyPropertiesAtIndex(
                image, CGImageSourceGetPrimaryImageIndex(image), nil
              ) as? [CFString: Any],
              let makerNote = metadata[kCGImagePropertyMakerAppleDictionary] as? [String: Any] else { return nil }
        return makerNote["17"] as? String
    }

    // The Apple maker note has its own TIFF-style IFD. Shift only the directory
    // entries and next-IFD pointer; keep the block length and value offsets intact.
    nonisolated static func removePairingEntry(from data: Data, header: Int, identifier: String) -> Data? {
        let directory = header + 14
        guard header >= data.startIndex, directory + 2 <= data.endIndex else { return nil }
        let byteOrder = (data[header + 12], data[header + 13])
        guard byteOrder == (0x4d, 0x4d) || byteOrder == (0x49, 0x49) else { return nil }
        let bigEndian = byteOrder == (0x4d, 0x4d)
        func read(_ offset: Int, _ width: Int) -> Int {
            let bytes = data[offset..<(offset + width)]
            if bigEndian { return bytes.reduce(0) { ($0 << 8) | Int($1) } }
            return bytes.reversed().reduce(0) { ($0 << 8) | Int($1) }
        }
        let count = read(directory, 2)
        let endOfEntries = directory + 2 + count * 12
        guard count > 0, endOfEntries + 4 <= data.endIndex else { return nil }
        for index in 0..<count {
            let entry = directory + 2 + index * 12
            guard read(entry, 2) == 17, read(entry + 2, 2) == 2 else { continue }
            let size = read(entry + 4, 4)
            let offset = header + read(entry + 8, 4)
            let value = Data(identifier.utf8)
            guard size == value.count + 1 || size == value.count,
                  offset >= endOfEntries + 4, offset + size <= data.endIndex,
                  data[offset..<(offset + value.count)].elementsEqual(value) else { continue }

            var result = data
            let tail = Data(data[(entry + 12)..<(endOfEntries + 4)])
            result.replaceSubrange(entry..<(entry + tail.count), with: tail)
            result.replaceSubrange((endOfEntries - 8)..<(endOfEntries + 4), with: Data(repeating: 0, count: 12))
            let low = UInt8((count - 1) & 0xff)
            let high = UInt8((count - 1) >> 8)
            result[directory] = bigEndian ? high : low
            result[directory + 1] = bigEndian ? low : high
            return result
        }
        return nil
    }

    nonisolated static func imageContentDifference(_ original: Data, _ replacement: Data) -> String? {
        guard let source = CGImageSourceCreateWithData(original as CFData, nil),
              let copy = CGImageSourceCreateWithData(replacement as CFData, nil) else { return "ImageIO 解码失败" }
        let sourceIndex = CGImageSourceGetPrimaryImageIndex(source)
        let copyIndex = CGImageSourceGetPrimaryImageIndex(copy)
        let sourceProperties = CGImageSourceCopyPropertiesAtIndex(source, sourceIndex, nil) as? [CFString: Any]
        let copyProperties = CGImageSourceCopyPropertiesAtIndex(copy, copyIndex, nil) as? [CFString: Any]
        let originalOrientation = sourceProperties?[kCGImagePropertyOrientation] as? Int ?? 1
        let replacementOrientation = copyProperties?[kCGImagePropertyOrientation] as? Int ?? 1
        guard sourceProperties != nil, copyProperties != nil else { return "缺少图片属性" }
        guard originalOrientation == replacementOrientation else { return "方向不同" }

        var gainMaps = [kCGImageAuxiliaryDataTypeHDRGainMap]
        if #available(iOS 18, macOS 15, *) { gainMaps.append(kCGImageAuxiliaryDataTypeISOGainMap) }
        for kind in gainMaps {
            switch (CGImageSourceCopyAuxiliaryDataInfoAtIndex(source, sourceIndex, kind),
                    CGImageSourceCopyAuxiliaryDataInfoAtIndex(copy, copyIndex, kind)) {
            case (nil, nil): continue
            case (let left?, let right?):
                if let delta = gainMapDifference(left, right) { return delta }
            default: return "HDR 增益图缺失"
            }
        }

        let options = [kCGImageSourceShouldAllowFloat: true] as CFDictionary
        guard let primary = CGImageSourceCreateImageAtIndex(source, sourceIndex, options),
              let still = CGImageSourceCreateImageAtIndex(copy, copyIndex, options) else { return "主图解码失败" }
        guard primary.width == still.width, primary.height == still.height else { return "像素尺寸不同" }
        guard primary.colorSpace == still.colorSpace else { return "解码色彩空间不同" }
        guard
              primary.bitsPerComponent == still.bitsPerComponent,
              primary.bitsPerPixel == still.bitsPerPixel,
              primary.bitmapInfo == still.bitmapInfo,
              let first = primary.dataProvider?.data, let second = still.dataProvider?.data else {
            return "解码像素布局不同"
        }
        let rowPixels = (primary.width * primary.bitsPerPixel + 7) / 8
        guard rowPixels <= primary.bytesPerRow, rowPixels <= still.bytesPerRow,
              CFDataGetLength(first) >= (primary.height - 1) * primary.bytesPerRow + rowPixels,
              CFDataGetLength(second) >= (still.height - 1) * still.bytesPerRow + rowPixels,
              let firstBytes = CFDataGetBytePtr(first), let secondBytes = CFDataGetBytePtr(second) else {
            return "像素缓冲区不完整"
        }
        for row in 0..<primary.height {
            if memcmp(firstBytes + row * primary.bytesPerRow, secondBytes + row * still.bytesPerRow, rowPixels) != 0 {
                return "主图像素不同"
            }
        }
        return nil
    }

    nonisolated private static func gainMapDifference(_ original: CFDictionary, _ replacement: CFDictionary) -> String? {
        guard let left = original as? [CFString: Any], let right = replacement as? [CFString: Any],
              let leftData = left[kCGImageAuxiliaryDataInfoData] as? Data,
              let rightData = right[kCGImageAuxiliaryDataInfoData] as? Data,
              leftData == rightData else { return "HDR 增益图像素不同" }
        guard let leftLayout = left[kCGImageAuxiliaryDataInfoDataDescription] as? NSDictionary,
              let rightLayout = right[kCGImageAuxiliaryDataInfoDataDescription] as? NSDictionary,
              leftLayout.isEqual(rightLayout) else { return "HDR 增益图布局不同" }

        let leftMeta = left[kCGImageAuxiliaryDataInfoMetadata] as AnyObject?
        let rightMeta = right[kCGImageAuxiliaryDataInfoMetadata] as AnyObject?
        switch (leftMeta, rightMeta) {
        case (nil, nil): return nil
        case (let first?, let second?):
            guard CFGetTypeID(first) == CGImageMetadataGetTypeID(),
                  CFGetTypeID(second) == CGImageMetadataGetTypeID() else { return "HDR 元数据无法读取" }
            let firstMetadata = unsafeDowncast(first, to: CGImageMetadata.self)
            let secondMetadata = unsafeDowncast(second, to: CGImageMetadata.self)
            guard let firstXMP = CGImageMetadataCreateXMPData(firstMetadata, nil),
                  let secondXMP = CGImageMetadataCreateXMPData(secondMetadata, nil),
                  CFEqual(firstXMP, secondXMP) else { return "HDR 元数据不同" }
            return nil
        default: return "HDR 元数据缺失"
        }
    }

    private struct Cover {
        let data: Data
        let type: UTType
        let fileExtension: String
        let usedCurrentRendition: Bool
    }

    private func selectCover(_ asset: PHAsset, resource: PHAssetResource?, directory: URL) async throws -> Cover {
        var primary: Cover?
        var exportFailure: Error?
        if let resource {
            let file = directory.appendingPathComponent("Source")
            do {
                try await export(resource, to: file)
                let data = try Data(contentsOf: file, options: .mappedIfSafe)
                primary = cover(from: data, asset: asset, current: resource.type == .fullSizePhoto)
            } catch {
                exportFailure = error
            }
        }

        // An unedited asset with one primary image has an authoritative camera cover.
        // Prefer its encoded data over PHImageManager's delivery representation.
        if let primary { return primary }
        do {
            let data = try await currentStill(asset)
            if let fallback = cover(from: data, asset: asset, current: true) { return fallback }
        } catch {
            throw PhotoFailure("读取全尺寸静态封面", exportFailure ?? error)
        }
        let types = PHAssetResource.assetResources(for: asset)
            .map { "\($0.type.rawValue):\($0.uniformTypeIdentifier)" }.joined(separator: ",")
        throw PhotoFailure("检查全尺寸静态封面", ConversionProblem.unknownFormat, context: "资源 \(types)")
    }

    private func cover(from data: Data, asset: PHAsset, current: Bool) -> Cover? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetCount(source) > 0,
              let identifier = CGImageSourceGetType(source) as String?,
              let type = UTType(identifier),
              let fileExtension = type.preferredFilenameExtension,
              [.heic, .heif, .jpeg, .png].contains(where: { type.conforms(to: $0) }),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, CGImageSourceGetPrimaryImageIndex(source), nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              (width == asset.pixelWidth && height == asset.pixelHeight
               || width == asset.pixelHeight && height == asset.pixelWidth) else {
            return nil
        }
        return Cover(data: data, type: type, fileExtension: fileExtension, usedCurrentRendition: current)
    }

    private nonisolated static func sameMetadataOffMain(_ source: PHAsset, _ replacement: PHAsset) async -> Bool {
        await Task.detached(priority: .utility) {
            Self.sameMetadata(source, replacement)
        }.value
    }

    private nonisolated static func sameMetadata(_ source: PHAsset, _ replacement: PHAsset) -> Bool {
        guard source.isFavorite == replacement.isFavorite, !replacement.isHidden else { return false }
        switch (source.creationDate, replacement.creationDate) {
        case (nil, _): break
        case let (date?, other?) where abs(date.timeIntervalSince(other)) <= 1: break
        default: return false
        }
        switch (source.location, replacement.location) {
        case (nil, nil): return true
        case let (location?, other?): return location.distance(from: other) < 1
        default: return false
        }
    }

    nonisolated static func requiredMetadataMatches(_ original: Data, _ replacement: Data) -> Bool {
        guard let left = CGImageSourceCreateWithData(original as CFData, nil),
              let right = CGImageSourceCreateWithData(replacement as CFData, nil),
              let leftProperties = CGImageSourceCopyPropertiesAtIndex(left, CGImageSourceGetPrimaryImageIndex(left), nil) as? [CFString: Any],
              let rightProperties = CGImageSourceCopyPropertiesAtIndex(right, CGImageSourceGetPrimaryImageIndex(right), nil) as? [CFString: Any] else { return false }

        let requiredKeys: [CFString] = [kCGImagePropertyOrientation, kCGImagePropertyPixelWidth,
                                        kCGImagePropertyPixelHeight, kCGImagePropertyColorModel,
                                        kCGImagePropertyProfileName, kCGImagePropertyExifDictionary,
                                        kCGImagePropertyGPSDictionary]
        return requiredKeys.allSatisfy { key in
            let leftValue = leftProperties[key] as AnyObject?
            let rightValue = rightProperties[key] as AnyObject?
            switch (leftValue, rightValue) {
            case (nil, nil): return true
            case let (left?, right?): return (left as? NSObject)?.isEqual(right) == true
            default: return false
            }
        }
    }

    private func verifyRemovalReplacement(_ record: ConversionRecord, original: PHAsset) async throws -> PHAsset {
        if record.replacementModifiedAt == nil {
            return try await verifyReplacement(record, waitingForVisibility: true, matchingCoverOf: original).asset
        }
        guard let identifier = record.replacementID,
              let replacement = Self.fetch(identifier),
              replacement.mediaType == .image,
              !replacement.mediaSubtypes.contains(.photoLive),
              removalCopyIsUnchanged(record, replacement: replacement) else {
            throw PhotoFailure("核对静态副本是否改变", ConversionProblem.unverifiable)
        }
        let resources = PHAssetResource.assetResources(for: replacement)
        let photos = resources.filter { $0.type == .photo }
        guard photos.count == 1,
              resources.allSatisfy({ $0.type == .photo || $0.type == .photoProxy }) else {
            throw PhotoFailure("检查副本资源", ConversionProblem.replacementIsLive)
        }
        guard await Self.sameMetadataOffMain(original, replacement) else {
            throw PhotoFailure("复核日期、位置和收藏", ConversionProblem.changed)
        }
        return replacement
    }

    private func removalCopyIsUnchanged(_ record: ConversionRecord, replacement: PHAsset) -> Bool {
        guard let modifiedAt = record.replacementModifiedAt,
              replacement.localIdentifier == record.replacementID,
              replacement.modificationDate == modifiedAt,
              replacement.mediaType == .image,
              !replacement.mediaSubtypes.contains(.photoLive) else { return false }
        return true
    }

    private func export(_ resource: PHAssetResource, to url: URL) async throws {
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        try await PHAssetResourceManager.default().writeData(for: resource, toFile: url, options: options)
    }

    private func currentStill(_ asset: PHAsset) async throws -> Data {
        let options = PHImageRequestOptions()
        options.version = .current
        options.isNetworkAccessAllowed = true
        return try await withCheckedThrowingContinuation { continuation in
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, info in
                if let error = info?[PHImageErrorKey] as? Error {
                    continuation.resume(throwing: error)
                } else if let data, (info?[PHImageCancelledKey] as? Bool) != true {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(throwing: ConversionProblem.unavailable)
                }
            }
        }
    }

    private static func digest(_ bytes: Data) -> String {
        SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }
}
