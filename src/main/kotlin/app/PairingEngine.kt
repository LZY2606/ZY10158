package app

/*
 * 请求/响应配对：
 *  - 仅同 capture、同代次、且 CAN ID 互为 UDS 对端（req -> req+8）的完整或不完整消息可配对
 *  - 服务判定：请求首字节 SID；正响应 SID+0x40；负响应 0x7F + SID + NRC
 *  - 响应须在请求之后（虚拟时钟）；同一请求取“最近且未结束”的响应，但不删除证据：
 *    后到的旧/重放响应标 REPLAY_RESPONSE 并保留两个候选
 *  - 不完整响应不伪装成功：状态在 link 上以响应 assembly 的 status 体现，仍参与归属
 */
object PairingEngine {

    private const val NRC_SID = 0x7F
    private const val POSITIVE_OFFSET = 0x40

    fun pair(assemblies: List<Assembly>): List<ReqRespLink> {
        val requests = assemblies.filter { it.direction == Direction.REQ && it.payload.isNotEmpty() }
            .sortedBy { it.startTsUs }
        val responses = assemblies.filter { it.direction == Direction.RESP && it.payload.isNotEmpty() }
            .sortedBy { it.startTsUs }
        val respById = responses.associateBy { it.id }

        val ownedBy = HashMap<Long, Long>()   // responseAssemblyId -> requestAssemblyId（首个命中者）
        val links = ArrayList<ReqRespLink>()
        var linkId = 0L

        for (req in requests) {
            val expectedRespId = CaptureParser.peerCanId(req.canId)
            val sid = req.serviceId
            val candidates = responses.asSequence()
                .filter { it.canId == expectedRespId }
                .filter { it.generation == req.generation }
                .filter { it.startTsUs >= req.startTsUs }
                .map { scoreCandidate(req, it) }
                .filter { it.score >= 0 }
                .sortedWith(compareByDescending<LinkCandidate> { it.score })
                .toList()

            if (candidates.isEmpty()) {
                links.add(mkLink(linkId++, req, null, LinkStatus.ORPHAN_REQUEST, emptyList(), sid, null))
                continue
            }

            val topTwo = candidates.take(2)
            val winnerId = candidates.first().requestAssemblyId
            val winner = respById.getValue(winnerId)
            val ambiguous = candidates.size >= 2 && candidates[1].score >= candidates[0].score - 1
            val owner = ownedBy[winnerId]
            val (baseStatus, nrc) = classify(winner, sid)
            val status = when {
                ambiguous -> LinkStatus.AMBIGUOUS
                owner != null -> LinkStatus.REPLAY_RESPONSE
                else -> baseStatus
            }
            if (owner == null) ownedBy[winnerId] = req.id
            links.add(mkLink(linkId++, req, winner, status, topTwo, sid, nrc))
        }

        // 未被任何请求引用的响应
        for (resp in responses) {
            if (resp.id in ownedBy) continue
            val peerReqId = CaptureParser.peerCanId(resp.canId)
            val respTime = resp.endTsUs ?: resp.startTsUs
            val priorReq = requests.firstOrNull { r ->
                r.canId == peerReqId && r.generation == resp.generation && r.startTsUs > respTime
            }
            val finalStatus = if (priorReq != null) LinkStatus.REPLAY_RESPONSE else LinkStatus.ORPHAN_RESPONSE
            val cands = requests
                .filter { it.canId == peerReqId && it.generation == resp.generation }
                .map { LinkCandidate(it.id, 0, "同代次候选请求（时间顺序异常）") }
            val (_, nrc) = classify(resp, null)
            links.add(mkLink(linkId++, null, resp, finalStatus, cands, null, nrc))
        }
        return links.sortedWith(compareBy({ it.generation }, { it.id }))
    }

    private fun scoreCandidate(req: Assembly, resp: Assembly): LinkCandidate {
        val respSid = resp.payload[0].toInt() and 0xFF
        val reqSid = req.serviceId ?: return LinkCandidate(resp.id, -1, "请求无法解析 SID")
        var score = 0
        val reason = StringBuilder()
        when {
            respSid == NRC_SID && resp.payload.size >= 2 &&
                    (resp.payload[1].toInt() and 0xFF) == reqSid -> {
                score += 10; reason.append("负响应 SID 匹配")
            }
            respSid == (reqSid + POSITIVE_OFFSET) and 0xFF -> {
                score += 10; reason.append("正响应 SID 匹配")
            }
            else -> return LinkCandidate(resp.id, -1, "响应 SID=0x${"%02X".format(respSid)} 与请求 SID=0x${"%02X".format(reqSid)} 不匹配")
        }
        if (!resp.complete) {
            score -= 2; reason.append("；响应不完整")
        }
        // 越早且越贴近请求的响应越优先
        score += 5
        reason.append("；startTs=${resp.startTsUs}")
        return LinkCandidate(resp.id, score, reason.toString())
    }

    private data class Classified(val status: LinkStatus, val nrc: Int?)
    private fun classify(resp: Assembly, reqSid: Int?): Pair<LinkStatus, Int?> {
        val sid = resp.payload[0].toInt() and 0xFF
        return if (sid == NRC_SID && resp.payload.size >= 3) {
            LinkStatus.NEGATIVE to (resp.payload[2].toInt() and 0xFF)
        } else LinkStatus.MATCHED to null
    }

    private fun mkLink(
        id: Long, req: Assembly?, resp: Assembly?, status: LinkStatus,
        candidates: List<LinkCandidate>, sid: Int?, nrc: Int?
    ): ReqRespLink {
        val gen = resp?.generation ?: req?.generation ?: 0
        return ReqRespLink(
            id, 0L, gen, req?.id, resp?.id, status, candidates, sid, nrc
        )
    }
}
