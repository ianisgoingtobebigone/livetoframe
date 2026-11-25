import Foundation
import Photos
import UIKit
import Vision

enum PhotoScene: String, Codable, CaseIterable {
    case person, landscape, text, other, unknown

    var label: String {
        switch self {
        case .person: "含人物"
        case .landscape: "风景"
        case .text: "文字"
        case .other: "其他"
        case .unknown: "未确定"
        }
    }

    var icon: String {
        switch self {
        case .person: "person.fill"
        case .landscape: "mountain.2.fill"
        case .text: "doc.text"
        case .other: "square.grid.2x2"
        case .unknown: "questionmark"
        }
    }
}

struct PhotoDateGroup: Identifiable {
    let id: Date?
    let assets: [PHAsset]

    var title: String {
        id?.formatted(.dateTime.year().month().day().locale(Locale(identifier: "zh_CN"))) ?? "日期未知"
    }
}

struct PhotoPreview: Identifiable {
    let asset: PHAsset
    var id: String { asset.localIdentifier }
}

struct SceneResult: Codable {
    let modifiedAt: Date?
    let scene: PhotoScene
}

enum LocalPhotoClassifier {
    private static let landscapes: Set<String> = [
        "beach", "cityscape", "desert", "forest", "lake", "mountain", "river",
        "water_body", "waterfall", "sunset_sunrise", "rice_field", "night_sky", "skyscraper"
    ]

    @MainActor
    static func classify(_ asset: PHAsset) async -> PhotoScene? {
        let image: UIImage? = await withCheckedContinuation { continuation in
            let options = PHImageRequestOptions()
            options.resizeMode = .fast
            options.deliveryMode = .highQualityFormat
            options.version = .current
            // An album scan only uses image data Photos already has on the device.
            options.isNetworkAccessAllowed = false
            PHImageManager.default().requestImage(
                for: asset, targetSize: CGSize(width: 640, height: 640),
                contentMode: .aspectFit, options: options
            ) { image, info in
                if (info?[PHImageResultIsDegradedKey] as? Bool) != true {
                    continuation.resume(returning: image)
                }
            }
        }
        guard let data = image?.cgImage, let orientation = image?.imageOrientation else { return nil }
        let imageOrientation = CGImagePropertyOrientation(orientation)
        return await Task.detached(priority: .utility) {
            detectScene(data, orientation: imageOrientation)
        }.value
    }

    private static func detectScene(_ image: CGImage, orientation: CGImagePropertyOrientation) -> PhotoScene? {
        let faces = VNDetectFaceRectanglesRequest()
        let bodies = VNDetectHumanRectanglesRequest()
        let scene = VNClassifyImageRequest()
        let text = VNRecognizeTextRequest()
        text.recognitionLevel = .fast
        let handler = VNImageRequestHandler(cgImage: image, orientation: orientation)
        do {
            try handler.perform([faces, bodies, scene, text])
            if faces.results?.isEmpty == false
                || bodies.results?.contains(where: { $0.confidence >= 0.35 }) == true
                || scene.results?.contains(where: { $0.identifier == "people" && $0.confidence >= 0.2 }) == true {
                return .person
            }
            if scene.results?.prefix(10).contains(where: { landscapes.contains($0.identifier) && $0.confidence >= 0.35 }) == true {
                return .landscape
            }
            if text.results?.filter({ ($0.topCandidates(1).first?.string.count ?? 0) >= 2 }).count ?? 0 >= 3 {
                return .text
            }
            return scene.results?.first?.confidence ?? 0 >= 0.18 ? .other : .unknown
        } catch {
            return nil
        }
    }
}

private extension CGImagePropertyOrientation {
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .upMirrored: self = .upMirrored
        case .down: self = .down
        case .downMirrored: self = .downMirrored
        case .left: self = .left
        case .leftMirrored: self = .leftMirrored
        case .right: self = .right
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
