package com.simplexray.re.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The card container shared by the dashboard, proxies and configuration pages:
 * a 30dp rounded, lightly shadowed card on the `background` colour.
 * Pass [onClick] to make the whole card clickable.
 */
@Composable
fun DashboardCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardModifier = modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(30.dp))
    val colors = CardDefaults.defaultColors(
        color = MiuixTheme.colorScheme.background,
        contentColor = MiuixTheme.colorScheme.onBackground,
    )
    if (onClick == null) {
        Card(modifier = cardModifier, cornerRadius = 30.dp, colors = colors, content = content)
    } else {
        Card(modifier = cardModifier, cornerRadius = 30.dp, colors = colors, onClick = onClick, content = content)
    }
}

/** Card title row: a 20dp summary-tinted icon followed by a 17sp semi-bold title. */
@Composable
fun CardHeading(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(title, style = MiuixTheme.textStyles.title3, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            maxLines = maxLines, overflow = overflow)
    }
}

/** Small capsule label on the `surface` colour, used for states, types and measurements. */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
) {
    Text(text, modifier.clip(CircleShape).background(MiuixTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 4.dp),
        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color)
}
