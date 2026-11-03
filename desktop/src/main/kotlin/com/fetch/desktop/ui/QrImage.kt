package com.fetch.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.fetch.desktop.qr.QrCode

/**
 * [text] as a QR code: dark modules on a white rounded square with a [margin]-module quiet
 * zone, as the TV's `QrCodeGenerator` draws it. Nothing when the text is too long for a QR code.
 */
@Composable
fun QrImage(
    text: String,
    size: Dp,
    modifier: Modifier = Modifier,
    margin: Int = 1,
    ecc: QrCode.Ecc = QrCode.Ecc.LOW,
    description: String? = null
) {
    val qr = remember(text, ecc) { QrCode.encode(text, ecc) } ?: return
    Canvas(modifier.size(size).semantics { description?.let { contentDescription = it } }) {
        val modules = qr.size + margin * 2
        val cell = this.size.minDimension / modules
        drawRoundRect(Color.White, cornerRadius = CornerRadius(this.size.minDimension * 0.06f))
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr[x, y]) {
                // A hair larger than a cell so neighbouring modules join without seams.
                drawRect(Color.Black, Offset((x + margin) * cell, (y + margin) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
