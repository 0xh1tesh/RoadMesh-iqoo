package com.jsons.roadmesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsons.roadmesh.R

/** ResiLane identity: ink ground, iQOO yellow for you and primary actions; red and green are only ever signals. */
object Tokens {
    val Ink = Color(0xFF070707)
    val Panel = Color(0xFF141414)
    val Raised = Color(0xFF1E1E1E)
    val Paper = Color(0xFFF4F4F0)
    val Yellow = Color(0xFFF0B81C)
    val Red = Color(0xFFE3342F)
    val Green = Color(0xFF2BD67B)
    val Blue = Color(0xFF4F7FE8)
    val Purple = Color(0xFFA06BE0)
    val Secondary = Color(0xFFD6D6D0)
    val Muted = Color(0xFFB4B4AE)
    val Faint = Color(0xFF9C9C96)
    val Line = Color(0x14FFFFFF)
    val Outline = Color(0x4DFFFFFF)
    val Scrim = Color(0xCC070707)
    val Sheet = Color(0xE0070707)
}

@OptIn(ExperimentalTextApi::class)
private fun saira(weight: Int) = Font(R.font.saira, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
val Saira = FontFamily(saira(400), saira(500), saira(600), saira(700), saira(800))
val Michroma = FontFamily(Font(R.font.michroma))

/** Live numbers use tabular figures so they do not jitter as they change. */
val Tabular = TextStyle(fontFamily = Saira, fontFeatureSettings = "tnum")

@Composable fun RoadMeshTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val type = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Saira, fontWeight = FontWeight.ExtraBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = Saira, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = Saira, fontWeight = FontWeight.ExtraBold),
        titleLarge = base.titleLarge.copy(fontFamily = Saira, fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.copy(fontFamily = Saira, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontFamily = Saira),
        bodyMedium = base.bodyMedium.copy(fontFamily = Saira),
        bodySmall = base.bodySmall.copy(fontFamily = Saira),
        labelLarge = base.labelLarge.copy(fontFamily = Saira, fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.copy(fontFamily = Saira, fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontFamily = Saira),
    )
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tokens.Yellow, onPrimary = Tokens.Ink, background = Tokens.Ink, onBackground = Tokens.Paper,
            surface = Tokens.Panel, onSurface = Tokens.Paper, onSurfaceVariant = Tokens.Muted, outline = Tokens.Outline, error = Tokens.Red,
        ),
        typography = type, shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)),
        content = content,
    )
}

/** The top edge of every screen is the light: green while the mesh is live, red while it is not, yellow on a caution. */
@Composable fun SignalEdge(color: Color) = Box(Modifier.fillMaxWidth().height(5.dp).background(color))

@Composable fun Wordmark(size: Dp = 30.dp, textSize: Int = 13) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(painterResource(R.drawable.mark_roadmesh), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(size))
    Text("RESILANE", fontFamily = Michroma, fontSize = textSize.sp, letterSpacing = (textSize * 0.06).sp, color = Tokens.Yellow, maxLines = 1)
}

@Composable fun StatusDot(color: Color, label: String, textColor: Color = Tokens.Paper) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
    Text(label, fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = textColor, maxLines = 1)
}

@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 56.dp) =
    Button(onClick, modifier.height(height), enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Tokens.Yellow, contentColor = Tokens.Ink, disabledContainerColor = Tokens.Raised, disabledContentColor = Tokens.Faint)) {
        Text(text, fontFamily = Saira, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, maxLines = 1)
    }

@Composable fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 48.dp) =
    OutlinedButton(onClick, modifier.height(height), enabled = enabled, shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Tokens.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Tokens.Paper)) {
        Text(text, fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
    }

/** A letter badge like the people rows in the identity: A, B, C for the three phones. */
@Composable fun RoleBadge(letter: Char, color: Color, size: Dp = 30.dp) = Box(Modifier.size(size).background(color, RoundedCornerShape(size / 2)), contentAlignment = Alignment.Center) {
    Text(letter.toString(), fontFamily = Saira, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.46f).sp, color = Tokens.Paper)
}
