package com.coorlay.media

import com.coorlay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeQueueTest {
    @Test fun lostReferenceChainWaitsForSuccessfullyQueuedKeyframe() {
        val chain = VideoReferenceChain()
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        assertTrue(chain.needsKeyFrame) // Receiving it is insufficient if the codec is still busy.
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
        chain.reset()
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
    }

    @Test fun overflowPreservesConfigurationAndResetsBeforeNewReferenceChain() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Frame(byteArrayOf(3)))
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        assertNull(queue.poll(0))
    }

    @Test fun byteBudgetAlsoTriggersRecoveryAndRejectsOversizedFrame() {
        val queue = VideoDecodeQueue(maxFrames = 8, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(6)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun polledFramesReleaseTheirBudget() {
        val queue = VideoDecodeQueue(maxFrames = 2, maxBytes = 10)
        repeat(5) {
            queue.offer(VideoJob.Frame(ByteArray(4)))
            queue.offer(VideoJob.Frame(ByteArray(4)))
            assertEquals(4, (queue.poll(0) as VideoJob.Frame).nalus.size)
            assertEquals(4, (queue.poll(0) as VideoJob.Frame).nalus.size)
            assertNull(queue.poll(0)) // No Resync: the decoder kept up.
        }
        queue.offer(VideoJob.Frame(ByteArray(4)))
        queue.discardFrames()
        queue.offer(VideoJob.Frame(ByteArray(8)))
        assertEquals(8, (queue.poll(0) as VideoJob.Frame).nalus.size)
        assertNull(queue.poll(0))
    }

    @Test fun fullOutputMustBeDrainedWhileRetryingTheSameInput() {
        var heldOutputs = 2
        var dequeues = 0
        val submitted = mutableListOf<Int>()
        for (frame in 1..3) {
            val index = VideoInputPump.acquire(
                running = { true },
                drain = { if (heldOutputs > 0) heldOutputs-- },
                dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            )
            assertEquals(0, index)
            submitted.add(frame)
            heldOutputs = 2
        }
        assertEquals(listOf(1, 2, 3), submitted)
        assertEquals(6, dequeues)
    }

    @Test fun stalledDecoderHasFiniteWaitAndShutdownCancelsImmediately() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, VideoInputPump.acquire(
            running = { true }, drain = {}, dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
        ))
        assertEquals(3, attempts)
        assertEquals(-1, VideoInputPump.acquire(running = { false }, drain = { fail() }, dequeue = { fail(); 0 }))
    }

    @Test fun pooledFramesComeBackWhenDiscardedOrOversized() {
        val returned = mutableListOf<ByteArray>()
        val queue = VideoDecodeQueue(maxFrames = 2, maxBytes = 100)
        val a = ByteArray(64); val b = ByteArray(64); val big = ByteArray(4096)
        queue.offer(VideoJob.Frame(a, length = 10, recycle = { returned += it }))
        queue.offer(VideoJob.Frame(b, length = 10, recycle = { returned += it }))
        // Third frame overflows: the two queued ones are dropped and handed back.
        queue.offer(VideoJob.Frame(ByteArray(64), length = 10, recycle = { returned += it }))
        assertEquals(listOf(a, b), returned)
        // Only the length counts against maxBytes, not the pooled buffer's size.
        queue.offer(VideoJob.Frame(big, length = 50, recycle = { returned += it }))
        assertEquals(2, returned.size)
        queue.offer(VideoJob.Frame(big, length = 500, recycle = { returned += it }))
        assertSame(big, returned.last())
    }

    @Test fun keyframeScanStopsAtTheFrameLength() {
        // A predicted frame followed by stale bytes of an older IDR frame in the same pooled buffer.
        val buffer = byteArrayOf(0, 0, 0, 1, 0x41, 1, 2, 0, 0, 0, 1, 0x65, 1)
        assertFalse(MediaCodecSupport.isRandomAccess(buffer, VideoCodec.H264, 7))
        assertTrue(MediaCodecSupport.isRandomAccess(buffer, VideoCodec.H264))
        assertFalse(VideoReferenceChain().accepts(buffer, VideoCodec.H264, 7))
    }
}
