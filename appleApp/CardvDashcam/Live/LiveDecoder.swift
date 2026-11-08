import AVFoundation
import CoreMedia
import Foundation
import Shared
import os

private let liveDecoderLogger = Logger(subsystem: "com.cardv.dashcam", category: "live")

/// The platform decoding abstraction the shared RTSP client feeds.
///
/// The protocol keeps the live screen independent of AVFoundation details; the app has one
/// implementation today (VideoToolbox inside `AVSampleBufferDisplayLayer` + AAC through
/// `AVSampleBufferAudioRenderer`), and a future platform could replace it without touching the UI
/// or the shared protocol code.
protocol LiveDecoding: AnyObject {
    var displayLayer: AVSampleBufferDisplayLayer { get }
    /// Called on the decoder's serial queue after the first video sample is enqueued.
    var onFirstVideoFrame: (() -> Void)? { get set }
    /// Called when video decoding/output fails; the stream itself may still be alive.
    var onFailure: ((String) -> Void)? { get set }

    func start()
    func stop()

    func consume(video: AppleLiveVideoSample)
    func consume(audio: AppleLiveAudioSample)
}

/// H.264 and AAC decode/render for live view.
///
/// The camera's H.264 arrives as AVCC access units (the shared client already stripped RTP and
/// converted NAL units), so the only video work here is building a `CMVideoFormatDescription`
/// from SPS/PPS and hand off sample buffers to `AVSampleBufferDisplayLayer`, which decodes with
/// VideoToolbox. AAC is described with the SDP's AudioSpecificConfig and rendered by
/// `AVSampleBufferAudioRenderer`. A render synchronizer keeps the two tracks in step.
final class AVFoundationLiveDecoder: LiveDecoding {
    let displayLayer = AVSampleBufferDisplayLayer()
    var onFirstVideoFrame: (() -> Void)?
    var onFailure: ((String) -> Void)?

    private let synchronizer = AVSampleBufferRenderSynchronizer()
    private let audioRenderer = AVSampleBufferAudioRenderer()
    private let queue = DispatchQueue(label: "com.cardv.live.decode", qos: .userInitiated)
    private var videoFormat: CMFormatDescription?
    private var videoParameterKey = ""
    private var audioFormat: CMFormatDescription?
    private var configured = false
    private var renderersAdded = false
    private var reportedFirstFrame = false
    private var failed = false

    init() {
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = CGColor(red: 0, green: 0, blue: 0, alpha: 1)
        #if os(iOS)
        displayLayer.preventsDisplaySleepDuringVideoPlayback = false
        #endif
    }

    func start() {
        queue.async { [weak self] in
            guard let self, !self.configured else { return }
            self.reportedFirstFrame = false
            self.failed = false
            self.videoFormat = nil
            self.videoParameterKey = ""
            self.audioFormat = nil
            self.displayLayer.flush()
            self.audioRenderer.flush()

            if !self.renderersAdded {
                self.synchronizer.addRenderer(self.displayLayer)
                self.synchronizer.addRenderer(self.audioRenderer)
                self.renderersAdded = true
            }
            self.synchronizer.setRate(1.0, time: .zero)
            self.configured = true
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.configured = false
            self.synchronizer.setRate(0, time: .zero)
            self.displayLayer.flushAndRemoveImage()
            self.audioRenderer.flush()
            self.videoFormat = nil
            self.audioFormat = nil
            self.videoParameterKey = ""
        }
    }

    func consume(video sample: AppleLiveVideoSample) {
        queue.async { [weak self] in
            guard let self, self.configured, !self.failed else { return }
            if let failure = self.refreshVideoFormatIfNeeded(sample) {
                self.fail(failure)
                return
            }
            guard let format = self.videoFormat,
                  let buffer = Self.makeSampleBuffer(
                      data: sample.data as Data,
                      format: format,
                      presentationMicros: sample.presentationTimeMicros,
                      duration: .invalid
                  )
            else { return }

            if !sample.isKeyFrame {
                CMSetAttachment(
                    buffer,
                    key: kCMSampleAttachmentKey_NotSync,
                    value: kCFBooleanTrue,
                    attachmentMode: kCMAttachmentMode_ShouldPropagate
                )
            }
            self.enqueueVideo(buffer)
        }
    }

    func consume(audio sample: AppleLiveAudioSample) {
        queue.async { [weak self] in
            guard let self, self.configured, !self.failed else { return }
            let cookie = sample.audioSpecificConfig as Data
            let rate = Int(sample.sampleRate)
            let channels = Int(sample.channels)
            let key = "\(rate)-\(channels)-\(cookie.base64EncodedString())"
            if self.audioFormat == nil || self.audioKey != key {
                self.audioKey = key
                self.audioFormat = Self.makeAudioFormat(
                    sampleRate: rate,
                    channels: channels,
                    audioSpecificConfig: cookie
                )
                liveDecoderLogger.info(
                    "audio \(rate) Hz \(channels) ch config=\(cookie.base64EncodedString(), privacy: .public) format=\(self.audioFormat == nil ? "failed" : "ok", privacy: .public)"
                )
            }
            guard let format = self.audioFormat,
                  let buffer = Self.makeSampleBuffer(
                      data: sample.data as Data,
                      format: format,
                      presentationMicros: sample.presentationTimeMicros,
                      duration: CMTime(value: 1024, timescale: CMTimeScale(rate))
                  )
            else { return }

            if self.audioRenderer.status == .failed {
                // Audio is optional; keep the picture running rather than failing the session.
                return
            }
            self.audioRenderer.enqueue(buffer)
        }
    }

    private var audioKey = ""

    private func refreshVideoFormatIfNeeded(_ sample: AppleLiveVideoSample) -> String? {
        guard let spsObject = sample.sps, let ppsObject = sample.pps else {
            return videoFormat == nil ? "The camera sent no H.264 parameter sets." : nil
        }
        let sps = spsObject as Data
        let pps = ppsObject as Data
        guard !sps.isEmpty, !pps.isEmpty else {
            return videoFormat == nil ? "The camera sent no H.264 parameter sets." : nil
        }
        let key = "\(sps.base64EncodedString())-\(pps.base64EncodedString())"
        if key == videoParameterKey, videoFormat != nil { return nil }

        var format: CMFormatDescription?
        let status = Self.withParameterSets(sps: sps, pps: pps) { pointers, sizes in
            CMVideoFormatDescriptionCreateFromH264ParameterSets(
                allocator: kCFAllocatorDefault,
                parameterSetCount: 2,
                parameterSetPointers: pointers,
                parameterSetSizes: sizes,
                nalUnitHeaderLength: 4,
                formatDescriptionOut: &format
            )
        }
        guard status == noErr, let format else {
            return "The camera's H.264 parameters are not valid."
        }
        videoFormat = format
        videoParameterKey = key
        let dimensions = CMVideoFormatDescriptionGetDimensions(format)
        liveDecoderLogger.info("video \(dimensions.width)x\(dimensions.height)")
        return nil
    }

    private func enqueueVideo(_ buffer: CMSampleBuffer) {
        if displayLayer.status == .failed {
            fail("Video decoding failed. Start live view again.")
            return
        }
        if displayLayer.requiresFlushToResumeDecoding {
            displayLayer.flush()
        }
        displayLayer.enqueue(buffer)
        if !reportedFirstFrame {
            reportedFirstFrame = true
            onFirstVideoFrame?()
        }
    }

    private func fail(_ message: String) {
        guard !failed else { return }
        failed = true
        liveDecoderLogger.error("failed \(message, privacy: .public)")
        onFailure?(message)
    }

    // MARK: - Core Media helpers

    private static func makeSampleBuffer(
        data: Data,
        format: CMFormatDescription,
        presentationMicros: Int64,
        duration: CMTime
    ) -> CMSampleBuffer? {
        guard !data.isEmpty else { return nil }
        var blockBuffer: CMBlockBuffer?
        let blockStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: data.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: data.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard blockStatus == kCMBlockBufferNoErr, let blockBuffer else { return nil }

        let copyStatus = data.withUnsafeBytes { bytes in
            CMBlockBufferReplaceDataBytes(
                with: bytes.baseAddress!,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: data.count
            )
        }
        guard copyStatus == kCMBlockBufferNoErr else { return nil }

        var timing = CMSampleTimingInfo(
            duration: duration,
            presentationTimeStamp: CMTime(value: presentationMicros, timescale: 1_000_000),
            decodeTimeStamp: .invalid
        )
        var sampleSize = data.count
        var sampleBuffer: CMSampleBuffer?
        let status = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: format,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard status == noErr else { return nil }
        return sampleBuffer
    }

    private static func makeAudioFormat(
        sampleRate: Int,
        channels: Int,
        audioSpecificConfig: Data
    ) -> CMFormatDescription? {
        guard sampleRate > 0, channels > 0, !audioSpecificConfig.isEmpty else { return nil }
        var description = AudioStreamBasicDescription(
            mSampleRate: Float64(sampleRate),
            mFormatID: kAudioFormatMPEG4AAC,
            mFormatFlags: 0,
            mBytesPerPacket: 0,
            mFramesPerPacket: 1024,
            mBytesPerFrame: 0,
            mChannelsPerFrame: UInt32(channels),
            mBitsPerChannel: 0,
            mReserved: 0
        )
        var format: CMFormatDescription?
        let status = audioSpecificConfig.withUnsafeBytes { cookie in
            CMAudioFormatDescriptionCreate(
                allocator: kCFAllocatorDefault,
                asbd: &description,
                layoutSize: 0,
                layout: nil,
                magicCookieSize: audioSpecificConfig.count,
                magicCookie: cookie.baseAddress,
                extensions: nil,
                formatDescriptionOut: &format
            )
        }
        return status == noErr ? format : nil
    }

    private static func withParameterSets(
        sps: Data,
        pps: Data,
        _ body: (UnsafePointer<UnsafePointer<UInt8>>, UnsafePointer<Int>) -> OSStatus
    ) -> OSStatus {
        sps.withUnsafeBytes { spsBytes in
            pps.withUnsafeBytes { ppsBytes in
                let pointers: [UnsafePointer<UInt8>] = [
                    spsBytes.bindMemory(to: UInt8.self).baseAddress!,
                    ppsBytes.bindMemory(to: UInt8.self).baseAddress!,
                ]
                let sizes: [Int] = [sps.count, pps.count]
                return pointers.withUnsafeBufferPointer { pointerBuffer in
                    sizes.withUnsafeBufferPointer { sizeBuffer in
                        body(pointerBuffer.baseAddress!, sizeBuffer.baseAddress!)
                    }
                }
            }
        }
    }
}
