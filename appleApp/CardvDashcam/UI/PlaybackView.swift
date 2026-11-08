import AVFoundation
import AVKit
import Shared
import SwiftUI

struct PlaybackView: View {
    let item: OpenClip

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        switch item.kind {
        case .video:
            VideoPlayerScreen(item: item, onClose: { dismiss() })
        case .photo:
            PhotoViewer(item: item, onClose: { dismiss() })
        }
    }
}

// MARK: - Video

@MainActor
private final class PlayerModel: ObservableObject {
    let player: AVPlayer
    @Published var isPlaying = false
    @Published var currentTime: Double = 0
    @Published var duration: Double = 0
    @Published var error: String?
    @Published var isScrubbing = false

    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var endObserver: NSObjectProtocol?

    init(url: URL) {
        let item = AVPlayerItem(url: url)
        player = AVPlayer(playerItem: item)
        player.automaticallyWaitsToMinimizeStalling = true

        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                switch item.status {
                case .failed:
                    self?.error = item.error?.localizedDescription
                        ?? "The camera's file could not be played."
                case .readyToPlay:
                    let seconds = item.duration.seconds
                    if seconds.isFinite, seconds > 0 { self?.duration = seconds }
                default:
                    break
                }
            }
        }

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor in
                guard let self, !self.isScrubbing else { return }
                self.currentTime = max(0, time.seconds)
                let seconds = self.player.currentItem?.duration.seconds ?? 0
                if seconds.isFinite, seconds > 0 {
                    self.duration = seconds
                }
            }
        }

        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.isPlaying = false
                self?.currentTime = self?.duration ?? 0
            }
        }

        #if os(iOS)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
    }

    deinit {
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
    }

    func play() {
        player.play()
        isPlaying = true
    }

    func pause() {
        player.pause()
        isPlaying = false
    }

    func togglePlay() {
        isPlaying ? pause() : play()
    }

    func seek(to seconds: Double) {
        let target = CMTime(seconds: max(0, min(seconds, duration)), preferredTimescale: 600)
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
        currentTime = target.seconds
    }

    func skip(_ delta: Double) {
        seek(to: currentTime + delta)
    }
}

private struct VideoPlayerScreen: View {
    let item: OpenClip
    let onClose: () -> Void

    @StateObject private var model: PlayerModel
    @Environment(\.colorScheme) private var colorScheme

    init(item: OpenClip, onClose: @escaping () -> Void) {
        self.item = item
        self.onClose = onClose
        _model = StateObject(wrappedValue: PlayerModel(url: item.url))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            PlayerLayerView(player: model.player)
                .ignoresSafeArea()

            if let error = model.error {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 34, weight: .light))
                    Text("Can't play this clip")
                        .font(.system(size: 18, weight: .semibold))
                    Text(error)
                        .font(.system(size: 13))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white.opacity(0.7))
                        .frame(maxWidth: 360)
                    Button("Close", action: onClose)
                        .buttonStyle(.bordered)
                        .tint(.white)
                        .padding(.top, 4)
                }
                .foregroundStyle(.white)
            }

            VStack {
                topBar
                Spacer()
                if model.error == nil {
                    controls
                }
            }
            .padding(20)
        }
        .onAppear {
            model.play()
        }
        .onDisappear {
            model.pause()
        }
        #if os(iOS)
        .statusBarHidden(true)
        #endif
        #if os(macOS)
        .frame(minWidth: 720, minHeight: 420)
        #endif
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(.black.opacity(0.45)))
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 1) {
                Text(item.clip.name)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text(Format.timestamp(item.clip))
                    .font(.system(size: 11))
                    .foregroundStyle(.white.opacity(0.65))
            }
            Spacer()
        }
    }

    private var controls: some View {
        VStack(spacing: 10) {
            Slider(
                value: Binding(
                    get: { min(model.currentTime, max(model.duration, 0.01)) },
                    set: { model.currentTime = $0 }
                ),
                in: 0...max(model.duration, 0.01)
            ) { editing in
                model.isScrubbing = editing
                if !editing { model.seek(to: model.currentTime) }
            }
            .tint(.white)

            HStack(spacing: 22) {
                Text(playbackTime(model.currentTime))
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.7))
                    .frame(width: 52, alignment: .leading)

                Spacer()

                Button { model.skip(-10) } label: {
                    Image(systemName: "gobackward.10")
                        .font(.system(size: 20, weight: .medium))
                }
                Button { model.togglePlay() } label: {
                    Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 22, weight: .semibold))
                        .frame(width: 54, height: 54)
                        .background(Circle().fill(.white.opacity(0.16)))
                }
                Button { model.skip(10) } label: {
                    Image(systemName: "goforward.10")
                        .font(.system(size: 20, weight: .medium))
                }

                Spacer()

                Text(playbackTime(model.duration))
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.7))
                    .frame(width: 52, alignment: .trailing)
            }
            .foregroundStyle(.white)
            .buttonStyle(.plain)
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(.black.opacity(0.45))
        )
    }

    private func playbackTime(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds)
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%d:%02d", minutes, secs)
    }
}

#if os(iOS)
private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerContainerView {
        let view = PlayerContainerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        return view
    }

    func updateUIView(_ view: PlayerContainerView, context: Context) {
        view.playerLayer.player = player
    }
}

private final class PlayerContainerView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}
#else
private struct PlayerLayerView: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> PlayerContainerView {
        let view = PlayerContainerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        return view
    }

    func updateNSView(_ view: PlayerContainerView, context: Context) {
        view.playerLayer.player = player
    }
}

private final class PlayerContainerView: NSView {
    let playerLayer = AVPlayerLayer()

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        playerLayer.videoGravity = .resizeAspect
        layer?.addSublayer(playerLayer)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layout() {
        super.layout()
        playerLayer.frame = bounds
    }
}
#endif

// MARK: - Photo

private struct PhotoViewer: View {
    let item: OpenClip
    let onClose: () -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            AsyncImage(url: item.url) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                case .failure:
                    VStack(spacing: 10) {
                        Image(systemName: "photo")
                            .font(.system(size: 40, weight: .light))
                        Text("Can't load this snapshot")
                            .font(.system(size: 15, weight: .semibold))
                    }
                    .foregroundStyle(.white.opacity(0.8))
                default:
                    ProgressView()
                        .tint(.white)
                }
            }
            .padding(24)

            VStack {
                HStack {
                    Button(action: onClose) {
                        Image(systemName: "xmark")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(Circle().fill(.black.opacity(0.45)))
                    }
                    .buttonStyle(.plain)
                    Spacer()
                    Text(Format.timestamp(item.clip))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.8))
                }
                Spacer()
            }
            .padding(20)
        }
        #if os(macOS)
        .frame(minWidth: 720, minHeight: 480)
        #endif
    }
}
