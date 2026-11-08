import Shared
import SwiftUI

struct LibraryView: View {
    @Environment(\.colorScheme) private var colorScheme
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var downloads: DownloadStore

    private var theme: CarDVTheme { CarDVTheme.of(colorScheme) }

    /// The iOS tab bar floats over the scroll content; leave room for it.
    private var bottomInset: CGFloat {
        #if os(iOS)
        return 86
        #else
        return 28
        #endif
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            folderPills
            toolbar
            content
        }
        .background(theme.background)
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("DASHCAM LIBRARY")
                        .font(.system(size: 10, weight: .semibold))
                        .tracking(2)
                        .foregroundStyle(theme.secondaryText)
                    Text(model.folder == Folder.photo ? "Snapshots" : folderHeading)
                        .font(.system(size: 26, weight: .bold))
                        .tracking(-0.6)
                        .foregroundStyle(theme.text)
                }
                Spacer()
                headerAction("arrow.clockwise", label: "Refresh") {
                    model.refreshLibrary()
                }
                headerAction("wifi.slash", label: "Disconnect") {
                    model.disconnect()
                }
            }

            HStack(spacing: 8) {
                if model.isRecording {
                    chip("Recording", symbol: "record.circle.fill", accent: true)
                }
                if let sd = model.status?.sdStatus {
                    chip("SD \(sd.capitalized)", symbol: "sdcard.fill", accent: false)
                }
                if let free = model.status?.freeMb {
                    chip("\(Format.megabytes(free)) free", symbol: "internaldrive.fill", accent: false)
                }
                if model.hung {
                    chip("Camera hung", symbol: "exclamationmark.triangle.fill", accent: true)
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 4)
    }

    private var folderHeading: String {
        switch model.folder.property {
        case "DCIM": return "Loop recordings"
        case "SOS": return "SOS events"
        case "LOCK": return "Locked clips"
        default: return "Library"
        }
    }

    private func headerAction(_ symbol: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(theme.secondaryText)
                .frame(width: 34, height: 34)
                .background(Circle().fill(theme.raised.opacity(0.75)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func chip(_ text: String, symbol: String, accent: Bool) -> some View {
        HStack(spacing: 4) {
            Image(systemName: symbol)
                .font(.system(size: 9, weight: .bold))
            Text(text)
                .font(.system(size: 10, weight: .semibold))
        }
        .foregroundStyle(accent ? theme.ember : theme.secondaryText)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(Capsule().fill(accent ? theme.ember.opacity(0.12) : theme.raised.opacity(0.6)))
    }

    // MARK: - Folders and toolbar

    private var folderPills: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 5) {
                ForEach(Folder.entries, id: \.property) { folder in
                    Button {
                        model.select(folder: folder)
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: folder.symbol)
                                .font(.system(size: 10, weight: .semibold))
                            Text(folder.title)
                                .lineLimit(1)
                            if let count = model.count(in: folder) {
                                Text("\(count)")
                                    .opacity(0.65)
                            }
                        }
                    }
                    .buttonStyle(
                        PillButtonStyle(theme: theme, selected: model.folder == folder)
                    )
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
        }
    }

    private var toolbar: some View {
        HStack(spacing: 8) {
            Text("\(model.currentClips.count) \(model.folder == Folder.photo ? "snapshots" : "clips")")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(theme.text)
            Spacer()

            Menu {
                ForEach(ClipSort.entries, id: \.name) { sort in
                    Button {
                        model.setSort(sort)
                    } label: {
                        if model.sort == sort {
                            Label(sort.title, systemImage: "checkmark")
                        } else {
                            Text(sort.title)
                        }
                    }
                }
            } label: {
                toolbarPill("arrow.up.arrow.down", model.sort.title)
            }
            .fixedSize()

            Button {
                model.toggleGrouping()
            } label: {
                toolbarPill("calendar", model.groupByDay ? "By day" : "Flat")
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 4)
    }

    private func toolbarPill(_ symbol: String, _ label: String) -> some View {
        HStack(spacing: 5) {
            Image(systemName: symbol)
                .font(.system(size: 10, weight: .semibold))
            Text(label)
                .font(.system(size: 11, weight: .medium))
        }
        .foregroundStyle(theme.secondaryText)
        .padding(.horizontal, 11)
        .padding(.vertical, 7)
        .background(Capsule().fill(theme.raised.opacity(0.65)))
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        if model.isLoadingFolder && model.currentClips.isEmpty {
            Spacer()
            ProgressView()
                .tint(theme.ember)
            Spacer()
        } else if let error = model.folderError, model.currentClips.isEmpty {
            message(
                symbol: "exclamationmark.triangle",
                title: "Can't load this folder",
                body: error,
                action: "Try again"
            ) {
                model.refreshLibrary()
            }
        } else if model.currentClips.isEmpty {
            message(
                symbol: model.folder == Folder.photo ? "camera" : "video",
                title: model.folder == Folder.photo ? "No snapshots yet" : "No clips yet",
                body: model.folder == Folder.photo
                    ? "Snapshots from the dashcam will appear here."
                    : "New recordings will appear here after the camera saves them.",
                action: nil,
                onAction: nil
            )
        } else {
            grid
        }
    }

    private var grid: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                ForEach(model.groups) { group in
                    VStack(alignment: .leading, spacing: 10) {
                        if model.groupByDay {
                            Text(Format.dayLabel(group.date))
                                .font(.system(size: 10, weight: .semibold))
                                .tracking(1.5)
                                .foregroundStyle(theme.secondaryText)
                                .padding(.top, 2)
                        }
                        LazyVGrid(
                            columns: [GridItem(.adaptive(minimum: 160), spacing: 14)],
                            alignment: .leading,
                            spacing: 18
                        ) {
                            ForEach(group.clips, id: \.path) { clip in
                                ClipTile(clip: clip)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, bottomInset)
        }
    }

    private func message(
        symbol: String,
        title: String,
        body: String,
        action: String?,
        onAction: (() -> Void)?
    ) -> some View {
        VStack(spacing: 10) {
            Spacer()
            Image(systemName: symbol)
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(theme.secondaryText.opacity(0.7))
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(theme.text)
            Text(body)
                .font(.system(size: 13))
                .foregroundStyle(theme.secondaryText)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 360)
            if let action, let onAction {
                Button(action) {
                    onAction()
                }
                .buttonStyle(.borderless)
                .foregroundStyle(theme.ember)
                .padding(.top, 4)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Tile

struct ClipTile: View {
    @Environment(\.colorScheme) private var colorScheme
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var downloads: DownloadStore

    let clip: Clip

    private var theme: CarDVTheme { CarDVTheme.of(colorScheme) }
    private var recording: Bool { model.isCurrentRecording(clip) }
    private var download: DownloadStore.State? { downloads.states[clip.path] }
    private var thumbnail: PlatformImage? { model.thumbnails[clip.path] }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(theme.raised)

                if let thumbnail {
                    thumbnail.swiftUI
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                } else {
                    Image(systemName: clip.isVideo ? "video" : "camera")
                        .font(.system(size: 30, weight: .light))
                        .foregroundStyle(theme.secondaryText.opacity(0.5))
                }

                LinearGradient(
                    colors: [.clear, .black.opacity(0.55)],
                    startPoint: .center,
                    endPoint: .bottom
                )

                if clip.isVideo && !recording {
                    Image(systemName: "play.fill")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 42, height: 42)
                        .background(Circle().fill(.black.opacity(0.35)))
                }

                VStack {
                    HStack(alignment: .top) {
                        if recording {
                            Text("REC")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(theme.error)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(Capsule().fill(.black.opacity(0.5)))
                        }
                        Spacer()
                        if !recording {
                            downloadButton
                        }
                    }
                    Spacer()
                    HStack(alignment: .bottom) {
                        if clip.isVideo, let duration = Format.duration(clip.durationSeconds?.doubleValue) {
                            Text(duration)
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(Capsule().fill(.black.opacity(0.5)))
                        }
                        Spacer()
                    }
                }
                .padding(10)

                if download?.phase == .downloading {
                    VStack {
                        Spacer()
                        ProgressView(value: download?.fraction ?? 0)
                            .progressViewStyle(.linear)
                            .tint(theme.ember)
                    }
                }
            }
            .aspectRatio(16.0 / 9.0, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .onTapGesture {
                guard !recording else { return }
                model.open(clip)
            }
            .contextMenu {
                Button("Play from camera") { model.open(clip) }
                    .disabled(recording)
                Button("Download") { model.download(clip) }
                    .disabled(recording || download?.isActive == true)
                if download?.phase == .finished {
                    #if os(macOS)
                    Button("Save As…") {
                        Task { try? await downloads.save(clip) }
                    }
                    #else
                    Button("Save to Photos") {
                        Task { try? await downloads.save(clip) }
                    }
                    #endif
                }
            }

            Text(Format.timestamp(clip))
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(theme.text)
                .lineLimit(1)
                .padding(.top, 8)

            Text(metadata)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(theme.secondaryText)
                .lineLimit(1)

            if let error = download?.error, download?.phase == .failed {
                Text(error)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(theme.error)
                    .lineLimit(1)
            }
        }
        .task(id: clip.path) {
            model.ensureThumbnail(clip)
        }
    }

    private var metadata: String {
        var parts: [String] = []
        if let resolution = clip.resolution, !resolution.isEmpty { parts.append(resolution) }
        if !clip.isVideo { parts.append("Snapshot") }
        parts.append(Format.size(clip.sizeBytes))
        return parts.joined(separator: " · ")
    }

    private var downloadButton: some View {
        Button {
            switch download?.phase {
            case .finished:
                Task { try? await downloads.save(clip) }
            case .failed, .none:
                model.download(clip)
            case .queued, .downloading:
                break
            }
        } label: {
            Group {
                switch download?.phase {
                case .queued:
                    Image(systemName: "clock")
                case .downloading:
                    ProgressView()
                        .scaleEffect(0.55)
                        .tint(theme.ember)
                case .finished:
                    Image(systemName: "checkmark")
                case .failed:
                    Image(systemName: "exclamationmark")
                case .none:
                    Image(systemName: "arrow.down")
                }
            }
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: 26, height: 26)
            .background(Circle().fill(.black.opacity(0.45)))
        }
        .buttonStyle(.plain)
    }
}
