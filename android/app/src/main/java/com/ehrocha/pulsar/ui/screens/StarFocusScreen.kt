/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.focus.FocusStar
import com.ehrocha.pulsar.focus.StarFocusAnalyzer
import com.ehrocha.pulsar.focus.StarFocusReading
import com.ehrocha.pulsar.focus.StarFocusStatus
import com.ehrocha.pulsar.focus.StarFocusTracker
import com.ehrocha.pulsar.transport.CameraTransport
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROI_PX = 32

private enum class Step(
    val labelRes: Int,
    val titleRes: Int,
    val icon: ImageVector,
) {
    PREP(R.string.star_focus_step_prep, R.string.star_focus_prep_title, Icons.Default.CameraAlt),
    AIM(R.string.star_focus_step_aim, R.string.star_focus_aim_title, Icons.Default.Star),
    FOCUS(R.string.star_focus_step_focus, R.string.star_focus_focus_title, Icons.Default.CenterFocusStrong),
    LOCK(R.string.star_focus_step_lock, R.string.star_focus_lock_title, Icons.Default.Lock),
}

/**
 * Star Focus Assist — 4-step wizard for nailing pinpoint focus on stars over
 * CCAPI before kicking off an astro run.
 *
 *  1. PREP   — instructions: lens to AF, camera mode dial to M (not Bulb).
 *  2. AIM    — detect several stars automatically, or select one manually.
 *  3. FOCUS  — stable relative star-size readout + manual drive-focus buttons.
 *  4. LOCK   — instructions: flip lens to MF if it has the switch; either way
 *              Pulsar will send `af: false` during the actual run.
 *
 * Live view starts on PREP→AIM and stops on AIM/FOCUS→LOCK (or screen exit).
 * Drive-focus on the RP requires the lens to be in AF mode on the body switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarFocusScreen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
) {
    // Star Focus runs on whichever Canon transport is active: CCAPI over
    // Wi-Fi, USB PTP, or PTP/IP. The wire-level live view + drive-focus
    // ops live behind the `CameraTransport` interface; this screen doesn't
    // care which one is on the other side.
    val canon by vm.canonCcapiTransport.collectAsState()
    val ptp by vm.ptpTransport.collectAsState()
    val ptpIp by vm.ptpIpTransport.collectAsState()
    val t: CameraTransport? = canon
        ?: ptp.takeIf { it?.supportsLiveView == true }
        ?: ptpIp.takeIf { it?.supportsLiveView == true }
    if (t == null) {
        // Defensive: the menu tile is gated, but if the user disconnects
        // mid-flow we bail.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var step by remember { mutableStateOf(Step.PREP) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var roiCenter by remember(t) { mutableStateOf<Pair<Int, Int>?>(null) }
    var focusReading by remember(t) { mutableStateOf(StarFocusReading()) }
    var comparisonGeneration by remember(t) { mutableIntStateOf(0) }
    var displayedSize by remember { mutableStateOf<IntSize?>(null) }
    var liveViewError by remember { mutableStateOf<String?>(null) }
    var framesPerSecond by remember { mutableDoubleStateOf(0.0) }

    // Live view runs only on AIM + FOCUS steps. Start on entry to AIM, stop
    // on transition out (or screen exit). Restarting on every recomposition
    // would thrash the camera, so we key on a derived flag.
    val liveViewActive = step == Step.AIM || step == Step.FOCUS
    LaunchedEffect(t, liveViewActive) {
        framesPerSecond = 0.0
        if (!liveViewActive) return@LaunchedEffect
        frame = null
        focusReading = StarFocusReading(manual = roiCenter != null)
        try {
            val started = t.startLiveView()
            if (!started) {
                liveViewError = t.lastLiveViewError ?: "start_failed"
                return@LaunchedEffect
            }
            liveViewError = null
            val meter = LiveViewFrameRate(SystemClock.elapsedRealtime())
            val tracker = StarFocusTracker()
            var trackedGeneration = comparisonGeneration
            var lastAnalysisAt = 0L
            // Sample independently of frame delivery so a stalled feed shows
            // zero FPS instead of retaining its last successful reading.
            launch {
                while (isActive) {
                    delay(1000)
                    val now = SystemClock.elapsedRealtime()
                    framesPerSecond = meter.sample(now)
                    if (now - lastAnalysisAt > 1500) {
                        tracker.reset()
                        focusReading = StarFocusReading(manual = roiCenter != null)
                    }
                }
            }
            while (isActive) {
                val cycleStart = SystemClock.elapsedRealtime()
                var receivedFrame = false
                val generation = comparisonGeneration
                val selection = roiCenter
                if (trackedGeneration != generation) {
                    tracker.reset()
                    trackedGeneration = generation
                    lastAnalysisAt = 0L
                }
                val analyzeNow = cycleStart - lastAnalysisAt >= 250
                val bytes = t.getLiveViewFrame()
                if (bytes != null && bytes.isNotEmpty()) {
                    // Analyze at most four previews per second. Detection and
                    // photometry share the decode worker, never the UI thread.
                    val (bmp, analysis) = withContext(Dispatchers.Default) {
                        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val result = if (b != null && analyzeNow) {
                            val pixels = IntArray(b.width * b.height)
                            b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
                            StarFocusAnalyzer.analyze(b.width, b.height, pixels, selection)
                        } else null
                        b to result
                    }
                    if (bmp != null) {
                        frame = bmp
                        meter.frameReceived()
                        receivedFrame = true
                    }
                    // A tap/reset may have happened while the worker ran.
                    // Never publish a measurement for the previous selection.
                    if (analysis != null && generation == comparisonGeneration) {
                        focusReading = tracker.update(analysis)
                        lastAnalysisAt = SystemClock.elapsedRealtime()
                    }
                }
                delay(liveViewPollingDelayMs(
                    SystemClock.elapsedRealtime() - cycleStart, receivedFrame,
                ))
            }
        } finally {
            // Runs on LOCK, PREP, screen exit, or transport change. Complete
            // cleanup despite effect cancellation so the camera gets its
            // display back rather than leaving host output enabled.
            withContext(NonCancellable) { t.stopLiveView() }
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.star_focus_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            StepIndicator(current = step, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    Step.PREP -> PrepStep(onNext = { step = Step.AIM })
                    Step.AIM -> AimStep(
                        transport = t,
                        frame = frame,
                        framesPerSecond = framesPerSecond,
                        liveViewError = liveViewError,
                        focusReading = focusReading,
                        automatic = roiCenter == null,
                        onAuto = {
                            roiCenter = null
                            comparisonGeneration++
                            focusReading = StarFocusReading()
                        },
                        onReset = {
                            comparisonGeneration++
                            focusReading = StarFocusReading(manual = roiCenter != null)
                        },
                        roiCenter = roiCenter,
                        displayedSize = displayedSize,
                        onDisplayedSizeChange = { displayedSize = it },
                        onRoiPicked = { x, y ->
                            roiCenter = x to y
                            comparisonGeneration++
                            focusReading = StarFocusReading(manual = true)
                        },
                        onBack = { step = Step.PREP },
                        onNext = { step = Step.FOCUS },
                    )
                    Step.FOCUS -> FocusStep(
                        transport = t,
                        frame = frame,
                        framesPerSecond = framesPerSecond,
                        liveViewError = liveViewError,
                        focusReading = focusReading,
                        automatic = roiCenter == null,
                        onAuto = {
                            roiCenter = null
                            comparisonGeneration++
                            focusReading = StarFocusReading()
                        },
                        onReset = {
                            comparisonGeneration++
                            focusReading = StarFocusReading(manual = roiCenter != null)
                        },
                        roiCenter = roiCenter,
                        displayedSize = displayedSize,
                        onDisplayedSizeChange = { displayedSize = it },
                        onBack = { step = Step.AIM },
                        onDone = { step = Step.LOCK },
                    )
                    Step.LOCK -> LockStep(
                        onBack = { step = Step.FOCUS },
                        onDone = onBack,
                    )
                }
            }
        }
    }
}

// ── Step UIs ─────────────────────────────────────────────────────────────

@Composable
private fun StepIndicator(current: Step, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Step.entries.forEachIndexed { idx, s ->
            val active = s == current
            val done = s.ordinal < current.ordinal
            val color = when {
                active -> MaterialTheme.colorScheme.primary
                done -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            }
            Surface(
                color = color.copy(alpha = if (active) 0.2f else 0.1f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        s.icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(s.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
            if (idx < Step.entries.size - 1) Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun PrepStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.star_focus_prep_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.star_focus_prep_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InstructionRow("1.", stringResource(R.string.star_focus_prep_step_af))
        InstructionRow("2.", stringResource(R.string.star_focus_prep_step_mode))
        InstructionRow("3.", stringResource(R.string.star_focus_prep_step_aim))
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text(stringResource(R.string.star_focus_btn_next)) }
    }
}

@Composable
private fun AimStep(
    transport: CameraTransport,
    frame: Bitmap?,
    framesPerSecond: Double,
    liveViewError: String?,
    focusReading: StarFocusReading,
    automatic: Boolean,
    onAuto: () -> Unit,
    onReset: () -> Unit,
    roiCenter: Pair<Int, Int>?,
    displayedSize: IntSize?,
    onDisplayedSizeChange: (IntSize) -> Unit,
    onRoiPicked: (Int, Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.star_focus_auto_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LiveViewBox(
            frame = frame,
            framesPerSecond = framesPerSecond,
            focusStars = focusReading.stars,
            liveViewError = liveViewError,
            roiCenter = roiCenter,
            onDisplayedSizeChange = onDisplayedSizeChange,
            onTap = { _, x, y -> onRoiPicked(x, y) },
            displayedSize = displayedSize,
            modifier = Modifier.weight(1f),
        )
        StarFocusReadout(focusReading, automatic, onAuto, onReset)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.star_focus_btn_back))
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(2f),
                enabled = focusReading.stars.size >= if (automatic) 3 else 1,
            ) { Text(stringResource(R.string.star_focus_btn_next)) }
        }
    }
}

@Composable
private fun FocusStep(
    transport: CameraTransport,
    frame: Bitmap?,
    framesPerSecond: Double,
    liveViewError: String?,
    focusReading: StarFocusReading,
    automatic: Boolean,
    onAuto: () -> Unit,
    onReset: () -> Unit,
    roiCenter: Pair<Int, Int>?,
    displayedSize: IntSize?,
    onDisplayedSizeChange: (IntSize) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LiveViewBox(
            frame = frame,
            framesPerSecond = framesPerSecond,
            focusStars = focusReading.stars,
            liveViewError = liveViewError,
            roiCenter = roiCenter,
            onDisplayedSizeChange = onDisplayedSizeChange,
            onTap = null,  // ROI locked during focus
            displayedSize = displayedSize,
            modifier = Modifier.weight(1f),
        )
        StarFocusReadout(focusReading, automatic, onAuto, onReset)
        // Drive-focus row
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.star_focus_near),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.star_focus_far),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FocusBtn("«««", "near3", transport, Modifier.weight(1f))
                    FocusBtn("««", "near2", transport, Modifier.weight(1f))
                    FocusBtn("«", "near1", transport, Modifier.weight(1f))
                    FocusBtn("»", "far1", transport, Modifier.weight(1f))
                    FocusBtn("»»", "far2", transport, Modifier.weight(1f))
                    FocusBtn("»»»", "far3", transport, Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.star_focus_btn_back))
            }
            Button(onClick = onDone, modifier = Modifier.weight(2f)) {
                Text(stringResource(R.string.star_focus_btn_focus_done))
            }
        }
    }
}

@Composable
private fun StarFocusReadout(
    reading: StarFocusReading,
    automatic: Boolean,
    onAuto: () -> Unit,
    onReset: () -> Unit,
) {
    val statusRes = when (reading.status) {
        StarFocusStatus.SEARCHING -> if (automatic) R.string.star_focus_search_auto else R.string.star_focus_search_manual
        StarFocusStatus.SAMPLING -> R.string.star_focus_sampling
        StarFocusStatus.UNSTABLE -> R.string.star_focus_unstable
        StarFocusStatus.IMPROVING -> R.string.star_focus_improving
        StarFocusStatus.NEAR_BEST -> R.string.star_focus_near_best
        StarFocusStatus.WORSENING -> R.string.star_focus_worsening
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (automatic) stringResource(R.string.star_focus_auto_count, reading.stars.size)
                    else stringResource(R.string.star_focus_manual),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!automatic) TextButton(onClick = onAuto) { Text(stringResource(R.string.star_focus_auto)) }
                TextButton(onClick = onReset) { Text(stringResource(R.string.star_focus_reset)) }
            }
            Text(stringResource(R.string.star_focus_metric), style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    reading.hfr?.let { stringResource(R.string.star_focus_metric_value, it) } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                reading.bestHfr?.let {
                    Text(stringResource(R.string.star_focus_best_value, it), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                stringResource(statusRes),
                style = MaterialTheme.typography.bodySmall,
                color = when (reading.status) {
                    StarFocusStatus.WORSENING -> MaterialTheme.colorScheme.error
                    StarFocusStatus.UNSTABLE -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                stringResource(R.string.star_focus_relative_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LockStep(onBack: () -> Unit, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.star_focus_lock_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.star_focus_lock_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InstructionRow("•", stringResource(R.string.star_focus_lock_step_switch))
        InstructionRow("•", stringResource(R.string.star_focus_lock_step_no_switch))
        InstructionRow("•", stringResource(R.string.star_focus_lock_step_avoid_ring))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.star_focus_btn_back))
            }
            Button(
                onClick = onDone,
                modifier = Modifier.weight(2f).height(48.dp),
            ) { Text(stringResource(R.string.star_focus_btn_done)) }
        }
    }
}

// ── Shared building blocks ───────────────────────────────────────────────

@Composable
private fun InstructionRow(bullet: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            bullet,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp).width(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LiveViewBox(
    frame: Bitmap?,
    framesPerSecond: Double,
    focusStars: List<FocusStar>,
    liveViewError: String?,
    roiCenter: Pair<Int, Int>?,
    onDisplayedSizeChange: (IntSize) -> Unit,
    onTap: ((Bitmap, Int, Int) -> Unit)?,
    displayedSize: IntSize?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .onSizeChanged { onDisplayedSizeChange(it) },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = frame
        when {
            liveViewError != null -> Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.star_focus_start_failed),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                if (liveViewError != "start_failed") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        liveViewError,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            bmp == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.star_focus_starting),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.star_focus_liveview_cd),
                    contentScale = ContentScale.Fit,
                    modifier = if (onTap != null) {
                        Modifier
                            .fillMaxSize()
                            .pointerInput(bmp.width, bmp.height) {
                                detectTapGestures { tap ->
                                    val (bx, by) = viewToBitmap(
                                        tap.x, tap.y,
                                        size.width.toFloat(), size.height.toFloat(),
                                        bmp.width, bmp.height,
                                    )
                                    if (bx in 0 until bmp.width && by in 0 until bmp.height) {
                                        onTap(bmp, bx, by)
                                    }
                                }
                            }
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
                val ds = displayedSize
                if (ds != null) {
                    val points = if (focusStars.isNotEmpty()) {
                        focusStars.map { it.x.toInt() to it.y.toInt() }
                    } else listOfNotNull(roiCenter)
                    for ((x, y) in points) {
                        RoiOverlay(
                            roiX = x, roiY = y,
                            bmpW = bmp.width, bmpH = bmp.height,
                            viewW = ds.width, viewH = ds.height,
                            markerColor = if (roiCenter == null) Color.Cyan else Color.Yellow,
                        )
                    }
                }
            }
        }
        if (bmp != null && liveViewError == null) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.star_focus_fps, framesPerSecond),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun BoxScope.RoiOverlay(
    roiX: Int, roiY: Int,
    bmpW: Int, bmpH: Int,
    viewW: Int, viewH: Int,
    markerColor: Color,
) {
    val scale = minOf(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH)
    val drawW = bmpW * scale
    val drawH = bmpH * scale
    val offX = (viewW - drawW) / 2f
    val offY = (viewH - drawH) / 2f
    val centerXpx = offX + roiX * scale
    val centerYpx = offY + roiY * scale
    val boxPx = ROI_PX * scale
    val density = LocalDensity.current
    Surface(
        color = Color.Transparent,
        border = BorderStroke(2.dp, markerColor),
        shape = CircleShape,
        modifier = Modifier
            // Anchor top-left so the offset is absolute. Without this the
            // parent Box's contentAlignment=Center placed the circle at the
            // centre first, then the absolute offset pushed it bottom-right.
            .align(Alignment.TopStart)
            .offset(
                x = with(density) { (centerXpx - boxPx / 2f).toDp() },
                y = with(density) { (centerYpx - boxPx / 2f).toDp() },
            )
            .size(with(density) { boxPx.toDp() }),
    ) {}
}

@Composable
private fun FocusBtn(
    label: String,
    action: String,
    transport: CameraTransport,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    OutlinedButton(
        onClick = { scope.launch { transport.driveFocus(action) } },
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

private fun viewToBitmap(
    tx: Float, ty: Float,
    viewW: Float, viewH: Float,
    bmpW: Int, bmpH: Int,
): Pair<Int, Int> {
    val scale = minOf(viewW / bmpW, viewH / bmpH)
    val drawW = bmpW * scale
    val drawH = bmpH * scale
    val offX = (viewW - drawW) / 2f
    val offY = (viewH - drawH) / 2f
    return ((tx - offX) / scale).toInt() to ((ty - offY) / scale).toInt()
}
