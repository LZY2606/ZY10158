package app

import java.math.BigInteger

/* ---------------- 时间与方向 ---------------- */

enum class Direction { REQ, RESP;
    fun opposite(): Direction = if (this == REQ) RESP else REQ
}

/* ---------------- 原始帧（永久只读） ---------------- */

data class RawFrame(
    val captureId: Long,
    val seq: Long,                 // 文件内行序，从 0 开始
    val canId: Int,
    val ext: Boolean,
    val data: ByteArray,
    val tsUs: Long,                // 虚拟时钟：捕获时间，微秒
    val declaredDirection: Direction,
    val rawLine: String,
    val resetId: Long? = null      // 该帧之前最近的复位边界
) {
    override fun equals(other: Any?) = other is RawFrame && other.seq == seq && other.captureId == captureId
    override fun hashCode() = captureId.hashCode() * 31 + seq.hashCode()
}

data class ResetBoundary(
    val id: Long,
    val captureId: Long,
    val afterSeq: Long,            // 在该 seq 的帧之后生效；-1 表示文件开头
    val tsUs: Long,
    val origin: String,            // fixture | human
    val canId: Int? = null         // null = 对所有 ECU ID 生效
)

/* ---------------- ISO-TP 帧分类 ---------------- */

enum class FrameClass { SF, FF, CF, FC, OTHER }

enum class FcFlowStatus { CONTINUE, WAIT, ABORT, RESERVED }

data class ParsedPci(
    val cls: FrameClass,
    val len: Int? = null,          // SF/FF 声明的载荷长度
    val sn: Int? = null,           // CF 序号（0..15）
    val fcStatus: FcFlowStatus? = null,
    val blockSize: Int? = null,
    val stMinUs: Int? = null,
    val reason: String? = null     // 解析失败原因（OTHER）
)

enum class AssemblyStatus {
    COMPLETE, WAIT_FC, WAIT_CF,
    TIMEOUT_FC, TIMEOUT_CF,
    SN_CONFLICT, ABORTED, INVALID
}

data class MissingRange(val sn: Int, val expectedLen: Int)

data class Evidence(
    val frameSeq: Long,
    val kind: String,   // retransmit | ignored-after-terminal | fc-wait-continue | fc-abort | fc-reserved | padding
    val detail: String
)

data class Assembly(
    val id: Long,
    val captureId: Long,
    val canId: Int,
    val direction: Direction,
    val generation: Int,
    val startFrameSeq: Long,
    val endFrameSeq: Long?,
    val status: AssemblyStatus,
    val payload: ByteArray,         // 已收到的字节（不完整时为部分载荷）
    val declaredLen: Int?,
    val receivedLen: Int,
    val missing: List<MissingRange>,
    val evidence: List<Evidence>,
    val startTsUs: Long,
    val endTsUs: Long?,
    val errorCode: String?,
    val firstDataFrameSeq: Long
) {
    val complete get() = status == AssemblyStatus.COMPLETE
    val serviceId: Int? get() = payload.firstOrNull()?.toInt()?.and(0xFF)
}

/* ---------------- 诊断 ---------------- */

enum class Severity { INFO, WARN, ERROR }

data class Diagnostic(
    val id: Long,
    val captureId: Long,
    val code: String,
    val severity: Severity,
    val frameSeq: Long?,
    val canId: Int?,
    val direction: Direction?,
    val generation: Int?,
    val message: String
)

/* ---------------- 请求/响应配对 ---------------- */

enum class LinkStatus { MATCHED, NEGATIVE, AMBIGUOUS, ORPHAN_REQUEST, REPLAY_RESPONSE, ORPHAN_RESPONSE }

data class LinkCandidate(val requestAssemblyId: Long, val score: Int, val reason: String)

data class ReqRespLink(
    val id: Long,
    val captureId: Long,
    val generation: Int,
    val requestAssemblyId: Long?,
    val responseAssemblyId: Long?,
    val status: LinkStatus,
    val candidates: List<LinkCandidate>,
    val serviceId: Int?,
    val nrc: Int?
)

/* ---------------- 人工判定（分层、版本化） ---------------- */

data class FrameAdjudication(
    val id: Long, val captureId: Long, val frameSeq: Long,
    val rejected: Boolean, val rejectReason: String?,
    val roleOverride: Direction?,
    val version: Int, val createdAtMs: Long
)

data class LinkAdjudication(
    val id: Long, val captureId: Long, val linkId: Long,
    val chosenRequestAssemblyId: Long?,
    val keepCandidates: Boolean,
    val note: String?,
    val version: Int, val createdAtMs: Long
)

/* ---------------- 派生视图（机器层 + 人工层投影） ---------------- */

data class EffectiveFrame(
    val raw: RawFrame,
    val generation: Int,
    val effectiveDirection: Direction,
    val rejected: Boolean,
    val rejectReason: String?,
    val pci: ParsedPci,
    val assemblyId: Long?
)

data class EffectiveAssembly(
    val assembly: Assembly,
    val rejected: Boolean
)

data class EffectiveLink(
    val link: ReqRespLink,
    val chosenRequestAssemblyId: Long?,
    val keepCandidates: Boolean,
    val note: String?
)

data class EffectiveView(
    val frames: List<EffectiveFrame>,
    val resets: List<ResetBoundary>,
    val assemblies: List<EffectiveAssembly>,
    val links: List<EffectiveLink>,
    val diagnostics: List<Diagnostic>,
    val generations: List<GenerationInfo>,
    val machineRunId: Long
)

data class GenerationInfo(
    val generation: Int,
    val canId: Int,
    val direction: Direction?,   // null = 该代次影响该 ID 两个方向
    val startedAfterSeq: Long,
    val startTsUs: Long
)

/* ---------------- 配置 ---------------- */

data class ReassemblyConfig(
    val fcTimeoutUs: Long = 100_000L,   // BS 流控超时（默认 100ms）
    val crTimeoutUs: Long = 250_000L    // 连续帧间隙超时（默认 250ms）
) {
    companion object {
        /* 半开时间窗：deadline = 起点 + timeout；t < deadline 在窗内，t == deadline 已超时。 */
        fun withinHalfOpen(tUs: Long, deadlineUs: Long): Boolean = tUs < deadlineUs
    }
}

/* ---------------- 捕获文件 ---------------- */

data class Capture(
    val id: Long,
    val sha256: String,
    val filename: String,
    val importedAtMs: Long,
    val frameCount: Int,
    val resetCount: Int,
    val published: Boolean,
    val latestRunId: Long?
)

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

fun ByteArray.toHexCompact(): String = joinToString("") { "%02X".format(it) }

fun parseHexCompact(s: String): ByteArray {
    val clean = s.trim().replace(" ", "")
    require(clean.length % 2 == 0) { "hex 长度必须为偶数: $s" }
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}

fun canIdToHex(id: Int, ext: Boolean = false): String {
    val digits = if (ext) 8 else 3
    return "%0${digits}X".format(id and (if (ext) 0x1FFFFFFF else 0x7FF))
}

fun hashToBig(s: String): BigInteger = BigInteger(s, 16)
