package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Test

class PartitionSchedulerTest {
    private val fast = NetworkProbe(10000.0, 10000.0, 2.0)
    @Test fun exploresAllPartitionsBeforeChoosing() {
        val scheduler = PartitionScheduler()
        assertEquals(Partition.LOCAL, scheduler.choose(fast, 1.0))
        scheduler.observe(Partition.LOCAL, Profile(100.0, 0.0, 0, 0, 1.0))
        assertEquals(Partition.ALIGNED, scheduler.choose(fast, 1.0))
        scheduler.observe(Partition.ALIGNED, Profile(20.0, 10.0, 1000, 100, 1.0))
        assertEquals(Partition.REMOTE, scheduler.choose(fast, 1.0))
    }
    @Test fun selectsAlignedWithFastNetwork() {
        val scheduler = calibrated()
        assertEquals(Partition.ALIGNED, scheduler.choose(fast, 1.0))
    }
    @Test fun selectsLocalWhenBandwidthDrops() {
        val scheduler = calibrated()
        assertEquals(Partition.LOCAL, scheduler.choose(NetworkProbe(2.0, 2.0, 100.0), 1.0))
    }
    private fun calibrated() = PartitionScheduler().apply {
        observe(Partition.LOCAL, Profile(100.0, 0.0, 0, 0, 1.0))
        observe(Partition.ALIGNED, Profile(20.0, 10.0, 1000, 100, 1.0))
        observe(Partition.REMOTE, Profile(5.0, 45.0, 20000, 100, 1.0))
    }
}
