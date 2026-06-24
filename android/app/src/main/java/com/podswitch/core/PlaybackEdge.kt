package com.podswitch.core

/**
 * Turns a stream of raw "what's playing now" observations into debounced start/stop signals.
 *
 * A MEDIA start must be SUSTAINED past [sustainMillis] before it counts — this filters out the
 * short "blip" sounds some apps emit (e.g. Google Messages keyboard/send sounds reported as
 * UNKNOWN usage) that would otherwise trigger a spurious device switch. CALL and NOTIFICATION
 * starts fire immediately, and a stop fires immediately.
 *
 * Pure and side-effect-free except through [scheduler] and the callbacks, so it is unit-testable
 * with a fake scheduler.
 */
class PlaybackEdge(
    private val sustainMillis: Long,
    private val scheduler: Scheduler,
    private val onStarted: (Category) -> Unit,
    private val onStopped: () -> Unit,
) {

    /** Schedules a single pending action, replacing any previously scheduled one. */
    interface Scheduler {
        fun schedule(delayMillis: Long, action: () -> Unit)
        fun cancel()
    }

    /** The category we have actually emitted as "playing" (null = idle/stopped). */
    private var committed: Category? = null

    /** A start that is scheduled but not yet emitted (waiting out the sustain window). */
    private var pending: Category? = null

    /** Feed the latest raw category (null when nothing relevant is playing). */
    fun update(next: Category?) {
        if (next == committed) {
            // Raw state is back to what we've already emitted — drop any in-flight change.
            if (pending != null) {
                pending = null
                scheduler.cancel()
            }
            return
        }
        if (pending != null && next == pending) return // already scheduled for this — let it ride

        // A genuinely new target: cancel whatever was scheduled and act on the new state.
        scheduler.cancel()
        pending = null

        when {
            next == null -> {
                committed = null
                onStopped()
            }
            needsSustain(next) -> {
                pending = next
                scheduler.schedule(sustainMillis) {
                    val category = pending ?: return@schedule
                    committed = category
                    pending = null
                    onStarted(category)
                }
            }
            else -> {
                committed = next
                onStarted(next)
            }
        }
    }

    /** Drop all state and any pending work. */
    fun reset() {
        scheduler.cancel()
        committed = null
        pending = null
    }

    /** Only MEDIA is debounced; calls and notifications are intentional and fire at once. */
    private fun needsSustain(category: Category): Boolean = category == Category.MEDIA
}
