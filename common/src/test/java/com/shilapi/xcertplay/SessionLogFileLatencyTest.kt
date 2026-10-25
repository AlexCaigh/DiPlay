package com.shilapi.xcertplay

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SessionLogFileLatencyTest {
    private val line = "connection state " + "x".repeat(300)

    private fun nanosOf(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private fun percentile(sorted: List<Long>, p: Double) = sorted[((sorted.size - 1) * p).toInt()]

    /** Fills the current file to just under the rotation limit, with every archive present. */
    private fun fillWithArchives(folder: File): SessionLogFile {
        repeat(SessionLogFile.REPORT_NAMES.size) { session ->
            SessionLogFile(folder.resolve("diplay.log")).use { log ->
                log.reset("session=$session")
                repeat((SessionLogFile.MAX_BYTES / (line.length + 15)).toInt() + 2) { log.append(line) }
            }
        }
        val log = SessionLogFile(folder.resolve("diplay.log"))
        log.reset("final")
        return log
    }

    @Test fun callerLatencyPerLineStaysFarBelowOneDiskOpenPerCall() {
        val folder = Files.createTempDirectory("diplay-latency").toFile()
        try {
            val log = SessionLogFile(folder.resolve("diplay.log"))
            log.reset("started")
            repeat(2_000) { log.append(line) }
            val samples = List(10_000) { nanosOf { log.append(line) } }.sorted()
            log.close()
            println("SESSIONLOG per-call p50=${percentile(samples, 0.5) / 1000}us p99=${percentile(samples, 0.99) / 1000}us max=${samples.last() / 1000}us")
            assertTrue("p50 ${percentile(samples, 0.5)}ns", percentile(samples, 0.5) < 15_000)
        } finally { folder.deleteRecursively() }
    }

    @Test fun theCallThatCrossesTheSizeLimitDoesNotCopyArchivesOnTheCallerThread() {
        val folder = Files.createTempDirectory("diplay-rotation").toFile()
        try {
            val log = fillWithArchives(folder)
            val cycleMaxima = mutableListOf<Long>()
            repeat(5) {
                var worst = 0L
                repeat((SessionLogFile.MAX_BYTES / (line.length + 15)).toInt() + 5) {
                    worst = maxOf(worst, nanosOf { log.append(line) })
                }
                cycleMaxima += worst
            }
            log.close()
            println("SESSIONLOG rotation-cycle worst call ms=${cycleMaxima.map { it / 1_000_000 }}")
            assertTrue("median worst call ${cycleMaxima.sorted()[2]}ns", cycleMaxima.sorted()[2] < 4_000_000)
        } finally { folder.deleteRecursively() }
    }

    @Test(timeout = 60_000) fun aSecondThreadIsNotBlockedBehindTheRotatingCaller() {
        val folder = Files.createTempDirectory("diplay-contention").toFile()
        try {
            val log = fillWithArchives(folder)
            val perCycle = (SessionLogFile.MAX_BYTES / (line.length + 15)).toInt() + 5
            val blocked = mutableListOf<Long>()
            repeat(5) {
                val start = CountDownLatch(1)
                var otherWorst = 0L
                val other = Thread {
                    start.await()
                    repeat(perCycle) { otherWorst = maxOf(otherWorst, nanosOf { log.append("other $line") }) }
                }
                other.start()
                start.countDown()
                repeat(perCycle) { log.append(line) }
                other.join(30_000)
                blocked += otherWorst
            }
            log.close()
            println("SESSIONLOG second-thread worst blocked ms=${blocked.map { it / 1_000_000 }}")
            assertTrue("median blocked ${blocked.sorted()[2]}ns", blocked.sorted()[2] < 4_000_000)
        } finally { folder.deleteRecursively() }
    }

    @Test fun linesKeepTheirOrderAndFormatAndAreRedactedOnceAfterClose() {
        val folder = Files.createTempDirectory("diplay-order").toFile()
        try {
            val file = folder.resolve("diplay.log")
            SessionLogFile(file).use { log ->
                log.reset("header")
                repeat(300) { log.append("12:00:00.000  line $it peer=192.168.1.5") }
                log.append("12:00:00.000  password=secret")
                log.append("12:00:00.000  tail")
            }
            val lines = file.readLines()
            assertEquals(listOf("header") + (0 until 300).map { "12:00:00.000  line $it peer=[ip]" } + "12:00:00.000  tail", lines)
        } finally { folder.deleteRecursively() }
    }

    @Test fun rotationStillKeepsTheCurrentFileBoundedAndHistoryIntact() {
        val folder = Files.createTempDirectory("diplay-bound").toFile()
        try {
            val log = fillWithArchives(folder)
            repeat(3_000) { log.append(line) }
            log.append("last line")
            log.close()
            assertTrue(folder.resolve("diplay.log").length() <= SessionLogFile.MAX_BYTES + 701)
            assertTrue(folder.resolve("diplay.log").readText().trimEnd().endsWith("last line"))
            assertTrue(folder.resolve("previous.log").length() > 0)
            assertEquals(8, folder.listFiles()!!.size)
        } finally { folder.deleteRecursively() }
    }
}
