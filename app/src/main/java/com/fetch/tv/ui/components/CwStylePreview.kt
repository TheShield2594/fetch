package com.fetch.tv.ui.components

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath

/** Small drawings of the Continue Watching card styles, used by the style picker in settings. */

@Composable
fun CardCwStylePreview(
    modifier: Modifier = Modifier,
    accentColor: Color = FetchTheme.colors.Primary
) {
    val bgColor = FetchTheme.colors.Background

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FetchTheme.radii.sm))
            .background(bgColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Match real CARD: width = portraitBaseWidth * 1.24, height = width / 1.77.
            val cardW = minOf(w * 0.9f, h * 0.68f * 1.77f)
            val cardH = cardW / 1.77f
            val padX = (w - cardW) * 0.5f
            val padY = (h - cardH) * 0.5f
            val cornerRadius = CornerRadius(h * 0.04f)
            val inset = cardW * 0.05f
            val cardPath = Path().apply {
                addRoundRect(RoundRect(Rect(Offset(padX, padY), Size(cardW, cardH)), cornerRadius))
            }

            clipPath(cardPath) {
                drawRect(color = accentColor.copy(alpha = 0.42f))
                // Dark bottom wash so overlay text reads, like the gradient on the real CARD.
                drawRect(
                    color = bgColor.copy(alpha = 0.68f),
                    topLeft = Offset(padX, padY + cardH * 0.48f),
                    size = Size(cardW, cardH * 0.52f)
                )
                // Title + episode overlay at bottom-left.
                val titleH = cardH * 0.1f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.85f),
                    topLeft = Offset(padX + inset, padY + cardH * 0.56f),
                    size = Size(cardW * 0.52f, titleH),
                    cornerRadius = CornerRadius(titleH)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.45f),
                    topLeft = Offset(padX + inset, padY + cardH * 0.71f),
                    size = Size(cardW * 0.34f, titleH * 0.7f),
                    cornerRadius = CornerRadius(titleH)
                )
                // Top-right remaining-time badge.
                val badgeW = cardW * 0.26f
                val badgeH = cardH * 0.14f
                drawRoundRect(
                    color = bgColor.copy(alpha = 0.78f),
                    topLeft = Offset(padX + cardW - badgeW - inset * 0.8f, padY + inset * 0.8f),
                    size = Size(badgeW, badgeH),
                    cornerRadius = CornerRadius(badgeH)
                )
                // Thin progress track + fill along the bottom edge.
                val trackH = (cardH * 0.035f).coerceAtLeast(2f)
                val trackY = padY + cardH - inset * 0.9f - trackH
                val trackW = cardW - (inset * 2f)
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(padX + inset, trackY),
                    size = Size(trackW, trackH),
                    cornerRadius = CornerRadius(trackH)
                )
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.95f),
                    topLeft = Offset(padX + inset, trackY),
                    size = Size(trackW * 0.55f, trackH),
                    cornerRadius = CornerRadius(trackH)
                )
            }
        }
    }
}

/** Static wide Continue Watching style placeholder (single card, poster strip + info). */
@Composable
fun WideCwStylePreview(
    modifier: Modifier = Modifier,
    accentColor: Color = FetchTheme.colors.Primary
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FetchTheme.radii.sm))
            .background(FetchTheme.colors.Background)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Match real WIDE: height = width * 0.4, so the card is 2.5:1 and must fit the panel.
            val cardW = minOf(w * 0.92f, h * 0.8f * 2.5f)
            val cardH = cardW * 0.4f
            val padX = (w - cardW) * 0.5f
            val padY = (h - cardH) * 0.5f
            val cornerRadius = CornerRadius(h * 0.05f)
            val posterW = cardH * (2f / 3f)
            val infoW = cardW - posterW
            val cardPath = Path().apply {
                addRoundRect(RoundRect(Rect(Offset(padX, padY), Size(cardW, cardH)), cornerRadius))
            }

            clipPath(cardPath) {
                drawRect(color = accentColor.copy(alpha = 0.2f))
                // Poster strip on the left.
                drawRect(
                    color = accentColor.copy(alpha = 0.5f),
                    topLeft = Offset(padX, padY),
                    size = Size(posterW, cardH)
                )

                val padI = infoW * 0.1f
                val infoX = padX + posterW + padI
                val infoMaxW = infoW - (padI * 2f)
                val lineH = cardH * 0.11f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.82f),
                    topLeft = Offset(infoX, padY + cardH * 0.18f),
                    size = Size(infoMaxW * 0.82f, lineH),
                    cornerRadius = CornerRadius(lineH)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.4f),
                    topLeft = Offset(infoX, padY + cardH * 0.34f),
                    size = Size(infoMaxW * 0.5f, lineH * 0.75f),
                    cornerRadius = CornerRadius(lineH)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.32f),
                    topLeft = Offset(infoX, padY + cardH * 0.47f),
                    size = Size(infoMaxW * 0.68f, lineH * 0.7f),
                    cornerRadius = CornerRadius(lineH)
                )
                // Bottom progress bar + remaining label.
                val barH = (cardH * 0.04f).coerceAtLeast(2f)
                val barY = padY + cardH * 0.72f
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(infoX, barY),
                    size = Size(infoMaxW, barH),
                    cornerRadius = CornerRadius(barH)
                )
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.95f),
                    topLeft = Offset(infoX, barY),
                    size = Size(infoMaxW * 0.48f, barH),
                    cornerRadius = CornerRadius(barH)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.35f),
                    topLeft = Offset(infoX, barY + barH + cardH * 0.06f),
                    size = Size(infoMaxW * 0.4f, lineH * 0.55f),
                    cornerRadius = CornerRadius(lineH)
                )
            }
        }
    }
}

/** Static portrait Continue Watching style placeholder (single card, title under art). */
@Composable
fun PosterCwStylePreview(
    modifier: Modifier = Modifier,
    accentColor: Color = FetchTheme.colors.Primary
) {
    val bgColor = FetchTheme.colors.Background

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FetchTheme.radii.sm))
            .background(bgColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Match real POSTER: 2:3 catalog card with a compact title block under the art.
            val titleH = h * 0.14f
            val titleGap = h * 0.03f
            val artW = minOf(h * 0.74f * (2f / 3f), w * 0.55f)
            val artH = artW * (3f / 2f)
            val padX = (w - artW) * 0.5f
            val padY = ((h - titleGap - titleH) - artH) * 0.5f
            val cornerRadius = CornerRadius(h * 0.03f)
            val inset = artW * 0.08f
            val artPath = Path().apply {
                addRoundRect(RoundRect(Rect(Offset(padX, padY), Size(artW, artH)), cornerRadius))
            }

            clipPath(artPath) {
                drawRect(color = accentColor.copy(alpha = 0.45f))
                // Top-right badge.
                val badgeW = artW * 0.38f
                val badgeH = artH * 0.1f
                drawRoundRect(
                    color = bgColor.copy(alpha = 0.78f),
                    topLeft = Offset(padX + artW - badgeW - inset * 0.6f, padY + inset * 0.6f),
                    size = Size(badgeW, badgeH),
                    cornerRadius = CornerRadius(badgeH)
                )
                // Pill progress near the bottom of the art.
                val pillPad = inset * 0.5f
                val trackH = (artH * 0.03f).coerceAtLeast(2f)
                val pillH = trackH + pillPad * 2f
                val pillY = padY + artH - pillH - inset * 0.6f
                val pillW = artW - (inset * 2f)
                drawRoundRect(
                    color = bgColor.copy(alpha = 0.7f),
                    topLeft = Offset(padX + inset, pillY),
                    size = Size(pillW, pillH),
                    cornerRadius = CornerRadius(pillH)
                )
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.95f),
                    topLeft = Offset(padX + inset + pillPad, pillY + pillPad),
                    size = Size((pillW - pillPad * 2f) * 0.5f, trackH),
                    cornerRadius = CornerRadius(trackH)
                )
            }
            // Title under the artwork, not overlaid.
            val titleY = padY + artH + titleGap
            val titleLineH = titleH * 0.42f
            drawRoundRect(
                color = accentColor.copy(alpha = 0.55f),
                topLeft = Offset(padX, titleY),
                size = Size(artW * 0.88f, titleLineH),
                cornerRadius = CornerRadius(titleLineH)
            )
            drawRoundRect(
                color = accentColor.copy(alpha = 0.3f),
                topLeft = Offset(padX, titleY + titleLineH + titleGap * 0.5f),
                size = Size(artW * 0.5f, titleLineH * 0.7f),
                cornerRadius = CornerRadius(titleLineH)
            )
        }
    }
}
