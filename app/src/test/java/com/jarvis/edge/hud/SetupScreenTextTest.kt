package com.jarvis.edge.hud

import com.jarvis.edge.setup.ItemPhase
import com.jarvis.edge.setup.ItemProgress
import com.jarvis.edge.setup.NetState
import com.jarvis.edge.setup.SetupProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SetupScreenTextTest {

    private fun item(id: String, phase: ItemPhase, label: String = id, bytes: Long = 0L, size: Long = 100L, reason: String? = null) =
        ItemProgress(id = id, label = label, required = true, phase = phase, bytes = bytes, size = size, reason = reason)

    private fun progress(
        vararg items: ItemProgress,
        net: NetState = NetState.ALLOWED,
        finished: Boolean = false,
    ) = SetupProgress(
        items = items.toList(),
        net = net,
        wifiOnly = true,
        bytesPerSec = 0L,
        finished = finished,
        canStartWithout = false,
    )

    @Test
    fun `gb text shows done of total in decimal GB`() {
        assertEquals("1.1 of 2.3 GB", gbText(1_100_000_000L, 2_300_000_000L))
    }

    @Test
    fun `free text shows decimal GB`() {
        assertEquals("4.0 GB free", freeText(4_000_000_000L))
    }

    @Test
    fun `eta is minutes, under a minute, or empty when unknown`() {
        assertEquals("~9 min left", etaText(540L))
        assertEquals("<1 min left", etaText(30L))
        assertEquals("", etaText(null))
    }

    @Test
    fun `step names the downloading item in lower case`() {
        val p = progress(
            item("asr", ItemPhase.DONE, label = "Speech · Zipformer"),
            item("qwen", ItemPhase.DOWNLOADING, label = "Brain · Qwen2.5 1.5B"),
        )
        assertEquals("downloading brain · qwen2.5 1.5b", stepText(p))
    }

    @Test
    fun `step says verifying and installing for the other active phases`() {
        assertEquals("verifying voice · alan", stepText(progress(item("alan", ItemPhase.VERIFYING, label = "Voice · Alan"))))
        assertEquals("installing voice · alan", stepText(progress(item("alan", ItemPhase.UNPACKING, label = "Voice · Alan"))))
    }

    @Test
    fun `step says waiting for Wi-Fi and offline ahead of the item`() {
        val downloading = item("qwen", ItemPhase.DOWNLOADING, label = "Brain")
        assertEquals("waiting for Wi-Fi", stepText(progress(downloading, net = NetState.WAITING_FOR_WIFI)))
        assertEquals("offline", stepText(progress(downloading, net = NetState.OFFLINE)))
    }

    @Test
    fun `step says ready when finished`() {
        assertEquals("Ready", stepText(progress(item("qwen", ItemPhase.DONE), finished = true)))
    }

    @Test
    fun `collapsed list keeps the active item, the next two queued and every failed one`() {
        val items = listOf(
            item("a", ItemPhase.DONE),
            item("b", ItemPhase.FAILED, reason = "checksum"),
            item("c", ItemPhase.DOWNLOADING),
            item("d", ItemPhase.QUEUED),
            item("e", ItemPhase.QUEUED),
            item("f", ItemPhase.QUEUED),
        )
        assertEquals(listOf("b", "c", "d", "e"), visibleItems(items).map { it.id })
    }
}
