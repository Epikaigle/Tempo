package me.avinas.tempo.ui.profile

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
 * Custom SVG artwork for every profile badge.
 *
 * Each badge is a bespoke, hand-drawn 24x24 vector — no stock icons. Every
 * glyph is concept-driven (Century is a wax seal, Ironclad is a shield with
 * a lightning cut-out, The Centennial is a trophy…) so a badge reads as a
 * collectible object someone *designed*, not a shape with an icon dropped in.
 *
 * Fill/stroke colors are placeholders — the Icon tint in [BadgeEmblem]
 * overrides them; only the geometry matters here. Subpaths that must punch
 * holes (rings, eyes, checker squares) use [PathFillType.EvenOdd].
 */
object BadgeArt {

    private val cache = HashMap<String, ImageVector>()

    fun artFor(badgeId: String): ImageVector = cache.getOrPut(badgeId) { build(badgeId) }

    private fun build(badgeId: String): ImageVector = when (badgeId) {
        "first_play" -> firstNote()
        "plays_100" -> century()
        "plays_500" -> soundPilgrim()
        "plays_1000" -> grandMaestro()
        "plays_5000" -> virtuoso()
        "plays_10000" -> legendary()
        "time_1h" -> firstHour()
        "time_24h" -> dayTripper()
        "time_100h" -> centurion()
        "time_500h" -> soundSage()
        "streak_7" -> weekWarrior()
        "streak_30" -> monthlyMaven()
        "streak_100" -> ironclad()
        "streak_365" -> yearRound()
        "artists_10" -> explorer()
        "artists_50" -> curator()
        "artists_100" -> connoisseur()
        "genres_10" -> genreHopper()
        "genres_25" -> eclectic()
        "night_owl" -> nightOwl()
        "early_bird" -> earlyBird()
        "marathon" -> marathon()
        "level_5" -> risingStar()
        "level_10" -> doubleDigits()
        "level_25" -> quarterCentury()
        "level_50" -> halfwayThere()
        "level_75" -> eliteListener()
        "level_100" -> centennial()
        else -> century()
    }

    private fun coin(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    /** Parses an SVG path string and replays it on this builder. */
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
                else -> {}
            }
        }
    }

    // Milestones — plays

    /** First Note — a single eighth note; the journey begins. */
    private fun firstNote() = coin("BadgeFirstNote") {
        path(fill = SolidColor(Color.White)) {
            svg("M6.7,17.4 a3.3,3.3 0 1,0 6.6,0 a3.3,3.3 0 1,0 -6.6,0 z")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M12.5,4.2 h1.6 v13.4 h-1.6 z")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M14.1,4.2 c3.1,1.2 5.1,3.3 5.1,6.9 c0,0.7 -0.6,1.1 -1.2,0.9 c-1.2,-0.4 -2.5,-1.2 -3.9,-1.7 v-2.1 c0.9,0.4 1.7,0.9 2.4,1.5 c-0.3,-2.1 -1.3,-3.4 -2.4,-4 z")
        }
    }

    /** Century — a wax seal: ring struck around a small star. */
    private fun century() = coin("BadgeCentury") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            svg(
                "M12,3.2 a8.8,8.8 0 1,0 0,17.6 a8.8,8.8 0 1,0 0,-17.6 z " +
                    "M12,5.7 a6.3,6.3 0 1,0 0,12.6 a6.3,6.3 0 1,0 0,-12.6 z"
            )
        }
        path(fill = SolidColor(Color.White)) {
            svg(
                "M12,8.3 L13.12,10.7 L15.52,10.86 L13.52,12.49 L14.17,14.99 L12,13.6 " +
                    "L9.83,14.99 L10.48,12.49 L8.48,10.86 L10.88,10.7 z"
            )
        }
    }

    /** Sound Pilgrim — a summit with a planted flag. */
    private fun soundPilgrim() = coin("BadgeSoundPilgrim") {
        path(fill = SolidColor(Color.White)) {
            svg("M2.6,19.6 L8.6,8.2 L11.6,12.9 L14.2,7.8 L21.4,19.6 z")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M13.5,2.4 h1.4 v5.4 h-1.4 z")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M14.9,2.6 h4.6 l-1.3,1.6 1.3,1.6 h-4.6 z")
        }
    }

    /** Grand Maestro — a conductor's baton catching sparks. */
    private fun grandMaestro() = coin("BadgeGrandMaestro") {
        path(fill = SolidColor(Color.White)) {
            svg("M3.9,18.9 L5.2,20.2 L17.2,8.2 L15.9,6.9 z")
        }
        path(fill = SolidColor(Color.White)) {
            svg(
                "M3.7,19.55 a0.85,0.85 0 1,0 1.7,0 a0.85,0.85 0 1,0 -1.7,0 z " +
                    "M15.7,7.55 a0.85,0.85 0 1,0 1.7,0 a0.85,0.85 0 1,0 -1.7,0 z"
            )
        }
        path(fill = SolidColor(Color.White)) {
            svg(
                "M18.7,1.3 c0.32,1.78 0.92,2.38 2.7,2.7 c-1.78,0.32 -2.38,0.92 -2.7,2.7 " +
                    "c-0.32,-1.78 -0.92,-2.38 -2.7,-2.7 c1.78,-0.32 2.38,-0.92 2.7,-2.7 z"
            )
        }
        path(fill = SolidColor(Color.White)) {
            svg(
                "M14.6,1.4 c0.16,0.86 0.44,1.14 1.3,1.3 c-0.86,0.16 -1.14,0.44 -1.3,1.3 " +
                    "c-0.16,-0.86 -0.44,-1.14 -1.3,-1.3 c0.86,-0.16 1.14,-0.44 1.3,-1.3 z"
            )
        }
    }

    /** Virtuoso — a brilliant-cut gem, crown and pavilion. */
    private fun virtuoso() = coin("BadgeVirtuoso") {
        path(fill = SolidColor(Color.White)) {
            svg("M8.2,4.4 h7.6 l2.6,3.9 h-12.8 z")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M5.6,9.6 h12.8 l-6.4,10.4 z")
        }
    }

    /** Legendary — a crown with three jewels struck into the band. */
    private fun legendary() = coin("BadgeLegendary") {
        path(fill = SolidColor(Color.White)) {
            svg("M3.8,17.2 L2.8,8.4 L8.1,11.1 L12,5.2 L15.9,11.1 L21.2,8.4 L20.2,17.2 z")
        }
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            svg(
                "M4.4,18.4 h15.2 v2.2 h-15.2 z " +
                    "M6.4,19.5 a0.8,0.8 0 1,0 1.6,0 a0.8,0.8 0 1,0 -1.6,0 z " +
                    "M11.2,19.5 a0.8,0.8 0 1,0 1.6,0 a0.8,0.8 0 1,0 -1.6,0 z " +
                    "M16,19.5 a0.8,0.8 0 1,0 1.6,0 a0.8,0.8 0 1,0 -1.6,0 z"
            )
        }
    }

    // Time

    /** First Hour — a clock face at one past twelve. */
    private fun firstHour() = coin("BadgeFirstHour") {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.1f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg("M12,3.9 a8.1,8.1 0 1,1 0,16.2 a8.1,8.1 0 1,1 0,-16.2")
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.3f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg("M12,12 L16.6,7.4")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M10.3,12 a1.7,1.7 0 1,0 3.4,0 a1.7,1.7 0 1,0 -3.4,0 z")
        }
    }

    /** Day Tripper — a full day's sun. */
    private fun dayTripper() = coin("BadgeDayTripper") {
        path(fill = SolidColor(Color.White)) {
            svg("M7.5,12 a4.5,4.5 0 1,0 9,0 a4.5,4.5 0 1,0 -9,0 z")
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg(
                "M12,5.8 V2.9 M18.2,12 H21.1 M12,18.2 V21.1 M5.8,12 H2.9 " +
                    "M16.3,7.7 L18.4,5.6 M16.3,16.3 L18.4,18.4 M7.7,16.3 L5.6,18.4 M7.7,7.7 L5.6,5.6"
            )
        }
    }

    /** Centurion — an hourglass, mid-pour. */
    private fun centurion() = coin("BadgeCenturion") {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            svg(
                "M6.5,3 h11 M6.5,21 h11 " +
                    "M7,3 v2.5 c0,3 2.4,4.4 3.8,5.5 c0.8,0.7 0.8,1.3 0,2 c-1.4,1.1 -3.8,2.5 -3.8,5.5 V21 " +
                    "M17,3 v2.5 c0,3 -2.4,4.4 -3.8,5.5 c-0.8,0.7 -0.8,1.3 0,2 c1.4,1.1 3.8,2.5 3.8,5.5 V21"
            )
        }
        path(fill = SolidColor(Color.White)) {
            svg(
                "M8.6,5.6 h6.8 l-3.4,3.1 z " +
                    "M11.4,11 h1.2 v3 h-1.2 z " +
                    "M8,18.5 l4,-3.6 4,3.6 z"
            )
        }
    }

    /** Sound Sage — headphones, the listener's crown. */
    private fun soundSage() = coin("BadgeSoundSage") {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg("M4.6,13.4 v-0.9 a7.4,7.4 0 0,1 14.8,0 v0.9")
        }
        path(fill = SolidColor(Color.White)) {
            svg(
                "M3.2,12.6 h4.1 v6.1 a2.05,2.05 0 0,1 -4.1,0 z " +
                    "M16.7,12.6 h4.1 v6.1 a2.05,2.05 0 0,1 -4.1,0 z"
            )
        }
    }

    // Streaks

    /** Week Warrior — a single flame, one week alive. */
    private fun weekWarrior() = coin("BadgeWeekWarrior") {
        path(fill = SolidColor(Color.White)) {
            svg(
                "M12,2.4 C12.6,4.7 14.2,5.9 15.6,7.3 C17.1,8.8 18.2,10.5 18.2,12.7 " +
                    "C18.2,16.4 15.4,19.3 12,19.3 C8.6,19.3 5.8,16.4 5.8,12.7 " +
                    "C5.8,10.6 6.8,9 8,7.8 C8,10 9,11.2 10.1,11.7 C9.4,8.6 10.4,5.8 12,2.4 z"
            )
        }
    }

    /** Monthly Maven — a torch: flame, bowl, handle. */
    private fun monthlyMaven() = coin("BadgeMonthlyMaven") {
        path(fill = SolidColor(Color.White)) {
            svg(
                "M12,1.1 C13.1,2.4 13.9,3.5 13.9,4.6 C13.9,5.8 13.1,6.7 12,6.7 " +
                    "C10.9,6.7 10.1,5.8 10.1,4.6 C10.1,3.5 10.9,2.4 12,1.1 z"
            )
        }
        path(fill = SolidColor(Color.White)) {
            svg("M7.5,7.4 h9 v2.6 c0,2.6 -1.9,4.7 -4.5,4.7 c-2.6,0 -4.5,-2.1 -4.5,-4.7 z")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M11,14.7 h2 v4.6 l-1,3.1 -1,-3.1 z")
        }
    }

    /** Ironclad — a shield with a lightning bolt struck clean through. */
    private fun ironclad() = coin("BadgeIronclad") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            svg(
                "M12,2 L19.8,4.8 V11 C19.8,16.1 16.5,19.9 12,21.4 C7.5,19.9 4.2,16.1 4.2,11 V4.8 z " +
                    "M12.9,6.8 L9.5,12.5 h2.2 L10.9,17.2 L14.5,11.5 h-2.3 z"
            )
        }
    }

    /** Year-Round — an eternal flame ringed inside a full circle of days. */
    private fun yearRound() = coin("BadgeYearRound") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            svg(
                "M12,3.1 a8.9,8.9 0 1,0 0,17.8 a8.9,8.9 0 1,0 0,-17.8 z " +
                    "M12,5.6 a6.4,6.4 0 1,0 0,12.8 a6.4,6.4 0 1,0 0,-12.8 z"
            )
        }
        path(fill = SolidColor(Color.White)) {
            svg(
                "M12,8.2 C12.9,9.5 13.7,10.5 13.7,11.7 C13.7,12.9 12.9,13.8 12,13.8 " +
                    "C11.1,13.8 10.3,12.9 10.3,11.7 C10.3,10.5 11.1,9.5 12,8.2 z"
            )
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg("M9.7,16.2 h4.6")
        }
    }

    // Discovery

    /** Explorer — a compass: needle, ring and hub. */
    private fun explorer() = coin("BadgeExplorer") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            svg(
                "M12,3.5 a8.5,8.5 0 1,0 0,17 a8.5,8.5 0 1,0 0,-17 z " +
                    "M12,5.1 a6.9,6.9 0 1,0 0,13.8 a6.9,6.9 0 1,0 0,-13.8 z " +
                    "M15.6,8.4 L13.3,13.3 L8.4,15.6 L10.7,10.7 z " +
                    "M10.8,12 a1.2,1.2 0 1,0 2.4,0 a1.2,1.2 0 1,0 -2.4,0 z"
            )
        }
    }

    /** Curator — a record framed like a gallery piece. */
    private fun curator() = coin("BadgeCurator") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            svg(
                "M3.5,3.5 h17 v17 h-17 z M6,6 h12 v12 h-12 z " +
                    "M8.5,12 a3.5,3.5 0 1,0 7,0 a3.5,3.5 0 1,0 -7,0 z " +
                    "M10.8,12 a1.2,1.2 0 1,0 2.4,0 a1.2,1.2 0 1,0 -2.4,0 z"
            )
        }
    }

    /** Connoisseur — a globe with meridians and latitude bands. */
    private fun connoisseur() = coin("BadgeConnoisseur") {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            svg(
                "M12,3.7 a8.3,8.3 0 1,0 0,16.6 a8.3,8.3 0 1,0 0,-16.6 M3.7,12 H20.3 " +
                    "M12,3.7 C9,3.7 7.5,7.3 7.5,12 C7.5,16.7 9,20.3 12,20.3 C15,20.3 16.5,16.7 16.5,12 C16.5,7.3 15,3.7 12,3.7 " +
                    "M5.1,8.4 C6.7,7.2 9.2,6.5 12,6.5 C14.8,6.5 17.3,7.2 18.9,8.4 " +
                    "M5.1,15.6 C6.7,16.8 9.2,17.5 12,17.5 C14.8,17.5 17.3,16.8 18.9,15.6"
            )
        }
    }

    /** Genre Hopper — an equalizer jumping between genres. */
    private fun genreHopper() = coin("BadgeGenreHopper") {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 3.4f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg("M4.6,15.4 V12.2 M9.4,17 V6.6 M14.2,16 V9.4 M19,15 V11.4")
        }
    }

    /** Eclectic — a painter's palette, thumb hole and paint wells. */
    private fun eclectic() = coin("BadgeEclectic") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            svg(
                "M12,3.1 C6.9,3.1 2.7,7.1 2.7,12 C2.7,16.9 6.9,20.9 12,20.9 " +
                    "C13.7,20.9 15,19.8 15,18.4 C15,17.7 14.7,17.1 14.2,16.6 " +
                    "C13.8,16.2 13.6,15.7 13.6,15.2 C13.6,14.1 14.5,13.2 15.6,13.2 H17.5 " +
                    "C20,13.2 21.3,11.4 21.3,9.3 C21.3,5.8 17.1,3.1 12,3.1 z " +
                    "M7.2,8.3 a1.9,1.9 0 1,0 3.8,0 a1.9,1.9 0 1,0 -3.8,0 z " +
                    "M14.8,8.1 a1.2,1.2 0 1,0 2.4,0 a1.2,1.2 0 1,0 -2.4,0 z " +
                    "M11.3,11 a1.2,1.2 0 1,0 2.4,0 a1.2,1.2 0 1,0 -2.4,0 z " +
                    "M6.5,11.6 a1.1,1.1 0 1,0 2.2,0 a1.1,1.1 0 1,0 -2.2,0 z"
            )
        }
    }

    // Engagement

    /** Night Owl — an owl's face: tufts, ringed eyes, beak. */
    private fun nightOwl() = coin("BadgeNightOwl") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            svg(
                "M6.4,5.3 L3.1,2.3 L4.3,7.3 C3.2,8.8 2.6,10.5 2.6,12.3 " +
                    "C2.6,16.8 6.8,20.4 12,20.4 C17.2,20.4 21.4,16.8 21.4,12.3 " +
                    "C21.4,10.5 20.8,8.8 19.7,7.3 L20.9,2.3 L17.6,5.3 " +
                    "C16,4.5 14.1,4.1 12,4.1 C9.9,4.1 8,4.5 6.4,5.3 z " +
                    "M6.4,11.5 a2.5,2.5 0 1,0 5,0 a2.5,2.5 0 1,0 -5,0 z " +
                    "M12.6,11.5 a2.5,2.5 0 1,0 5,0 a2.5,2.5 0 1,0 -5,0 z " +
                    "M12,13.4 L13.4,16 L12,17.5 L10.6,16 z"
            )
        }
    }

    /** Early Bird — sunrise on the horizon with a bird crossing. */
    private fun earlyBird() = coin("BadgeEarlyBird") {
        path(fill = SolidColor(Color.White)) {
            svg("M7,16.5 a5,5 0 0,1 10,0 z")
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg("M2.5,16.5 H21.5")
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg("M3.9,12.9 L5.7,14.2 M20.1,12.9 L18.3,14.2 M12,9.6 V11.3")
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg(
                "M9.2,6.4 C10,5.5 10.9,5.5 11.7,6.4 " +
                    "M12.3,6.4 C13.1,5.5 14,5.5 14.8,6.4"
            )
        }
    }

    /** Marathon — the checkered finish flag. */
    private fun marathon() = coin("BadgeMarathon") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            svg(
                "M5.6,2.6 h1.7 v18.8 h-1.7 z " +
                    "M8.3,3.6 H20.8 V11.1 H8.3 z " +
                    "M8.3,3.6 h2.5 v2.5 h-2.5 z M13.3,3.6 h2.5 v2.5 h-2.5 z M18.3,3.6 h2.5 v2.5 h-2.5 z " +
                    "M10.8,6.1 h2.5 v2.5 h-2.5 z M15.8,6.1 h2.5 v2.5 h-2.5 z " +
                    "M8.3,8.6 h2.5 v2.5 h-2.5 z M13.3,8.6 h2.5 v2.5 h-2.5 z M18.3,8.6 h2.5 v2.5 h-2.5 z"
            )
        }
    }

    // Level milestones

    /** Rising Star — a star lifting off on a swoosh. */
    private fun risingStar() = coin("BadgeRisingStar") {
        path(fill = SolidColor(Color.White)) {
            svg(
                "M12,5.5 L13.09,8.3 L16.09,8.47 L13.76,10.37 L14.53,13.28 L12,11.65 " +
                    "L9.47,13.28 L10.24,10.37 L7.91,8.47 L10.91,8.3 z"
            )
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg("M4.4,17.8 C7.3,16.3 10.2,15.7 13,15.9 C15.5,16.1 17.5,16.8 19,17.9")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M17.7,15.9 L21,16.9 L19.3,19.5 z")
        }
    }

    /** Double Digits — the 10, struck like a mint mark. */
    private fun doubleDigits() = coin("BadgeDoubleDigits") {
        path(fill = SolidColor(Color.White)) {
            svg("M6.7,4.2 h2.5 v14.2 h-2.5 z M6.7,4.6 L4.3,6.4 L6.7,6.4 z")
        }
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            svg(
                "M11.9,11.3 a3.5,7.2 0 1,0 7,0 a3.5,7.2 0 1,0 -7,0 z " +
                    "M14,11.3 a1.4,4 0 1,0 2.8,0 a1.4,4 0 1,0 -2.8,0 z"
            )
        }
    }

    /** Quarter Century — a dial filled to one quarter. */
    private fun quarterCentury() = coin("BadgeQuarterCentury") {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg("M12,4.5 a7.5,7.5 0 1,0 0,15 a7.5,7.5 0 1,0 0,-15")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M12,12 L12,6.1 A5.9,5.9 0 0,1 17.9,12 z")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M10.9,12 a1.1,1.1 0 1,0 2.2,0 a1.1,1.1 0 1,0 -2.2,0 z")
        }
    }

    /** Halfway There — a dial filled to half. */
    private fun halfwayThere() = coin("BadgeHalfwayThere") {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg("M12,4.5 a7.5,7.5 0 1,0 0,15 a7.5,7.5 0 1,0 0,-15")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M12,12 L6.1,12 A5.9,5.9 0 0,1 17.9,12 z")
        }
    }

    /** Elite Listener — a dial filled to three quarters. */
    private fun eliteListener() = coin("BadgeEliteListener") {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg("M12,4.5 a7.5,7.5 0 1,0 0,15 a7.5,7.5 0 1,0 0,-15")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M12,12 L12,6.1 A5.9,5.9 0 1,1 6.1,12 z")
        }
        path(fill = SolidColor(Color.White)) {
            svg("M10.9,12 a1.1,1.1 0 1,0 2.2,0 a1.1,1.1 0 1,0 -2.2,0 z")
        }
    }

    /** The Centennial — a trophy with a star struck on the cup. */
    private fun centennial() = coin("BadgeCentennial") {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            svg(
                "M7,3.4 H17 V8.2 C17,11 14.8,13.3 12,13.3 C9.2,13.3 7,11 7,8.2 z " +
                    "M12,6.3 L12.5,7.3 L13.6,7.4 L12.8,8.1 L13,9.2 L12,8.7 L11,9.2 L11.2,8.1 L10.4,7.4 L11.5,7.3 z"
            )
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round
        ) {
            svg(
                "M7,4.9 C4.3,4.9 3,6.3 3,8 C3,9.7 4.3,11.1 7,11.1 " +
                    "M17,4.9 C19.7,4.9 21,6.3 21,8 C21,9.7 19.7,11.1 17,11.1"
            )
        }
        path(fill = SolidColor(Color.White)) {
            svg(
                "M11,13.3 h2 v2.5 h-2 z " +
                    "M8,15.8 h8 v2 h-8 z " +
                    "M6.6,17.8 h10.8 v2.2 h-10.8 z"
            )
        }
    }
}
