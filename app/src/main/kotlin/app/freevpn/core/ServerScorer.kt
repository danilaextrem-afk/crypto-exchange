package app.freevpn.core

import kotlin.math.min
import kotlin.math.pow

/** Выбор сервера по фактическим измерениям, а не по географии. Меньше балл — лучше. */
object ServerScorer {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val UNKNOWN_LATENCY = 300.0

    fun score(server: ServerProfile, stats: ServerStats?, nowMs: Long): Double {
        val latency = stats?.latencyMs ?: UNKNOWN_LATENCY
        val failurePenalty = (stats?.consecutiveFailures ?: 0) * 400.0
        val lossPenalty = (stats?.lossPercent ?: 0.0) * 20.0
        val loadPenalty = (server.load ?: 30).coerceIn(0, 100) * 3.0
        val speedBonus = min(stats?.downMbps ?: 0.0, 100.0) * 2.0
        val stalePenalty =
            if (stats == null || nowMs - stats.lastTestedEpoch > DAY_MS) 50.0 else 0.0
        return latency + failurePenalty + lossPenalty + loadPenalty + stalePenalty - speedBonus
    }

    fun order(
        servers: List<ServerProfile>,
        stats: Map<String, ServerStats>,
        nowMs: Long,
        preferredId: String? = null,
        avoid: Set<String> = emptySet()
    ): List<ServerProfile> {
        val sorted = servers.sortedBy { score(it, stats[it.id], nowMs) }
        val (bad, good) = sorted.partition { it.id in avoid }
        val preferred = good.firstOrNull { it.id == preferredId }
        return if (preferred != null) listOf(preferred) + (good - preferred) + bad else good + bad
    }

    fun ewma(old: Double?, new: Double, alpha: Double = 0.3): Double =
        if (old == null) new else old * (1 - alpha) + new * alpha
}

object Backoff {
    /** 1, 2, 4, 8, 16, 30, 30… секунд. */
    fun seconds(attempt: Int): Int =
        min(30.0, 2.0.pow((attempt - 1).coerceIn(0, 10))).toInt()
}

enum class Verdict { HEALTHY, DEGRADED, DEAD }

/** Считает подряд неудачные проверки туннеля. */
class HealthTracker(private val maxConsecutiveFailures: Int = 3) {
    var consecutiveFailures = 0
        private set

    fun record(ok: Boolean): Verdict {
        consecutiveFailures = if (ok) 0 else consecutiveFailures + 1
        return when {
            consecutiveFailures == 0 -> Verdict.HEALTHY
            consecutiveFailures >= maxConsecutiveFailures -> Verdict.DEAD
            else -> Verdict.DEGRADED
        }
    }
}

/** Скорость по приращению счётчиков туннеля. */
class SpeedMeter(private val smoothing: Double = 1.0) {
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastT = 0L
    private var rxRate = 0.0
    private var txRate = 0.0

    /** Возвращает (приём, отдача) в байтах/с. */
    fun update(totalRx: Long, totalTx: Long, nowMs: Long): Pair<Long, Long> {
        if (lastT == 0L) {
            lastRx = totalRx; lastTx = totalTx; lastT = nowMs
            return 0L to 0L
        }
        val dt = (nowMs - lastT).coerceAtLeast(1)
        val rx = ((totalRx - lastRx).coerceAtLeast(0) * 1000.0) / dt
        val tx = ((totalTx - lastTx).coerceAtLeast(0) * 1000.0) / dt
        rxRate = rxRate * (1 - smoothing) + rx * smoothing
        txRate = txRate * (1 - smoothing) + tx * smoothing
        lastRx = totalRx; lastTx = totalTx; lastT = nowMs
        return rxRate.toLong() to txRate.toLong()
    }
}
