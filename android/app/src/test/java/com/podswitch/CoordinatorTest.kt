package com.podswitch

import com.podswitch.core.BluetoothConnector
import com.podswitch.core.Category
import com.podswitch.core.Config
import com.podswitch.core.Coordinator
import com.podswitch.core.Mode
import com.podswitch.core.NotificationPresenter
import com.podswitch.core.SettingsStore
import com.podswitch.core.SwitchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coordinator behaviour with fakes: action dispatch and notificationPending tracking. */
class CoordinatorTest {

    private val target = "AA:BB:CC:DD:EE:FF"

    private class FakeSettings(var config: Config) : SettingsStore {
        override fun currentConfig(): Config = config
    }

    private class FakeConnector(
        var paired: Boolean = true,
        var active: Boolean = false,
    ) : BluetoothConnector {
        var connectCalls = 0
        override fun isPaired(targetDeviceId: String) = paired
        override fun isActiveOutput(targetDeviceId: String) = active
        override fun connect(targetDeviceId: String) {
            connectCalls++
        }
    }

    private class FakeNotifier : NotificationPresenter {
        var askShown = 0
        var askCleared = 0
        override fun showAsk() { askShown++ }
        override fun clearAsk() { askCleared++ }
    }

    private class FakePresence : com.podswitch.core.PresencePort {
        var peerActive = false
        var localActive: Boolean? = null
        override var onPeerChanged: (() -> Unit)? = null
        override fun peerActiveOnTarget() = peerActive
        override fun setLocalActive(active: Boolean) { localActive = active }
        /** Simulate a peer dropping its claim and notifying us. */
        fun releaseAndNotify() { peerActive = false; onPeerChanged?.invoke() }
    }

    private fun config(
        enabled: Boolean = true,
        mode: Mode = Mode.STEAL,
        categories: Set<Category> = setOf(Category.MEDIA),
        targetId: String? = target,
        yield: Boolean = false,
    ) = Config(enabled, mode, categories, targetId, yieldToOtherSource = yield)

    // ---- None ----

    @Test
    fun disabled_doesNothing() {
        val connector = FakeConnector()
        val notifier = FakeNotifier()
        val coordinator = Coordinator(FakeSettings(config(enabled = false)), connector, notifier)

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals(0, connector.connectCalls)
        assertEquals(0, notifier.askShown)
    }

    // ---- Connect (STEAL) ----

    @Test
    fun steal_connectsOnAudioStarted() {
        val connector = FakeConnector()
        val notifier = FakeNotifier()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.STEAL)), connector, notifier)

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals(1, connector.connectCalls)
    }

    // ---- Notify (ASK) + pending tracking ----

    @Test
    fun ask_notifiesOnce_thenSuppressesWhilePending() {
        val connector = FakeConnector()
        val notifier = FakeNotifier()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.ASK)), connector, notifier)

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))
        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals("second AudioStarted is suppressed by notificationPending", 1, notifier.askShown)
        assertEquals(0, connector.connectCalls)
    }

    @Test
    fun ask_thenAccept_clearsPromptAndConnects() {
        val connector = FakeConnector()
        val notifier = FakeNotifier()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.ASK)), connector, notifier)

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))
        coordinator.handle(SwitchEvent.UserAcceptedSwitch)

        assertEquals(1, notifier.askShown)
        assertEquals(1, notifier.askCleared)
        assertEquals(1, connector.connectCalls)
    }

    @Test
    fun ask_acceptAfterPrompt_thenNewAudioNotifiesAgain() {
        val connector = FakeConnector()
        val notifier = FakeNotifier()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.ASK)), connector, notifier)

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))
        coordinator.handle(SwitchEvent.UserAcceptedSwitch)
        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals(2, notifier.askShown)
    }

    @Test
    fun ask_audioStopped_clearsPendingPrompt() {
        val connector = FakeConnector()
        val notifier = FakeNotifier()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.ASK)), connector, notifier)

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))
        coordinator.handle(SwitchEvent.AudioStopped)
        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals(2, notifier.askShown)
        assertTrue(notifier.askCleared >= 1)
    }

    // ---- None via accept-while-unpaired (no error ever surfaced) ----

    @Test
    fun accept_whileUnpaired_isNoOp_noConnect() {
        val connector = FakeConnector(paired = false)
        val notifier = FakeNotifier()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.ASK)), connector, notifier)

        coordinator.handle(SwitchEvent.UserAcceptedSwitch)

        assertEquals(0, connector.connectCalls)
        assertEquals(0, notifier.askShown)
        assertEquals(0, notifier.askCleared)
    }

    @Test
    fun accept_whileUnpaired_afterPrompt_isNone_promptNotConsumed() {
        val connector = FakeConnector(paired = true)
        val notifier = FakeNotifier()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.ASK)), connector, notifier)

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))
        connector.paired = false
        coordinator.handle(SwitchEvent.UserAcceptedSwitch)

        assertEquals(1, notifier.askShown)
        assertEquals(0, notifier.askCleared)
        assertEquals(0, connector.connectCalls)
    }

    // ---- None when already active ----

    @Test
    fun accept_whileActive_isNoOp() {
        val connector = FakeConnector(active = true)
        val notifier = FakeNotifier()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.STEAL)), connector, notifier)

        coordinator.handle(SwitchEvent.UserAcceptedSwitch)

        assertEquals(0, connector.connectCalls)
        assertFalse(notifier.askCleared > 0)
    }

    @Test
    fun steal_alreadyActive_doesNotConnect() {
        val connector = FakeConnector(active = true)
        val notifier = FakeNotifier()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.STEAL)), connector, notifier)

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals(0, connector.connectCalls)
    }

    // ---- yieldToOtherSource: don't grab back after another source takes the target ----

    @Test
    fun yield_afterTargetLost_suppressesSteal() {
        val connector = FakeConnector()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.STEAL, yield = true)), connector, FakeNotifier())

        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = true))
        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = false)) // taken by another source
        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals("must not grab the target back while yielded", 0, connector.connectCalls)
    }

    @Test
    fun yield_afterTargetReturns_resumesSteal() {
        val connector = FakeConnector()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.STEAL, yield = true)), connector, FakeNotifier())

        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = true))
        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = false))
        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = true)) // freed back to us
        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals(1, connector.connectCalls)
    }

    @Test
    fun yield_disconnectWithoutPriorConnect_doesNotYield() {
        val connector = FakeConnector()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.STEAL, yield = true)), connector, FakeNotifier())

        // Target merely idle/disconnected at start — not "taken" from us.
        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = false))
        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals("an idle target is still stealable", 1, connector.connectCalls)
    }

    @Test
    fun yield_off_stillStealsAfterTargetLost() {
        val connector = FakeConnector()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.STEAL, yield = false)), connector, FakeNotifier())

        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = true))
        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = false))
        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals(1, connector.connectCalls)
    }

    @Test
    fun yield_audioStoppedWhileNotHolding_keepsYield() {
        // The classic bug: another source steals the device (disconnect), our media auto-pauses
        // (AudioStopped) while we DON'T hold the device. The yield must survive the pause, so we
        // don't grab it back on the next AudioStarted.
        val connector = FakeConnector()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.STEAL, yield = true)), connector, FakeNotifier())

        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = true))
        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = false)) // stolen
        coordinator.handle(SwitchEvent.AudioStopped) // auto-pause from the steal — NOT a fresh session
        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals("yield must survive the steal-induced pause", 0, connector.connectCalls)
    }

    @Test
    fun yield_audioStoppedWhileHolding_resetsYield() {
        // A genuine stop while we hold the device ends the session and clears any stale yield.
        val connector = FakeConnector()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.STEAL, yield = true)), connector, FakeNotifier())

        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = true)) // we hold it
        coordinator.handle(SwitchEvent.AudioStopped) // genuine session end
        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals(1, connector.connectCalls)
    }

    // ---- coordination: protect the active device via peer presence ----

    @Test
    fun coordination_peerActive_suppressesSteal() {
        val connector = FakeConnector()
        val presence = FakePresence().apply { peerActive = true }
        val coordinator = Coordinator(
            FakeSettings(config(mode = Mode.STEAL, yield = true)), connector, FakeNotifier(), presence,
        )

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals("a peer is actively playing — don't steal", 0, connector.connectCalls)
    }

    @Test
    fun coordination_peerActive_ignoredWhenToggleOff() {
        val connector = FakeConnector()
        val presence = FakePresence().apply { peerActive = true }
        val coordinator = Coordinator(
            FakeSettings(config(mode = Mode.STEAL, yield = false)), connector, FakeNotifier(), presence,
        )

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))

        assertEquals(1, connector.connectCalls)
    }

    @Test
    fun coordination_peerRelease_takesOverWhileStillPlaying() {
        val connector = FakeConnector()
        val presence = FakePresence().apply { peerActive = true }
        val coordinator = Coordinator(
            FakeSettings(config(mode = Mode.STEAL, yield = true)), connector, FakeNotifier(), presence,
        )

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA)) // suppressed: peer active
        assertEquals(0, connector.connectCalls)

        presence.releaseAndNotify() // peer stopped → we should take over

        assertEquals("take over once the peer releases", 1, connector.connectCalls)
    }

    // ---- coordination: reclaim after the peer goes idle (regression: was permanently stuck) ----

    @Test
    fun coordination_peerGoingInactiveClearsStaleYieldAndReclaimsIfPlaying() {
        // A peer takes the target (targetYielded=true via a Bluetooth disconnect), then reports
        // it's no longer active. We're already playing, so we should reclaim immediately instead
        // of staying yielded forever just because the earbuds never reconnected to us at the
        // OS/Bluetooth level.
        val connector = FakeConnector()
        val presence = FakePresence().apply { peerActive = true }
        val coordinator = Coordinator(
            FakeSettings(config(mode = Mode.STEAL, yield = true)), connector, FakeNotifier(), presence,
        )

        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = true))
        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = false)) // taken by the peer
        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA)) // suppressed: peer active
        assertEquals(0, connector.connectCalls)

        presence.releaseAndNotify() // peer confirmed it stopped

        assertEquals("must reclaim once the peer confirms it's inactive", 1, connector.connectCalls)
    }

    @Test
    fun coordination_peerGoingInactiveClearsStaleYieldForALaterPlay() {
        // Same as above, but we weren't already playing when the peer went idle — the yield flag
        // must still be cleared so the *next* AudioStarted succeeds immediately rather than
        // staying stuck on the stale Bluetooth-based guard.
        val connector = FakeConnector()
        val presence = FakePresence().apply { peerActive = true }
        val coordinator = Coordinator(
            FakeSettings(config(mode = Mode.STEAL, yield = true)), connector, FakeNotifier(), presence,
        )

        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = true))
        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = false)) // taken by the peer

        presence.releaseAndNotify() // peer confirmed it stopped; not playing yet

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA)) // later play attempt
        assertEquals(1, connector.connectCalls)
    }

    @Test
    fun coordination_broadcastsLocalActiveOnlyWhenHoldingAndPlaying() {
        val connector = FakeConnector()
        val presence = FakePresence()
        val coordinator = Coordinator(
            FakeSettings(config(mode = Mode.STEAL, yield = true)), connector, FakeNotifier(), presence,
        )

        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA)) // playing but not yet holding
        assertEquals(false, presence.localActive)

        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = true)) // now we hold it
        assertEquals(true, presence.localActive)

        coordinator.handle(SwitchEvent.AudioStopped) // stopped playing
        assertEquals(false, presence.localActive)
    }

    @Test
    fun yield_userAcceptOverridesGuard() {
        val connector = FakeConnector()
        val coordinator = Coordinator(FakeSettings(config(mode = Mode.STEAL, yield = true)), connector, FakeNotifier())

        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = true))
        coordinator.handle(SwitchEvent.TargetConnectionChanged(connected = false))
        coordinator.handle(SwitchEvent.UserAcceptedSwitch)

        assertEquals("explicit accept connects despite the yield", 1, connector.connectCalls)

        // And the yield was cleared, so a later AudioStarted also connects.
        coordinator.handle(SwitchEvent.AudioStarted(Category.MEDIA))
        assertEquals(2, connector.connectCalls)
    }
}
