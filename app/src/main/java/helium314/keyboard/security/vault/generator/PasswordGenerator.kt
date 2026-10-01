// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.generator

import java.security.SecureRandom
import kotlin.math.log2

/**
 * High-entropy cryptographic Password & Passphrase Generator.
 * Runs exclusively in Settings (zero keyboard RAM bloat).
 */
object PasswordGenerator {
    private val secureRandom = SecureRandom()

    private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{}|;:,.<>?"
    private const val LOOKALIKE = "1lI0Oo"

    private val PASSPHRASE_WORDS = listOf(
        "correct", "horse", "battery", "staple", "galaxy", "velvet", "thunder", "shadow",
        "crystal", "dragon", "falcon", "harbor", "island", "jungle", "meteor", "planet",
        "silver", "tiger", "voyage", "winter", "wizard", "zenith", "anchor", "beacon",
        "canyon", "desert", "echo", "forest", "glacier", "horizon", "matrix", "nebula",
        "orbit", "phoenix", "quarry", "radar", "summit", "timber", "vortex", "wildfire"
    )

    data class GeneratorConfig(
        val length: Int = 16,
        val includeUppercase: Boolean = true,
        val includeLowercase: Boolean = true,
        val includeDigits: Boolean = true,
        val includeSymbols: Boolean = true,
        val excludeLookalike: Boolean = false,
        val isPassphraseMode: Boolean = false,
        val passphraseWordCount: Int = 4,
        val passphraseSeparator: String = "-"
    )

    data class StrengthAssessment(
        val entropyBits: Double,
        val rating: Rating,
        val progressFraction: Float
    ) {
        enum class Rating(val label: String) {
            WEAK("Weak"),
            FAIR("Fair"),
            STRONG("Strong"),
            UNBREAKABLE("Unbreakable")
        }
    }

    /**
     * Generates a secure random password or passphrase according to config.
     */
    fun generate(config: GeneratorConfig): String {
        if (config.isPassphraseMode) {
            return generatePassphrase(config.passphraseWordCount, config.passphraseSeparator)
        }

        var pool = buildString {
            if (config.includeUppercase) append(UPPERCASE)
            if (config.includeLowercase) append(LOWERCASE)
            if (config.includeDigits) append(DIGITS)
            if (config.includeSymbols) append(SYMBOLS)
        }

        if (config.excludeLookalike) {
            pool = pool.filter { it !in LOOKALIKE }
        }

        if (pool.isEmpty()) {
            pool = LOWERCASE + DIGITS
        }

        val passwordChars = CharArray(config.length.coerceIn(8, 64))
        for (i in passwordChars.indices) {
            passwordChars[i] = pool[secureRandom.nextInt(pool.length)]
        }
        return String(passwordChars)
    }

    private fun generatePassphrase(wordCount: Int, separator: String): String {
        val count = wordCount.coerceIn(3, 8)
        val selectedWords = mutableListOf<String>()
        for (i in 0 until count) {
            selectedWords.add(PASSPHRASE_WORDS[secureRandom.nextInt(PASSPHRASE_WORDS.size)])
        }
        return selectedWords.joinToString(separator)
    }

    /**
     * Estimates cryptographic entropy in bits and assigns a NIST strength rating.
     */
    fun estimateStrength(password: String): StrengthAssessment {
        if (password.isEmpty()) {
            return StrengthAssessment(0.0, StrengthAssessment.Rating.WEAK, 0.0f)
        }

        var poolSize = 0
        if (password.any { it in UPPERCASE }) poolSize += 26
        if (password.any { it in LOWERCASE }) poolSize += 26
        if (password.any { it in DIGITS }) poolSize += 10
        if (password.any { it in SYMBOLS }) poolSize += SYMBOLS.length

        if (poolSize == 0) poolSize = 26

        val entropy = password.length * log2(poolSize.toDouble())
        val rating = when {
            entropy < 36.0 -> StrengthAssessment.Rating.WEAK
            entropy < 60.0 -> StrengthAssessment.Rating.FAIR
            entropy < 85.0 -> StrengthAssessment.Rating.STRONG
            else -> StrengthAssessment.Rating.UNBREAKABLE
        }

        val progress = (entropy / 100.0).coerceIn(0.1, 1.0).toFloat()

        return StrengthAssessment(
            entropyBits = entropy,
            rating = rating,
            progressFraction = progress
        )
    }
}
