package takagi.ru.monica.utils

import takagi.ru.monica.testing.readSourceText

import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavBillingAddressBackupGuardTest {

    @Test
    fun webDavBackupAndRestoreKeepBillingAddressesInCardWalletBackup() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt")

        assertTrue(source.contains("item.itemType != ItemType.BILLING_ADDRESS"))
        assertTrue(source.contains("ItemType.BILLING_ADDRESS -> preferences.includeBankCards || preferences.includeDocuments"))
        assertTrue(source.contains("ItemType.BILLING_ADDRESS -> File(foldersRootDir, \"${'$'}folderKey/billing_addresses\")"))
        assertTrue(source.contains("ItemType.BILLING_ADDRESS -> \"billing_address\""))
        assertTrue(source.contains("restoreCardWalletItemFromJson(tempFile, ItemType.BILLING_ADDRESS)"))
        assertTrue(projectFile("app/src/main/java/takagi/ru/monica/utils/LocalBackupReplacement.kt").contains("database.secureItemDao().deleteAllLocalItemsByType(it)"))
        assertTrue(source.contains("billingAddresses = cardWalletItems.count { it.itemType == ItemType.BILLING_ADDRESS }"))
        assertTrue(source.contains("billingAddresses = if (restoredBillingAddressCount > 0) restoredBillingAddressCount else billingAddressItems"))
    }

    @Test
    fun webDavBackupAndRestoreKeepPaymentAccountsInCardWalletBackup() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt")

        assertTrue(source.contains("item.itemType != ItemType.PAYMENT_ACCOUNT"))
        assertTrue(source.contains("ItemType.PAYMENT_ACCOUNT -> preferences.includeBankCards || preferences.includeDocuments"))
        assertTrue(source.contains("ItemType.PAYMENT_ACCOUNT -> File(foldersRootDir, \"${'$'}folderKey/payment_accounts\")"))
        assertTrue(source.contains("ItemType.PAYMENT_ACCOUNT -> \"payment_account\""))
        assertTrue(source.contains("restoreCardWalletItemFromJson(tempFile, ItemType.PAYMENT_ACCOUNT)"))
        assertTrue(projectFile("app/src/main/java/takagi/ru/monica/utils/LocalBackupReplacement.kt").contains("database.secureItemDao().deleteAllLocalItemsByType(it)"))
        assertTrue(source.contains("paymentAccounts = cardWalletItems.count { it.itemType == ItemType.PAYMENT_ACCOUNT }"))
        assertTrue(source.contains("paymentAccounts = if (restoredPaymentAccountCount > 0) restoredPaymentAccountCount else paymentAccountItems"))
    }

    @Test
    fun backupReportsExposeBillingAddressCounts() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/data/BackupReport.kt")

        assertTrue(source.contains("val billingAddresses: Int = 0"))
        assertTrue(source.contains("val paymentAccounts: Int = 0"))
        assertTrue(source.contains("R.string.legacy_ui_report_count_billingaddresses, successItems.billingAddresses, totalItems.billingAddresses"))
        assertTrue(source.contains("R.string.legacy_ui_report_count_billingaddresses, restoredSuccessfully.billingAddresses, backupContains.billingAddresses"))
        assertTrue(source.contains("R.string.legacy_ui_report_count_paymentaccounts, successItems.paymentAccounts, totalItems.paymentAccounts"))
        assertTrue(source.contains("R.string.legacy_ui_report_count_paymentaccounts, restoredSuccessfully.paymentAccounts, backupContains.paymentAccounts"))
        assertTrue(source.replace(Regex("\\s+"), " ").contains("passwords + notes + totp + bankCards + documents + billingAddresses + paymentAccounts"))
    }

    private fun projectFile(relativePath: String): String {
        val start = Paths.get("").toAbsolutePath()
        var cursor = start
        while (cursor.parent != null) {
            val candidate = cursor.resolve(relativePath).toFile()
            if (candidate.exists()) {
                return candidate.readSourceText()
            }
            cursor = cursor.parent
        }
        error("Project file not found from $start: $relativePath")
    }
}
