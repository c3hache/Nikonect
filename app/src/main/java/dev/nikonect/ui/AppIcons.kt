package dev.nikonect.ui

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.*

enum class UiIcon { TUNE, MONITOR, GALLERY, SETTINGS, CAMERA, REFRESH, DISCONNECT, ADD, REMOVE, PLAY, STOP, GRID, HISTOGRAM, MORE, ROTATE, FULLSCREEN, DOWNLOAD, SHARE, CLOSE, BEFORE, NEXT, INFO, DELETE, CHECK, WIFI, HIDE, FOLDER, RESTORE, PHONE, WRENCH }
private fun PathBuilder.line(x1: Float, y1: Float, x2: Float, y2: Float) { moveTo(x1, y1); lineTo(x2, y2) }
private fun PathBuilder.box(x1: Float, y1: Float, x2: Float, y2: Float) { moveTo(x1, y1); lineTo(x2, y1); lineTo(x2, y2); lineTo(x1, y2); close() }
private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
    moveTo(cx + radius, cy)
    curveTo(cx + radius, cy + radius * .5523f, cx + radius * .5523f, cy + radius, cx, cy + radius)
    curveTo(cx - radius * .5523f, cy + radius, cx - radius, cy + radius * .5523f, cx - radius, cy)
    curveTo(cx - radius, cy - radius * .5523f, cx - radius * .5523f, cy - radius, cx, cy - radius)
    curveTo(cx + radius * .5523f, cy - radius, cx + radius, cy - radius * .5523f, cx + radius, cy)
}
private val vectors = UiIcon.entries.associateWith { symbol ->
    ImageVector.Builder(symbol.name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            when (symbol) {
                UiIcon.WRENCH -> {
                    moveTo(20f, 4f); lineTo(16f, 8f); lineTo(13f, 5f); lineTo(16f, 2f)
                    curveTo(10f, 1f, 8f, 7f, 10f, 11f); lineTo(3f, 18f)
                    curveTo(1f, 21f, 4f, 23f, 6f, 21f); lineTo(14f, 13f)
                    curveTo(19f, 14f, 23f, 9f, 20f, 4f); close()
                }
                UiIcon.FOLDER -> { moveTo(3f, 5f); lineTo(9f, 5f); lineTo(11f, 8f); lineTo(21f, 8f); lineTo(21f, 20f); lineTo(3f, 20f); close() }
                UiIcon.RESTORE -> { moveTo(4f, 10f); curveTo(7f, 2f, 19f, 3f, 21f, 11f); curveTo(23f, 19f, 12f, 25f, 6f, 19f); moveTo(4f, 4f); lineTo(4f, 10f); lineTo(10f, 10f); line(12f, 8f, 12f, 13f); line(12f, 13f, 16f, 15f) }
                UiIcon.PHONE -> { box(6f, 2f, 18f, 22f); line(10f, 18f, 14f, 18f) }
                UiIcon.DELETE -> { line(4f, 6f, 20f, 6f); box(9f, 3f, 15f, 6f); moveTo(6f, 6f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 6f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f) }
                UiIcon.CHECK -> { moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f) }
                UiIcon.WIFI -> { moveTo(2f, 8f); curveTo(7f, 2f, 17f, 2f, 22f, 8f); moveTo(5f, 12f); curveTo(9f, 8f, 15f, 8f, 19f, 12f); moveTo(8f, 16f); curveTo(10f, 14f, 14f, 14f, 16f, 16f); circle(12f, 20f, 1f) }
                UiIcon.HIDE -> { moveTo(3f, 9f); lineTo(12f, 16f); lineTo(21f, 9f) }
                UiIcon.TUNE -> { line(3f, 6f, 21f, 6f); line(3f, 12f, 21f, 12f); line(3f, 18f, 21f, 18f); line(8f, 3f, 8f, 9f); line(16f, 9f, 16f, 15f); line(9f, 15f, 9f, 21f) }
                UiIcon.MONITOR -> { box(3f, 4f, 21f, 17f); line(12f, 17f, 12f, 21f); line(8f, 21f, 16f, 21f) }
                UiIcon.GALLERY -> { box(4f, 3f, 21f, 19f); moveTo(4f, 15f); lineTo(9f, 10f); lineTo(13f, 14f); lineTo(16f, 11f); lineTo(21f, 16f); circle(16f, 7f, 1f); moveTo(1f, 7f); lineTo(1f, 22f); lineTo(17f, 22f) }
                UiIcon.SETTINGS -> { for (i in 0..32) { val a = i * PI / 16; val r = if (i % 4 < 2) 9f else 7f; val x = 12 + cos(a).toFloat() * r; val y = 12 + sin(a).toFloat() * r; if (i == 0) moveTo(x, y) else lineTo(x, y) }; close(); circle(12f, 12f, 3f) }
                UiIcon.CAMERA -> { moveTo(3f, 7f); lineTo(7f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(17f, 7f); lineTo(21f, 7f); lineTo(21f, 20f); lineTo(3f, 20f); close(); circle(12f, 13f, 4f) }
                UiIcon.REFRESH -> { moveTo(20f, 9f); curveTo(18f, 2f, 7f, 2f, 4f, 9f); curveTo(0f, 18f, 12f, 25f, 19f, 17f); moveTo(20f, 4f); lineTo(20f, 9f); lineTo(15f, 9f) }
                UiIcon.DISCONNECT -> { line(9f, 3f, 9f, 8f); line(15f, 3f, 15f, 8f); moveTo(6f, 8f); lineTo(18f, 8f); lineTo(18f, 13f); curveTo(18f, 19f, 6f, 19f, 6f, 13f); close(); line(12f, 18f, 12f, 22f); line(3f, 3f, 21f, 21f) }
                UiIcon.ADD -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
                UiIcon.REMOVE -> line(5f, 12f, 19f, 12f)
                UiIcon.PLAY -> { moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close() }
                UiIcon.STOP -> box(5f, 5f, 19f, 19f)
                UiIcon.GRID -> { box(3f, 3f, 21f, 21f); line(9f, 3f, 9f, 21f); line(15f, 3f, 15f, 21f); line(3f, 9f, 21f, 9f); line(3f, 15f, 21f, 15f) }
                UiIcon.HISTOGRAM -> { line(3f, 21f, 21f, 21f); line(5f, 21f, 5f, 14f); line(10f, 21f, 10f, 4f); line(15f, 21f, 15f, 9f); line(20f, 21f, 20f, 16f) }
                UiIcon.MORE -> { circle(12f, 4f, 1f); circle(12f, 12f, 1f); circle(12f, 20f, 1f) }
                UiIcon.ROTATE -> { moveTo(4f, 7f); curveTo(1f, 14f, 5f, 21f, 12f, 21f); moveTo(20f, 17f); curveTo(23f, 10f, 19f, 3f, 12f, 3f); box(8f, 6f, 16f, 18f); moveTo(4f, 12f); lineTo(4f, 7f); lineTo(9f, 7f) }
                UiIcon.FULLSCREEN -> { moveTo(3f, 9f); lineTo(3f, 3f); lineTo(9f, 3f); moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f); moveTo(21f, 15f); lineTo(21f, 21f); lineTo(15f, 21f); moveTo(9f, 21f); lineTo(3f, 21f); lineTo(3f, 15f) }
                UiIcon.DOWNLOAD -> { line(12f, 3f, 12f, 16f); moveTo(7f, 11f); lineTo(12f, 16f); lineTo(17f, 11f); moveTo(4f, 17f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 17f) }
                UiIcon.SHARE -> { circle(5f, 12f, 3f); circle(19f, 5f, 3f); circle(19f, 19f, 3f); line(8f, 11f, 16f, 6f); line(8f, 13f, 16f, 18f) }
                UiIcon.CLOSE -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
                UiIcon.BEFORE -> { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
                UiIcon.NEXT -> { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
                UiIcon.INFO -> { circle(12f, 12f, 9f); line(12f, 10f, 12f, 17f); circle(12f, 6f, .5f) }
            }
        }
    }.build()
}
@Composable
fun AppIcon(icon: UiIcon, description: String?, modifier: Modifier = Modifier) { Icon(vectors.getValue(icon), description, modifier) }
val PageIcons = listOf(UiIcon.MONITOR, UiIcon.TUNE, UiIcon.GALLERY, UiIcon.SETTINGS)
