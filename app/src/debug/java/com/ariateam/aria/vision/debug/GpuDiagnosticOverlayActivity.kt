package com.ariateam.aria.vision.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ariateam.aria.ui.theme.AriaNavy
import com.ariateam.aria.ui.theme.AriaPanel
import com.ariateam.aria.ui.theme.AriaSlate
import com.ariateam.aria.ui.theme.AriaTheme
import java.util.Locale

class GpuDiagnosticOverlayActivity : ComponentActivity() {

    companion object {
        @Volatile
        var currentActivity: GpuDiagnosticOverlayActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivity = this
        setContent {
            AriaTheme {
                val data = GpuDiagnosticStateHolder.liveData.value
                GpuDiagnosticScreen(data = data)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        currentActivity = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentActivity === this) {
            currentActivity = null
        }
    }

    fun updateData(newData: GpuDiagnosticData) {
        GpuDiagnosticStateHolder.currentData = newData
    }
}

@Composable
fun GpuDiagnosticScreen(data: GpuDiagnosticData?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaNavy)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (data == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Awaiting Diagnostic Data...",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            return@Box
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Card 1: Header + Model Spec + Execution Config
            HeaderAndModelCard(data)

            // Card 2: Primary Performance (Large p50) + Delegation & Partition Breakdown
            PerformanceAndDelegationCard(data)

            // Card 3: Numerical Parity vs CPU Reference
            ParityCard(data)

            // Card 4: Observed Runtime Logcat (Verbatim, readable wrapping) + Root Cause / Resolution
            LogcatAndSummaryCard(data)
        }
    }
}

@Composable
private fun HeaderAndModelCard(data: GpuDiagnosticData) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AriaPanel),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            // Header Row 1: Diagnostic title + Synthetic disclaimer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "BENCHMARK DIAGNOSTIC",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "SYNTHETIC RNG 42 • NOT LIVE CAMERA",
                    color = AriaSlate,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            // Header Row 2: Stage Badge + Device / OS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(data.stageBadgeColor))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (data.stage == "BEFORE") "BEFORE: PARTIAL GPU DELEGATION" else "AFTER: FULL GPU DELEGATION",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    text = "${data.deviceModel} • ${data.androidVersion}",
                    color = AriaSlate,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Model basename + size
            Text(
                text = "Model: ${data.modelName} (${String.format(Locale.US, "%.2f MB", data.modelSizeBytes / 1_000_000.0)})",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            // Hash prefix + FP32 config
            val hashPrefix = if (data.modelSha256.length >= 16) data.modelSha256.take(16) else data.modelSha256
            Text(
                text = "SHA-256: $hashPrefix... • FP32 Sustained • 1w+5s",
                color = Color(0xFF38BDF8),
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun PerformanceAndDelegationCard(data: GpuDiagnosticData) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AriaPanel),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                text = "MEASURED INFERENCE LATENCY & GRAPH DELEGATION",
                color = Color(0xFF38BDF8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(3.dp))

            // Large p50 Latency Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("GPU p50 LATENCY", color = AriaSlate, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format(Locale.US, "%.2f ms", data.p50LatencyMs),
                        color = if (data.p50LatencyMs < 200.0) Color(0xFF10B981) else Color(0xFFEF4444),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format(Locale.US, "Range: %.2f - %.2f ms", data.minLatencyMs, data.maxLatencyMs),
                        color = AriaSlate,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    val sampleStr = data.sampleTimingsMs.joinToString(", ") { String.format(Locale.US, "%.1f", it) }
                    Text(
                        text = "Runs: [$sampleStr] ms",
                        color = AriaSlate,
                        fontSize = 7.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(5.dp))

            // 3 Equal-weighted Columns for Graph Delegation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Column 1: GPU Nodes
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF0F172A), RoundedCornerShape(4.dp))
                        .padding(vertical = 3.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("GPU NODES", color = AriaSlate, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${data.gpuDelegatedNodes} / ${data.totalNodes}",
                            color = if (data.gpuDelegatedNodes == data.totalNodes) Color(0xFF10B981) else Color(0xFFEF4444),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                        val pct = if (data.totalNodes > 0) (data.gpuDelegatedNodes.toDouble() / data.totalNodes) * 100 else 0.0
                        Text(
                            text = String.format(Locale.US, "%.1f%% graph", pct),
                            color = AriaSlate,
                            fontSize = 7.5.sp
                        )
                    }
                }

                // Column 2: CPU Fallback
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF0F172A), RoundedCornerShape(4.dp))
                        .padding(vertical = 3.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("CPU FALLBACK", color = AriaSlate, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${data.cpuFallbackNodes} nodes",
                            color = if (data.cpuFallbackNodes == 0) Color(0xFF10B981) else Color(0xFFEF4444),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (data.cpuFallbackNodes == 0) "Zero fallback" else "XNNPack fallback",
                            color = AriaSlate,
                            fontSize = 7.5.sp
                        )
                    }
                }

                // Column 3: Partitions
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF0F172A), RoundedCornerShape(4.dp))
                        .padding(vertical = 3.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PARTITIONS", color = AriaSlate, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${data.partitionCount}",
                            color = if (data.partitionCount == 1) Color(0xFF10B981) else Color(0xFFEF4444),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (data.partitionCount == 1) "Single partition" else "Context sync",
                            color = AriaSlate,
                            fontSize = 7.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ParityCard(data: GpuDiagnosticData) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AriaPanel),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "NUMERICAL PARITY (vs CPU REFERENCE)",
                color = Color(0xFF38BDF8),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Parity against CPU 4T execution; NOT ground-truth task accuracy",
                color = AriaSlate,
                fontSize = 7.5.sp
            )

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF0F172A), RoundedCornerShape(4.dp))
                        .padding(vertical = 3.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ARGMAX", color = AriaSlate, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = String.format(Locale.US, "%.2f%%", data.argmaxAgreementVsCpu * 100),
                            color = Color(0xFF10B981),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF0F172A), RoundedCornerShape(4.dp))
                        .padding(vertical = 3.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("WALKABLE", color = AriaSlate, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = String.format(Locale.US, "%.2f%%", data.walkableAgreementVsCpu * 100),
                            color = Color(0xFF10B981),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF0F172A), RoundedCornerShape(4.dp))
                        .padding(vertical = 3.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MAX DIFF", color = AriaSlate, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = String.format(Locale.US, "%.6f", data.maxDiffVsCpu),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF0F172A), RoundedCornerShape(4.dp))
                        .padding(vertical = 3.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MAE", color = AriaSlate, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = String.format(Locale.US, "%.6f", data.maeVsCpu),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogcatAndSummaryCard(data: GpuDiagnosticData) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "OBSERVED LITERT RUNTIME LOGCAT (REAL HARDWARE)",
                color = Color(0xFF94A3B8),
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(3.dp))

            val linesToDisplay = if (data.rawReplacingLines.isNotEmpty()) {
                data.rawReplacingLines
            } else {
                data.observedLogLines.filter { it.contains("Replacing") || it.contains("GPU delegate kernels") }
            }

            if (linesToDisplay.isEmpty()) {
                Text(text = "No log lines captured.", color = Color.Gray, fontSize = 8.sp)
            } else {
                linesToDisplay.forEach { line ->
                    val isErrorOrFallback = line.contains("Replacing 64") || line.contains("XNNPack")
                    val textColor = if (isErrorOrFallback) Color(0xFFF87171) else Color(0xFF4ADE80)
                    Text(
                        text = line,
                        color = textColor,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 10.5.sp,
                        softWrap = true,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (data.stage == "BEFORE") {
                    "Root Cause: 5x TRANSPOSE_CONV with fused ReLU require Op v4 (GPU delegate supports ≤ v3) -> decoder falls back to CPU XNNPack."
                } else {
                    "Optimization: Unfused TransposeConv to v3 + SLICE + explicit ReLU v1 -> 100% nodes delegated to GPU with zero CPU fallback."
                },
                color = Color(0xFF94A3B8),
                fontSize = 7.5.sp,
                lineHeight = 10.5.sp
            )
        }
    }
}
