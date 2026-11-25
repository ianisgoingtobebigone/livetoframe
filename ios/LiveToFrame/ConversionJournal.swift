import Foundation

struct ConversionRecord: Codable {
    enum State: String, Codable {
        case writing, written, verified, removed
    }

    let originalID: String
    var operationID: String = UUID().uuidString
    let originalModifiedAt: Date?
    let digest: String
    var state: State
    var replacementID: String?
    var replacementModifiedAt: Date? = nil
    var usedCurrentRendition: Bool? = nil
    var matchedImageContent: Bool? = nil
    var savedStillDigest: String? = nil
    var albumIdentifier: String? = nil
    var replacementFilename: String? = nil
    var verified: Bool = false
    var migratedAlbumIDs: [String] = []
    var requiredAlbumIDs: [String] = []

    private enum CodingKeys: String, CodingKey {
        case originalID, operationID, originalModifiedAt, digest, state, replacementID, replacementModifiedAt
        case usedCurrentRendition, matchedImageContent, savedStillDigest, albumIdentifier, replacementFilename
        case verified, migratedAlbumIDs, requiredAlbumIDs
    }

    init(originalID: String, originalModifiedAt: Date?, digest: String, state: State,
         replacementID: String? = nil, usedCurrentRendition: Bool? = nil,
         replacementModifiedAt: Date? = nil,
         matchedImageContent: Bool? = nil, savedStillDigest: String? = nil,
         albumIdentifier: String? = nil, replacementFilename: String? = nil,
         verified: Bool = false, migratedAlbumIDs: [String] = [], requiredAlbumIDs: [String] = []) {
        self.originalID = originalID
        operationID = UUID().uuidString
        self.originalModifiedAt = originalModifiedAt
        self.digest = digest
        self.state = state
        self.replacementID = replacementID
        self.replacementModifiedAt = replacementModifiedAt
        self.usedCurrentRendition = usedCurrentRendition
        self.matchedImageContent = matchedImageContent
        self.savedStillDigest = savedStillDigest
        self.albumIdentifier = albumIdentifier
        self.replacementFilename = replacementFilename
        self.verified = verified
        self.migratedAlbumIDs = migratedAlbumIDs
        self.requiredAlbumIDs = requiredAlbumIDs
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        originalID = try values.decode(String.self, forKey: .originalID)
        operationID = try values.decodeIfPresent(String.self, forKey: .operationID) ?? originalID
        originalModifiedAt = try values.decodeIfPresent(Date.self, forKey: .originalModifiedAt)
        digest = try values.decode(String.self, forKey: .digest)
        state = try values.decode(State.self, forKey: .state)
        replacementID = try values.decodeIfPresent(String.self, forKey: .replacementID)
        replacementModifiedAt = try values.decodeIfPresent(Date.self, forKey: .replacementModifiedAt)
        usedCurrentRendition = try values.decodeIfPresent(Bool.self, forKey: .usedCurrentRendition)
        matchedImageContent = try values.decodeIfPresent(Bool.self, forKey: .matchedImageContent)
        savedStillDigest = try values.decodeIfPresent(String.self, forKey: .savedStillDigest)
        albumIdentifier = try values.decodeIfPresent(String.self, forKey: .albumIdentifier)
        replacementFilename = try values.decodeIfPresent(String.self, forKey: .replacementFilename)
        verified = try values.decodeIfPresent(Bool.self, forKey: .verified) ?? (state == .verified)
        migratedAlbumIDs = try values.decodeIfPresent([String].self, forKey: .migratedAlbumIDs) ?? []
        requiredAlbumIDs = try values.decodeIfPresent([String].self, forKey: .requiredAlbumIDs) ?? []
    }
}

enum ConversionSafetyPolicy {
    static func conversionIDs(_ selectedIDs: [String], favoriteIDs: Set<String>, excludingFavorites: Bool) -> [String] {
        excludingFavorites ? selectedIDs.filter { !favoriteIDs.contains($0) } : selectedIDs
    }

    static func removableIDs(_ records: [ConversionRecord]) -> [String] {
        records.filter {
            $0.verified && $0.state == .verified && $0.usedCurrentRendition != true
                && Set($0.migratedAlbumIDs) == Set($0.requiredAlbumIDs)
        }.map(\.originalID)
    }

    static func albumMembershipsSatisfied(required: Set<String>, replacement: Set<String>) -> Bool {
        required.isSubset(of: replacement)
    }
}

@MainActor
final class ConversionJournal {
    private let fileURL: URL
    private(set) var records: [String: ConversionRecord]

    init() throws {
        let appSupport = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        )
        fileURL = appSupport.appendingPathComponent("Conversions.json")
        records = try Self.readRecords(at: fileURL)
    }

    init(testDirectory: URL) throws {
        fileURL = testDirectory.appendingPathComponent("Conversions.json")
        records = try Self.readRecords(at: fileURL)
    }

    private static func readRecords(at fileURL: URL) throws -> [String: ConversionRecord] {
        do {
            let data = try Data(contentsOf: fileURL)
            return try JSONDecoder().decode([String: ConversionRecord].self, from: data)
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
            return [:]
        }
    }

    func save(_ record: ConversionRecord) throws {
        try save([record])
    }

    func save(_ records: [ConversionRecord]) throws {
        var updated = self.records
        for record in records {
            updated[record.operationID] = record
        }
        try persist(updated)
    }

    func upsert(_ record: ConversionRecord) throws {
        try save(record)
    }

    func recordForRemoval(operationID: String, originalID: String, replacementID: String) -> ConversionRecord? {
        guard let record = records[operationID],
              record.originalID == originalID, record.replacementID == replacementID,
              record.state == .verified, record.verified else { return nil }
        return record
    }

    func latestRecord(for originalID: String) -> ConversionRecord? {
        records.values.filter { $0.originalID == originalID }
            .max { ($0.originalModifiedAt ?? .distantPast) < ($1.originalModifiedAt ?? .distantPast) }
    }

    var unfinishedRecords: [ConversionRecord] {
        records.values.filter { $0.state == .writing || $0.state == .written }
    }

    private func persist(_ updated: [String: ConversionRecord]) throws {
        try JSONEncoder().encode(updated).write(to: fileURL, options: .atomic)
        records = updated
    }
}
