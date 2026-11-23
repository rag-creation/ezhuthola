package com.rr.numio.ezhuthola

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.rr.numio.ezhuthola.R

/**
 * The official "Numio." wordmark, exactly as shipped in the brand files:
 * cream letters for dark screens, dark letters for light screens.
 * Never redraw or recolour it; swap these two PNGs if the brand ever changes.
 */
@Composable
fun OfficialWordmark(dark: Boolean, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(if (dark) R.drawable.numio_wordmark_on_dark else R.drawable.numio_wordmark_on_light),
        contentDescription = "Numio",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
