package app

/*
 * 视图服务：
 *  - 原始帧/人工判定从 SQLite 读取
 *  - 每次重算产出新的 machine run；人工判定作为输入层叠加
 *  - 已审核结果不被覆盖：投影时人工拒绝、角色修正、链接选择始终优先生效
 */
class ViewService(private val repo: Repository, private val cfg: ReassemblyConfig = ReassemblyConfig()) {

    fun recompute(captureId: Long): Long {
        val frames = repo.loadFrames(captureId)
        val resets = repo.loadResets(captureId)
        val frameAdj = repo.loadFrameAdjudications(captureId)

        val rejected = frameAdj.filter { it.rejected }.map { it.frameSeq }.toSet()
        val roles = frameAdj.filter { it.roleOverride != null }.associate { it.frameSeq to it.roleOverride!! }

        val result = Reassembler(cfg).run(frames, resets, rejected, roles)
        // 代次边界作为类型化诊断一并落盘
        val diags = ArrayList(result.diagnostics)
        var genDiagId = (diags.maxOfOrNull { it.id + 1 } ?: 0L)
        for (g in result.generations) {
            diags.add(
                Diagnostic(
                    genDiagId++, captureId, "GENERATION_BOUNDARY", Severity.INFO,
                    g.startedAfterSeq, g.canId, g.direction, g.generation,
                    "CAN ID ${canIdToHex(g.canId)} ${g.direction} 进入代次 ${g.generation}"
                )
            )
        }
        val links = PairingEngine.pair(result.assemblies)
        return repo.publishMachineRun(captureId, cfg, result.copy(diagnostics = diags), links)
    }

    fun effectiveView(captureId: Long): EffectiveView? {
        val capture = repo.getCapture(captureId) ?: return null
        val runId = capture.latestRunId ?: return null
        val stored = repo.loadMachineRun(captureId, runId) ?: return null
        val frames = repo.loadFrames(captureId)
        val resets = repo.loadResets(captureId)
        val frameAdj = repo.loadFrameAdjudications(captureId).associateBy { it.frameSeq }
        val linkAdj = repo.loadLinkAdjudications(captureId).associateBy { it.linkId }
        val linksRaw = repo.loadLinks(runId, captureId)

        // 重新构造 EffectiveFrame：直接由 StoredMachineRun 不便（其 only stores frameAssembly），
        // 因此以原始帧 + frameAssembly + 重算时的拒绝/角色投影展示。
        val effFrames = frames.map { f ->
            val adj = frameAdj[f.seq]
            val effDir = adj?.roleOverride ?: f.declaredDirection
            val pci = IsoTp.classify(f.data)
            EffectiveFrame(
                f, generationOf(stored, f, effDir), effDir,
                adj?.rejected ?: false, adj?.rejectReason, pci, stored.frameAssembly[f.seq]
            )
        }
        val effAssemblies = stored.assemblies.map { a ->
            val anyRejected = effFrames.any { it.assemblyId == a.id && it.rejected }
            EffectiveAssembly(a, anyRejected)
        }
        val effLinks = linksRaw.map { l ->
            val adj = linkAdj[l.id]
            EffectiveLink(
                l,
                adj?.chosenRequestAssemblyId ?: l.requestAssemblyId,
                adj?.keepCandidates ?: (l.status == LinkStatus.AMBIGUOUS),
                adj?.note
            )
        }
        val generations = deriveGenerations(stored.diagnostics)
        return EffectiveView(effFrames, resets, effAssemblies, effLinks, stored.diagnostics, generations, runId)
    }

    private fun generationOf(run: StoredMachineRun, f: RawFrame, dir: Direction): Int =
        run.diagnostics
            .filter {
                it.code == "GENERATION_BOUNDARY" && it.canId == f.canId &&
                    it.direction == dir && (it.frameSeq ?: -1L) < f.seq
            }
            .maxOfOrNull { it.generation ?: 0 } ?: 0

    private fun deriveGenerations(diags: List<Diagnostic>): List<GenerationInfo> =
        diags.filter { it.code == "GENERATION_BOUNDARY" }.map {
            GenerationInfo(it.generation ?: 0, it.canId ?: 0, it.direction, it.frameSeq ?: -1L, 0L)
        }
}
