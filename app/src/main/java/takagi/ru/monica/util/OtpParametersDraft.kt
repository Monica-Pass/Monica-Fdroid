package takagi.ru.monica.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData

/** Text drafts retain unfinished input when switching credentials or recreating the editor. */
@Serializable
data class OtpParametersDraft(
    val period: String = "30",
    val digits: String = "6",
    val algorithm: String = "SHA1",
    val counter: String = "0",
    val pin: String = "",
) {
    fun selectType(type: OtpType): OtpParametersDraft = when (type) {
        OtpType.STEAM -> copy(period = "30", digits = "5", algorithm = "SHA1")
        OtpType.MOTP -> copy(period = "10", digits = "6", algorithm = "SHA1")
        else -> if (digits == "5") copy(digits = "6") else this
    }

    fun isValid(type: OtpType): Boolean = when (type) {
        OtpType.STEAM -> true
        OtpType.MOTP -> pin.length == 4 && pin.all { it in '0'..'9' }
        else -> (type == OtpType.HOTP || period.toIntOrNull()?.let { it > 0 } == true) &&
            digits.toIntOrNull() in 4..10 &&
            algorithm in listOf("SHA1", "SHA256", "SHA512") &&
            (type != OtpType.HOTP || counter.toLongOrNull()?.let { it >= 0 } == true)
    }

    fun toData(secret: String, type: OtpType, issuer: String, accountName: String): TotpData =
        TotpDataResolver.normalizeTotpData(TotpData(
            secret = secret,
            issuer = issuer,
            accountName = accountName,
            otpType = type,
            period = if (type == OtpType.MOTP) 10 else period.toIntOrNull() ?: 30,
            digits = if (type == OtpType.MOTP) 6 else digits.toIntOrNull() ?: 6,
            algorithm = algorithm,
            counter = counter.toLongOrNull() ?: 0L,
            pin = pin,
        ))

    fun encode(): String = Json.encodeToString(this)

    companion object {
        fun decode(value: String): OtpParametersDraft =
            runCatching { Json.decodeFromString<OtpParametersDraft>(value) }.getOrDefault(OtpParametersDraft())

        fun from(data: TotpData): OtpParametersDraft = OtpParametersDraft(
            period = data.period.toString(), digits = data.digits.toString(), algorithm = data.algorithm,
            counter = data.counter.toString(), pin = data.pin,
        )
    }
}
