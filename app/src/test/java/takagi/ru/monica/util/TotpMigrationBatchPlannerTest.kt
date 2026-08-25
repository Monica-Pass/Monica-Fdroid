package takagi.ru.monica.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData

class TotpMigrationBatchPlannerTest {

    @Test
    fun `invalid item rejects the complete selection before saving`() {
        val plan = planTotpMigrationBatch(
            listOf(
                item(label = "Valid", secret = "JBSWY3DPEHPK3PXP"),
                item(label = "Invalid", secret = "", algorithm = "MD5")
            )
        )

        assertEquals(TotpMigrationBatchPlan.Rejected(invalidItemCount = 1), plan)
    }

    @Test
    fun `duplicate entries collapse predictably while preserving first occurrence order`() {
        val first = item(label = "First", secret = "JBSWY3DPEHPK3PXP")
        val duplicate = item(label = "Duplicate", secret = "JBSWY3DPEHPK3PXP")
        val second = item(label = "Second", secret = "GEZDGNBVGY3TQOJQ", type = OtpType.HOTP)

        val plan = planTotpMigrationBatch(listOf(first, duplicate, second))

        assertTrue(plan is TotpMigrationBatchPlan.Ready)
        plan as TotpMigrationBatchPlan.Ready
        assertEquals(listOf(first, second), plan.items)
        assertEquals(1, plan.duplicateCount)
    }

    private fun item(
        label: String,
        secret: String,
        algorithm: String = "SHA1",
        type: OtpType = OtpType.TOTP
    ): TotpParseResult {
        return TotpParseResult(
            totpData = TotpData(
                secret = secret,
                issuer = label,
                accountName = "account@example.com",
                algorithm = algorithm,
                otpType = type,
                counter = if (type == OtpType.HOTP) 1L else 0L
            ),
            label = label,
            accountName = "account@example.com"
        )
    }
}
