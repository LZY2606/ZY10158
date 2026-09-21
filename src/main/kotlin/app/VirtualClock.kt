package app

/*
 * 可控虚拟时钟：分析过程完全由捕获文件的时间戳驱动，禁止使用墙钟。
 * 页面回放通过 advanceTo(index) 暴露“已经过到第几帧”，所有超时判定均为半开区间。
 */
class VirtualClock(
    private val timestampsUs: List<Long>,
    private val resetTimestampsUs: List<Long> = emptyList()
) {
    var nowUs: Long = timestampsUs.firstOrNull()?.let { 0L } ?: 0L
        private set
    private var index: Int = -1

    val totalEvents: Int get() = timestampsUs.size + resetTimestampsUs.size

    fun currentIndex(): Int = index

    fun advanceTo(eventOrdinal: Int): Long {
        val all = mergedEvents()
        val clamped = eventOrdinal.coerceIn(-1, all.lastIndex)
        index = clamped
        nowUs = if (clamped < 0) (all.firstOrNull()?.first ?: 0L) else all[clamped].first
        return nowUs
    }

    fun advance(): Long? {
        val all = mergedEvents()
        if (index >= all.lastIndex) return null
        return advanceTo(index + 1)
    }

    fun isExpired(deadlineUs: Long): Boolean {
        // 半开：t == deadline 即超时
        return nowUs >= deadlineUs
    }

    fun within(startUs: Long, timeoutUs: Long): Boolean =
        ReassemblyConfig.withinHalfOpen(nowUs, startUs + timeoutUs)

    private fun mergedEvents(): List<Pair<Long, Int>> =
        (timestampsUs.map { it to 0 } + resetTimestampsUs.map { it to 1 }).sortedBy { it.first }
}
