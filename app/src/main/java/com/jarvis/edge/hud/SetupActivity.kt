package com.jarvis.edge.hud

import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.edge.tools.ir.IrCodes
import org.json.JSONObject
import java.io.File

class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.navigationBarColor = android.graphics.Color.parseColor("#02040A")
        val baseJson = assets.open("ir/codes.json").bufferedReader().use { it.readText() }
        val catalog = IrCodes(baseJson)
        val irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager

        setContent {
            var deviceType by remember { mutableStateOf("tv") }
            val profiles = remember(deviceType) { catalog.brandsFor(deviceType) }
            var brandIndex by remember(deviceType) { mutableIntStateOf(0) }
            var tested by remember(deviceType, brandIndex) { mutableStateOf(false) }

            val activeProfile = profiles.getOrNull(brandIndex)

            JarvisTheme {
            Column(
                modifier = Modifier.fillMaxSize().background(Jx.backdrop).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ScreenHeader(title = "Remote control", subtitle = "infrared setup for your tv and ac", onBack = { finish() })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("tv" to "TV", "ac" to "Air conditioner").forEach { (type, label) ->
                        PillButton(label, onClick = { deviceType = type }, selected = deviceType == type)
                    }
                }
                GlassPanel(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(22.dp)) {
                    if (activeProfile != null) {
                        HudLabel("code set ${brandIndex + 1} of ${profiles.size}", size = 10)
                        Spacer(Modifier.height(8.dp))
                        Text(activeProfile.brand, color = Jx.Text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text("Point the phone at the ${if (deviceType == "tv") "TV" else "AC"} and send the power signal.", color = Jx.TextDim, fontSize = 14.sp)
                    } else {
                        Text("No code sets left for ${deviceType.uppercase()}.", color = Jx.Text, fontSize = 18.sp)
                    }
                }
                if (activeProfile != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            RingButton(
                                onClick = {
                                    val power = activeProfile.commands["power"]
                                    if (power != null && irManager?.hasIrEmitter() == true) {
                                        irManager.transmit(power.carrierHz, power.pattern)
                                        tested = true
                                    } else {
                                        Toast.makeText(this@SetupActivity, "IR blaster not available on this device.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                size = 88.dp, color = Jx.Cyan, filled = true,
                            ) { Glyph(GlyphKind.WAVE, Color.White, 34.dp) }
                            Spacer(Modifier.height(8.dp))
                            HudLabel("test power", color = Jx.TextDim, size = 10)
                        }
                    }
                    if (tested) {
                        GlassPanel(Modifier.fillMaxWidth(), accent = Jx.Mint, brackets = false) {
                            Text("Did it react?", color = Jx.Text, fontSize = 18.sp)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PillButton("Yes, save it", onClick = {
                                    saveSelection(deviceType, activeProfile.commands)
                                    Toast.makeText(this@SetupActivity, "Saved ${activeProfile.brand} for ${deviceType.uppercase()}", Toast.LENGTH_SHORT).show()
                                    finish()
                                }, color = Jx.Mint, selected = true)
                                PillButton("No, try next", onClick = { brandIndex++ })
                            }
                        }
                    }
                }
            }
            }
        }
    }

    private fun saveSelection(device: String, commands: Map<String, com.jarvis.edge.tools.ir.IrCode>) {
        val file = File(filesDir, OVERRIDE_FILE_NAME)
        val root = if (file.exists()) {
            runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        root.put(device, IrCodes.serializeRawCommandMap(commands))
        file.writeText(root.toString())
    }

    companion object {
        const val OVERRIDE_FILE_NAME = "ir_configured.json"
    }
}
