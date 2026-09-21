package app

/**
 * Read-model JSON DTOs shared by the API and the browser replay page.
 */
object ApiDtos {

    private fun directionOf(m: Message): Direction = m.direction

    fun capture(c: Capture, runs: List<ParserRun>): Map<String, Any?> = linkedMapOf(
        "id" to c.id,
        "name" to c.name,
        "fileSha256" to c.fileSha256,
        "frameCount" to c.frameCount,
        "importedAtMs" to c.importedAtMs,
        "reviewVersion" to c.reviewVersion,
        "runs" to runs.map { runSummary(it) }
    )

    fun runSummary(r: ParserRun) = linkedMapOf(
        "id" to r.id,
        "runNumber" to r.runNumber,
        "startedAtMs" to r.startedAtMs,
        "finishedAtMs" to r.finishedAtMs,
        "reviewVersionAtRun" to r.reviewVersionAtRun,
        "published" to r.published
    )

    fun frame(f: RawFrame, review: FrameReview?, gen: Int, rejected: Boolean, effectiveDirection: Direction) =
        linkedMapOf(
            "id" to f.id,
            "seq" to f.seq,
            "offsetMs" to f.offsetMs,
            "canId" to "%03X".format(f.canId),
            "direction" to f.direction.name,
            "effectiveDirection" to effectiveDirection.name,
            "dataHex" to Hex.encode(f.data),
            "frameType" to f.frameType.name,
            "gen" to gen,
            "rejected" to rejected,
            "review" to if (review == null) null else linkedMapOf(
                "rejected" to review.rejected,
                "roleOverride" to review.roleOverride?.name,
                "note" to review.note,
                "version" to review.version,
                "updatedAtMs" to review.updatedAtMs
            )
        )

    fun message(m: Message, localKeyToId: Map<String, Long>? = null, idToKey: Map<Long, String>? = null) = linkedMapOf(
        "id" to m.id,
        "localKey" to m.localKey,
        "canId" to "%03X".format(m.canId),
        "direction" to m.direction.name,
        "gen" to m.gen,
        "firstFrameSeq" to m.firstFrameSeq,
        "lastFrameSeq" to m.lastFrameSeq,
        "startMs" to m.startMs,
        "endMs" to m.endMs,
        "multiFrame" to m.multiFrame,
        "expectedLength" to m.expectedLength,
        "payloadHex" to Hex.encode(m.payload),
        "missingIndices" to m.missingIndices,
        "receivedFrameSeqs" to m.receivedFrameSeqs,
        "status" to m.status.name,
        "statusDetail" to m.statusDetail,
        "sid" to m.sid?.let { "%02X".format(it) },
        "pairedLocalKey" to (m.pairedMessageId?.let { idToKey?.get(it) }),
        "candidateLocalKeys" to m.candidateMessageIds.map { idx -> idToKey?.get(idx.toLong()) }
    )

    fun diagnostic(d: Diagnostic, idToKey: Map<Long, String>) = linkedMapOf(
        "id" to d.id,
        "code" to d.code.name,
        "severity" to d.severity.name,
        "offsetMs" to d.offsetMs,
        "canId" to d.canId?.let { "%03X".format(it) },
        "direction" to d.direction?.name,
        "gen" to d.gen,
        "frameSeq" to d.frameSeq,
        "messageLocalKey" to d.messageLocalKey,
        "detail" to d.detail
    )

    fun directive(d: RestartDirective) = linkedMapOf(
        "id" to d.id,
        "offsetMs" to d.offsetMs,
        "scopeCanId" to d.scopeCanId?.let { "%03X".format(it) },
        "note" to d.note,
        "source" to "CAPTURE"
    )

    fun mark(m: RestartMark) = linkedMapOf(
        "id" to m.id,
        "offsetMs" to m.offsetMs,
        "scopeCanId" to m.scopeCanId?.let { "%03X".format(it) },
        "note" to m.note,
        "createdAtMs" to m.createdAtMs,
        "source" to "HUMAN"
    )

    fun attribution(a: AttributionReview, candidates: List<String>, candidatesValid: List<String>) = linkedMapOf(
        "messageLocalKey" to a.messageLocalKey,
        "choiceLocalKey" to a.choiceLocalKey,
        "keepBoth" to a.keepBoth,
        "note" to a.note,
        "version" to a.version,
        "candidateLocalKeys" to candidates,
        "validCandidateLocalKeys" to candidatesValid
    )

    @Suppress("UNCHECKED_CAST")
    fun captureDetail(
        capture: Capture,
        runs: List<ParserRun>,
        frames: List<RawFrame>,
        reviews: Map<Long, FrameReview>,
        directives: List<RestartDirective>,
        marks: List<RestartMark>,
        gens: Map<Long, Int>,
        effDirs: Map<Long, Direction>,
        latestRun: ParserRun?,
        messages: List<Message>,
        diagnostics: List<Diagnostic>,
    ): Map<String, Any?> {
        val idToKey = messages.associate { it.id to it.localKey }
        val attributions = mutableListOf<Map<String, Any?>>()
        if (latestRun != null) {
            for (m in messages) {
                if (m.candidateMessageIds.isNotEmpty()) {
                    val review = reviews // not stored per frame here; attribution reviews loaded separately
                    val candidates = m.candidateMessageIds.mapNotNull { idToKey[it.toLong()] }
                    attributions += linkedMapOf(
                        "messageLocalKey" to m.localKey,
                        "candidateLocalKeys" to candidates,
                        "pairedLocalKey" to (m.pairedMessageId?.let { idToKey[it] }),
                        "sid" to m.sid?.let { "%02X".format(it) }
                    )
                }
            }
        }
        return linkedMapOf(
            "capture" to capture(capture, runs),
            "timeouts" to linkedMapOf("nBsMs" to N_BS_TIMEOUT_MS, "nCrMs" to N_CR_TIMEOUT_MS, "p2Ms" to P2_TIMEOUT_MS),
            "frames" to frames.map { frame(it, reviews[it.id], gens[it.id] ?: 0, reviews[it.id]?.rejected == true, effDirs[it.id] ?: it.direction) },
            "restartDirectives" to directives.map { directive(it) },
            "restartMarks" to marks.map { mark(it) },
            "latestRun" to (latestRun?.let { runSummary(it) }),
            "messages" to messages.map { message(it, idToKey = idToKey) },
            "diagnostics" to diagnostics.map { diagnostic(it, idToKey) },
            "ambiguities" to attributions
        )
    }
}
