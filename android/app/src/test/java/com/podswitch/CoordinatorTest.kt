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

    private fun config(
        enabled: Boolean = true,
        mode: Mode = Mode.STEAL,
        categories: Set<Category> = setOf(Category.MEDIA),
        targetId: String? = target,
    ) = Config(enabled, mode, categories, targetId)

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
}
