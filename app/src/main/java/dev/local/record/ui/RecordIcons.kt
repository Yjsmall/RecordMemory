package dev.local.record.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Rounded optical shapes on a shared 24 dp grid; controls supply their own semantics and tint. */
internal object RecordIcons {
    val Search = outline("Search") {
        moveTo(17f, 10.5f)
        curveTo(17f, 14.09f, 14.09f, 17f, 10.5f, 17f)
        curveTo(6.91f, 17f, 4f, 14.09f, 4f, 10.5f)
        curveTo(4f, 6.91f, 6.91f, 4f, 10.5f, 4f)
        curveTo(14.09f, 4f, 17f, 6.91f, 17f, 10.5f)
        close()
        moveTo(15.2f, 15.2f)
        lineTo(20.5f, 20.5f)
    }
    val Send = outline("Send") {
        moveTo(12f, 19f)
        lineTo(12f, 5f)
        moveTo(6.5f, 10.5f)
        lineTo(12f, 5f)
        lineTo(17.5f, 10.5f)
    }
    val Edit = outline("Edit") {
        moveTo(14.5f, 5f)
        lineTo(18.5f, 9f)
        moveTo(4.5f, 15f)
        lineTo(15.5f, 4f)
        curveTo(16.3f, 3.2f, 17.6f, 3.2f, 18.4f, 4f)
        lineTo(20f, 5.6f)
        curveTo(20.8f, 6.4f, 20.8f, 7.7f, 20f, 8.5f)
        lineTo(9f, 19.5f)
        lineTo(3.5f, 20.5f)
        close()
    }
    val More = filled("More") {
        circle(5f, 12f, 1.65f)
        circle(12f, 12f, 1.65f)
        circle(19f, 12f, 1.65f)
    }
    val Delete = outline("Delete") {
        moveTo(4.5f, 6.5f)
        lineTo(19.5f, 6.5f)
        moveTo(9f, 6.5f)
        lineTo(9f, 4.5f)
        curveTo(9f, 3.67f, 9.67f, 3f, 10.5f, 3f)
        lineTo(13.5f, 3f)
        curveTo(14.33f, 3f, 15f, 3.67f, 15f, 4.5f)
        lineTo(15f, 6.5f)
        moveTo(6f, 6.5f)
        lineTo(7f, 18.8f)
        curveTo(7.1f, 20f, 7.8f, 20.5f, 9f, 20.5f)
        lineTo(15f, 20.5f)
        curveTo(16.2f, 20.5f, 16.9f, 20f, 17f, 18.8f)
        lineTo(18f, 6.5f)
        moveTo(10f, 10.5f)
        lineTo(10.3f, 16.5f)
        moveTo(14f, 10.5f)
        lineTo(13.7f, 16.5f)
    }
    val Mic = outline("Mic") {
        moveTo(8.5f, 6f)
        curveTo(8.5f, 1.33f, 15.5f, 1.33f, 15.5f, 6f)
        lineTo(15.5f, 11f)
        curveTo(15.5f, 15.67f, 8.5f, 15.67f, 8.5f, 11f)
        close()
        moveTo(5.5f, 10.5f)
        lineTo(5.5f, 11f)
        curveTo(5.5f, 19.67f, 18.5f, 19.67f, 18.5f, 11f)
        lineTo(18.5f, 10.5f)
        moveTo(12f, 17.5f)
        lineTo(12f, 21f)
        moveTo(8.5f, 21f)
        lineTo(15.5f, 21f)
    }
    val Wave = outline("Wave", width = 2.3f) {
        moveTo(4f, 10f)
        lineTo(4f, 14f)
        moveTo(8f, 7f)
        lineTo(8f, 17f)
        moveTo(12f, 4f)
        lineTo(12f, 20f)
        moveTo(16f, 8f)
        lineTo(16f, 16f)
        moveTo(20f, 10.5f)
        lineTo(20f, 13.5f)
    }
    val Back = outline("Back") {
        moveTo(14.5f, 5.5f)
        lineTo(8f, 12f)
        lineTo(14.5f, 18.5f)
    }
    val Next = outline("Next") {
        moveTo(9.5f, 5.5f)
        lineTo(16f, 12f)
        lineTo(9.5f, 18.5f)
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
    val Play = filled("Play") {
        moveTo(8f, 5.6f)
        curveTo(8f, 4.7f, 8.9f, 4.2f, 9.7f, 4.7f)
        lineTo(19.3f, 10.9f)
        curveTo(20.1f, 11.4f, 20.1f, 12.6f, 19.3f, 13.1f)
        lineTo(9.7f, 19.3f)
        curveTo(8.9f, 19.8f, 8f, 19.3f, 8f, 18.4f)
        close()
    }
    val Pause = filled("Pause") {
        roundedRect(6.5f, 5f, 10.5f, 19f, 1.3f)
        roundedRect(13.5f, 5f, 17.5f, 19f, 1.3f)
    }
    val Stop = filled("Stop") { roundedRect(5.5f, 5.5f, 18.5f, 18.5f, 2.4f) }
    val Settings = outline("Settings") {
        moveTo(4f, 6f)
        lineTo(6f, 6f)
        moveTo(10f, 6f)
        lineTo(20f, 6f)
        circle(8f, 6f, 2f)
        moveTo(4f, 12f)
        lineTo(14f, 12f)
        moveTo(18f, 12f)
        lineTo(20f, 12f)
        circle(16f, 12f, 2f)
        moveTo(4f, 18f)
        lineTo(8f, 18f)
        moveTo(12f, 18f)
        lineTo(20f, 18f)
        circle(10f, 18f, 2f)
    }
    val Cloud = outline("Cloud") {
        moveTo(7f, 18.5f)
        curveTo(1.4f, 18.5f, 1.4f, 10f, 7f, 10f)
        curveTo(7.6f, 3f, 17.8f, 2.7f, 18.5f, 10f)
        curveTo(23.8f, 10.6f, 23f, 18.5f, 18f, 18.5f)
        close()
    }
    val Text = outline("Text") {
        moveTo(5f, 5.5f)
        lineTo(19f, 5.5f)
        moveTo(5f, 10f)
        lineTo(15f, 10f)
        moveTo(5f, 14.5f)
        lineTo(19f, 14.5f)
        moveTo(5f, 19f)
        lineTo(12f, 19f)
    }
    val Spark = outline("Spark") {
        moveTo(11f, 4f)
        curveTo(12.6f, 9.2f, 13.8f, 10.4f, 19f, 12f)
        curveTo(13.8f, 13.6f, 12.6f, 14.8f, 11f, 20f)
        curveTo(9.4f, 14.8f, 8.2f, 13.6f, 3f, 12f)
        curveTo(8.2f, 10.4f, 9.4f, 9.2f, 11f, 4f)
        close()
        moveTo(19f, 3f)
        lineTo(19f, 7f)
        moveTo(17f, 5f)
        lineTo(21f, 5f)
    }
    val Memory = outline("Memory") {
        moveTo(5.5f, 20.5f)
        lineTo(5.5f, 5.5f)
        curveTo(5.5f, 4.1f, 6.6f, 3f, 8f, 3f)
        lineTo(16f, 3f)
        curveTo(17.4f, 3f, 18.5f, 4.1f, 18.5f, 5.5f)
        lineTo(18.5f, 20.5f)
        lineTo(12f, 16.5f)
        close()
        moveTo(9f, 7.5f)
        lineTo(15f, 7.5f)
    }
    val Chat = outline("Chat") {
        moveTo(7f, 4f)
        lineTo(17f, 4f)
        curveTo(19.2f, 4f, 21f, 5.8f, 21f, 8f)
        lineTo(21f, 13f)
        curveTo(21f, 15.2f, 19.2f, 17f, 17f, 17f)
        lineTo(9f, 17f)
        lineTo(4f, 20.5f)
        lineTo(4f, 16f)
        curveTo(3.4f, 15.2f, 3f, 14.1f, 3f, 13f)
        lineTo(3f, 8f)
        curveTo(3f, 5.8f, 4.8f, 4f, 7f, 4f)
        close()
        moveTo(7.5f, 9f)
        lineTo(16.5f, 9f)
        moveTo(7.5f, 12.5f)
        lineTo(13.5f, 12.5f)
    }
    val Export = outline("Export") {
        moveTo(12f, 14f)
        lineTo(12f, 3.5f)
        moveTo(7.5f, 8f)
        lineTo(12f, 3.5f)
        lineTo(16.5f, 8f)
        tray()
    }
    val Import = outline("Import") {
        moveTo(12f, 3.5f)
        lineTo(12f, 14f)
        moveTo(7.5f, 9.5f)
        lineTo(12f, 14f)
        lineTo(16.5f, 9.5f)
        tray()
    }

    private fun PathBuilder.tray() {
        moveTo(4f, 14f)
        lineTo(4f, 18.5f)
        curveTo(4f, 19.9f, 5.1f, 21f, 6.5f, 21f)
        lineTo(17.5f, 21f)
        curveTo(18.9f, 21f, 20f, 19.9f, 20f, 18.5f)
        lineTo(20f, 14f)
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
        val control = radius * 0.552285f
        moveTo(cx + radius, cy)
        curveTo(cx + radius, cy + control, cx + control, cy + radius, cx, cy + radius)
        curveTo(cx - control, cy + radius, cx - radius, cy + control, cx - radius, cy)
        curveTo(cx - radius, cy - control, cx - control, cy - radius, cx, cy - radius)
        curveTo(cx + control, cy - radius, cx + radius, cy - control, cx + radius, cy)
        close()
    }

    private fun PathBuilder.roundedRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        val control = radius * 0.552285f
        moveTo(left + radius, top)
        lineTo(right - radius, top)
        curveTo(right - radius + control, top, right, top + radius - control, right, top + radius)
        lineTo(right, bottom - radius)
        curveTo(right, bottom - radius + control, right - radius + control, bottom, right - radius, bottom)
        lineTo(left + radius, bottom)
        curveTo(left + radius - control, bottom, left, bottom - radius + control, left, bottom - radius)
        lineTo(left, top + radius)
        curveTo(left, top + radius - control, left + radius - control, top, left + radius, top)
        close()
    }

    private fun outline(name: String, width: Float = 1.8f, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    private fun filled(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = draw)
        }.build()
}
