package dev.halcamera.camera

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceProfiles
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MandatoryStreamCombination
import android.hardware.camera2.params.MultiResolutionStreamConfigurationMap
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads CameraCharacteristics of every public camera id, and of the physical cameras behind each logical one,
 * into a [CameraProbeSnapshot]. Hidden ids are never guessed (the same rule as [CameraEndpointResolver]) and no
 * camera is opened, so this can run while LIVE holds the device.
 *
 * Every section is read under its own try/catch: a vendor HAL that throws on one key must not hide the rest
 * of the report, and the failure is recorded as a row so the export says which key it was.
 */
class CameraProbeReader(private val manager: CameraManager, private val extraDevice: List<ProbeRow> = emptyList()) {

    fun read(): CameraProbeSnapshot {
        val errors = ArrayList<String>()
        val ids = try { manager.cameraIdList.toList() } catch (e: Exception) { errors += "cameraIdList: ${e.message}"; emptyList() }
        // Lens roles come from the shared enumeration rather than from a second inference here. Only that one applies
        // LensRoles.dedupeMain across the rear cameras, so inferring locally would let PROBE call two lenses Wide
        // while LIVE and BENCHMARK call one of them Wide and the other unplaced. The whole endpoint is kept rather
        // than the role alone because the title also carries the 35 mm equivalent for the cameras the role leaves
        // unnamed, and that figure is computed by the same enumeration.
        val endpoints = try {
            CameraEndpointResolver(manager).resolve().associateBy { it.key }
        } catch (e: Exception) { errors += "endpoint roles: ${e.message}"; emptyMap() }
        val cameras = ArrayList<CameraProbeEntry>()
        ids.forEach { id ->
            val chars = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { errors += "camera $id: ${e.message}"; return@forEach }
            cameras += entry(id, null, chars, endpoints)
            if (Build.VERSION.SDK_INT >= 28) {
                chars.physicalCameraIds.filter { it !in ids }.sorted().forEach { pid ->
                    val pc = try { manager.getCameraCharacteristics(pid) } catch (e: Exception) { errors += "physical $id/$pid: ${e.message}"; return@forEach }
                    cameras += entry(pid, id, pc, endpoints)
                }
            }
        }
        return CameraProbeSnapshot(
            capturedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()),
            device = device() + extraDevice,
            cameras = cameras,
            errors = errors
        )
    }

    private fun device(): List<ProbeRow> = listOf(
        ProbeRow("Manufacturer", Build.MANUFACTURER),
        ProbeRow("Model", Build.MODEL),
        ProbeRow("Device", Build.DEVICE),
        ProbeRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
        ProbeRow("Build", Build.DISPLAY),
        ProbeRow("Security patch", Build.VERSION.SECURITY_PATCH),
        ProbeRow("SoC", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE),
        ProbeRow("ABIs", Build.SUPPORTED_ABIS.joinToString(", "))
    )

    private fun entry(id: String, physicalOf: String?, c: CameraCharacteristics, endpoints: Map<String, CameraEndpoint>): CameraProbeEntry {
        val facing = c[CameraCharacteristics.LENS_FACING]
        val level = MetadataNames.name("INFO_SUPPORTED_HARDWARE_LEVEL_", c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL])
        // The shared label carries the id, so PROBE names a camera the same way LIVE, BENCHMARK and CTS do. The
        // hardware level and the physical marker are PROBE's own additions and follow the parenthesis.
        val key = physicalOf?.let { "$it.$id" } ?: id
        val endpoint = endpoints[key]
        val role = endpoint?.role ?: LensRole.UNKNOWN
        val title = ProbeTitle.of(
            key, role, facing, physical = physicalOf != null, hardwareLevel = level,
            equivalentFocalMm = endpoint?.equivalentFocalMm
        )
        val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val sections = ArrayList<ProbeSection>()
        sections += guarded("Identity") { identity(id, physicalOf, c) }
        sections += guarded("Capabilities") { capabilities(c) }
        sections += guarded("Sensor") { sensor(c) }
        sections += guarded("Lens") { lens(c) }
        sections += guarded("Control") { control(c) }
        sections += guarded("Processing") { processing(c) }
        sections += guarded("Request") { request(c) }
        sections += guarded("Request · result keys") { requestResultKeys(c) }
        if (Build.VERSION.SDK_INT >= 28) sections += guarded("Session keys") { sessionKeys(c) }
        if (Build.VERSION.SDK_INT >= 29) sections += guarded("Mandatory stream combinations") { mandatoryCombinations(c) }
        if (map != null) {
            sections += guarded("Streams · PRIVATE (SurfaceTexture)") { surfaceTextureStreams(map) }
            sections += guarded("Streams · PRIVATE (MediaRecorder)") { mediaRecorderStreams(map) }
            val formats = try { map.outputFormats.toList() } catch (_: Exception) { emptyList() }
            formats.sortedBy { formatName(it) }.forEach { format ->
                sections += guarded("Streams · ${formatName(format)}") { formatStreams(map, format) }
            }
            sections += guarded("High speed video") { highSpeed(map) }
            sections += guarded("Reprocessing inputs") { inputs(map) }
        } else {
            sections += ProbeSection("Streams", listOf(ProbeRow("!", "SCALER_STREAM_CONFIGURATION_MAP is null")))
        }
        sections += guarded("All characteristics") { rawDump(c) }
        // No id prefix: the label already opens with the key, and prefixing it again read "0 · Camera · 0 (...)".
        return CameraProbeEntry(id, physicalOf, title, sections)
    }

    private fun guarded(title: String, rows: () -> List<ProbeRow>): ProbeSection =
        ProbeSection(title, try { rows() } catch (e: Exception) { listOf(ProbeRow("!", "${e.javaClass.simpleName}: ${e.message}")) })

    // ---- sections ----

    private fun identity(id: String, physicalOf: String?, c: CameraCharacteristics): List<ProbeRow> {
        val rows = ArrayList<ProbeRow>()
        rows += ProbeRow("Camera ID", id)
        if (physicalOf != null) rows += ProbeRow("Physical camera of", physicalOf)
        rows += ProbeRow("Facing", facingLabel(c[CameraCharacteristics.LENS_FACING]))
        rows += ProbeRow("Hardware level", names("INFO_SUPPORTED_HARDWARE_LEVEL_", listOfNotNull(c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]).toIntArray()))
        if (Build.VERSION.SDK_INT >= 28) {
            val physical = c.physicalCameraIds.sorted()
            rows += ProbeRow("Logical multi-camera", if (physical.isEmpty()) "no" else "yes · physical ids ${physical.joinToString()}")
        }
        rows += ProbeRow("Sensor orientation", "${c[CameraCharacteristics.SENSOR_ORIENTATION] ?: "—"}°")
        rows += ProbeRow("Timestamp source", MetadataNames.name("SENSOR_INFO_TIMESTAMP_SOURCE_", c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE]))
        if (Build.VERSION.SDK_INT >= 34) {
            rows += ProbeRow("Readout timestamp", MetadataNames.name("SENSOR_READOUT_TIMESTAMP_", c[CameraCharacteristics.SENSOR_READOUT_TIMESTAMP]))
        }
        rows += ProbeRow("Flash", if (c[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true) "available" else "none")
        if (Build.VERSION.SDK_INT >= 28) {
            rows += ProbeRow("Pose reference", MetadataNames.name("LENS_POSE_REFERENCE_", c[CameraCharacteristics.LENS_POSE_REFERENCE]))
        }
        if (Build.VERSION.SDK_INT >= 31 && physicalOf == null) {
            val extensions = try {
                names("EXTENSION_", manager.getCameraExtensionCharacteristics(id).supportedExtensions.toIntArray(), CameraExtensionCharacteristics::class.java)
            } catch (e: Exception) { "unreadable: ${e.message}" }
            rows += ProbeRow("Extensions", extensions)
        }
        if (Build.VERSION.SDK_INT >= 35 && physicalOf == null) {
            val setup = try { manager.isCameraDeviceSetupSupported(id) } catch (_: Exception) { null }
            rows += ProbeRow("CameraDeviceSetup", when (setup) { true -> "supported"; false -> "not supported"; null -> "—" })
        }
        return rows
    }

    private fun capabilities(c: CameraCharacteristics): List<ProbeRow> {
        val present = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toList().orEmpty()
        val all = MetadataNames.of("REQUEST_AVAILABLE_CAPABILITIES_")
        return all.entries.sortedBy { it.key }.map { (value, name) -> ProbeRow(name, if (value in present) "✓" else "✗") } +
            present.filter { it !in all }.sorted().map { ProbeRow("$it (vendor)", "✓") }
    }

    private fun sensor(c: CameraCharacteristics): List<ProbeRow> {
        val rows = ArrayList<ProbeRow>()
        c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.let { rows += ProbeRow("Physical size", String.format(Locale.US, "%.2f x %.2f mm", it.width, it.height)) }
        c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]?.let { rows += ProbeRow("Pixel array", it.toString()) }
        c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { rows += ProbeRow("Active array", "${it.width()}x${it.height()} @ (${it.left},${it.top})") }
        c[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE]?.let { rows += ProbeRow("Pre-correction array", "${it.width()}x${it.height()} @ (${it.left},${it.top})") }
        rows += ProbeRow("Color filter", MetadataNames.name("SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_", c[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]))
        c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.let { rows += ProbeRow("ISO range", "${it.lower} – ${it.upper}") }
        c[CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY]?.let { rows += ProbeRow("Max analog ISO", it.toString()) }
        c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.let { rows += ProbeRow("Exposure range", "${ProbeFormat.exposureNs(it.lower)} – ${ProbeFormat.exposureNs(it.upper)}") }
        c[CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION]?.let { rows += ProbeRow("Max frame duration", ProbeFormat.exposureNs(it)) }
        c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL]?.let { rows += ProbeRow("White level", it.toString()) }
        c[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]?.let { p -> rows += ProbeRow("Black level", (0 until 4).joinToString(", ") { p.getOffsetForIndex(it % 2, it / 2).toString() }) }
        rows += ProbeRow("Lens shading applied", c[CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED]?.toString() ?: "—")
        c[CameraCharacteristics.SENSOR_AVAILABLE_TEST_PATTERN_MODES]?.let { rows += ProbeRow("Test patterns", names("SENSOR_TEST_PATTERN_MODE_", it)) }
        rows += ProbeRow("Reference illuminant 1", c[CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1]?.let { MetadataNames.name("SENSOR_REFERENCE_ILLUMINANT1_", it) } ?: "—")
        return rows
    }

    private fun lens(c: CameraCharacteristics): List<ProbeRow> {
        val rows = ArrayList<ProbeRow>()
        c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.let { rows += ProbeRow("Focal lengths", it.joinToString(", ") { f -> String.format(Locale.US, "%.2f mm", f) }) }
        val focal = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.minOrNull()
        val size = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
        LensRoles.equivalentFocalMm(focal, size?.width, size?.height)?.let { rows += ProbeRow("35mm equivalent", String.format(Locale.US, "%.1f mm · %s", it, LensRoles.roleFor(it).name)) }
        c[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]?.let { rows += ProbeRow("Apertures", it.joinToString(", ") { f -> String.format(Locale.US, "f/%.2f", f) }) }
        c[CameraCharacteristics.LENS_INFO_AVAILABLE_FILTER_DENSITIES]?.let { rows += ProbeRow("Filter densities", it.joinToString(", ")) }
        c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]?.let { rows += ProbeRow("Min focus distance", if (it == 0f) "0 (fixed focus)" else String.format(Locale.US, "%.2f diopters (%.1f cm)", it, 100f / it)) }
        c[CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE]?.let { rows += ProbeRow("Hyperfocal distance", String.format(Locale.US, "%.2f diopters", it)) }
        rows += ProbeRow("Focus calibration", MetadataNames.name("LENS_INFO_FOCUS_DISTANCE_CALIBRATION_", c[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION]))
        c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.let { rows += ProbeRow("OIS modes", names("LENS_OPTICAL_STABILIZATION_MODE_", it)) }
        if (Build.VERSION.SDK_INT >= 28) {
            c[CameraCharacteristics.LENS_INTRINSIC_CALIBRATION]?.let { rows += ProbeRow("Intrinsic calibration", it.joinToString(", ")) }
            c[CameraCharacteristics.LENS_DISTORTION]?.let { rows += ProbeRow("Distortion", it.joinToString(", ")) }
        }
        return rows
    }

    private fun control(c: CameraCharacteristics): List<ProbeRow> {
        val rows = ArrayList<ProbeRow>()
        c[CameraCharacteristics.CONTROL_AVAILABLE_MODES]?.let { rows += ProbeRow("Control modes", names("CONTROL_MODE_", it)) }
        c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]?.let { rows += ProbeRow("AE modes", names("CONTROL_AE_MODE_", it)) }
        c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.let { rows += ProbeRow("AE FPS ranges", it.joinToString(", ") { r -> "[${r.lower},${r.upper}]" }) }
        c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]?.let { r ->
            val step = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]
            rows += ProbeRow("AE compensation", "${r.lower} – ${r.upper}" + (step?.let { " · step $it EV" } ?: ""))
        }
        rows += ProbeRow("AE lock", c[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE]?.toString() ?: "—")
        c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.let { rows += ProbeRow("AF modes", names("CONTROL_AF_MODE_", it)) }
        c[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]?.let { rows += ProbeRow("AWB modes", names("CONTROL_AWB_MODE_", it)) }
        rows += ProbeRow("AWB lock", c[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE]?.toString() ?: "—")
        rows += ProbeRow("Max regions AE/AF/AWB", listOf(
            c[CameraCharacteristics.CONTROL_MAX_REGIONS_AE], c[CameraCharacteristics.CONTROL_MAX_REGIONS_AF], c[CameraCharacteristics.CONTROL_MAX_REGIONS_AWB]
        ).joinToString(" / ") { it?.toString() ?: "—" })
        c[CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES]?.let { rows += ProbeRow("Scene modes", names("CONTROL_SCENE_MODE_", it)) }
        c[CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS]?.let { rows += ProbeRow("Effects", names("CONTROL_EFFECT_MODE_", it)) }
        c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.let { rows += ProbeRow("Video stabilization", names("CONTROL_VIDEO_STABILIZATION_MODE_", it)) }
        if (Build.VERSION.SDK_INT >= 30) {
            c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.let { rows += ProbeRow("Zoom ratio range", "${it.lower} – ${it.upper}") }
        }
        c[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM]?.let { rows += ProbeRow("Max digital zoom", it.toString()) }
        rows += ProbeRow("Cropping type", MetadataNames.name("SCALER_CROPPING_TYPE_", c[CameraCharacteristics.SCALER_CROPPING_TYPE]))
        c[CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE]?.let { rows += ProbeRow("Post-RAW boost", "${it.lower} – ${it.upper}") }
        if (Build.VERSION.SDK_INT >= 34) {
            c[CameraCharacteristics.CONTROL_AVAILABLE_SETTINGS_OVERRIDES]?.let { rows += ProbeRow("Settings overrides", names("CONTROL_SETTINGS_OVERRIDE_", it)) }
            c[CameraCharacteristics.CONTROL_AUTOFRAMING_AVAILABLE]?.let { rows += ProbeRow("Autoframing", it.toString()) }
        }
        return rows
    }

    private fun processing(c: CameraCharacteristics): List<ProbeRow> {
        val rows = ArrayList<ProbeRow>()
        c[CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES]?.let { rows += ProbeRow("Edge modes", names("EDGE_MODE_", it)) }
        c[CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES]?.let { rows += ProbeRow("Noise reduction", names("NOISE_REDUCTION_MODE_", it)) }
        c[CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES]?.let { rows += ProbeRow("Hot pixel modes", names("HOT_PIXEL_MODE_", it)) }
        c[CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES]?.let { rows += ProbeRow("Tonemap modes", names("TONEMAP_MODE_", it)) }
        c[CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS]?.let { rows += ProbeRow("Tonemap curve points", it.toString()) }
        c[CameraCharacteristics.SHADING_AVAILABLE_MODES]?.let { rows += ProbeRow("Shading modes", names("SHADING_MODE_", it)) }
        c[CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES]?.let { rows += ProbeRow("Aberration modes", names("COLOR_CORRECTION_ABERRATION_MODE_", it)) }
        if (Build.VERSION.SDK_INT >= 28) {
            c[CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES]?.let { rows += ProbeRow("Distortion correction", names("DISTORTION_CORRECTION_MODE_", it)) }
            c[CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES]?.let { rows += ProbeRow("OIS data modes", names("STATISTICS_OIS_DATA_MODE_", it)) }
        }
        c[CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES]?.let { rows += ProbeRow("Face detect modes", names("STATISTICS_FACE_DETECT_MODE_", it)) }
        c[CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT]?.let { rows += ProbeRow("Max face count", it.toString()) }
        c[CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES]?.let { rows += ProbeRow("Lens shading map", names("STATISTICS_LENS_SHADING_MAP_MODE_", it)) }
        c[CameraCharacteristics.STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES]?.let { rows += ProbeRow("Hot pixel map", it.joinToString(", ")) }
        return rows
    }

    private fun request(c: CameraCharacteristics): List<ProbeRow> {
        val rows = ArrayList<ProbeRow>()
        rows += ProbeRow("Max output streams", "raw ${c[CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW] ?: "—"} · " +
            "proc ${c[CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC] ?: "—"} · " +
            "proc stalling ${c[CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING] ?: "—"}")
        rows += ProbeRow("Max input streams", c[CameraCharacteristics.REQUEST_MAX_NUM_INPUT_STREAMS]?.toString() ?: "—")
        rows += ProbeRow("Pipeline max depth", c[CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH]?.toString() ?: "—")
        rows += ProbeRow("Partial result count", c[CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT]?.toString() ?: "—")
        rows += ProbeRow("Sync max latency", MetadataNames.name("SYNC_MAX_LATENCY_", c[CameraCharacteristics.SYNC_MAX_LATENCY]))
        rows += ProbeRow("Key counts", "characteristics ${c.keys.size} · request ${c.availableCaptureRequestKeys.size} · " +
            "result ${c.availableCaptureResultKeys.size}")
        return rows
    }

    /**
     * Names only: a characteristics probe knows which keys a CaptureRequest may carry and a CaptureResult will
     * return, not their values, which exist per request. Long (100+ names each), so its own folded section.
     */
    private fun requestResultKeys(c: CameraCharacteristics): List<ProbeRow> = listOf(
        ProbeRow("Request keys", ProbeFormat.list(c.availableCaptureRequestKeys.map { it.name })),
        ProbeRow("Result keys", ProbeFormat.list(c.availableCaptureResultKeys.map { it.name }))
    )

    /** Long on vendor HALs (Snapdragon lists 60+ session keys), so this is its own section and starts folded. */
    @androidx.annotation.RequiresApi(28)
    private fun sessionKeys(c: CameraCharacteristics): List<ProbeRow> = listOf(
        ProbeRow("Session keys", ProbeFormat.list(c.availableSessionKeys.map { it.name })),
        ProbeRow("Physical request keys", ProbeFormat.list(c.availablePhysicalCameraRequestKeys.map { it.name }))
    )

    private fun surfaceTextureStreams(map: StreamConfigurationMap): List<ProbeRow> {
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        return sizes.sortedWith(bySizeDesc).map { s ->
            ProbeFormat.streamRow(s.width, s.height,
                map.getOutputMinFrameDuration(SurfaceTexture::class.java, s), map.getOutputStallDuration(SurfaceTexture::class.java, s))
        }
    }

    private fun mediaRecorderStreams(map: StreamConfigurationMap): List<ProbeRow> {
        val sizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty()
        return sizes.sortedWith(bySizeDesc).map { s ->
            ProbeFormat.streamRow(s.width, s.height,
                map.getOutputMinFrameDuration(MediaRecorder::class.java, s), map.getOutputStallDuration(MediaRecorder::class.java, s))
        }
    }

    private fun formatStreams(map: StreamConfigurationMap, format: Int): List<ProbeRow> {
        val sizes = map.getOutputSizes(format).orEmpty()
        val rows = sizes.sortedWith(bySizeDesc).map { s ->
            ProbeFormat.streamRow(s.width, s.height, map.getOutputMinFrameDuration(format, s), map.getOutputStallDuration(format, s))
        }
        val high = map.getHighResolutionOutputSizes(format).orEmpty()
        return rows + high.sortedWith(bySizeDesc).map { s -> ProbeRow("${s.width}x${s.height}", "high resolution (BURST_CAPTURE not guaranteed)") }
    }

    private fun highSpeed(map: StreamConfigurationMap): List<ProbeRow> {
        val sizes = map.highSpeedVideoSizes.orEmpty()
        return sizes.sortedWith(bySizeDesc).map { s ->
            ProbeRow("${s.width}x${s.height}", map.getHighSpeedVideoFpsRangesFor(s).joinToString(", ") { "[${it.lower},${it.upper}]" })
        }
    }

    private fun inputs(map: StreamConfigurationMap): List<ProbeRow> {
        val inputs = map.inputFormats ?: IntArray(0)
        return inputs.map { input ->
            val sizes = map.getInputSizes(input).orEmpty().sortedWith(bySizeDesc).joinToString(", ") { "${it.width}x${it.height}" }
            val outputs = (map.getValidOutputFormatsForInput(input) ?: IntArray(0)).joinToString(", ") { formatName(it) }
            ProbeRow(formatName(input), "sizes $sizes\noutputs $outputs")
        }
    }

    /**
     * Every key the HAL reports, printed raw. The named sections above decode the common ones; this one is the
     * complete record, so a vendor key or a key added in a later API level still reaches the file.
     */
    private fun rawDump(c: CameraCharacteristics): List<ProbeRow> =
        c.keys.sortedBy { it.name }.map { key ->
            val value = try { formatValue(c.get(key)) } catch (e: Exception) { "unreadable: ${e.message}" }
            ProbeRow(key.name, value)
        }

    private fun formatValue(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.joinToString(", ", "[", "]")
        is LongArray -> v.joinToString(", ", "[", "]")
        is FloatArray -> v.joinToString(", ", "[", "]")
        is DoubleArray -> v.joinToString(", ", "[", "]")
        is BooleanArray -> v.joinToString(", ", "[", "]")
        is ByteArray -> "byte[${v.size}]"
        is Array<*> -> {
            // Short scalars stay on one line; a list of long descriptions is unreadable joined by commas.
            val items = v.map { formatValue(it) }
            if (items.sumOf { it.length } > 80) items.joinToString("\n") else items.joinToString(", ", "[", "]")
        }
        is StreamConfigurationMap -> "(see STREAMS sections)"
        else -> formatObject(v)
    }

    /**
     * Platform value classes that do not override toString(). Each is named by API level so the reader still
     * compiles against minSdk; anything else unknown prints its class name instead of an identity hash.
     */
    private fun formatObject(v: Any): String {
        if (Build.VERSION.SDK_INT >= 29 && v is MandatoryStreamCombination) return v.description.toString()
        if (Build.VERSION.SDK_INT >= 33 && v is DynamicRangeProfiles) {
            return v.supportedProfiles.sorted().joinToString(", ") { longName(it, DynamicRangeProfiles::class.java) }
        }
        if (Build.VERSION.SDK_INT >= 34 && v is ColorSpaceProfiles) {
            return v.getSupportedColorSpaces(ImageFormat.UNKNOWN).joinToString(", ") { it.name }
        }
        if (Build.VERSION.SDK_INT >= 31 && v is MultiResolutionStreamConfigurationMap) {
            return v.outputFormats.joinToString("\n") { f -> "${formatName(f)}: " + v.getOutputInfo(f).joinToString(", ") { "${it.width}x${it.height}@${it.physicalCameraId}" } }
        }
        val text = v.toString()
        val identity = "${v.javaClass.name}@"
        return if (text.startsWith(identity)) v.javaClass.simpleName else text
    }

    /** Value of a `public static final long` on [owner] by name, for classes whose constants are longs. */
    private fun longName(value: Long, owner: Class<*>): String =
        owner.fields.firstOrNull { Modifier.isStatic(it.modifiers) && it.type == Long::class.javaPrimitiveType && it.getLong(null) == value }?.name
            ?: value.toString()

    @androidx.annotation.RequiresApi(29)
    private fun mandatoryCombinations(c: CameraCharacteristics): List<ProbeRow> {
        val rows = ArrayList<ProbeRow>()
        fun add(label: String, combos: Array<MandatoryStreamCombination>?) {
            if (combos == null) return
            rows += ProbeRow(label, ProbeFormat.list(combos.map { it.description.toString() }))
        }
        add("Regular", c[CameraCharacteristics.SCALER_MANDATORY_STREAM_COMBINATIONS])
        if (Build.VERSION.SDK_INT >= 30) add("Concurrent", c[CameraCharacteristics.SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS])
        if (Build.VERSION.SDK_INT >= 31) add("Maximum resolution", c[CameraCharacteristics.SCALER_MANDATORY_MAXIMUM_RESOLUTION_STREAM_COMBINATIONS])
        if (Build.VERSION.SDK_INT >= 33) {
            add("Ten-bit output", c[CameraCharacteristics.SCALER_MANDATORY_TEN_BIT_OUTPUT_STREAM_COMBINATIONS])
            add("Preview stabilization", c[CameraCharacteristics.SCALER_MANDATORY_PREVIEW_STABILIZATION_OUTPUT_STREAM_COMBINATIONS])
            add("Stream use case", c[CameraCharacteristics.SCALER_MANDATORY_USE_CASE_STREAM_COMBINATIONS])
        }
        return rows
    }

    // ---- helpers ----

    /** Every platform value for the enum behind [prefix], marked present or absent (see [ProbeFormat.inventory]). */
    private fun names(prefix: String, values: IntArray, owner: Class<*> = CameraMetadata::class.java): String =
        ProbeFormat.inventory(MetadataNames.of(prefix, owner), values.toList())

    /** Same three words the camera label uses, so the Identity row and the picker never disagree. */
    private fun facingLabel(facing: Int?): String =
        CameraLabel.facing(LensRole.UNKNOWN, facing) ?: "facing ${facing ?: "—"}"

    private fun formatName(format: Int): String =
        MetadataNames.of("", ImageFormat::class.java)[format]
            ?: MetadataNames.of("", PixelFormat::class.java)[format]?.let { "PixelFormat.$it" }
            ?: "0x${Integer.toHexString(format)}"

    private val bySizeDesc = compareByDescending<Size> { it.width.toLong() * it.height }.thenByDescending { it.width }
}

/**
 * Enum labels from the platform's own constant names, so a value added in a later API level is still named on
 * a device that has it, and no table of magic numbers has to be kept in step with CameraMetadata.
 */
object MetadataNames {
    private val cache = HashMap<String, Map<Int, String>>()

    fun of(prefix: String, owner: Class<*> = CameraMetadata::class.java): Map<Int, String> = synchronized(cache) {
        cache.getOrPut("${owner.name}#$prefix") {
            // The last word of the prefix comes back when the remainder is only a number: LEVEL_3, not "3".
            val lastWord = prefix.trimEnd('_').substringAfterLast('_', "")
            owner.fields
                .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith(prefix) }
                // Range markers such as CONTROL_SCENE_MODE_DEVICE_CUSTOM_START are not values.
                .filterNot { it.name.endsWith("_START") || it.name.endsWith("_END") }
                .sortedBy { it.name }
                .associate { field ->
                    val stripped = field.name.removePrefix(prefix)
                    field.getInt(null) to if (stripped.first().isDigit() && lastWord.isNotEmpty()) "${lastWord}_$stripped" else stripped
                }
        }
    }

    fun name(prefix: String, value: Int?, owner: Class<*> = CameraMetadata::class.java): String =
        if (value == null) "—" else of(prefix, owner)[value] ?: value.toString()
}
