package app.tingyu.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tingyu.R

/** Decorative beside the app name; the launcher and in-app identity share this vector. */
@Composable
internal fun BrandIcon(size: Dp = 48.dp) {
    Surface(modifier = Modifier.size(size), shape = RoundedCornerShape(size / 4), color = TingYuColors.Blue) {
        Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null)
    }
}
