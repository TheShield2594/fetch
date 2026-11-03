package com.fetch.desktop.party

import com.fetch.desktop.PlayerSession
import com.fetch.desktop.player.ExternalPlayer

/**
 * The built-in player ([PlayerSession] and its libmpv) as a [PartyPlayer]. mpv reports pause and
 * speed changes a moment after they are asked for, so what was last asked counts until then.
 */
class MpvPartyPlayer(private val session: PlayerSession) : PartyPlayer {
    private val mpv get() = session.mpv
    private var askedPaused: Boolean? = null
    private var askedRate: Float? = null
    private var renderedLoad = -1

    override val mediaKey: String?
        get() {
            val target = session.target ?: return null
            if (target.contentId.isBlank() || target.contentType.isBlank()) return null
            return partyMediaKey(target.contentType, target.contentId, target.video?.season, target.video?.episode)
        }

    override val positionMs: Long?
        get() = if (mpv.duration > 0) (mpv.position * 1000).toLong() else null

    override val durationMs: Long get() = (mpv.duration * 1000).toLong().coerceAtLeast(0L)

    override val playing: Boolean
        get() {
            askedPaused?.let { if (it == mpv.paused) askedPaused = null else return !it }
            return !mpv.paused
        }

    override val loading: Boolean get() = mpv.buffering

    override val rendered: Boolean
        get() {
            if (renderedLoad == session.loads) return true
            if (!mpv.buffering && mpv.duration > 0 && mpv.frame() != null && mpv.error == null) renderedLoad = session.loads
            return renderedLoad == session.loads
        }

    override val ended: Boolean get() = mpv.duration > 0 && mpv.position >= mpv.duration - 0.5

    override val failed: Boolean get() = mpv.error != null

    override val rate: Float
        get() {
            val actual = mpv.speed.toFloat()
            askedRate?.let { if (kotlin.math.abs(it - actual) < 0.001f) askedRate = null else return it }
            return actual
        }

    override fun setPaused(paused: Boolean) {
        askedPaused = paused
        mpv.pause(paused)
    }

    override fun seekTo(positionMs: Long) = mpv.seekExact(positionMs / 1000.0)

    override fun setRate(rate: Float) {
        askedRate = rate
        mpv.changeSpeed(rate.toDouble())
    }

    override fun currentMedia(): PartyMedia? {
        val target = session.target ?: return null
        if (mediaKey == null) return null
        val video = target.video
        val stream = session.stream
        return PartyMedia(
            type = target.contentType,
            metaId = target.contentId,
            metaType = target.contentType,
            videoId = video?.id,
            season = video?.season,
            episode = video?.episode,
            title = target.name,
            episodeTitle = video?.title?.takeIf { it.isNotBlank() },
            description = session.description?.take(DESCRIPTION_LIMIT),
            poster = target.poster,
            background = target.backdrop,
            logo = target.logo,
            source = stream?.let {
                PartySource(
                    infoHash = it.getEffectiveInfoHash(),
                    fileIdx = it.getEffectiveFileIdx(),
                    filename = it.behaviorHints?.filename ?: it.clientResolve?.filename,
                    videoSize = it.behaviorHints?.videoSize,
                    bingeGroup = it.behaviorHints?.bingeGroup,
                    name = it.name,
                )
            },
        )
    }

    override fun currentStream(): PartyStream? {
        val stream = session.stream ?: return null
        // Your own media server's links carry your token and only work on your network.
        if (stream.serverTarget != null) return null
        val key = mediaKey ?: return null
        val url = ExternalPlayer.playableUrl(stream) ?: return null
        if (!isShareableStreamUrl(url)) return null
        return PartyStream(
            mediaKey = key,
            url = url,
            headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
            responseHeaders = stream.behaviorHints?.proxyHeaders?.response.orEmpty(),
            name = stream.name,
            description = stream.description ?: stream.title,
            addonName = stream.addonName,
            addonLogo = stream.addonLogo,
            filename = stream.behaviorHints?.filename,
            videoSize = stream.behaviorHints?.videoSize,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            badges = stream.badges,
        )
    }

    private companion object {
        const val DESCRIPTION_LIMIT = 600
    }
}
