package dev.halcamera.benchmark.domain

import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/** Independent RUNS, never frames. Method and seed are frozen for saved comparison reproducibility. */
object RepeatStatistics {
    const val VERSION = "run-permutation-v1"
    const val MIN_RUNS = 5
    const val MAX_RUNS = 50
    const val RESAMPLES = 19999
    const val SEED = 680069L
    data class Summary(val n: Int, val mean: Double, val median: Double, val sd: Double?)

    fun summary(values: List<Double>): Summary? {
        if (values.isEmpty()) return null
        require(values.all { it.isFinite() && it in 0.0..1e12 })
        val sorted = values.sorted()
        val mean = sorted.average()
        val median = if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 else sorted[sorted.size / 2]
        return Summary(sorted.size, mean, median,
            if (sorted.size < 2) null else sqrt(sorted.sumOf { (it - mean) * (it - mean) } / (sorted.size - 1)))
    }

    /** Two-sided |mean(B)-mean(A)| label permutation; exact <= 50,000 partitions, otherwise conservative MC. */
    fun pValue(a: List<Double>, b: List<Double>): Double {
        require(a.size in MIN_RUNS..MAX_RUNS && b.size in MIN_RUNS..MAX_RUNS)
        summary(a); summary(b)
        // Normalize before summing, both for numeric stability and invariance to a common offset/scale.
        val values = (a.sorted() + b.sorted())
        val min = values.min()
        val span = values.max() - min
        if (span == 0.0) return 1.0
        val pool = values.map { (it - min) / span }.toDoubleArray()
        val total = pool.sum()
        val observed = abs(pool.take(a.size).average() - pool.drop(a.size).average())
        fun extreme(sumA: Double): Boolean =
            abs(sumA / a.size - (total - sumA) / b.size) >= observed - 1e-12
        var partitions = 1.0
        for (i in 1..minOf(a.size, b.size)) partitions *= (pool.size - i + 1).toDouble() / i
        var hits = 0
        var count = 0
        if (partitions <= 50000.0) {
            fun visit(start: Int, remaining: Int, sum: Double) {
                if (remaining == 0) { count++; if (extreme(sum)) hits++; return }
                for (i in start..pool.size - remaining) visit(i + 1, remaining - 1, sum + pool[i])
            }
            visit(0, a.size, 0.0)
            return hits.toDouble() / count
        }
        val random = Random(SEED)
        val indices = IntArray(pool.size) { it }
        repeat(RESAMPLES) {
            for (i in indices.indices) indices[i] = i
            var sum = 0.0
            for (i in a.indices) {
                val j = i + random.nextInt(pool.size - i)
                val tmp = indices[i]; indices[i] = indices[j]; indices[j] = tmp
                sum += pool[indices[i]]
            }
            if (extreme(sum)) hits++
        }
        return (hits + 1.0) / (RESAMPLES + 1.0)
    }

    fun adjustedP(p: Double, tests: Int) = (p * tests).coerceAtMost(1.0)
}
