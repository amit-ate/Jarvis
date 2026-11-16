package com.jarvis.edge.hud

import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.widget.Toast
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
        val baseJson = assets.open("ir/codes.json").bufferedReader().use { it.readText() }
        val catalog = IrCodes(baseJson)
        val irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager

        setContent {
            var deviceType by remember { mutableStateOf("tv") }
            val profiles = remember(deviceType) { catalog.brandsFor(deviceType) }
            var brandIndex by remember(deviceType) { mutableIntStateOf(0) }
            var tested by remember(deviceType, brandIndex) { mutableStateOf(false) }

            val activeProfile = profiles.getOrNull(brandIndex)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0F1A))
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Infrared Device Setup",
                    color = Color(0xFF38BDF8),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("tv", "ac").forEach { type ->
                        val selected = deviceType == type
                        Button(onClick = { deviceType = type }) {
                            Text(if (selected) "[${type.uppercase()}]" else type.uppercase())
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    text = activeProfile?.let { "Brand ${brandIndex + 1}/${profiles.size}: ${it.brand}" }
                        ?: "No code sets remaining for ${deviceType.uppercase()}.",
                    color = Color.White,
                    fontSize = 20.sp,
                )

                Spacer(Modifier.height(20.dp))
                if (activeProfile != null) {
                    Button(
                        onClick = {
                            val power = activeProfile.commands["power"]
                            if (power != null && irManager?.hasIrEmitter() == true) {
                                irManager.transmit(power.carrierHz, power.pattern)
                                tested = true
                            } else {
                                Toast.makeText(
                                    this@SetupActivity,
                                    "IR blaster not available on this device.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) {
                        Text("Test power")
                    }

                    if (tested) {
                        Spacer(Modifier.height(20.dp))
                        Text("Did it react?", color = Color(0xFFE2E8F0), fontSize = 18.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Button(
                                onClick = {
                                    saveSelection(deviceType, activeProfile.commands)
                                    Toast.makeText(
                                        this@SetupActivity,
                                        "Saved ${activeProfile.brand} for ${deviceType.uppercase()}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    finish()
                                },
                            ) {
                                Text("Yes")
                            }
                            Spacer(Modifier.width(16.dp))
                            OutlinedButton(
                                onClick = { brandIndex++ },
                            ) {
                                Text("No", color = Color.White)
                            }
                        }
                    }
                } else {
                    OutlinedButton(onClick = { finish() }) {
                        Text("Close", color = Color.White)
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
