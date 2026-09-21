package app

import java.util.TreeMap

internal data class StreamKey(val canId: Int, val direction: Direction, val generation: Int)

/*
 * 重组引擎（纯函数式，时间完全由捕获虚拟时钟驱动，无墙钟依赖）。
 *
 * 隔离键：CAN ID + 方向 + 代次。
 * 代次：每条 (canId, direction) 流独立计数；#RESET 或人工边界使其递增，跨重启帧绝不拼接。
 * 时间窗：半开区间 [start, start+timeout)；t == deadline 即超时。
 */
data class ReassemblyResult(
    val assemblies: List<Assembly>,
    val diagnostics: List<Diagnostic>,
    val frameAssembly: Map<Long, Long>,
    val generations: List<GenerationInfo>,
    val effectiveFrames: List<EffectiveFrame>
)

internal class OpenAssembly(
    var id: Long,
    val canId: Int,
    val direction: Direction,
    var generation: Int,
    val startFrameSeq: Long,
    val startTsUs: Long,
    var declaredLen: Int
) {
    var status = AssemblyStatus.WAIT_FC
    var errorCode: String? = null
    var endFrameSeq: Long? = null
    var endTsUs: Long? = null
    val payload = ArrayList<Byte>()
    val missing = ArrayList<MissingRange>()
    val evidence = ArrayList<Evidence>()
    val lateEvidence = ArrayList<Evidence>()
    var expectedSn = 1
    var blockRemaining = -1
    var deadlineUs = 0L
    var lastSeenTs = startTsUs
    var terminated = false
    val pendingCf = TreeMap<Int, Pair<Long, ByteArray>>()
    val receivedCf = HashMap<Int, ByteArray>()
    var firstDataFrameSeq = startFrameSeq

    fun receivedLen() = payload.size
}

class Reassembler(internal val cfg: ReassemblyConfig = ReassemblyConfig()) {

    internal var diagId = 0L
    internal var asmId = 0L
    internal val diags = ArrayList<Diagnostic>()
    internal val completed = ArrayList<OpenAssembly>()
    internal val frameAssembly = HashMap<Long, Long>()
    internal val open = HashMap<StreamKey, OpenAssembly>()
    internal val genCounter = HashMap<Pair<Int, Direction>, Int>()
    internal val generationEvents = ArrayList<GenerationInfo>()
    internal val knownCanIds = HashSet<Int>()
    internal val lastFinished = HashMap<StreamKey, OpenAssembly>()

    internal fun diag(code: String, sev: Severity, seq: Long?, id: Int?, dir: Direction?, gen: Int?, msg: String) {
        diags.add(Diagnostic(diagId++, 0L, code, sev, seq, id, dir, gen, msg))
    }

    internal fun generationOf(canId: Int, dir: Direction): Int = genCounter[canId to dir] ?: 0

    fun run(
        frames: List<RawFrame>,
        resets: List<ResetBoundary>,
        rejections: Set<Long> = emptySet(),
        roleOverrides: Map<Long, Direction> = emptyMap()
    ): ReassemblyResult {
        val effective = ArrayList<EffectiveFrame>(frames.size)
        val resetAfter = resets.groupBy { it.afterSeq }

        for (frame in frames) {
            knownCanIds.add(frame.canId)
            expireOpenAssemblies(frame.tsUs, frame.seq)
            resetAfter[frame.seq - 1]?.forEach { applyReset(it, frame.seq, frame.tsUs) }

            val rejected = frame.seq in rejections
            val effDir = roleOverrides[frame.seq] ?: frame.declaredDirection
            val pci = IsoTp.classify(frame.data)

            if (rejected) {
                diag("FRAME_REJECTED", Severity.INFO, frame.seq, frame.canId, effDir,
                    generationOf(frame.canId, effDir), "帧已被人工标记为污染帧并拒绝")
                effective.add(EffectiveFrame(frame, generationOf(frame.canId, effDir), effDir, true, null, pci, null))
                continue
            }

            processFrame(frame, effDir, pci)
            effective.add(EffectiveFrame(
                frame, generationOf(frame.canId, effDir), effDir, false, null, pci, frameAssembly[frame.seq]
            ))
        }
        for (a in open.values.sortedBy { it.startTsUs }) {
            val code = if (a.status == AssemblyStatus.WAIT_FC) "FLOW_CONTROL_TIMEOUT" else "CONSECUTIVE_FRAME_TIMEOUT"
            val st = if (a.status == AssemblyStatus.WAIT_FC) AssemblyStatus.TIMEOUT_FC else AssemblyStatus.TIMEOUT_CF
            terminateOpen(a, st, code, a.endFrameSeq ?: a.startFrameSeq, a.endTsUs ?: a.lastSeenTs)
        }
        open.clear()
        return ReassemblyResult(
            completed.sortedBy { it.startFrameSeq }.map { toAssembly(it) },
            diags.sortedBy { it.id },
            frameAssembly,
            generationEvents.sortedWith(compareBy({ it.startedAfterSeq }, { it.canId }, { it.direction })),
            effective
        )
    }

    internal fun applyReset(r: ResetBoundary, atSeq: Long, tsUs: Long) {
        val affected: List<Pair<Int, Direction>> = if (r.canId != null) {
            Direction.entries.map { r.canId to it }
        } else {
            knownCanIds.flatMap { id -> Direction.entries.map { d -> id to d } }
        }
        for ((id, dir) in affected) {
            val key = StreamKey(id, dir, genCounter[id to dir] ?: 0)
            open[key]?.let { a ->
                terminateOpen(a, AssemblyStatus.ABORTED, "RESET_DURING_REASSEMBLY", atSeq - 1, r.tsUs)
            }
            val next = (genCounter[id to dir] ?: 0) + 1
            genCounter[id to dir] = next
            generationEvents.add(GenerationInfo(next, id, dir, r.afterSeq, tsUs))
        }
    }

    internal fun expireOpenAssemblies(nowUs: Long, nextSeq: Long) {
        for (a in open.values.toList()) {
            if (a.terminated || a.deadlineUs == 0L) continue
            if (ReassemblyConfig.withinHalfOpen(nowUs, a.deadlineUs)) continue
            val (status, code) = when (a.status) {
                AssemblyStatus.WAIT_FC -> AssemblyStatus.TIMEOUT_FC to "FLOW_CONTROL_TIMEOUT"
                AssemblyStatus.WAIT_CF -> AssemblyStatus.TIMEOUT_CF to "CONSECUTIVE_FRAME_TIMEOUT"
                else -> continue
            }
            // 超时在下一事件时刻被观测到：终止位置记为“下一事件前一帧”，时间记为 deadline
            terminateOpen(a, status, code, nextSeq - 1, a.deadlineUs)
            diag(code, Severity.ERROR, nextSeq, a.canId, a.direction, a.generation,
                "重组在虚拟时钟 ${a.deadlineUs}us 超时（半开窗 [start,deadline)），下一事件 now=${nowUs}us seq=$nextSeq")
        }
    }
}

/* ============================ 帧处理 ============================ */

private fun Reassembler.processFrame(frame: RawFrame, dir: Direction, pci: ParsedPci): OpenAssembly? {
    val gen = generationOf(frame.canId, dir)
    return when (pci.cls) {
        FrameClass.SF -> handleSf(frame, dir, gen, pci)
        FrameClass.FF -> handleFf(frame, dir, gen, pci)
        FrameClass.CF -> handleCf(frame, dir, gen, pci)
        FrameClass.FC -> handleFc(frame, dir, gen, pci)
        FrameClass.OTHER -> {
            diag("INVALID_PCI", Severity.WARN, frame.seq, frame.canId, dir, gen,
                "无法解析 ISO-TP 帧：${pci.reason ?: "未知 PCI"}，原始=${frame.data.toHex()}")
            null
        }
    }
}

private fun Reassembler.handleSf(frame: RawFrame, dir: Direction, gen: Int, pci: ParsedPci): OpenAssembly {
    val payload = IsoTp.sfPayload(frame.data, pci)
    val a = OpenAssembly(asmId++, frame.canId, dir, gen, frame.seq, frame.tsUs, pci.len!!)
    a.firstDataFrameSeq = frame.seq
    a.payload.addAll(payload.toList())
    a.status = AssemblyStatus.COMPLETE
    a.deadlineUs = 0L
    a.endFrameSeq = frame.seq
    a.endTsUs = frame.tsUs
    publish(a)
    return a
}

private fun Reassembler.handleFf(frame: RawFrame, dir: Direction, gen: Int, pci: ParsedPci): OpenAssembly {
    val key = StreamKey(frame.canId, dir, gen)
    open[key]?.let { old ->
        old.evidence.add(Evidence(frame.seq, "ignored-after-terminal",
            "新 FF 到达时旧重组仍未完成（${old.status}），旧重组作废"))
        terminateOpen(old, AssemblyStatus.ABORTED, "ABORTED_BY_NEW_FF", frame.seq - 1, frame.tsUs)
        diag("UNEXPECTED_FF", Severity.WARN, frame.seq - 1, frame.canId, dir, gen,
            "旧多帧重组未结束即收到新 FF（seq=${frame.seq}）")
    }
    val len12 = ((frame.data[0].toInt() and 0x0F) shl 8) or (frame.data[1].toInt() and 0xFF)
    val esc = len12 == 0
    val head = IsoTp.ffPayload(frame.data, esc)
    val a = OpenAssembly(asmId++, frame.canId, dir, gen, frame.seq, frame.tsUs, pci.len!!)
    a.firstDataFrameSeq = frame.seq
    a.payload.addAll(head.toList())
    a.status = AssemblyStatus.WAIT_FC
    a.deadlineUs = frame.tsUs + cfg.fcTimeoutUs
    a.lastSeenTs = frame.tsUs
    a.endFrameSeq = frame.seq
    a.endTsUs = frame.tsUs
    open[key] = a
    frameAssembly[frame.seq] = a.id
    return a
}

private fun Reassembler.handleFc(frame: RawFrame, dir: Direction, gen: Int, pci: ParsedPci): OpenAssembly? {
    // 方向=数据流归属；FC 在对端 CAN ID 上发送但与被唤醒的数据流同方向、同代次。
    val dataCanId = CaptureParser.peerCanId(frame.canId)
    if (dataCanId == null) {
        diag("UNEXPECTED_FC", Severity.WARN, frame.seq, frame.canId, dir, gen,
            "FC 帧所在 ID ${canIdToHex(frame.canId)} 无已知 UDS 对端 ID")
        return null
    }
    // FC 所在 ID 的代次与数据流独立，因此这里按“对端 ID + 同方向”找 WAIT_FC，
    // 并校验 FC 不早于该 FF（防止旧代次 FC 唤醒新 FF）。
    val openKey = open.keys.firstOrNull {
        it.canId == dataCanId && it.direction == dir && open[it]?.status == AssemblyStatus.WAIT_FC
    }
    val a = openKey?.let { open[it] }
    if (a == null) {
        diag("UNEXPECTED_FC", Severity.WARN, frame.seq, frame.canId, dir, gen,
            "FC 无对应等待中的 FF（数据流 ${canIdToHex(dataCanId)} $dir）")
        return null
    }
    if (frame.tsUs < a.startTsUs) {
        diag("FC_WRONG_GENERATION", Severity.WARN, frame.seq, frame.canId, dir, gen,
            "FC 时间戳早于它匹配的 FF，疑似旧代次残留")
        return null
    }
    frameAssembly[frame.seq] = a.id
    when (pci.fcStatus!!) {
        FcFlowStatus.CONTINUE -> {
            a.status = AssemblyStatus.WAIT_CF
            a.blockRemaining = if (pci.blockSize == 0) -1 else pci.blockSize!!
            a.deadlineUs = frame.tsUs + cfg.crTimeoutUs
            a.evidence.add(Evidence(frame.seq, "fc-wait-continue", "CTS BS=${pci.blockSize} STmin=${pci.stMinUs}us"))
        }
        FcFlowStatus.WAIT -> {
            a.deadlineUs = frame.tsUs + cfg.fcTimeoutUs
            a.evidence.add(Evidence(frame.seq, "fc-wait-continue", "收到 FC.WAIT，重新计时"))
        }
        FcFlowStatus.ABORT -> {
            a.evidence.add(Evidence(frame.seq, "fc-abort", "发送方以 FC.ABORT 中止"))
            terminateOpen(a, AssemblyStatus.ABORTED, "FLOW_CONTROL_ABORT", frame.seq, frame.tsUs)
            diag("FLOW_CONTROL_ABORT", Severity.ERROR, frame.seq, frame.canId, dir, gen, "FC=ABORT，重组中止")
        }
        FcFlowStatus.RESERVED -> {
            a.evidence.add(Evidence(frame.seq, "fc-reserved", "FC FS 为保留值"))
            terminateOpen(a, AssemblyStatus.ABORTED, "FLOW_CONTROL_RESERVED", frame.seq, frame.tsUs)
            diag("FLOW_CONTROL_ABORT", Severity.ERROR, frame.seq, frame.canId, dir, gen, "FC FS 为保留值，重组中止")
        }
    }
    return a
}

private fun Reassembler.handleCf(frame: RawFrame, dir: Direction, gen: Int, pci: ParsedPci): OpenAssembly? {
    if (frame.data.size < 2) {
        diag("INVALID_PCI", Severity.WARN, frame.seq, frame.canId, dir, gen, "CF 帧无数据字节")
        return null
    }
    val key = StreamKey(frame.canId, dir, gen)
    var a = open[key]
    val sn = pci.sn!!
    val bytes = frame.data.copyOfRange(1, frame.data.size)

    if (a == null) {
        val prior = lastFinished[key]
        if (prior != null) {
            val priorBytes = prior.receivedCf[sn]
            if (priorBytes != null) {
                if (priorBytes.contentEquals(bytes)) {
                    prior.lateEvidence.add(Evidence(frame.seq, "retransmit",
                        "重组已结束；CF(SN=$sn) 迟到重传，载荷一致，仅保留证据"))
                    diag("CF_RETRANSMIT", Severity.INFO, frame.seq, frame.canId, dir, gen,
                        "CF(SN=$sn) 在重组结束后重传，载荷一致")
                } else {
                    prior.lateEvidence.add(Evidence(frame.seq, "ignored-after-terminal",
                        "重组已结束；CF(SN=$sn) 载荷冲突，拒绝覆盖并标记冲突"))
                    prior.status = AssemblyStatus.SN_CONFLICT
                    prior.errorCode = "SN_CONFLICT"
                    diag("SN_CONFLICT", Severity.ERROR, frame.seq, frame.canId, dir, gen,
                        "重组结束后 CF(SN=$sn) 出现不同载荷，冲突已拒绝，已收载荷不变")
                }
                frameAssembly[frame.seq] = prior.id
            } else {
                diag("UNEXPECTED_CF", Severity.WARN, frame.seq, frame.canId, dir, gen,
                    "CF(SN=$sn) 无对应活动重组，且该 SN 未被接收过")
            }
            return prior
        }
        diag("UNEXPECTED_CF", Severity.WARN, frame.seq, frame.canId, dir, gen,
            "CF 无对应多帧重组（ID=${canIdToHex(frame.canId)} 代次=$gen）")
        return null
    }

    if (a.status == AssemblyStatus.WAIT_FC) {
        diag("CF_BEFORE_FLOW_CONTROL", Severity.ERROR, frame.seq, frame.canId, dir, gen,
            "尚未收到 FC 即到达 CF(SN=$sn)")
        terminateOpen(a, AssemblyStatus.INVALID, "CF_BEFORE_FLOW_CONTROL", frame.seq, frame.tsUs)
        return a
    }

    // 半开窗：CF 在 deadline 端点或之后到达，不得接收
    if (a.status == AssemblyStatus.WAIT_CF && frame.tsUs >= a.deadlineUs) {
        a.evidence.add(Evidence(frame.seq, "ignored-after-terminal",
            "CF(SN=$sn) 到达时刻 ${frame.tsUs} 不早于截止 ${a.deadlineUs}，按超时丢弃"))
        terminateOpen(a, AssemblyStatus.TIMEOUT_CF, "CONSECUTIVE_FRAME_TIMEOUT", frame.seq - 1, a.lastSeenTs)
        diag("CONSECUTIVE_FRAME_TIMEOUT", Severity.ERROR, frame.seq, frame.canId, dir, gen,
            "CF 落在半开窗端点/之后（t=${frame.tsUs}, deadline=${a.deadlineUs}）")
        return a
    }

    if (a.status != AssemblyStatus.WAIT_CF) {
        val priorBytes = a.receivedCf[sn]
        if (priorBytes != null && !priorBytes.contentEquals(bytes)) {
            a.lateEvidence.add(Evidence(frame.seq, "ignored-after-terminal",
                "状态 ${a.status} 下 CF(SN=$sn) 载荷冲突，拒绝覆盖"))
            diag("SN_CONFLICT", Severity.ERROR, frame.seq, frame.canId, dir, gen,
                "CF(SN=$sn) 冲突（状态=${a.status}），后来者未覆盖")
        } else {
            a.lateEvidence.add(Evidence(frame.seq, "ignored-after-terminal",
                "状态 ${a.status} 下收到 CF(SN=$sn)，忽略"))
        }
        frameAssembly[frame.seq] = a.id
        return a
    }

    val existing = a.receivedCf[sn] ?: a.pendingCf[sn]?.second
    if (existing != null) {
        if (existing.contentEquals(bytes)) {
            a.evidence.add(Evidence(frame.seq, "retransmit",
                "CF(SN=$sn) 重复且载荷一致，按重传处理，不覆盖已收字节"))
            diag("CF_RETRANSMIT", Severity.INFO, frame.seq, frame.canId, dir, gen, "CF(SN=$sn) 重传，载荷一致")
            a.deadlineUs = frame.tsUs + cfg.crTimeoutUs
            a.lastSeenTs = frame.tsUs
            frameAssembly[frame.seq] = a.id
            return a
        }
        diag("SN_CONFLICT", Severity.ERROR, frame.seq, frame.canId, dir, gen,
            "CF(SN=$sn) 出现不同载荷，冲突，拒绝后来者覆盖")
        terminateOpen(a, AssemblyStatus.SN_CONFLICT, "SN_CONFLICT", frame.seq, frame.tsUs)
        return a
    }

    a.pendingCf[sn] = frame.seq to bytes
    a.endFrameSeq = frame.seq
    a.endTsUs = frame.tsUs
    val beforeLen = a.receivedLen()
    drainPending(a, frame.tsUs)
    // 仅当本帧真的贡献了新字节（窗口仍活动）才顺延截止时间；端点超时帧不刷新窗口
    if (!a.terminated && a.receivedLen() > beforeLen && a.receivedLen() < a.declaredLen) {
        if (a.blockRemaining == 0) {
            a.status = AssemblyStatus.WAIT_FC
            a.deadlineUs = frame.tsUs + cfg.fcTimeoutUs
        } else {
            a.status = AssemblyStatus.WAIT_CF
            a.deadlineUs = frame.tsUs + cfg.crTimeoutUs
        }
    }
    return a
}

/* ============================ 收尾辅助 ============================ */

private fun Reassembler.drainPending(a: OpenAssembly, currentTs: Long) {
    while (true) {
        val (seq, bytes) = a.pendingCf.remove(a.expectedSn) ?: break
        appendCf(a, seq, bytes, currentTs)
        a.expectedSn = (a.expectedSn + 1) and 0x0F
        if (a.expectedSn == 0) a.expectedSn = 1
        if (a.terminated) return
        if (a.receivedLen() >= a.declaredLen) {
            completeAssembly(a, seq, currentTs)
            return
        }
        if (a.blockRemaining > 0) {
            a.blockRemaining--
            if (a.blockRemaining == 0) {
                a.status = AssemblyStatus.WAIT_FC
                a.deadlineUs = currentTs + cfg.fcTimeoutUs
            }
        }
    }
    if (a.pendingCf.isNotEmpty() && !a.pendingCf.containsKey(a.expectedSn) &&
        a.missing.none { it.sn == a.expectedSn }) {
        val expectedLen = (a.declaredLen - a.receivedLen()).coerceAtLeast(0)
        a.missing.add(MissingRange(a.expectedSn, expectedLen))
    }
}

private fun Reassembler.appendCf(a: OpenAssembly, seq: Long, bytes: ByteArray, tsUs: Long) {
    a.receivedCf[a.expectedSn] = bytes
    var added = 0
    for (b in bytes) {
        if (a.payload.size >= a.declaredLen) {
            val pad = bytes.size - added
            a.evidence.add(Evidence(seq, "padding", "CF(SN=${a.expectedSn}) 尾部 $pad 字节为填充，不计入载荷"))
            break
        }
        a.payload.add(b); added++
    }
    a.lastSeenTs = tsUs
    frameAssembly[seq] = a.id
    a.missing.removeAll { it.sn == a.expectedSn }
}

private fun Reassembler.completeAssembly(a: OpenAssembly, lastSeq: Long, lastTs: Long) {
    a.status = AssemblyStatus.COMPLETE
    a.errorCode = null
    a.endFrameSeq = lastSeq
    a.endTsUs = lastTs
    a.missing.clear()
    publish(a)
}

private fun Reassembler.terminateOpen(a: OpenAssembly, status: AssemblyStatus, code: String, seq: Long, tsUs: Long) {
    if (a.terminated) return
    a.status = status
    a.errorCode = code
    a.endFrameSeq = seq
    a.endTsUs = tsUs
    a.terminated = true
    val key = StreamKey(a.canId, a.direction, a.generation)
    if (open[key] === a) open.remove(key)
    if (a.receivedLen() < a.declaredLen && a.missing.none { it.sn == a.expectedSn }) {
        a.missing.add(MissingRange(a.expectedSn, (a.declaredLen - a.receivedLen()).coerceAtLeast(0)))
    }
    publish(a)
}

private fun toAssembly(a: OpenAssembly): Assembly = Assembly(
    a.id, 0L, a.canId, a.direction, a.generation, a.startFrameSeq, a.endFrameSeq,
    a.status, a.payload.toByteArray(), a.declaredLen, a.payload.size,
    a.missing.toList(), (a.evidence + a.lateEvidence).sortedBy { it.frameSeq },
    a.startTsUs, a.endTsUs, a.errorCode, a.firstDataFrameSeq
)

private fun Reassembler.publish(a: OpenAssembly) {
    a.terminated = true
    frameAssembly.getOrPut(a.firstDataFrameSeq) { a.id }
    if (completed.none { it.id == a.id }) completed.add(a)
    val key = StreamKey(a.canId, a.direction, a.generation)
    if (open[key] === a) open.remove(key)
    lastFinished[key] = a
}
