import Photos
import PhotosUI
import SwiftUI

struct LivePreviewView: View {
    let asset: PHAsset

    @Environment(\.dismiss) private var dismiss
    @StateObject private var loader = LivePreviewLoader()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("实况预览", systemImage: "livephoto")
                    .font(.headline)
                Spacer()
                Button("完成") { dismiss() }
                    .font(.subheadline.weight(.semibold))
            }

            ZStack {
                Color.black
                if let photo = loader.photo {
                    LivePreviewPlayer(photo: photo, replay: loader.replay)
                } else if let image = loader.cover {
                    Image(uiImage: image).resizable().scaledToFit()
                }
                if loader.isLoading {
                    ProgressView().tint(.white)
                }
            }
            .clipShape(.rect(cornerRadius: 16))
            .accessibilityLabel("实况照片预览")

            HStack {
                if let date = asset.creationDate {
                    Text(date.formatted(.dateTime.year().month().day().hour().minute().locale(Locale(identifier: "zh_CN"))))
                }
                Spacer()
                if loader.photo != nil {
                    Button("重播", systemImage: "arrow.counterclockwise") { loader.replay += 1 }
                } else if !loader.isLoading {
                    Text("暂时无法播放实况")
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .padding(18)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .onAppear { loader.load(asset) }
        .onDisappear { loader.cancel() }
    }
}

@MainActor
private final class LivePreviewLoader: ObservableObject {
    @Published private(set) var photo: PHLivePhoto?
    @Published private(set) var cover: UIImage?
    @Published private(set) var isLoading = true
    @Published var replay = 0

    private var requests: [PHImageRequestID] = []
    private var generation = 0

    func load(_ asset: PHAsset) {
        guard requests.isEmpty else { return }
        let generation = self.generation
        let coverOptions = PHImageRequestOptions()
        coverOptions.deliveryMode = .opportunistic
        coverOptions.isNetworkAccessAllowed = true
        requests.append(PHImageManager.default().requestImage(
            for: asset, targetSize: CGSize(width: 1000, height: 1000),
            contentMode: .aspectFit, options: coverOptions
        ) { [weak self] cover, _ in
            DispatchQueue.main.async {
                guard let self, self.generation == generation, let cover else { return }
                self.cover = cover
            }
        })

        let options = PHLivePhotoRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        requests.append(PHImageManager.default().requestLivePhoto(
            for: asset, targetSize: CGSize(width: 1000, height: 1000),
            contentMode: .aspectFit, options: options
        ) { [weak self] photo, info in
            let isDegraded = (info?[PHImageResultIsDegradedKey] as? Bool) == true
            DispatchQueue.main.async {
                guard let self, self.generation == generation, !isDegraded else { return }
                self.photo = photo
                self.isLoading = false
            }
        })
    }

    func cancel() {
        generation += 1
        for request in requests { PHImageManager.default().cancelImageRequest(request) }
        requests.removeAll()
        photo = nil
        cover = nil
        isLoading = true
    }
}

private struct LivePreviewPlayer: UIViewRepresentable {
    let photo: PHLivePhoto
    let replay: Int

    final class Coordinator {
        var lastReplay: Int?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> PHLivePhotoView {
        let view = PHLivePhotoView()
        view.contentMode = .scaleAspectFit
        return view
    }

    func updateUIView(_ view: PHLivePhotoView, context: Context) {
        guard view.livePhoto !== photo || context.coordinator.lastReplay != replay else { return }
        view.livePhoto = photo
        context.coordinator.lastReplay = replay
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
