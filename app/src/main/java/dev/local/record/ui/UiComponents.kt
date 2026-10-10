package dev.local.record.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Small, consistent outline icons; decorative icons leave semantics to their parent controls. */
internal object RecordIcons {
    val Send = outline("Send") {
        moveTo(12f, 20f)
        lineTo(12f, 4f)
        moveTo(6f, 10f)
        lineTo(12f, 4f)
        lineTo(18f, 10f)
    }
    val Edit = outline("Edit") {
        moveTo(4f, 15f)
        lineTo(15f, 4f)
        lineTo(20f, 9f)
        lineTo(9f, 20f)
        lineTo(3f, 21f)
        close()
        moveTo(12f, 7f)
        lineTo(17f, 12f)
    }
    val More = outline("More") {
        moveTo(5f, 12f)
        lineTo(5.1f, 12f)
        moveTo(12f, 12f)
        lineTo(12.1f, 12f)
        moveTo(19f, 12f)
        lineTo(19.1f, 12f)
    }
    val Delete = outline("Delete") {
        moveTo(4f, 6f)
        lineTo(20f, 6f)
        moveTo(9f, 6f)
        lineTo(9f, 3f)
        lineTo(15f, 3f)
        lineTo(15f, 6f)
        moveTo(6f, 6f)
        lineTo(7f, 21f)
        lineTo(17f, 21f)
        lineTo(18f, 6f)
        moveTo(10f, 10f)
        lineTo(10f, 17f)
        moveTo(14f, 10f)
        lineTo(14f, 17f)
    }
    val Mic = outline("Mic") {
        moveTo(9f, 5f)
        curveTo(9f, 1f, 15f, 1f, 15f, 5f)
        lineTo(15f, 11f)
        curveTo(15f, 15f, 9f, 15f, 9f, 11f)
        close()
        moveTo(5f, 10f)
        lineTo(5f, 11f)
        curveTo(5f, 20f, 19f, 20f, 19f, 11f)
        lineTo(19f, 10f)
        moveTo(12f, 18f)
        lineTo(12f, 22f)
        moveTo(8f, 22f)
        lineTo(16f, 22f)
    }
    val Wave = outline("Wave") {
        moveTo(3f, 10f)
        lineTo(3f, 14f)
        moveTo(7.5f, 6f)
        lineTo(7.5f, 18f)
        moveTo(12f, 3f)
        lineTo(12f, 21f)
        moveTo(16.5f, 7f)
        lineTo(16.5f, 17f)
        moveTo(21f, 10f)
        lineTo(21f, 14f)
    }
    val Back = outline("Back") {
        moveTo(15f, 5f)
        lineTo(8f, 12f)
        lineTo(15f, 19f)
    }
    val Next = outline("Next") {
        moveTo(9f, 5f)
        lineTo(16f, 12f)
        lineTo(9f, 19f)
    }
    val Down = outline("Down") {
        moveTo(6f, 9f)
        lineTo(12f, 15f)
        lineTo(18f, 9f)
    }
    val Plus = outline("Plus") {
        moveTo(12f, 5f)
        lineTo(12f, 19f)
        moveTo(5f, 12f)
        lineTo(19f, 12f)
    }
    val Play = outline("Play") {
        moveTo(8f, 4f)
        lineTo(20f, 12f)
        lineTo(8f, 20f)
        close()
    }
    val Pause = outline("Pause") {
        moveTo(8f, 5f)
        lineTo(8f, 19f)
        moveTo(16f, 5f)
        lineTo(16f, 19f)
    }
    val Stop = outline("Stop") {
        moveTo(6f, 6f)
        lineTo(18f, 6f)
        lineTo(18f, 18f)
        lineTo(6f, 18f)
        close()
    }
    val Settings = outline("Settings") {
        moveTo(4f, 6f)
        lineTo(20f, 6f)
        moveTo(4f, 12f)
        lineTo(20f, 12f)
        moveTo(4f, 18f)
        lineTo(20f, 18f)
        moveTo(8f, 3f)
        lineTo(8f, 9f)
        moveTo(16f, 9f)
        lineTo(16f, 15f)
        moveTo(10f, 15f)
        lineTo(10f, 21f)
    }
    val Cloud = outline("Cloud") {
        moveTo(7f, 18f)
        curveTo(0f, 18f, 1f, 9f, 7f, 9f)
        curveTo(8f, 1f, 19f, 2f, 19f, 10f)
        curveTo(25f, 11f, 23f, 18f, 18f, 18f)
        close()
    }
    val Text = outline("Text") {
        moveTo(4f, 5f)
        lineTo(20f, 5f)
        moveTo(4f, 10f)
        lineTo(16f, 10f)
        moveTo(4f, 15f)
        lineTo(20f, 15f)
        moveTo(4f, 20f)
        lineTo(13f, 20f)
    }
    val Spark = outline("Spark") {
        moveTo(12f, 3f)
        lineTo(14.5f, 9.5f)
        lineTo(21f, 12f)
        lineTo(14.5f, 14.5f)
        lineTo(12f, 21f)
        lineTo(9.5f, 14.5f)
        lineTo(3f, 12f)
        lineTo(9.5f, 9.5f)
        close()
    }
    val Memory = outline("Memory") {
        moveTo(5f, 4f)
        lineTo(19f, 4f)
        lineTo(19f, 21f)
        lineTo(12f, 17f)
        lineTo(5f, 21f)
        close()
    }
    val Chat = outline("Chat") {
        moveTo(4f, 4f)
        lineTo(20f, 4f)
        lineTo(20f, 16f)
        lineTo(9f, 16f)
        lineTo(4f, 21f)
        close()
        moveTo(8f, 9f)
        lineTo(16f, 9f)
        moveTo(8f, 12f)
        lineTo(13f, 12f)
    }
    val Export = outline("Export") {
        moveTo(12f, 15f)
        lineTo(12f, 3f)
        moveTo(7f, 8f)
        lineTo(12f, 3f)
        lineTo(17f, 8f)
        moveTo(4f, 14f)
        lineTo(4f, 21f)
        lineTo(20f, 21f)
        lineTo(20f, 14f)
    }
    val Import = outline("Import") {
        moveTo(12f, 3f)
        lineTo(12f, 15f)
        moveTo(7f, 10f)
        lineTo(12f, 15f)
        lineTo(17f, 10f)
        moveTo(4f, 14f)
        lineTo(4f, 21f)
        lineTo(20f, 21f)
        lineTo(20f, 14f)
    }
    private fun outline(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathFillType = PathFillType.NonZero, pathBuilder = draw)
        }.build()
}

@Composable
internal fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Int = 44) {
    Surface(modifier.size(size.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size((size / 2).dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
internal fun SectionLabel(title: String, accessory: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        accessory?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp), content = content)
    }
}
