import XCTest
@testable import LiveToFrame

final class ConversionSafetyTests: XCTestCase {
    func testFavoriteIsExcludedAtConversionBoundary() {
        let candidates = ["favorite", "ordinary"]
        XCTAssertEqual(ConversionSafetyPolicy.conversionIDs(candidates, favoriteIDs: ["favorite"], excludingFavorites: true), ["ordinary"])
        XCTAssertEqual(ConversionSafetyPolicy.conversionIDs(candidates, favoriteIDs: ["favorite"], excludingFavorites: false), candidates)
    }

    func testOnlyVerifiedRecordsCanBeRemoved() {
        let records = [record("verified", .verified, verified: true),
                       record("written", .written), record("writing", .writing),
                       record("fallback", .verified, verified: true, usedCurrentRendition: true),
                       record("album-mismatch", .verified, verified: true,
                              migratedAlbumIDs: [], requiredAlbumIDs: ["user-album"])]
        XCTAssertEqual(ConversionSafetyPolicy.removableIDs(records), ["verified"])
    }

    func testRecoveryRequiresCopyInEverySourceAlbum() {
        XCTAssertTrue(ConversionSafetyPolicy.albumMembershipsSatisfied(required: [], replacement: []))
        XCTAssertTrue(ConversionSafetyPolicy.albumMembershipsSatisfied(
            required: ["album-a", "album-b"], replacement: ["album-a", "album-b", "output"]
        ))
        XCTAssertFalse(ConversionSafetyPolicy.albumMembershipsSatisfied(
            required: ["album-a", "album-b"], replacement: ["album-a", "output"]
        ))
    }

    @MainActor
    func testRemovalRequiresTheCopyForThisOperation() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        var first = record("source", .verified, verified: true)
        var second = record("source", .verified, verified: true)
        first.replacementID = "copy-a"
        second.replacementID = "copy-b"
        try ConversionJournal(testDirectory: directory).save([first, second])
        let journal = try ConversionJournal(testDirectory: directory)
        XCTAssertNil(journal.recordForRemoval(operationID: second.operationID, originalID: "source", replacementID: "copy-a"))
        XCTAssertEqual(journal.recordForRemoval(operationID: second.operationID, originalID: "source",
                                                replacementID: "copy-b")?.operationID, second.operationID)
    }

    @MainActor
    func testWritingIntentSurvivesJournalReopenAndCanBeResumed() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let record = record("source", .writing)
        let firstLaunch = try ConversionJournal(testDirectory: directory)
        try firstLaunch.upsert(record)

        let afterRelaunch = try ConversionJournal(testDirectory: directory)
        XCTAssertEqual(afterRelaunch.unfinishedRecords.map(\.originalID), ["source"])
        XCTAssertEqual(afterRelaunch.latestRecord(for: "source")?.operationID, record.operationID)
    }

    private func record(_ id: String, _ state: ConversionRecord.State, verified: Bool = false,
                        usedCurrentRendition: Bool? = false, migratedAlbumIDs: [String] = [],
                        requiredAlbumIDs: [String] = []) -> ConversionRecord {
        ConversionRecord(originalID: id, originalModifiedAt: nil, digest: "source", state: state,
                         usedCurrentRendition: usedCurrentRendition,
                         verified: verified, migratedAlbumIDs: migratedAlbumIDs,
                         requiredAlbumIDs: requiredAlbumIDs)
    }
}
