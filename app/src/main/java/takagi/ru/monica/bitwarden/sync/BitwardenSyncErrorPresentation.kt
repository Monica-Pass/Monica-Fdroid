package takagi.ru.monica.bitwarden.sync

import takagi.ru.monica.R
import takagi.ru.monica.utils.StringResolver

/** Keeps recovery actions intact when repository error messages are translated. */
internal fun classifyBitwardenSyncError(message: String, strings: StringResolver): SyncExecutionOutcome {
    fun matches(vararg resources: Int) = resources.any { message.contains(strings.get(it), ignoreCase = true) }
    val msg = message.lowercase()
    return when {
        matches(R.string.legacy_ui_vault_locked, R.string.bitwarden_message_key_unavailable) ||
            msg.contains("mdk not available") ||
            // Older persisted errors may still contain these labels.
            msg.contains("vault 未解锁") ||
            msg.contains("密钥不可用") -> {
            SyncExecutionOutcome.Blocked(SyncBlockReason.VAULT_LOCKED, message)
        }

        matches(
            R.string.legacy_ui_reauthenticate,
            R.string.bitwarden_message_token_refresh_failed,
            R.string.bitwarden_message_token_unavailable
        ) ||
            msg.contains("token 刷新失败") ||
            msg.contains("重新登录") ||
            msg.contains("401") ||
            msg.contains("403") ||
            msg.contains("unauthorized") ||
            msg.contains("forbidden") -> {
            SyncExecutionOutcome.Blocked(SyncBlockReason.AUTH_REQUIRED, message)
        }

        msg.contains("timeout") ||
            msg.contains("connect") ||
            msg.contains("network") ||
            msg.contains("ioexception") -> {
            SyncExecutionOutcome.RetryableError(message)
        }

        else -> SyncExecutionOutcome.FatalError(message)
    }
}
