package me.avinas.tempo.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom vector icons used across ratings, celebrations, and menu actions.
 */
object TempoIcons {

    private fun vector(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    private fun PathBuilder.svg(pathStr: String) {
        for (node in PathParser().parsePathString(pathStr).toNodes()) {
            when (node) {
                is PathNode.MoveTo -> moveTo(node.x, node.y)
                is PathNode.RelativeMoveTo -> moveToRelative(node.dx, node.dy)
                is PathNode.LineTo -> lineTo(node.x, node.y)
                is PathNode.RelativeLineTo -> lineToRelative(node.dx, node.dy)
                is PathNode.HorizontalTo -> horizontalLineTo(node.x)
                is PathNode.RelativeHorizontalTo -> horizontalLineToRelative(node.dx)
                is PathNode.VerticalTo -> verticalLineTo(node.y)
                is PathNode.RelativeVerticalTo -> verticalLineToRelative(node.dy)
                is PathNode.CurveTo -> curveTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
                is PathNode.RelativeCurveTo -> curveToRelative(node.dx1, node.dy1, node.dx2, node.dy2, node.dx3, node.dy3)
                is PathNode.ReflectiveCurveTo -> reflectiveCurveTo(node.x1, node.y1, node.x2, node.y2)
                is PathNode.RelativeReflectiveCurveTo -> reflectiveCurveToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
                is PathNode.QuadTo -> quadTo(node.x1, node.y1, node.x2, node.y2)
                is PathNode.RelativeQuadTo -> quadToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
                is PathNode.ReflectiveQuadTo -> reflectiveQuadTo(node.x, node.y)
                is PathNode.RelativeReflectiveQuadTo -> reflectiveQuadToRelative(node.dx, node.dy)
                is PathNode.ArcTo -> arcTo(node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta, node.isMoreThanHalf, node.isPositiveArc, node.arcStartX, node.arcStartY)
                is PathNode.RelativeArcTo -> arcToRelative(node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta, node.isMoreThanHalf, node.isPositiveArc, node.arcStartDx, node.arcStartDy)
                is PathNode.Close -> close()
            }
        }
    }

    /** Filled star with subtle radiused apexes for high-craft reviews. */
    val StarFilled: ImageVector by lazy {
        vector("TempoStarFilled") {
            path(fill = SolidColor(Color.White)) {
                svg("M12,2.5 L14.9,8.6 L21.6,9.5 L16.7,14.2 L17.9,20.8 L12,17.6 L6.1,20.8 L7.3,14.2 L2.4,9.5 L9.1,8.6 Z")
            }
        }
    }

    /** Outlined star with disciplined 1.6dp frame. */
    val StarOutline: ImageVector by lazy {
        vector("TempoStarOutline") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                svg(
                    "M12,2.5 L14.9,8.6 L21.6,9.5 L16.7,14.2 L17.9,20.8 L12,17.6 L6.1,20.8 L7.3,14.2 L2.4,9.5 L9.1,8.6 Z " +
                    "M12,6.2 L10,10.4 L5.4,11.0 L8.8,14.2 L8.0,18.7 L12,16.5 L16.0,18.7 L15.2,14.2 L18.6,11.0 L14,10.4 Z"
                )
            }
        }
    }

    /** Radiant spotlight burst: primary 4-point diamond star + dual satellite diamond glints. */
    val SparkleBurst: ImageVector by lazy {
        vector("TempoSparkleBurst") {
            // Main central diamond burst
            path(fill = SolidColor(Color.White)) {
                svg("M12,1.8 C12.2,7.2 7.2,12.2 1.8,12 C7.2,11.8 12.2,16.8 12,22.2 C11.8,16.8 16.8,11.8 22.2,12 C16.8,12.2 11.8,7.2 12,1.8 Z")
            }
            // Satellite glint top-right
            path(fill = SolidColor(Color.White)) {
                svg("M19.5,2.5 C19.6,4.6 17.6,6.6 15.5,6.5 C17.6,6.4 19.6,8.4 19.5,10.5 C19.4,8.4 21.4,6.4 23.5,6.5 C21.4,6.6 19.4,4.6 19.5,2.5 Z")
            }
            // Satellite glint bottom-left
            path(fill = SolidColor(Color.White)) {
                svg("M4.5,14.5 C4.6,16.2 3.2,17.8 1.5,17.7 C3.2,17.6 4.6,19.2 4.5,20.9 C4.4,19.2 6.0,17.6 7.7,17.7 C6.0,17.8 4.4,16.2 4.5,14.5 Z")
            }
        }
    }

    /** Geometric checkmark with rounded vertices. */
    val Check: ImageVector by lazy {
        vector("TempoCheck") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                svg("M4.5,12.5 L9.5,17.5 L19.5,6.5")
            }
        }
    }

    /** Success badge: ring + checkmark. */
    val CheckCircle: ImageVector by lazy {
        vector("TempoCheckCircle") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                svg(
                    "M12,2 A10,10 0 1,0 12,22 A10,10 0 1,0 12,2 Z " +
                    "M12,4 A8,8 0 1,1 12,20 A8,8 0 1,1 12,4 Z"
                )
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                svg("M7.8,12.2 L10.6,15.0 L16.2,9.4")
            }
        }
    }

    /** Warning shield / circle with exclamation badge. */
    val AlertCircle: ImageVector by lazy {
        vector("TempoAlertCircle") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                svg(
                    "M12,2 A10,10 0 1,0 12,22 A10,10 0 1,0 12,2 Z " +
                    "M12,4 A8,8 0 1,1 12,20 A8,8 0 1,1 12,4 Z"
                )
            }
            path(fill = SolidColor(Color.White)) {
                svg("M11,7.2 H13 V13 H11 Z M11,15.2 H13 V17.2 H11 Z")
            }
        }
    }

    /** Error / warning dismiss badge. */
    val ErrorCircle: ImageVector by lazy {
        vector("TempoErrorCircle") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                svg(
                    "M12,2 A10,10 0 1,0 12,22 A10,10 0 1,0 12,2 Z " +
                    "M12,4 A8,8 0 1,1 12,20 A8,8 0 1,1 12,4 Z"
                )
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round
            ) {
                svg("M8.8,8.8 L15.2,15.2 M15.2,8.8 L8.8,15.2")
            }
        }
    }

    /** Info badge. */
    val InfoCircle: ImageVector by lazy {
        vector("TempoInfoCircle") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                svg(
                    "M12,2 A10,10 0 1,0 12,22 A10,10 0 1,0 12,2 Z " +
                    "M12,4 A8,8 0 1,1 12,20 A8,8 0 1,1 12,4 Z"
                )
            }
            path(fill = SolidColor(Color.White)) {
                svg("M11,6.8 H13 V8.8 H11 Z M11,10.8 H13 V17.2 H11 Z")
            }
        }
    }

    /** Minimalist editorial trash / delete glyph. */
    val Trash: ImageVector by lazy {
        vector("TempoTrash") {
            // Lid with micro-handle
            path(fill = SolidColor(Color.White)) {
                svg("M9,3.5 H15 V5 H9 Z M4.5,5 H19.5 V6.5 H4.5 Z")
            }
            // Can with fluted recesses
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                svg(
                    "M6,7.5 L7.2,19.8 C7.3,20.5 7.9,21 8.6,21 H15.4 C16.1,21 16.7,20.5 16.8,19.8 L18,7.5 H6 Z " +
                    "M9.2,9.5 H10.7 V18.5 H9.2 Z M13.3,9.5 H14.8 V18.5 H13.3 Z"
                )
            }
        }
    }

    /** Dual-pathway merge streams with node anchor. */
    val MergeStreams: ImageVector by lazy {
        vector("TempoMergeStreams") {
            // Converged target node
            path(fill = SolidColor(Color.White)) {
                svg("M12,19.5 A2.5,2.5 0 1,0 12,14.5 A2.5,2.5 0 0,0 12,19.5 Z")
            }
            // Left stream node
            path(fill = SolidColor(Color.White)) {
                svg("M6.5,7 A2,2 0 1,0 6.5,3 A2,2 0 0,0 6.5,7 Z")
            }
            // Right stream node
            path(fill = SolidColor(Color.White)) {
                svg("M17.5,7 A2,2 0 1,0 17.5,3 A2,2 0 0,0 17.5,7 Z")
            }
            // Connecting curved tracks
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round
            ) {
                svg("M6.5,7 C6.5,12 12,12 12,15 M17.5,7 C17.5,12 12,12 12,15")
            }
        }
    }

    /** Branching split streams icon. */
    val SplitStreams: ImageVector by lazy {
        vector("TempoSplitStreams") {
            // Source root node
            path(fill = SolidColor(Color.White)) {
                svg("M12,7 A2.5,2.5 0 1,0 12,2 A2.5,2.5 0 0,0 12,7 Z")
            }
            // Left branch node
            path(fill = SolidColor(Color.White)) {
                svg("M6.5,21 A2,2 0 1,0 6.5,17 A2,2 0 0,0 6.5,21 Z")
            }
            // Right branch node
            path(fill = SolidColor(Color.White)) {
                svg("M17.5,21 A2,2 0 1,0 17.5,17 A2,2 0 0,0 17.5,21 Z")
            }
            // Diverging branch lines
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round
            ) {
                svg("M12,7 C12,11 6.5,12 6.5,17 M12,7 C12,11 17.5,12 17.5,17")
            }
        }
    }

    /** Precision editorial pen / pencil glyph. */
    val Edit: ImageVector by lazy {
        vector("TempoEdit") {
            path(fill = SolidColor(Color.White)) {
                svg(
                    "M3,17.2 L3,21 H6.8 L17.8,10.0 L14.0,6.2 L3,17.2 Z " +
                    "M20.7,7.1 C21.1,6.7 21.1,6.0 20.7,5.6 L18.4,3.3 C18.0,2.9 17.3,2.9 16.9,3.3 L15.1,5.1 L18.9,8.9 L20.7,7.1 Z"
                )
            }
        }
    }

    /** Telemetry stepped equalizer / sort bars. */
    val SortBars: ImageVector by lazy {
        vector("TempoSortBars") {
            path(fill = SolidColor(Color.White)) {
                svg(
                    "M4,17.5 H8 V19.5 H4 Z " +
                    "M4,11 H13.5 V13 H4 Z " +
                    "M4,4.5 H19.5 V6.5 H4 Z"
                )
            }
        }
    }

    /** Modern broadcast wave / podcast glyph. */
    val Podcast: ImageVector by lazy {
        vector("TempoPodcast") {
            // Center microphone pill
            path(fill = SolidColor(Color.White)) {
                svg("M12,14 C13.4,14 14.5,12.9 14.5,11.5 V5.5 C14.5,4.1 13.4,3 12,3 C10.6,3 9.5,4.1 9.5,5.5 V11.5 C9.5,12.9 10.6,14 12,14 Z")
            }
            // Base stand & stem
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round
            ) {
                svg("M7.5,10.5 C7.5,13.2 9.5,15.5 12,15.5 C14.5,15.5 16.5,13.2 16.5,10.5 M12,15.5 V19.5 M8.5,19.5 H15.5")
            }
            // Outward radiation rings
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round
            ) {
                svg("M4.5,7 C3.5,8.4 3,10.1 3,12 C3,13.9 3.5,15.6 4.5,17 M19.5,7 C20.5,8.4 21,10.1 21,12 C21,13.9 20.5,15.6 19.5,17")
            }
        }
    }

    /** Modern hardback book with audio waves. */
    val Audiobook: ImageVector by lazy {
        vector("TempoAudiobook") {
            // Open book spine & wings
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                svg(
                    "M12,6.5 C10.2,5.2 7.7,4.8 5,4.8 C3.6,4.8 2.5,5.1 2,5.5 V19.5 C2.5,19.1 3.8,18.8 5,18.8 C7.5,18.8 10.1,19.4 12,20.8 " +
                    "C13.9,19.4 16.5,18.8 19,18.8 C20.2,18.8 21.5,19.1 22,19.5 V5.5 C21.5,5.1 20.4,4.8 19,4.8 C16.3,4.8 13.8,5.2 12,6.5 Z " +
                    "M11,8.3 V18.6 C9.4,17.5 7.3,17.1 5,17.1 C4.1,17.1 3.4,17.3 3,17.5 V7.2 C3.5,7.0 4.2,6.9 5,6.9 C7.2,6.9 9.3,7.4 11,8.3 Z"
                )
            }
            // Sound wave indicator over right page
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round
            ) {
                svg("M15,9.5 V14.5 M17.2,8 V16 M19.5,10 V14")
            }
        }
    }

    /** Google Play store stylized faceted tri-color prism. */
    val GooglePlay: ImageVector by lazy {
        vector("TempoGooglePlay") {
            path(fill = SolidColor(Color.White)) {
                svg("M3.8,2.4 C3.6,2.7 3.5,3.2 3.5,3.7 V20.3 C3.5,20.8 3.6,21.3 3.8,21.6 L3.9,21.7 L13.8,11.8 V11.5 L3.9,1.7 L3.8,2.4 Z")
            }
            path(fill = SolidColor(Color.White)) {
                svg("M17.1,15.1 L13.8,11.8 V11.5 L17.1,8.2 L17.2,8.3 L21.0,10.5 C22.1,11.1 22.1,12.2 21.0,12.8 L17.2,15.0 L17.1,15.1 Z")
            }
            path(fill = SolidColor(Color.White)) {
                svg("M13.8,11.8 L3.8,21.7 C4.2,22.1 4.9,22.2 5.7,21.7 L17.1,15.2 L13.8,11.8 Z")
            }
            path(fill = SolidColor(Color.White)) {
                svg("M13.8,11.5 L17.1,8.2 L5.7,1.7 C4.9,1.2 4.2,1.3 3.8,1.7 L13.8,11.5 Z")
            }
        }
    }
}
