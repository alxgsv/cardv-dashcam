package com.cardv.shared

import com.cardv.shared.model.CameraStatus
import com.cardv.shared.model.Clip
import com.cardv.shared.model.Folder
import com.cardv.shared.model.Health
import com.cardv.shared.model.LiveStream
import com.cardv.shared.model.VideoOp
import com.cardv.shared.protocol.CameraDateTimes
import com.cardv.shared.protocol.CameraHungException
import com.cardv.shared.protocol.CgiClient
import com.cardv.shared.protocol.DirXmlParser
import com.cardv.shared.protocol.MalformedResponseException
import com.cardv.shared.protocol.defaultHttpClientEngine
import com.cardv.shared.protocol.parseCameraDateTime
import com.cardv.shared.safety.CommandQueue
import com.cardv.shared.safety.CommandQueueConfig
import com.cardv.shared.safety.SafetyLevel
import com.cardv.shared.safety.Setting
import com.cardv.shared.safety.SettingsCatalog
import com.cardv.shared.safety.WriteResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlin.concurrent.Volatile

data class CameraClientConfig(
    val readTimeoutMillis: Long = 10_000,
    val writeTimeoutMillis: Long = 15_000,
    val healthTimeoutMillis: Long = 5_000,
    /** `count` for `action=dir` pagination; the real camera accepts 100. */
    val pageSize: Int = 100,
    /** Give the firmware a moment to restart recording before restoring the microphone. */
    val micReapplyDelayMillis: Long = 1_500,
    /** Experimental settings stay hidden and unwritable unless this is enabled explicitly. */
    val allowExperimental: Boolean = false,
) {
    init {
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(writeTimeoutMillis > 0) { "writeTimeoutMillis must be positive" }
        require(healthTimeoutMillis > 0) { "healthTimeoutMillis must be positive" }
        require(pageSize > 0) { "pageSize must be positive" }
        require(micReapplyDelayMillis >= 0) { "micReapplyDelayMillis must not be negative" }
    }
}

/**
 * The shared camera API.
 *
 * Safety rules live here and in [CommandQueue], never in the UI:
 * - all `Config.cgi` commands are serialized;
 * - writes use a 15 s timeout and are not cancelled by user navigation;
 * - every write is followed by a health check, and a failure latches the camera as hung;
 * - settings are validated against [SettingsCatalog] before anything is sent;
 * - folder names come from [Folder], so `Lock` instead of `LOCK` cannot happen.
 *
 * Thumbnails and future downloads are file reads and intentionally bypass the command queue.
 */
class CameraClient(
    baseUrl: String = DEFAULT_BASE_URL,
    engine: HttpClientEngine? = null,
    private val config: CameraClientConfig = CameraClientConfig(),
) : AutoCloseable {
    private val httpClient: HttpClient = HttpClient(engine ?: defaultHttpClientEngine()) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = config.readTimeoutMillis
        }
    }

    private val cgi = CgiClient(
        client = httpClient,
        baseUrl = baseUrl,
        defaultTimeoutMillis = config.readTimeoutMillis,
    )

    private val queue = CommandQueue(
        config = CommandQueueConfig(
            readTimeoutMillis = config.readTimeoutMillis,
            writeTimeoutMillis = config.writeTimeoutMillis,
            healthTimeoutMillis = config.healthTimeoutMillis,
        ),
        healthCheck = { cgi.isHealthy(config.healthTimeoutMillis) },
    )

    val baseUrl: String = baseUrl.trimEnd('/')

    /**
     * Whether [SafetyLevel.EXPERIMENTAL] settings may be written. It starts from
     * [CameraClientConfig.allowExperimental] and can follow the user's explicit opt-in while the
     * session is open; the catalog still validates names and values either way.
     */
    @Volatile
    private var allowExperimental: Boolean = config.allowExperimental

    /** True after a write whose health check failed. Further writes are refused until a restart. */
    val isHung: Boolean get() = queue.isHung

    /** Explicit opt-in/opt-out for experimental settings; never bypasses the catalog. */
    fun setExperimentalAllowed(allowed: Boolean) {
        allowExperimental = allowed
    }

    /**
     * Cheap liveness check: `Camera.Menu.EV` plus the preview status.
     *
     * Throws [CameraHungException] if the queue already latched a hung camera, and protocol or
     * transport exceptions otherwise.
     */
    suspend fun health(): Health {
        val evDocument = queue.read { cgi.get("Camera.Menu.EV", config.readTimeoutMillis) }
        val ev = evDocument.value("Camera.Menu.EV")
            ?: throw MalformedResponseException("health response had no Camera.Menu.EV")

        val preview = queue.read {
            cgi.get("Camera.Preview.MJPEG.status.*", config.readTimeoutMillis)
        }
        return Health(
            ev = ev,
            mode = preview.value("Camera.Preview.MJPEG.status.mode"),
            recording = preview.value("Camera.Preview.MJPEG.status.record")
                ?.equals("Recording", ignoreCase = true) == true,
        )
    }

    /** `action=get&property=Camera.*` mapped to typed fields; the raw map is kept for the UI. */
    suspend fun status(): CameraStatus {
        val document = queue.read { cgi.get("Camera.*", config.readTimeoutMillis) }
        return statusFrom(document.values)
    }

    /**
     * All clips in [folder], oldest first, following `count` pagination until a page is short.
     *
     * The camera silently returns the loop list for wrongly-cased folder names; [Folder] prevents
     * that by construction.
     */
    suspend fun list(folder: Folder = Folder.DEFAULT): List<Clip> {
        val clips = mutableListOf<Clip>()
        var from = 0
        var pages = 0
        while (pages < MAX_LIST_PAGES) {
            val listing = queue.read {
                cgi.dir(
                    property = folder.property,
                    count = config.pageSize,
                    from = from,
                    timeoutMillis = config.readTimeoutMillis,
                )
            }.let(DirXmlParser::parse)

            clips += listing.clips
            pages++
            if (listing.amount < config.pageSize ||
                listing.clips.size < config.pageSize ||
                listing.clips.isEmpty()
            ) {
                break
            }
            from += config.pageSize
        }
        return clips
    }

    /**
     * Extract the embedded JPEG from the MP4 `skip` box.
     *
     * `Range: bytes=0-65535`; the JPEG starts at file offset 60 and its length is the big-endian
     * u32 at offset 56. Decoding and caching stay on the platform. Returns `null` when the range is
     * unavailable or the header is inconsistent.
     */
    suspend fun thumbnail(clip: Clip): ByteArray? {
        val bytes = cgi.fetchRange(
            filePath = clip.path,
            start = 0,
            endInclusive = THUMBNAIL_RANGE_END,
            timeoutMillis = config.readTimeoutMillis,
        ) ?: return null
        if (bytes.size < THUMBNAIL_JPEG_OFFSET + 2) return null
        if (bytes[THUMBNAIL_JPEG_OFFSET] != 0xFF.toByte() ||
            bytes[THUMBNAIL_JPEG_OFFSET + 1] != 0xD8.toByte()
        ) {
            return null
        }

        val length = readBigEndianU32(bytes, THUMBNAIL_LENGTH_OFFSET)
        if (length <= 0) return null
        val end = THUMBNAIL_JPEG_OFFSET + length
        if (end > bytes.size) return null
        return bytes.copyOfRange(THUMBNAIL_JPEG_OFFSET, end.toInt())
    }

    /** Set the camera clock with `TimeSettings` (camera-local time, `YYYY$MM$DD$hh$mm$ss`). */
    suspend fun syncClock(now: LocalDateTime): WriteResult<Unit> =
        setSetting(SettingsCatalog.TIME_SETTINGS, CameraDateTimes.toTimeSettings(now))

    /**
     * Low-risk `Video=` operations.
     *
     * Recording restart resets `SoundRecord` to OFF, so if the microphone was on before a
     * [VideoOp.RECORD] toggle it is restored afterwards (safety rule 6).
     */
    suspend fun video(op: VideoOp): WriteResult<Unit> {
        if (op != VideoOp.RECORD) {
            return setSetting(SettingsCatalog.VIDEO, op.value)
        }

        val microphoneWasOn = runCatching {
            queue.read { cgi.get("Camera.Menu.SoundRecord", config.readTimeoutMillis) }
                .value("Camera.Menu.SoundRecord")
                ?.equals("ON", ignoreCase = true) == true
        }.getOrDefault(false)

        val result = setSetting(SettingsCatalog.VIDEO, op.value)
        if (result !is WriteResult.Applied || !microphoneWasOn) return result

        if (config.micReapplyDelayMillis > 0) delay(config.micReapplyDelayMillis)
        return setSetting(SettingsCatalog.VIDEO, VideoOp.UNMUTE.value)
    }

    /**
     * Write a setting, but only through [SettingsCatalog].
     *
     * - `FORBIDDEN` and unknown properties are refused without touching the network;
     * - `EXPERIMENTAL` is refused unless experimental writes were opted in (the config default
     *   or [setExperimentalAllowed]);
     * - `NEEDS_STANDBY` is refused while the camera reports `Recording`;
     * - values are validated against the catalog.
     */
    suspend fun setSetting(setting: Setting, value: String): WriteResult<Unit> {
        val catalogSetting = SettingsCatalog.find(setting.name)
            ?: return WriteResult.Refused(
                reason = "setting is not in the catalog",
                setting = setting.name,
                level = SafetyLevel.FORBIDDEN,
            )

        when (catalogSetting.level) {
            SafetyLevel.FORBIDDEN -> return WriteResult.Refused(
                reason = "setting is forbidden and must never be sent",
                setting = catalogSetting.name,
                level = catalogSetting.level,
            )

            SafetyLevel.EXPERIMENTAL -> if (!allowExperimental) {
                return WriteResult.Refused(
                    reason = "experimental settings are disabled",
                    setting = catalogSetting.name,
                    level = catalogSetting.level,
                )
            }

            SafetyLevel.NEEDS_STANDBY -> {
                val recording = recordingState()
                if (recording != false) {
                    return WriteResult.Refused(
                        reason = if (recording == true) {
                            "stop recording before changing this setting"
                        } else {
                            "cannot confirm that recording is stopped"
                        },
                        setting = catalogSetting.name,
                        level = catalogSetting.level,
                    )
                }
            }

            SafetyLevel.SAFE -> Unit
        }

        if (queue.isHung) {
            return WriteResult.Hung("camera already marked hung")
        }

        if (!catalogSetting.accepts(value)) {
            return WriteResult.Refused(
                reason = "value is not allowed for ${catalogSetting.name}",
                setting = catalogSetting.name,
                level = catalogSetting.level,
            )
        }

        return queue.write {
            cgi.set(catalogSetting.name, value, config.writeTimeoutMillis)
        }.dropValue()
    }

    fun clipUrl(clip: Clip): String {
        val path = if (clip.path.startsWith('/')) clip.path else "/${clip.path}"
        return baseUrl + path
    }

    fun liveUrl(stream: LiveStream = LiveStream.AV2): String {
        val withoutScheme = baseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
        val host = withoutScheme.substringBefore(':').ifBlank { "192.168.42.1" }
        return "rtsp://$host:554/${stream.path}"
    }

    override fun close() {
        httpClient.close()
    }

    private suspend fun recordingState(): Boolean? =
        runCatching {
            queue.read { cgi.get("Camera.Preview.MJPEG.status.record", config.readTimeoutMillis) }
                .value("Camera.Preview.MJPEG.status.record")
                ?.equals("Recording", ignoreCase = true)
        }.getOrNull()

    private fun statusFrom(values: Map<String, String>): CameraStatus = CameraStatus(
        firmware = values["Camera.Menu.FWversion"],
        model = values["Camera.Menu.Model"],
        sdStatus = values["Camera.Menu.SD0"],
        freeMb = values["Camera.Menu.CardInfo.Free"]?.toLongOrNull(),
        totalMb = values["Camera.Menu.CardInfo.Total"]?.toLongOrNull(),
        cameraTime = values["Camera.TimeDate"]?.let(::parseCameraDateTime),
        recording = values["Camera.Preview.MJPEG.status.record"]
            ?.equals("Recording", ignoreCase = true) == true,
        mode = values["Camera.Preview.MJPEG.status.mode"],
        recFileName = values["Camera.RecFileName"],
        dbFileNum = values["Camera.DBFileNum"],
        fileTotal = values["Camera.File.Total"]?.toLongOrNull(),
        isStreaming = values["Camera.Menu.IsStreaming"]
            ?.let { it.equals("YES", true) || it.equals("ON", true) || it == "1" } == true,
        ev = values["Camera.Menu.EV"],
        awb = values["Camera.Menu.AWB"],
        dateLogoStamp = values["Camera.Menu.DateLogoStamp"],
        soundRecord = values["Camera.Menu.SoundRecord"],
        videoClipTime = values["Camera.Menu.VideoClipTime"],
        raw = values,
    )

    private fun <T> WriteResult<T>.dropValue(): WriteResult<Unit> = when (this) {
        is WriteResult.Applied -> WriteResult.Applied(Unit)
        is WriteResult.Rejected -> this
        is WriteResult.Refused -> this
        is WriteResult.Hung -> this
        is WriteResult.Failed -> this
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "http://192.168.42.1"

        private const val THUMBNAIL_RANGE_END = 65_535L
        private const val THUMBNAIL_LENGTH_OFFSET = 56
        private const val THUMBNAIL_JPEG_OFFSET = 60
        private const val MAX_LIST_PAGES = 1_000

        private fun readBigEndianU32(bytes: ByteArray, offset: Int): Long {
            if (offset < 0 || offset + 4 > bytes.size) return -1
            return ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)
        }
    }
}
