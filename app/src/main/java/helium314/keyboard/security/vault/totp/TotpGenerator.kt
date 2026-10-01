// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.totp

import android.net.Uri
import helium314.keyboard.latin.utils.LogCatcher
import java.nio.ByteBuffer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * Pure Kotlin RFC 6238 Time-Based One-Time Password (TOTP) generator.
 * Fully compatible with KeePass, Google Authenticator, Authy, and FreeOTP.
 *
 * Supports raw Base32 secret keys and standard otpauth://totp/ URIs.
 * Supports HMAC-SHA1, HMAC-SHA256, and HMAC-SHA512 with 6 or 8 digits.
 */
object TotpGenerator {
    private const val TAG = "TotpGenerator"

    enum class Algorithm(val hmacName: String) {
        SHA1("HmacSHA1"),
        SHA256("HmacSHA256"),
        SHA512("HmacSHA512")
    }

    data class TotpConfig(
        val secret: String,
        val digits: Int = 6,
        val periodSeconds: Int = 30,
        val algorithm: Algorithm = Algorithm.SHA1,
        val label: String? = null,
        val issuer: String? = null
    )

    data class TotpResult(
        val code: String,
        val remainingSeconds: Int,
        val progressFraction: Float,
        val periodSeconds: Int
    )

    init {
        LogCatcher.markComponentActive("TotpGenerator", "TOTP", "Ready")
    }

    /**
     * Parses a raw Base32 string or an otpauth://totp/... URI into a TotpConfig.
     */
    fun parseConfig(input: String): TotpConfig? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("otpauth://", ignoreCase = true)) {
            return parseUri(trimmed)
        }

        // Sanitize raw Base32 string
        val sanitized = sanitizeBase32(trimmed)
        if (!isValidBase32(sanitized)) {
            LogCatcher.log('W', TAG, "parseConfig: Invalid Base32 characters in raw secret")
            return null
        }
        return TotpConfig(secret = sanitized)
    }

    private fun parseUri(uriString: String): TotpConfig? {
        return try {
            val uri = Uri.parse(uriString)
            if (!uri.scheme.equals("otpauth", ignoreCase = true) || !uri.host.equals("totp", ignoreCase = true)) {
                LogCatcher.log('W', TAG, "parseUri: Unsupported OTP scheme or host: $uriString")
                return null
            }

            val secretRaw = uri.getQueryParameter("secret") ?: return null
            val secret = sanitizeBase32(secretRaw)
            if (!isValidBase32(secret)) return null

            val digits = uri.getQueryParameter("digits")?.toIntOrNull()?.let { if (it == 8) 8 else 6 } ?: 6
            val period = uri.getQueryParameter("period")?.toIntOrNull()?.coerceIn(10, 120) ?: 30
            val algoStr = uri.getQueryParameter("algorithm")?.uppercase(Locale.US) ?: "SHA1"
            val algorithm = when (algoStr) {
                "SHA256" -> Algorithm.SHA256
                "SHA512" -> Algorithm.SHA512
                else -> Algorithm.SHA1
            }
            val issuer = uri.getQueryParameter("issuer")
            val label = uri.path?.removePrefix("/")

            TotpConfig(
                secret = secret,
                digits = digits,
                periodSeconds = period,
                algorithm = algorithm,
                label = label,
                issuer = issuer
            )
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "parseUri error: ${t.message}", t)
            null
        }
    }

    /**
     * Computes the current TOTP result (code, remaining seconds, progress fraction).
     */
    fun generate(config: TotpConfig, timeMs: Long = System.currentTimeMillis()): TotpResult? {
        return try {
            val keyBytes = decodeBase32(config.secret)
            val timeStep = (timeMs / 1000L) / config.periodSeconds
            val remainingSec = (config.periodSeconds - ((timeMs / 1000L) % config.periodSeconds)).toInt()
            val progress = remainingSec.toFloat() / config.periodSeconds.toFloat()

            val counterBytes = ByteBuffer.allocate(8).putLong(timeStep).array()
            val mac = Mac.getInstance(config.algorithm.hmacName)
            mac.init(SecretKeySpec(keyBytes, config.algorithm.hmacName))
            val hash = mac.doFinal(counterBytes)

            // Dynamic truncation (RFC 4226)
            val offset = hash[hash.size - 1].toInt() and 0x0F
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)

            val modulus = 10.0.pow(config.digits).toInt()
            val otp = binary % modulus
            val formatSpec = "%0${config.digits}d"
            val code = String.format(Locale.US, formatSpec, otp)

            TotpResult(
                code = code,
                remainingSeconds = remainingSec,
                progressFraction = progress,
                periodSeconds = config.periodSeconds
            )
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "TOTP generation error: ${t.message}", t)
            null
        }
    }

    /**
     * Convenience helper to generate TOTP code directly from raw secret/URI string.
     */
    fun generateFromSecret(secretOrUri: String, timeMs: Long = System.currentTimeMillis()): TotpResult? {
        val config = parseConfig(secretOrUri) ?: return null
        return generate(config, timeMs)
    }

    fun getRemainingSeconds(timeMs: Long = System.currentTimeMillis(), periodSeconds: Int = 30): Int {
        val rem = (periodSeconds - ((timeMs / 1000L) % periodSeconds)).toInt()
        return if (rem == 0) periodSeconds else rem
    }

    fun getProgressFraction(timeMs: Long = System.currentTimeMillis(), periodSeconds: Int = 30): Float {
        return getRemainingSeconds(timeMs, periodSeconds).toFloat() / periodSeconds.toFloat()
    }

    // --- Base32 Decoding (RFC 4648) ---

    private fun sanitizeBase32(input: String): String {
        return input.uppercase(Locale.US)
            .replace(" ", "")
            .replace("-", "")
            .replace("=", "")
    }

    private fun isValidBase32(input: String): Boolean {
        if (input.isEmpty()) return false
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        for (c in input) {
            if (c !in base32Chars) return false
        }
        return true
    }

    private fun decodeBase32(input: String): ByteArray {
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleanInput = sanitizeBase32(input)
        val outLength = (cleanInput.length * 5) / 8
        val out = ByteArray(outLength)

        var buffer = 0
        var bitsLeft = 0
        var outIndex = 0

        for (c in cleanInput) {
            val valIndex = base32Chars.indexOf(c)
            if (valIndex < 0) continue

            buffer = (buffer shl 5) or (valIndex and 31)
            bitsLeft += 5

            if (bitsLeft >= 8) {
                out[outIndex++] = (buffer shr (bitsLeft - 8)).toByte()
                bitsLeft -= 8
            }
        }
        return out
    }
}
