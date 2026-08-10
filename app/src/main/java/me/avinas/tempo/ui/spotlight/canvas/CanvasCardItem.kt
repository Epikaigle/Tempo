package me.avinas.tempo.ui.spotlight.canvas

import me.avinas.tempo.ui.spotlight.SpotlightCardData
import java.util.UUID
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoBackground
import me.avinas.tempo.ui.theme.TempoCyan
import me.avinas.tempo.ui.theme.TempoErrorDeep
import me.avinas.tempo.ui.theme.TempoErrorSoft
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoInfoSoft
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoPrimaryMuted
import me.avinas.tempo.ui.theme.TempoSuccessDeep
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TempoSurfaceSunken
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TempoWarningSoft

/**
 * Represents a card placed on the sharing canvas.
 * Each card can be positioned, scaled, and rotated independently.
 */
data class CanvasCardItem(
    val id: String = UUID.randomUUID().toString(),
    val cardData: SpotlightCardData,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 0.5f,
    val rotation: Float = 0f,
    val zIndex: Int = 0
)

/**
 * Represents a text element on the canvas with full customization options.
 */
data class CanvasTextItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "Tap to edit",
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val zIndex: Int = 0,
    val style: TextStyle = TextStyle()
)

/**
 * Text styling configuration
 */
data class TextStyle(
    val fontPreset: FontPreset = FontPreset.Modern,
    val fontSize: Int = 24, // in sp
    val color: Color = Color.White,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val hasOutline: Boolean = false,
    val outlineColor: Color = Color.Black,
    val hasShadow: Boolean = true,
    val alignment: TextAlign = TextAlign.Center,
    val hasBackground: Boolean = false,
    val backgroundColor: Color = Color.Black
) {
    val fontWeight: FontWeight
        get() = if (isBold) FontWeight.Bold else FontWeight.Normal
    
    val fontStyle: FontStyle
        get() = if (isItalic) FontStyle.Italic else FontStyle.Normal
}

/**
 * Font presets for easy font selection
 */
enum class FontPreset(val displayName: String, val fontFamily: FontFamily) {
    Modern("Modern", FontFamily.SansSerif),
    Classic("Classic", FontFamily.Serif),
    Mono("Mono", FontFamily.Monospace),
    Cursive("Cursive", FontFamily.Cursive),
    Default("System", FontFamily.Default)
}

/**
 * Color presets for text
 */
object TextColors {
    val presets: List<Color> by lazy {
        listOf(
            Color.White,
            Color(0xFFF8FAFC), // Slate-50
            Color(0xFFFEF08A), // Yellow-200
            Color(0xFF86EFAC), // Green-300
            TempoInfoSoft, // Blue-300
            TempoAccent, // Violet-300
            TempoErrorSoft, // Rose-300
            TempoWarningSoft, // Orange-300
            TempoCyan, // Cyan-300
            Color(0xFFA5B4FC), // Indigo-300
            Color(0xFFF9A8D4), // Pink-300
            Color.Black
        )
    }
}

/**
 * Background options for the canvas
 */
data class CanvasBackground(
    val id: String,
    val name: String,
    val isGradient: Boolean,
    val colors: List<Color>? = null,
    val solidColor: Color? = null
) {
    fun getBrush(): Brush? = if (isGradient && colors != null) {
        Brush.verticalGradient(colors)
    } else null
    
    fun getColor(): Color = solidColor ?: Color.Black
    
    companion object {
        // Premium Mesh-style Backgrounds (Simulated via complex gradients in renderer)
        val Holographic = CanvasBackground(
            id = "holographic",
            name = "Holo",
            isGradient = true,
            colors = listOf(Color(0xFFF9A8D4), TempoAccent, TempoCyan, Color(0xFFF0ABFC))
        )
        
        val MidnightGrain = CanvasBackground(
            id = "midnight_grain",
            name = "Noise",
            isGradient = true,
            colors = listOf(TempoSurfaceSunken, TempoSurfaceDialog, Color(0xFF020617))
        )

        val DeepOcean = CanvasBackground(
            id = "deep_ocean",
            name = "Ocean",
            isGradient = true,
            colors = listOf(TempoSurfaceSunken, TempoSurfaceDialog, TempoBackground)
        )
        
        val SunsetBlur = CanvasBackground(
            id = "sunset_blur",
            name = "Dusk",
            isGradient = true,
            colors = listOf(Color(0xFF0D5C4A), TempoErrorDeep, TempoWarning)
        )
        
        val Aurora = CanvasBackground(
            id = "aurora",
            name = "Aurora",
            isGradient = true,
            colors = listOf(TempoSuccessDeep, TempoInfo, TempoPrimary)
        )
        
        val ElectricVoid = CanvasBackground(
            id = "electric_void",
            name = "Void",
            isGradient = true,
            colors = listOf(TempoBackground, Color(0xFF1D4ED8), TempoPrimaryMuted)
        )
        
        val NeoMint = CanvasBackground(
            id = "neo_mint",
            name = "Mint",
            isGradient = true,
            colors = listOf(Color(0xFF0F766E), Color(0xFF2DD4BF), Color(0xFF064E3B))
        )

        val GoldenHour = CanvasBackground(
            id = "golden_hour",
            name = "Gold",
            isGradient = true,
            colors = listOf(TempoWarning, Color(0xFFEA580C), TempoErrorDeep)
        )
        
        // Solid backgrounds
        val PureBlack = CanvasBackground(
            id = "pure_black",
            name = "Black",
            isGradient = false,
            solidColor = TempoBackground
        )
        
        val Charcoal = CanvasBackground(
            id = "charcoal",
            name = "Charcoal",
            isGradient = false,
            solidColor = Color(0xFF1F2937)
        )
        
        val OffWhite = CanvasBackground(
            id = "off_white",
            name = "Paper",
            isGradient = false,
            solidColor = Color(0xFFF8FAFC)
        )
        
        val DeepPurple = CanvasBackground(
            id = "deep_purple",
            name = "Velvet",
            isGradient = false,
            solidColor = Color(0xFF2E1065)
        )
        
        val gradients: List<CanvasBackground> by lazy {
            listOf(Holographic, MidnightGrain, DeepOcean, SunsetBlur, Aurora, ElectricVoid, NeoMint, GoldenHour)
        }
        
        val solids: List<CanvasBackground> by lazy {
            listOf(PureBlack, Charcoal, OffWhite, DeepPurple)
        }
        
        val all: List<CanvasBackground> by lazy {
            gradients + solids
        }
        
        fun getById(id: String): CanvasBackground = all.find { it.id == id } ?: DeepOcean
    }
}

/**
 * State for the ShareCanvas screen
 */
data class CanvasState(
    val canvasItems: List<CanvasCardItem> = emptyList(),
    val textItems: List<CanvasTextItem> = emptyList(),
    val selectedItemId: String? = null,
    val selectedItemType: SelectedItemType = SelectedItemType.None,
    val availableCards: List<SpotlightCardData> = emptyList(),
    val showCardPicker: Boolean = false,
    val showBackgroundPicker: Boolean = false,
    val showTextEditor: Boolean = false,
    val editingTextId: String? = null,
    val selectedBackground: CanvasBackground = CanvasBackground.DeepOcean
) {
    val selectedCard: CanvasCardItem?
        get() = if (selectedItemType == SelectedItemType.Card) {
            canvasItems.find { it.id == selectedItemId }
        } else null
    
    val selectedText: CanvasTextItem?
        get() = if (selectedItemType == SelectedItemType.Text) {
            textItems.find { it.id == selectedItemId }
        } else null
    
    val maxCardLimit: Int = 5
    val maxTextLimit: Int = 5
    val canAddMoreCards: Boolean
        get() = canvasItems.size < maxCardLimit
    val canAddMoreText: Boolean
        get() = textItems.size < maxTextLimit
    
    val maxZIndex: Int
        get() = maxOf(
            canvasItems.maxOfOrNull { it.zIndex } ?: 0,
            textItems.maxOfOrNull { it.zIndex } ?: 0
        )
}

enum class SelectedItemType {
    None, Card, Text
}
