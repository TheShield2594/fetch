package com.fetch.tv.data.mapper

import com.fetch.tv.data.remote.dto.BehaviorHintsDto
import com.fetch.tv.data.remote.dto.ProxyHeadersDto
import com.fetch.tv.data.remote.dto.StreamClientResolveParsedDto
import com.fetch.tv.data.remote.dto.StreamClientResolveRawDto
import com.fetch.tv.data.remote.dto.StreamClientResolveStreamDto
import com.fetch.tv.data.remote.dto.StreamClientResolveDto
import com.fetch.tv.data.remote.dto.StreamDto
import com.fetch.tv.domain.model.ProxyHeaders
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import com.fetch.tv.domain.model.Subtitle
import com.fetch.tv.domain.model.StreamClientResolve
import com.fetch.tv.domain.model.StreamClientResolveParsed
import com.fetch.tv.domain.model.StreamClientResolveRaw
import com.fetch.tv.domain.model.StreamClientResolveStream
import com.fetch.tv.domain.model.toStreamAudioDelayMs

fun StreamDto.toDomain(addonName: String, addonLogo: String?): Stream = Stream(
    name = name,
    title = title,
    description = description,
    url = url,
    ytId = ytId,
    infoHash = infoHash,
    fileIdx = fileIdx,
    externalUrl = externalUrl,
    behaviorHints = behaviorHints?.toDomain(),
    addonName = addonName,
    addonLogo = addonLogo,
    sources = sources,
    clientResolve = clientResolve?.toDomain(),
    subtitles = subtitles.orEmpty().mapNotNull { dto ->
        val url = dto.url.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Subtitle(
            id = dto.id?.takeIf { it.isNotBlank() } ?: url,
            url = url,
            lang = dto.lang.ifBlank { "Unknown" },
            addonName = addonName,
            addonLogo = addonLogo,
            isStreamProvided = true,
            headers = dto.headers
        )
    }
)

fun StreamClientResolveDto.toDomain(): StreamClientResolve = StreamClientResolve(
    type = type,
    infoHash = infoHash,
    fileIdx = fileIdx,
    torrentName = torrentName,
    filename = filename,
    stream = stream?.toDomain(),
    isCached = isCached
)

fun StreamClientResolveStreamDto.toDomain(): StreamClientResolveStream = StreamClientResolveStream(
    raw = raw?.toDomain()
)

fun StreamClientResolveRawDto.toDomain(): StreamClientResolveRaw = StreamClientResolveRaw(
    torrentName = torrentName,
    filename = filename,
    parsed = parsed?.toDomain()
)

fun StreamClientResolveParsedDto.toDomain(): StreamClientResolveParsed = StreamClientResolveParsed(
    rawTitle = rawTitle,
    parsedTitle = parsedTitle,
    resolution = resolution,
    quality = quality,
    hdr = hdr,
    codec = codec,
    audio = audio,
    channels = channels,
    languages = languages,
    group = group,
    edition = edition
)

fun BehaviorHintsDto.toDomain(): StreamBehaviorHints = StreamBehaviorHints(
    notWebReady = notWebReady,
    bingeGroup = bingeGroup,
    countryWhitelist = countryWhitelist,
    proxyHeaders = proxyHeaders?.toDomain(),
    videoHash = videoHash,
    videoSize = videoSize,
    filename = filename,
    audioDelayMs = audioDelayMs.toStreamAudioDelayMs()
)

fun ProxyHeadersDto.toDomain(): ProxyHeaders = ProxyHeaders(
    request = sanitizeHeaderMap(request),
    response = sanitizeHeaderMap(response)
)

private fun sanitizeHeaderMap(headers: Map<String, String>?): Map<String, String>? {
    if (headers == null) return null
    val raw: Map<*, *> = headers
    if (raw.isEmpty()) return null

    val sanitized = LinkedHashMap<String, String>(raw.size)
    raw.forEach { (rawKey, rawValue) ->
        val key = (rawKey as? String)?.trim().orEmpty()
        val value = (rawValue as? String)?.trim().orEmpty()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        if (key.equals("Range", ignoreCase = true)) return@forEach
        sanitized[key] = value
    }
    return sanitized.takeIf { it.isNotEmpty() }
}
