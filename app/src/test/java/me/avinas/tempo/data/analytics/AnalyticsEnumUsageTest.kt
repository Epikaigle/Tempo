package me.avinas.tempo.data.analytics

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails the build when a schema enum value has no production call site.
 *
 * This exists because the schema accumulated values nobody could ever send. `TrackingGapReason`
 * carried five reasons while the only call site passed one, `ImportPhase.STARTED` was never
 * passed at all, `GrantVia.SETTINGS` was declared while `via` was hardcoded to `ONBOARDING`,
 * and `AccessResult.DENIED` — the single most useful thing that event could report — was
 * unreachable. Each of those looked like a working dimension in the published list and read as
 * a constant in the data, and review discipline had not caught any of them.
 *
 * A reference check rather than a runtime one, because the question is whether a value *can* be
 * sent, and only the source knows that.
 */
class AnalyticsEnumUsageTest {

    private val enumFile = "data/analytics/AnalyticsEvent.kt"

    /** Enums whose values are passed as arguments, so an unreferenced value cannot be sent. */
    private val enumsUnderTest = listOf(
        "TempoFeature",
        "ImportProvider",
        "ImportPhase",
        "BackupTarget",
        "FailureClass",
        "AccessResult",
        "ExemptionResult",
        "RevivedBy",
        "RecoveryAction",
        "TrackingGapSeverity",
        "OnboardingStepName",
        "OnboardingAction",
    )

    @Test
    fun `every enum value has a production call site`() {
        val sources = mainSourceText()
        assertTrue(
            "could not find production sources to scan; the test would pass vacuously",
            sources.isNotEmpty(),
        )

        val dead = mutableListOf<String>()
        enumsUnderTest.forEach { enumName ->
            valuesOf(enumName).forEach { value ->
                val needle = "$enumName.$value"
                if (sources.none { it.contains(needle) }) {
                    dead += needle
                }
            }
        }

        assertTrue(
            "these enum values are declared but nothing can ever send them, so they read as " +
                "constants in the data while looking like working dimensions in the published " +
                "list — delete them, or wire them up:\n  " + dead.joinToString("\n  "),
            dead.isEmpty(),
        )
    }

    /** Reads the enum's declared values out of the schema file. */
    private fun valuesOf(enumName: String): List<String> {
        val text = schemaFile().readText()
        val block = Regex("enum class $enumName\\s*\\{([^}]*)}").find(text)
            ?: error("enum '$enumName' not found in $enumFile")
        // Values may be comma- or newline-separated, and carry no bodies.
        return block.groupValues[1]
            .split(',', '\n')
            .map { it.substringBefore("//").trim() }
            .filter { it.matches(Regex("[A-Z][A-Z0-9_]*")) }
    }

    private fun schemaFile(): File {
        val candidates = listOf(
            File("src/main/java/me/avinas/tempo/$enumFile"),
            File("app/src/main/java/me/avinas/tempo/$enumFile"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("schema file not found; looked in ${candidates.joinToString { it.path }}")
    }

    /**
     * Every production source.
     *
     * The schema file is included rather than skipped: a value produced by a mapper declared
     * there — `TrackingGapSeverity`, built by `AnalyticsBucket.gap` — does have a production
     * call site, and excluding the file would flag all seven of its values as dead. Matching is
     * on the qualified `Enum.VALUE` form, so the bare declarations in the enum body do not
     * count as references to themselves.
     */
    private fun mainSourceText(): List<String> {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
            .filter { it.isDirectory }
        return roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .map { it.readText() }
    }
}
