import AVFoundation
import SwiftUI

/// Hosts the decoder's `AVSampleBufferDisplayLayer`. The only per-platform view in live view.
struct LiveVideoSurface: View {
    let decoder: LiveDecoding

    var body: some View {
        LiveVideoSurfaceRepresentable(layer: decoder.displayLayer)
    }
}

#if os(iOS)
private struct LiveVideoSurfaceRepresentable: UIViewRepresentable {
    let layer: AVSampleBufferDisplayLayer

    func makeUIView(context: Context) -> LiveLayerHostView {
        let view = LiveLayerHostView()
        view.backgroundColor = .black
        view.attach(layer)
        return view
    }

    func updateUIView(_ view: LiveLayerHostView, context: Context) {
        view.attachIfNeeded(layer)
    }
}

final class LiveLayerHostView: UIView {
    private(set) var hostedLayer: AVSampleBufferDisplayLayer?

    func attach(_ layer: AVSampleBufferDisplayLayer) {
        layer.removeFromSuperlayer()
        hostedLayer = layer
        layer.videoGravity = .resizeAspect
        layer.frame = bounds
        self.layer.addSublayer(layer)
    }

    func attachIfNeeded(_ layer: AVSampleBufferDisplayLayer) {
        if hostedLayer !== layer {
            attach(layer)
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        hostedLayer?.frame = bounds
    }
}
#else
private struct LiveVideoSurfaceRepresentable: NSViewRepresentable {
    let layer: AVSampleBufferDisplayLayer

    func makeNSView(context: Context) -> LiveLayerHostView {
        let view = LiveLayerHostView()
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor.black.cgColor
        view.attach(layer)
        return view
    }

    func updateNSView(_ view: LiveLayerHostView, context: Context) {
        view.attachIfNeeded(layer)
    }
}

final class LiveLayerHostView: NSView {
    private(set) var hostedLayer: AVSampleBufferDisplayLayer?

    func attach(_ layer: AVSampleBufferDisplayLayer) {
        layer.removeFromSuperlayer()
        hostedLayer = layer
        layer.videoGravity = .resizeAspect
        layer.frame = bounds
        self.layer?.addSublayer(layer)
    }

    func attachIfNeeded(_ layer: AVSampleBufferDisplayLayer) {
        if hostedLayer !== layer {
            attach(layer)
        }
    }

    override func layout() {
        super.layout()
        hostedLayer?.frame = bounds
    }
}
#endif
