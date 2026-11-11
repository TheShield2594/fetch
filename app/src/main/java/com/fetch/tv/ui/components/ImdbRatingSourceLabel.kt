package com.fetch.tv.ui.components

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.fetch.tv.R
import com.fetch.tv.core.build.AppFeaturePolicy

// Draws either the logo or the text label, never both, so it takes a modifier for each
// (logoModifier, textModifier) rather than one `modifier` for an outer element it doesn't have.
@SuppressLint("ModifierParameter")
@Composable
fun ImdbRatingSourceLabel(
    logoModifier: Modifier,
    textStyle: TextStyle,
    textColor: Color,
    textModifier: Modifier = Modifier
) {
    val label = stringResource(R.string.cd_imdb)
    if (AppFeaturePolicy.imdbRatingLogoEnabled) {
        val context = LocalContext.current
        val model = remember(context) {
            ImageRequest.Builder(context)
                .data(R.raw.imdb_logo_2016)
                .build()
        }
        AsyncImage(
            model = model,
            contentDescription = label,
            modifier = logoModifier,
            contentScale = ContentScale.Fit
        )
    } else {
        Text(
            text = label,
            modifier = textModifier,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
