package app

import java.util.TreeMap

data class EffectiveFrame(
    val frame: RawFrame,
    val effectiveDirection: Direction,
    val rejected: Boolean,
    val gen: Int,
    val boundaryStart: Boolean,
)

data class EngineResult(
    val messages: List<Message>,
    val diagnostics: List<Diagnostic>,
)

private class OpenMessage(
    var ff: RawFrame,
    var canId: Int,
    var dir: Direction,
    var gen: Int,
    var expectedLength: Int,
    var ffBytes: List<Byte>,
    var receivedSeqs: MutableList<Int>,
    /** transfer slot (1-based, runs 1..14,0..) -> CF payload */
    var slotPayloads: MutableMap<Int, ByteArray>,
    var nextSlot: Int,
    var awaitingFc: Boolean,
    var lastActivityMs: Long,
    var gap: Boolean,
) {
    val localKey: String get() = "m%03X-%s-g%d-f%d".format(canId, dir.name, gen, ff.seq)
}

/**
 * Simplified ISO 15765-2 reassembly.
 *
 * Frames are fully partitioned by data CAN ID, data direction and ECU generation: the state
 * machine never carries state across those boundaries, so frames from a different CAN ID,
 * direction or ECU life can never be concatenated into one payload. Flow-control frames
 * travel in the opposite direction and are routed onto the peer's open transfer using the
 * standard UDS physical-ID mapping; they never start a transfer of their own.
 *
 * Timeouts use a half-open window on the injected virtual clock: a frame exactly at
 * start + N_Bs / N_CR is still on time ([0, timeout] valid); only arrival strictly greater
 * than the timeout aborts the transfer.
 */
class ReassemblyEngine(
    private val captureId: Long,
    private val nBs: Long = N_BS_TIMEOUT_MS,
    private val nCr: Long = N_CR_TIMEOUT_MS,
) {
    private val messages = ArrayList<Message>()
    private val diagnostics = ArrayList<Diagnostic>()
    private var nextId = 1L

    fun run(effective: List<EffectiveFrame>, virtualNowMs: Long): EngineResult {
        // Data channel key: (CAN ID of the data, direction of the data, gen).
        val channels = LinkedHashMap<Triple<Int, Direction, Int>, MutableList<EffectiveFrame>>()
        for (ef0 in effective) {
            val ef = ef0
            if (ef.rejected) {
                diagnostics += Diagnostic(
                    captureId = captureId, code = DiagnosticCode.FRAME_REJECTED,
                    severity = Severity.INFO, offsetMs = ef.frame.offsetMs, canId = ef.frame.canId,
                    direction = ef.effectiveDirection, gen = ef.gen, frameSeq = ef.frame.seq,
                    messageLocalKey = null, detail = "frame rejected by human review; excluded from reassembly"
                )
                continue
            }
            val f = ef.frame
            val dataKey: Triple<Int, Direction, Int>? = when (f.frameType) {
                FrameType.FC -> {
                    // FC rides the opposite CAN ID; route to the peer data channel.
                    val peerId = IsoTp.peerCanId(f.canId)
                    val peerDir = if (ef.effectiveDirection == Direction.TX) Direction.RX else Direction.TX
                    if (peerId != null) Triple(peerId, peerDir, ef.gen)
                    else {
                        diagnostics += Diagnostic(
                            id = 0, runId = 0, captureId = captureId,
                            code = DiagnosticCode.ISO_UNEXPECTED_FRAME, severity = Severity.WARNING,
                            offsetMs = f.offsetMs, canId = f.canId,
                            direction = ef.effectiveDirection, gen = ef.gen, frameSeq = f.seq,
                            messageLocalKey = null,
                            detail = "flow control on non-standard CAN ID ${"%03X".format(f.canId)}; cannot route"
                        )
                        null
                    }
                }
                else -> Triple(f.canId, ef.effectiveDirection, ef.gen)
            }
            if (dataKey != null) channels.getOrPut(dataKey) { ArrayList() }.add(ef)
        }

        // For each channel the deadline is either the next generation's first frame
        // (a restart truncates the open transfer) or the virtual end of the capture.
        val firstFrameByGen = HashMap<Pair<Int, Direction>, TreeMap<Int, Long>>()
        for (entry0 in channels) {
            val k = entry0.key
            val frames = entry0.value
            firstFrameByGen.getOrPut(k.first to k.second) { TreeMap() }
                .putIfAbsent(k.third, frames.first().frame.offsetMs)
        }

        for (entry1 in channels) {
            val key = entry1.key
            val frames = entry1.value
            val canId = key.first
            val dir = key.second
            val gen = key.third
            val nextEntry = firstFrameByGen.getValue(canId to dir).higherEntry(gen)
            val state = State(canId, dir, gen)
            for (ef in frames) state.accept(ef)
            state.finalize(
                deadlineMs = nextEntry?.value ?: virtualNowMs,
                nextGenStartsAt = nextEntry?.value
            )
        }
        return EngineResult(
            messages.sortedWith(compareBy({ it.startMs }, { it.firstFrameSeq })),
            diagnostics.sortedWith(compareBy({ it.offsetMs ?: Long.MAX_VALUE }, { it.frameSeq ?: Int.MAX_VALUE }))
        )
    }

    private inner class State(val canId: Int, val dir: Direction, val gen: Int) {
        private var open: OpenMessage? = null

        fun accept(ef: EffectiveFrame) {
            val f = ef.frame
            when (f.frameType) {
                FrameType.SF -> handleSingleFrame(ef)
                FrameType.FF -> handleFirstFrame(ef)
                FrameType.FC -> handleFlowControl(ef)
                FrameType.CF -> handleConsecutiveFrame(ef)
                FrameType.UNKNOWN -> unexpected(f, dir, gen,
                    "unclassified PCI nibble: data=${Hex.encode(f.data)}")
            }
        }

        private fun keyFor(f: RawFrame) = "m%03X-%s-g%d-f%d".format(canId, dir.name, gen, f.seq)

        private fun handleSingleFrame(ef: EffectiveFrame) {
            val f = ef.frame
            val cur = open
            if (cur != null) {
                unexpected(f, dir, gen, "single frame arrived while transfer ${cur.localKey} open")
                val hard = cur.gap
                if (hard) closeConflict(cur, f.offsetMs,
                    "transfer with missing slot(s) aborted by interleaved single frame")
                else closeIncomplete(cur, f.offsetMs, DiagnosticCode.ISO_UNEXPECTED_FRAME,
                    "transfer aborted by interleaved single frame")
            }
            val payload = IsoTp.sfPayload(f.data)
            emit(Message(
                id = nextId++, runId = 0, captureId = captureId, localKey = keyFor(f),
                canId = canId, direction = dir, gen = gen,
                firstFrameSeq = f.seq, lastFrameSeq = f.seq, startMs = f.offsetMs, endMs = f.offsetMs,
                multiFrame = false, expectedLength = payload.size, payload = payload,
                missingIndices = emptyList(), receivedFrameSeqs = listOf(f.seq),
                status = MessageStatus.COMPLETE, statusDetail = ""
            ))
        }

        private fun handleFirstFrame(ef: EffectiveFrame) {
            val f = ef.frame
            val cur = open
            if (cur != null) {
                unexpected(f, dir, gen, "new first frame arrived while transfer ${cur.localKey} open")
                if (cur.gap) closeConflict(cur, f.offsetMs,
                    "transfer with missing slot(s) aborted by interleaved first frame")
                else closeIncomplete(cur, f.offsetMs, DiagnosticCode.ISO_UNEXPECTED_FRAME,
                    "transfer aborted by interleaved first frame")
            }
            val total = IsoTp.ffLength(f.data)
            if (total < 8 || total > 4095) {
                unexpected(f, dir, gen, "first-frame length $total outside simplified range 8..4095")
                return
            }
            open = OpenMessage(
                ff = f, canId = canId, dir = dir, gen = gen, expectedLength = total,
                ffBytes = IsoTp.ffPayload(f.data).toList(),
                receivedSeqs = mutableListOf(f.seq),
                slotPayloads = HashMap(),
                nextSlot = 1,
                awaitingFc = true,
                lastActivityMs = f.offsetMs,
                gap = false,
            )
        }

        private fun handleFlowControl(ef: EffectiveFrame) {
            val f = ef.frame
            val cur = open
            if (cur == null || !cur.awaitingFc) {
                unexpected(f, dir, gen, "flow control without a pending first frame on peer channel")
                return
            }
            val fs = f.data.getOrElse(1) { 0 }.toInt() and 0xFF
            if (f.data.size < 3 || fs != 0) {
                unexpected(f, dir, gen, "only CTS flow control (FS=0) is modeled; got FS=$fs")
                return
            }
            val gapMs = f.offsetMs - cur.lastActivityMs
            if (gapMs > nBs) {
                // FC strictly after the half-open window: the sender already gave up.
                closeTimeout(cur, f.offsetMs, DiagnosticCode.ISO_TIMEOUT_N_BS,
                    "flow control ${gapMs}ms after first frame; N_Bs=${nBs}ms (half-open [0,$nBs])")
                unexpected(f, dir, gen, "late flow control after N_Bs timeout")
                return
            }
            cur.awaitingFc = false
            cur.lastActivityMs = f.offsetMs
        }

        private fun handleConsecutiveFrame(ef: EffectiveFrame) {
            val f = ef.frame
            val cur = open
            if (cur == null || cur.awaitingFc) {
                unexpected(f, dir, gen, "consecutive frame outside an active transfer")
                return
            }
            val gapMs = f.offsetMs - cur.lastActivityMs
            if (gapMs > nCr) {
                closeTimeout(cur, f.offsetMs, DiagnosticCode.ISO_TIMEOUT_N_CR,
                    "consecutive frame ${gapMs}ms after previous frame; N_Cr=${nCr}ms (half-open [0,$nCr])")
                unexpected(f, dir, gen, "consecutive frame after N_Cr timeout")
                return
            }

            val index = IsoTp.cfIndex(f.data)
            val payload = IsoTp.cfPayload(f.data)

            val previousSlot = cur.slotPayloads.keys.firstOrNull { slotSn(it) == index }
            if (previousSlot != null) {
                val previous = cur.slotPayloads.getValue(previousSlot)
                if (previous.contentEquals(payload)) {
                    diagnostics += Diagnostic(
                        captureId = captureId, code = DiagnosticCode.ISO_DUPLICATE_RETRANSMIT,
                        severity = Severity.INFO, offsetMs = f.offsetMs, canId = canId,
                        direction = dir, gen = gen, frameSeq = f.seq, messageLocalKey = cur.localKey,
                        detail = "CF index $index retransmitted with identical payload; evidence kept, bytes not appended"
                    )
                } else {
                    diagnostics += Diagnostic(
                        captureId = captureId, code = DiagnosticCode.ISO_SEQUENCE_CONFLICT,
                        severity = Severity.ERROR, offsetMs = f.offsetMs, canId = canId,
                        direction = dir, gen = gen, frameSeq = f.seq, messageLocalKey = cur.localKey,
                        detail = "CF index $index arrived with conflicting payload: was ${Hex.encode(previous)}, now ${Hex.encode(payload)}; later data does not overwrite"
                    )
                    closeConflict(cur, f.offsetMs,
                        "conflicting payload for CF sequence index $index at frame seq ${f.seq}")
                }
                return
            }

            val slot = IsoTp.slotFor(index, cur.nextSlot)
            if (slot > cur.nextSlot) {
                // At least one intermediate slot was dropped on the bus. Keep the bytes that
                // did arrive and remember the gap instead of silently pretending success.
                cur.gap = true
                diagnostics += Diagnostic(
                    captureId = captureId, code = DiagnosticCode.ISO_UNEXPECTED_FRAME,
                    severity = Severity.WARNING, offsetMs = f.offsetMs, canId = canId,
                    direction = dir, gen = gen, frameSeq = f.seq, messageLocalKey = cur.localKey,
                    detail = "CF index $index arrived while ${slot - cur.nextSlot} earlier slot(s) are missing; received bytes retained"
                )
            }
            cur.slotPayloads[slot] = payload
            cur.receivedSeqs += f.seq
            cur.lastActivityMs = f.offsetMs
            if (slot >= cur.nextSlot) cur.nextSlot = slot + 1

            if (!cur.gap) {
                val assembled = assemble(cur)
                if (assembled.size >= cur.expectedLength) closeComplete(cur, f.offsetMs, assembled)
            }
        }

        private fun slotSn(slot: Int): Int = if (slot % 15 == 0) 0 else slot % 15

        /**
         * Close an open transfer at the channel deadline. If a later generation exists for this
         * CAN ID + direction, the cause is the ECU restart: a multi-frame transfer split across a
         * restart is never reassembled into one payload.
         */
        fun finalize(deadlineMs: Long, nextGenStartsAt: Long?) {
            val cur = open ?: return
            if (nextGenStartsAt != null) {
                closeIncomplete(cur, nextGenStartsAt, DiagnosticCode.ISO_CROSS_RESTART_TRUNCATION,
                    "transfer cut by ECU restart boundary at t=$nextGenStartsAt ms; frames across the boundary are not concatenated")
                return
            }
            if (cur.awaitingFc) {
                val gapMs = deadlineMs - cur.lastActivityMs
                if (gapMs > nBs) {
                    closeTimeout(cur, deadlineMs, DiagnosticCode.ISO_TIMEOUT_N_BS,
                        "no flow control within N_Bs=${nBs}ms (half-open [0,$nBs]); capture ends ${gapMs}ms after first frame")
                } else {
                    closeIncomplete(cur, deadlineMs, null,
                        "capture ended while waiting for flow control (${gapMs}ms elapsed, within N_Bs)")
                }
            } else {
                val gapMs = deadlineMs - cur.lastActivityMs
                if (gapMs > nCr) {
                    closeTimeout(cur, deadlineMs, DiagnosticCode.ISO_TIMEOUT_N_CR,
                        "missing consecutive frames; N_Cr=${nCr}ms (half-open [0,$nCr]) exceeded by ${gapMs - nCr}ms")
                } else {
                    closeIncomplete(cur, deadlineMs, null,
                        "capture ended ${gapMs}ms after last frame (within N_Cr); expected ${cur.expectedLength} bytes")
                }
            }
        }

        private fun requiredChunks(cur: OpenMessage): Int =
            ((cur.expectedLength - cur.ffBytes.size) + 6) / 7

        private fun assemble(cur: OpenMessage): ByteArray {
            val out = ArrayList<Byte>(cur.expectedLength)
            out.addAll(cur.ffBytes)
            val n = requiredChunks(cur)
            for (slot in 1..n) {
                val chunk = cur.slotPayloads[slot]
                if (chunk != null) out.addAll(chunk.toList())
            }
            return out.toByteArray()
        }

        private fun missingSlots(cur: OpenMessage): List<Int> {
            val missing = ArrayList<Int>()
            for (slot in 1..requiredChunks(cur)) {
                if (slot !in cur.slotPayloads) missing += slotSn(slot)
            }
            return missing
        }

        private fun closeComplete(cur: OpenMessage, endMs: Long, assembled: ByteArray) {
            val payload = assembled.copyOf(cur.expectedLength)
            if (assembled.size > cur.expectedLength) {
                diagnostics += Diagnostic(
                    captureId = captureId, code = DiagnosticCode.ISO_LENGTH_MISMATCH,
                    severity = Severity.WARNING, offsetMs = endMs, canId = canId,
                    direction = dir, gen = gen, frameSeq = null, messageLocalKey = cur.localKey,
                    detail = "received ${assembled.size} bytes for declared length ${cur.expectedLength}; truncated"
                )
            }
            emit(cur.toMessage(payload, emptyList(), MessageStatus.COMPLETE, "", endMs))
            open = null
        }

        private fun closeTimeout(cur: OpenMessage, endMs: Long, code: DiagnosticCode, detail: String) {
            diagnostics += Diagnostic(
                captureId = captureId, code = code, severity = Severity.ERROR,
                offsetMs = cur.lastActivityMs, canId = canId, direction = dir, gen = gen,
                frameSeq = null, messageLocalKey = cur.localKey, detail = detail
            )
            emit(cur.toMessage(assemble(cur), missingSlots(cur), MessageStatus.TIMEOUT, detail, endMs))
            open = null
        }

        private fun closeConflict(cur: OpenMessage, endMs: Long, detail: String) {
            emit(cur.toMessage(assemble(cur), missingSlots(cur), MessageStatus.CONFLICT, detail, endMs))
            open = null
        }

        private fun closeIncomplete(cur: OpenMessage, endMs: Long, code: DiagnosticCode?, detail: String) {
            if (code != null) diagnostics += Diagnostic(
                captureId = captureId, code = code, severity = Severity.ERROR,
                offsetMs = cur.lastActivityMs, canId = canId, direction = dir, gen = gen,
                frameSeq = null, messageLocalKey = cur.localKey, detail = detail
            )
            emit(cur.toMessage(assemble(cur), missingSlots(cur), MessageStatus.INCOMPLETE, detail, endMs))
            open = null
        }

        private fun OpenMessage.toMessage(
            payload: ByteArray, missing: List<Int>, status: MessageStatus, detail: String, endMs: Long
        ) = Message(
            id = nextId++, runId = 0, captureId = captureId, localKey = localKey,
            canId = canId, direction = dir, gen = gen, firstFrameSeq = ff.seq,
            lastFrameSeq = receivedSeqs.lastOrNull() ?: ff.seq, startMs = ff.offsetMs, endMs = endMs,
            multiFrame = true, expectedLength = expectedLength, payload = payload,
            missingIndices = missing, receivedFrameSeqs = receivedSeqs.toList(),
            status = status, statusDetail = detail
        )

        private fun emit(m: Message) { messages += m }

        private fun unexpected(f: RawFrame, d: Direction, g: Int, detail: String) {
            diagnostics += Diagnostic(
                id = 0, runId = 0, captureId = captureId,
                code = DiagnosticCode.ISO_UNEXPECTED_FRAME, severity = Severity.WARNING,
                offsetMs = f.offsetMs, canId = f.canId, direction = d, gen = g, frameSeq = f.seq,
                messageLocalKey = null, detail = detail
            )
        }
    }
}
