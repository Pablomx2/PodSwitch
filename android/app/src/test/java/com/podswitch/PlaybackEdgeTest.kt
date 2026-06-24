package com.podswitch

import com.podswitch.core.Category
import com.podswitch.core.PlaybackEdge
import org.junit.Assert.assertEquals
import org.junit.Test

/** Drives [PlaybackEdge] with a manual scheduler to verify the sustain-debounce behaviour. */
class PlaybackEdgeTest {

    /** Runs its pending action only on [fire]; mimics the Handler without real time. */
    private class ManualScheduler : PlaybackEdge.Scheduler {
        private var pending: (() -> Unit)? = null
        val hasPending: Boolean get() = pending != null
        override fun schedule(delayMillis: Long, action: () -> Unit) { pending = action }
        override fun cancel() { pending = null }
        fun fire() { val a = pending; pending = null; a?.invoke() }
    }

    private class Fixture {
        val scheduler = ManualScheduler()
        val started = mutableListOf<Category>()
        var stopped = 0
        val edge = PlaybackEdge(
            sustainMillis = 1000L,
            scheduler = scheduler,
            onStarted = { started.add(it) },
            onStopped = { stopped++ },
        )
    }

    @Test
    fun mediaStart_isDeferredUntilSustained() {
        val f = Fixture()
        f.edge.update(Category.MEDIA)
        assertEquals("must not fire instantly", emptyList<Category>(), f.started)
        assertEquals(true, f.scheduler.hasPending)
        f.scheduler.fire()
        assertEquals(listOf(Category.MEDIA), f.started)
    }

    @Test
    fun transientMediaBlip_neverFires() {
        val f = Fixture()
        f.edge.update(Category.MEDIA) // blip starts
        f.edge.update(null)           // blip ends before the sustain window elapses
        assertEquals(false, f.scheduler.hasPending)
        f.scheduler.fire()            // no-op: nothing pending
        assertEquals(emptyList<Category>(), f.started)
        assertEquals(0, f.stopped)
    }

    @Test
    fun repeatedBlips_collapseToNothing() {
        val f = Fixture()
        repeat(5) {
            f.edge.update(Category.MEDIA)
            f.edge.update(null)
        }
        f.scheduler.fire()
        assertEquals(emptyList<Category>(), f.started)
        assertEquals(0, f.stopped)
    }

    @Test
    fun callStart_firesImmediately() {
        val f = Fixture()
        f.edge.update(Category.CALL)
        assertEquals(listOf(Category.CALL), f.started)
        assertEquals(false, f.scheduler.hasPending)
    }

    @Test
    fun notificationStart_firesImmediately() {
        val f = Fixture()
        f.edge.update(Category.NOTIFICATION)
        assertEquals(listOf(Category.NOTIFICATION), f.started)
    }

    @Test
    fun stopAfterSustainedMedia_firesStop() {
        val f = Fixture()
        f.edge.update(Category.MEDIA)
        f.scheduler.fire()
        assertEquals(listOf(Category.MEDIA), f.started)
        f.edge.update(null)
        assertEquals(1, f.stopped)
    }

    @Test
    fun sustainedMedia_emitsOnceWhileContinuing() {
        val f = Fixture()
        f.edge.update(Category.MEDIA)
        f.scheduler.fire()
        f.edge.update(Category.MEDIA) // still playing, same category
        assertEquals("no duplicate start", listOf(Category.MEDIA), f.started)
        assertEquals(false, f.scheduler.hasPending)
    }

    @Test
    fun callPreemptsPendingMedia() {
        val f = Fixture()
        f.edge.update(Category.MEDIA) // scheduled, not yet fired
        f.edge.update(Category.CALL)  // call takes over before media sustains
        assertEquals(listOf(Category.CALL), f.started)
        assertEquals(false, f.scheduler.hasPending)
    }

    @Test
    fun resetDropsPendingStart() {
        val f = Fixture()
        f.edge.update(Category.MEDIA)
        f.edge.reset()
        assertEquals(false, f.scheduler.hasPending)
        f.scheduler.fire()
        assertEquals(emptyList<Category>(), f.started)
    }
}
