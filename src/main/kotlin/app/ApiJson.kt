@file:JvmName("ApiJson")
package app

fun captureJson(c: Capture) = mapOf(
    "id" to c.id, "sha256" to c.sha256, "filename" to c.filename,
    "importedAtMs" to c.importedAtMs, "frameCount" to c.frameCount,
    "resetCount" to c.resetCount, "published" to c.published, "latestRunId" to c.latestRunId
)

private fun frameJson(f: EffectiveFrame, visible: Boolean) = mapOf(
    "seq" to f.raw.seq,
    "tsUs" to f.raw.tsUs,
    "tsText" to formatTs(f.raw.tsUs),
    "canId" to canIdToHex(f.raw.canId, f.raw.ext),
    "ext" to f.raw.ext,
    "direction" to f.effectiveDirection.name,
    "declaredDirection" to f.raw.declaredDirection.name,
    "data" to f.raw.data.toHexCompact(),
    "pciClass" to f.pci.cls.name,
    "pciReason" to f.pci.reason,
    "declaredLen" to f.pci.len,
    "sn" to f.pci.sn,
    "fcStatus" to f.pci.fcStatus?.name,
    "generation" to f.generation,
    "assemblyId" to (if (visible) f.assemblyId else null),
    "rejected" to f.rejected,
    "rejectReason" to f.rejectReason,
    "rawLine" to f.raw.rawLine
)

private fun assemblyJson(a: EffectiveAssembly, visible: Boolean) = mapOf(
    "id" to (if (visible) a.assembly.id else null),
    "canId" to canIdToHex(a.assembly.canId),
    "direction" to a.assembly.direction.name,
    "generation" to a.assembly.generation,
    "startFrameSeq" to a.assembly.startFrameSeq,
    "endFrameSeq" to a.assembly.endFrameSeq,
    "status" to a.assembly.status.name,
    "complete" to a.assembly.complete,
    "payload" to a.assembly.payload.toHexCompact(),
    "payloadText" to printablePayload(a.assembly.payload),
    "declaredLen" to a.assembly.declaredLen,
    "receivedLen" to a.assembly.receivedLen,
    "missing" to a.assembly.missing.map { mapOf("sn" to it.sn, "expectedLen" to it.expectedLen) },
    "evidence" to a.assembly.evidence.map {
        mapOf("frameSeq" to it.frameSeq, "kind" to it.kind, "detail" to it.detail)
    },
    "startTsUs" to a.assembly.startTsUs,
    "endTsUs" to a.assembly.endTsUs,
    "errorCode" to a.assembly.errorCode,
    "serviceId" to a.assembly.serviceId?.let { "%02X".format(it) },
    "rejected" to a.rejected
)

private fun linkJson(l: EffectiveLink, visible: Boolean) = mapOf(
    "id" to (if (visible) l.link.id else null),
    "generation" to l.link.generation,
    "requestAssemblyId" to l.link.requestAssemblyId,
    "responseAssemblyId" to l.link.responseAssemblyId,
    "status" to l.link.status.name,
    "serviceId" to l.link.serviceId?.let { "%02X".format(it) },
    "nrc" to l.link.nrc?.let { "%02X".format(it) },
    "candidates" to l.link.candidates.map {
        mapOf("requestAssemblyId" to it.requestAssemblyId, "score" to it.score, "reason" to it.reason)
    },
    "chosenRequestAssemblyId" to (if (visible) l.chosenRequestAssemblyId else null),
    "keepCandidates" to (if (visible) l.keepCandidates else false),
    "note" to (if (visible) l.note else null),
    "machineVersion" to true
)

private fun diagnosticJson(d: Diagnostic, visible: Boolean) = mapOf(
    "id" to d.id,
    "code" to d.code,
    "severity" to d.severity.name,
    "frameSeq" to d.frameSeq,
    "canId" to d.canId?.let { canIdToHex(it) },
    "direction" to d.direction?.name,
    "generation" to d.generation,
    "message" to d.message
).let { if (visible) it else it + ("hiddenByPlayhead" to true) }

fun viewJson(view: EffectiveView, playhead: Int?): Map<String, Any?> {
    val cutoff = playhead ?: (view.frames.size - 1)
    val frameVisible = view.frames.map { it.raw.seq <= cutoff }

    // 帧->可见 assembly：assembly 起点在 playhead 内即可见，但其内容也按时点截断
    val visibleAsmIds = view.frames.zip(frameVisible)
        .filter { it.second }
        .mapNotNull { it.first.assemblyId }
        .toSet()
    val asmVisible = view.assemblies.map { it.assembly.id in visibleAsmIds }

    val linksVisible = view.links.map { l ->
        val reqVisible = l.link.requestAssemblyId?.let { rid ->
            view.assemblies.firstOrNull { it.assembly.id == rid }?.assembly?.startFrameSeq?.let { it <= cutoff }
        } ?: false
        val respVisible = l.link.responseAssemblyId?.let { rid ->
            view.assemblies.firstOrNull { it.assembly.id == rid }?.assembly?.startFrameSeq?.let { it <= cutoff }
        } ?: false
        reqVisible || respVisible
    }

    val framesJson = view.frames.zip(frameVisible).map { (f, v) -> frameJson(f, v) }
    val resetsJson = view.resets.filter { it.afterSeq <= cutoff }.map {
        mapOf(
            "id" to it.id, "afterSeq" to it.afterSeq, "tsUs" to it.tsUs,
            "origin" to it.origin, "canId" to it.canId?.let { id -> canIdToHex(id) }
        )
    }

    return mapOf(
        "machineRunId" to view.machineRunId,
        "playhead" to cutoff,
        "frameCount" to view.frames.size,
        "frames" to framesJson,
        "resets" to resetsJson,
        "assemblies" to view.assemblies.zip(asmVisible).filter { it.second }.map { assemblyJson(it.first, true) },
        "links" to view.links.zip(linksVisible).filter { it.second }.map { linkJson(it.first, true) },
        "generations" to view.generations.map {
            mapOf(
                "generation" to it.generation,
                "canId" to canIdToHex(it.canId),
                "direction" to it.direction?.name,
                "startedAfterSeq" to it.startedAfterSeq,
                "startTsUs" to it.startTsUs
            )
        },
        "diagnostics" to view.diagnostics
            .filter { d ->
                when (d.code) {
                    "GENERATION_BOUNDARY" -> (d.frameSeq ?: -1L) < cutoff
                    else -> d.frameSeq == null || d.frameSeq <= cutoff
                }
            }
            .map { diagnosticJson(it, true) },
        "config" to mapOf(
            "fcTimeoutUs" to 100_000L,
            "crTimeoutUs" to 250_000L,
            "timeWindow" to "half-open [start, deadline); frame at t==deadline is a timeout"
        )
    )
}

fun formatTs(us: Long): String {
    val sec = us / 1_000_000
    val frac = us % 1_000_000
    return "%d.%06d".format(sec, frac)
}

private fun printablePayload(data: ByteArray): String {
    val ascii = data.joinToString("") { b ->
        val v = b.toInt() and 0xFF
        if (v in 0x20..0x7E) v.toChar().toString() else "."
    }
    return ascii
}
