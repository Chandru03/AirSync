package com.example.airsync.data.bluetooth

import com.example.airsync.data.bluetooth.aap.EarSideResolver
import com.example.airsync.domain.model.EarStatus
import com.example.airsync.domain.model.PodBattery
import com.example.airsync.domain.model.WearState.IN_CASE
import com.example.airsync.domain.model.WearState.IN_EAR
import com.example.airsync.domain.model.WearState.OUT_OF_EAR
import com.example.airsync.domain.model.WearState.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Test

class EarSideResolverTest {

    private val resolver = EarSideResolver()

    @Test
    fun `identical roles map to both sides`() {
        assertEquals(EarStatus(IN_EAR, IN_EAR), resolver.onEar(IN_EAR, IN_EAR))
        assertEquals(EarStatus(IN_CASE, IN_CASE), resolver.onEar(IN_CASE, IN_CASE))
    }

    @Test
    fun `left bud docked while right is primary - the real Fold capture`() {
        resolver.onEar(IN_EAR, IN_EAR)
        resolver.onBattery(PodBattery(84), PodBattery(90))
        // Left taken out: primary (right) stays in, secondary out.
        assertEquals(EarStatus(left = OUT_OF_EAR, right = IN_EAR), resolver.onEar(IN_EAR, OUT_OF_EAR))
        // Left into case; the battery frame (left charging) arrives 40 ms later and confirms sides.
        resolver.onEar(IN_EAR, IN_CASE)
        assertEquals(EarStatus(left = IN_CASE, right = IN_EAR), resolver.onBattery(PodBattery(83, charging = true), PodBattery(89)))
        // Lid closed: left unreachable.
        assertEquals(EarStatus(left = UNKNOWN, right = IN_EAR), resolver.onEar(IN_EAR, UNKNOWN))
        assertEquals(EarStatus(left = UNKNOWN, right = IN_EAR), resolver.onBattery(null, PodBattery(89)))
    }

    @Test
    fun `removing the primary swaps roles for later frames`() {
        resolver.onEar(IN_EAR, IN_EAR)
        resolver.onBattery(PodBattery(80), PodBattery(80))
        // Right (primary by default) removed: pods first report primary OUT…
        assertEquals(EarStatus(left = IN_EAR, right = OUT_OF_EAR), resolver.onEar(OUT_OF_EAR, IN_EAR))
        // …then re-send with the left promoted to primary.
        assertEquals(EarStatus(left = IN_EAR, right = OUT_OF_EAR), resolver.onEar(IN_EAR, OUT_OF_EAR))
        // Right goes into the case: battery hint confirms the right side is docked.
        resolver.onEar(IN_EAR, IN_CASE)
        assertEquals(EarStatus(left = IN_EAR, right = IN_CASE), resolver.onBattery(PodBattery(80), PodBattery(80, charging = true)))
    }

    @Test
    fun `battery hint overrides a wrong sticky side`() {
        // Sticky default says right is primary, but the battery says the right bud is the docked one.
        resolver.onEar(IN_CASE, IN_EAR)
        assertEquals(EarStatus(left = IN_EAR, right = IN_CASE), resolver.onBattery(PodBattery(70), PodBattery(70, charging = true)))
        // Right bud comes back out of the case and into the ear; it is still the primary.
        resolver.onBattery(PodBattery(70), PodBattery(70))
        assertEquals(EarStatus(left = IN_EAR, right = IN_EAR), resolver.onEar(IN_EAR, IN_EAR))
        // Left removed: primary (right) stays in, secondary (left) out.
        assertEquals(EarStatus(left = OUT_OF_EAR, right = IN_EAR), resolver.onEar(IN_EAR, OUT_OF_EAR))
    }
}
