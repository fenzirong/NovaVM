package com.novavm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novavm.app.engine.HostCapabilities

/**
 * Batch 1 shell: proves the toolchain, JNI library and Compose stack build,
 * and surfaces the live host capability probe.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovaVmTheme {
                HomeScreen()
            }
        }
    }
}

private val NovaGreen = Color(0xFF4ADE80)
private val NovaDark = Color(0xFF070B11)
private val NovaSurface = Color(0xFF111A24)
private val NovaMuted = Color(0xFF8FA3BA)

@Composable
private fun NovaVmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NovaGreen,
            onPrimary = NovaDark,
            background = NovaDark,
            onBackground = Color(0xFFD7E3F4),
            surface = NovaSurface,
            onSurface = Color(0xFFD7E3F4)
        ),
        content = content
    )
}

@Composable
private fun HomeScreen() {
    val capabilities = remember {
        runCatching { HostCapabilities.detect() }.getOrElse {
            HostCapabilities.conservative()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaDark)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = "NOVAVM",
            color = NovaGreen,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "// Batch 1 · engine core online",
            color = NovaMuted,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(Modifier.height(24.dp))
        CapabilityCard(capabilities)

        Spacer(Modifier.height(16.dp))
        Text(
            text = "READY FOR BATCH 2",
            color = NovaGreen,
            fontSize = 12.sp,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            "JNI capability probe, surface blitter, disk image tooling",
            "VM repository with per-VM shared folders and snapshots",
            "QEMU argv builder, AVF probe, QMP + RFB clients",
            "Host/guest transfer and root/Xposed boot scripts"
        ).forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(NovaGreen)
                )
                Spacer(Modifier.width(8.dp))
                Text(text = line, color = Color(0xFFB6C6DA), fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CapabilityCard(capabilities: HostCapabilities) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "HOST CAPABILITIES",
                color = NovaGreen,
                fontSize = 12.sp,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(4.dp))
            CapabilityRow("ABI", capabilities.primaryAbi)
            CapabilityRow("CPU cores", capabilities.cpuCores.toString())
            CapabilityRow(
                "RAM",
                if (capabilities.totalRamMb > 0) "${capabilities.totalRamMb} MB" else "unknown"
            )
            CapabilityRow("KVM", if (capabilities.kvmAvailable) "available" else "software TCG")
            CapabilityRow("vhost-vsock", if (capabilities.vhostVsockAvailable) "yes" else "no")
            CapabilityRow(
                "AVF / pKVM",
                if (capabilities.avfUsable) "usable" else "not available"
            )
            CapabilityRow("Preferred backend", capabilities.preferredBackend)
        }
    }
}

@Composable
private fun CapabilityRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = NovaMuted,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = Color(0xFFE6EEF8),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
