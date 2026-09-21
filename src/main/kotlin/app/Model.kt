package app

/**
 * Domain model for the CAN diagnostic capture analyzer.
 *
 * Layering:
 *  - L0 raw:   RawFrame + RestartDirective (immutable once imported; content-addressed by file hash)
 *  - L1 parse: ParserRun, Message, Diagnostic (reproduced from L0 + review inputs, versioned)
 *  - L2 review: frame / attribution / restart judgments (versioned, never overwritten by a re-parse)
 */

const val N_BS_TIMEOUT_MS = 100L
const val N_CR_TIMEOUT_MS = 100L
const val P2_TIMEOUT_MS = 500L

enum class Direction { TX, RX }

enum class FrameType { SF, FF, CF, FC, UNKNOWN }

enum class MessageStatus { COMPLETE, INCOMPLETE, CONFLICT, TIMEOUT, UNEXPECTED }

enum class DiagnosticCode {
    ISO_TIMEOUT_N_BS,
    ISO_TIMEOUT_N_CR,
    ISO_SEQUENCE_CONFLICT,
    ISO_DUPLICATE_RETRANSMIT,
    ISO_UNEXPECTED_FRAME,
    ISO_CROSS_RESTART_TRUNCATION,
    ISO_MALFORMED_FRAME,
    ISO_LENGTH_MISMATCH,
    PAIR_ORPHAN_RESPONSE,
    PAIR_REPLAY_SUSPECT,
    PAIR_AMBIGUOUS,
    PAIR_UNANSWERED_REQUEST,
    FRAME_REJECTED,
    RESTART_DETECTED
}

enum class Severity { INFO, WARNING, ERROR }

/** L0: one raw CAN frame, permanently read-only. */
data class RawFrame(
    val id: Long = 0,
    val captureId: Long,
    val seq: Int,
    val offsetMs: Long,
    val canId: Int,
    val direction: Direction,
    val data: ByteArray,
) {
    val frameType: FrameType get() = IsoTp.classify(data)
    override fun equals(other: Any?) = other is RawFrame && other.id == id
    override fun hashCode() = id.hashCode()
}

/** A `# restart` directive embedded in a capture file (imported, not a human judgment). */
data class RestartDirective(
    val id: Long = 0,
    val captureId: Long,
    val offsetMs: Long,
    val scopeCanId: Int?,
    val note: String,
)

data class Capture(
    val id: Long = 0,
    val name: String,
    val fileSha256: String,
    val frameCount: Int,
    val importedAtMs: Long,
    val reviewVersion: Long = 1,
)

data class ParserRun(
    val id: Long = 0,
    val captureId: Long,
    val runNumber: Int,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val reviewVersionAtRun: Long,
    val published: Boolean,
)

/** L1: one reassembled message (single or multi-frame). */
data class Message(
    val id: Long = 0,
    val runId: Long,
    val captureId: Long,
    val localKey: String,
    val canId: Int,
    val direction: Direction,
    val gen: Int,
    val firstFrameSeq: Int,
    val lastFrameSeq: Int,
    val startMs: Long,
    val endMs: Long,
    val multiFrame: Boolean,
    val expectedLength: Int?,
    val payload: ByteArray,
    val missingIndices: List<Int>,
    val receivedFrameSeqs: List<Int>,
    val status: MessageStatus,
    val statusDetail: String,
    val pairedMessageId: Long? = null,
    val sid: Int? = null,
    val candidateMessageIds: List<Int> = emptyList(),
)

data class Diagnostic(
    val id: Long = 0,
    val runId: Long = 0,
    val captureId: Long,
    val code: DiagnosticCode,
    val severity: Severity,
    val offsetMs: Long?,
    val canId: Int?,
    val direction: Direction?,
    val gen: Int?,
    val frameSeq: Int?,
    val messageLocalKey: String?,
    val detail: String,
)

/** L2 review: per-frame human judgment, versioned. */
data class FrameReview(
    val frameId: Long,
    val rejected: Boolean,
    val roleOverride: Direction?,
    val note: String,
    val version: Long,
    val updatedAtMs: Long,
)

/** L2 review: human resolution of an ambiguous request/response attribution. */
data class AttributionReview(
    val messageLocalKey: String,
    val choiceLocalKey: String?,
    val keepBoth: Boolean,
    val note: String,
    val version: Long,
    val updatedAtMs: Long,
)

/** L2 review: human-marked ECU restart boundary. */
data class RestartMark(
    val id: Long = 0,
    val captureId: Long,
    val offsetMs: Long,
    val scopeCanId: Int?,
    val note: String,
    val createdAtMs: Long,
)

data class MessageWithPayloadKey(
    val message: Message,
    val frameRole: Direction,
    val frameGen: Int,
)
