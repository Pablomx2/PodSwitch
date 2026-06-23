package com.podswitch

import com.podswitch.core.Category
import com.podswitch.core.Config
import com.podswitch.core.DeviceStatus
import com.podswitch.core.Mode
import com.podswitch.core.SwitchAction
import com.podswitch.core.SwitchEngine
import com.podswitch.core.SwitchEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Table-driven coverage of [SwitchEngine.decide]. */
class SwitchEngineTest {

    private val target = "AA:BB:CC:DD:EE:FF"

    private fun config(
        enabled: Boolean = true,
        mode: Mode = Mode.STEAL,
        categories: Set<Category> = setOf(Category.MEDIA, Category.CALL, Category.NOTIFICATION),
        targetId: String? = target,
        yield: Boolean = false,
    ) = Config(enabled, mode, categories, targetId, yieldToOtherSource = yield)

    private fun status(
        paired: Boolean = true,
        active: Boolean = false,
        pending: Boolean = false,
        yielded: Boolean = false,
    ) = DeviceStatus(paired, active, pending, targetYielded = yielded)

    // ---- Master gate: disabled ----

    @Test
    fun disabled_audioStarted_isNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(SwitchEvent.AudioStarted(Category.MEDIA), config(enabled = false), status()),
        )
    }

    @Test
    fun disabled_accepted_isNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(SwitchEvent.UserAcceptedSwitch, config(enabled = false), status()),
        )
    }

    @Test
    fun disabled_stopped_isNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(SwitchEvent.AudioStopped, config(enabled = false), status()),
        )
    }

    // ---- Master gate: unconfigured target ----

    @Test
    fun nullTarget_audioStarted_isNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(SwitchEvent.AudioStarted(Category.MEDIA), config(targetId = null), status()),
        )
    }

    @Test
    fun nullTarget_accepted_isNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(SwitchEvent.UserAcceptedSwitch, config(targetId = null), status()),
        )
    }

    @Test
    fun nullTarget_stopped_isNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(SwitchEvent.AudioStopped, config(targetId = null), status()),
        )
    }

    // ---- AudioStarted: category gating across every category x enabled/disabled ----

    @Test
    fun audioStarted_categoryNotEnabled_isNone() {
        for (category in Category.entries) {
            val cfg = config(categories = emptySet())
            assertEquals(
                "category=$category should be filtered when not enabled",
                SwitchAction.None,
                SwitchEngine.decide(SwitchEvent.AudioStarted(category), cfg, status()),
            )
        }
    }

    @Test
    fun audioStarted_eachEnabledCategory_steal_connects() {
        for (category in Category.entries) {
            val cfg = config(mode = Mode.STEAL, categories = setOf(category))
            assertEquals(
                "category=$category enabled under STEAL should Connect",
                SwitchAction.Connect,
                SwitchEngine.decide(SwitchEvent.AudioStarted(category), cfg, status()),
            )
        }
    }

    @Test
    fun audioStarted_eachEnabledCategory_ask_notifies() {
        for (category in Category.entries) {
            val cfg = config(mode = Mode.ASK, categories = setOf(category))
            assertEquals(
                "category=$category enabled under ASK should Notify",
                SwitchAction.Notify,
                SwitchEngine.decide(SwitchEvent.AudioStarted(category), cfg, status(pending = false)),
            )
        }
    }

    // ---- AudioStarted: pairing / active-output gating ----

    @Test
    fun audioStarted_notPaired_isNone() {
        for (mode in Mode.entries) {
            assertEquals(
                "mode=$mode not paired should be None",
                SwitchAction.None,
                SwitchEngine.decide(SwitchEvent.AudioStarted(Category.MEDIA), config(mode = mode), status(paired = false)),
            )
        }
    }

    @Test
    fun audioStarted_alreadyActive_isNone() {
        for (mode in Mode.entries) {
            assertEquals(
                "mode=$mode already active should be None",
                SwitchAction.None,
                SwitchEngine.decide(SwitchEvent.AudioStarted(Category.MEDIA), config(mode = mode), status(active = true)),
            )
        }
    }

    // ---- AudioStarted: ASK + notificationPending ----

    @Test
    fun audioStarted_ask_pending_isNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(
                SwitchEvent.AudioStarted(Category.MEDIA),
                config(mode = Mode.ASK),
                status(pending = true),
            ),
        )
    }

    @Test
    fun audioStarted_steal_pendingIrrelevant_connects() {
        assertEquals(
            SwitchAction.Connect,
            SwitchEngine.decide(
                SwitchEvent.AudioStarted(Category.MEDIA),
                config(mode = Mode.STEAL),
                status(pending = true),
            ),
        )
    }

    // ---- UserAcceptedSwitch ----

    @Test
    fun accepted_notPaired_isNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(SwitchEvent.UserAcceptedSwitch, config(), status(paired = false)),
        )
    }

    @Test
    fun accepted_alreadyActive_isNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(SwitchEvent.UserAcceptedSwitch, config(), status(active = true)),
        )
    }

    @Test
    fun accepted_pairedAndInactive_connects() {
        assertEquals(
            SwitchAction.Connect,
            SwitchEngine.decide(SwitchEvent.UserAcceptedSwitch, config(), status(paired = true, active = false)),
        )
    }

    @Test
    fun accepted_modeDoesNotMatter() {
        for (mode in Mode.entries) {
            assertEquals(
                "accept under mode=$mode should Connect when paired+inactive",
                SwitchAction.Connect,
                SwitchEngine.decide(SwitchEvent.UserAcceptedSwitch, config(mode = mode), status()),
            )
        }
    }

    // ---- yieldToOtherSource guard ----

    @Test
    fun audioStarted_yieldOn_yielded_steal_isNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(
                SwitchEvent.AudioStarted(Category.MEDIA),
                config(mode = Mode.STEAL, yield = true),
                status(yielded = true),
            ),
        )
    }

    @Test
    fun audioStarted_yieldOn_yielded_ask_isNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(
                SwitchEvent.AudioStarted(Category.MEDIA),
                config(mode = Mode.ASK, yield = true),
                status(yielded = true),
            ),
        )
    }

    @Test
    fun audioStarted_yieldOff_yielded_steal_stillConnects() {
        assertEquals(
            "guard is inert while the option is off",
            SwitchAction.Connect,
            SwitchEngine.decide(
                SwitchEvent.AudioStarted(Category.MEDIA),
                config(mode = Mode.STEAL, yield = false),
                status(yielded = true),
            ),
        )
    }

    @Test
    fun audioStarted_yieldOn_notYielded_steal_connects() {
        assertEquals(
            SwitchAction.Connect,
            SwitchEngine.decide(
                SwitchEvent.AudioStarted(Category.MEDIA),
                config(mode = Mode.STEAL, yield = true),
                status(yielded = false),
            ),
        )
    }

    @Test
    fun accepted_ignoresYieldGuard_connects() {
        assertEquals(
            "an explicit accept overrides the yield guard",
            SwitchAction.Connect,
            SwitchEngine.decide(
                SwitchEvent.UserAcceptedSwitch,
                config(yield = true),
                status(yielded = true),
            ),
        )
    }

    @Test
    fun targetConnectionChanged_isAlwaysNone() {
        for (connected in listOf(true, false)) {
            assertEquals(
                SwitchAction.None,
                SwitchEngine.decide(SwitchEvent.TargetConnectionChanged(connected), config(), status()),
            )
        }
    }

    // ---- AudioStopped always None when enabled+configured ----

    @Test
    fun stopped_isAlwaysNone() {
        assertEquals(
            SwitchAction.None,
            SwitchEngine.decide(SwitchEvent.AudioStopped, config(), status(paired = true, active = false)),
        )
    }

    // ---- Exhaustive sweep: every relevant combination is total (never throws) ----

    @Test
    fun exhaustiveSweep_matchesContract() {
        for (mode in Mode.entries) {
            for (category in Category.entries) {
                for (categoryEnabled in listOf(true, false)) {
                    for (paired in listOf(true, false)) {
                        for (active in listOf(true, false)) {
                            for (pending in listOf(true, false)) {
                                val cfg = config(
                                    mode = mode,
                                    categories = if (categoryEnabled) setOf(category) else emptySet(),
                                )
                                val st = status(paired = paired, active = active, pending = pending)

                                val expected = when {
                                    !categoryEnabled -> SwitchAction.None
                                    !paired -> SwitchAction.None
                                    active -> SwitchAction.None
                                    mode == Mode.STEAL -> SwitchAction.Connect
                                    pending -> SwitchAction.None
                                    else -> SwitchAction.Notify
                                }

                                assertEquals(
                                    "mode=$mode cat=$category enabled=$categoryEnabled paired=$paired active=$active pending=$pending",
                                    expected,
                                    SwitchEngine.decide(SwitchEvent.AudioStarted(category), cfg, st),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
