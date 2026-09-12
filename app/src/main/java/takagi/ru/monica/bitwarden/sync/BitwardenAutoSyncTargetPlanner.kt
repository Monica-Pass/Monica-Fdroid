package takagi.ru.monica.bitwarden.sync

internal object BitwardenAutoSyncTargetPlanner {

    fun allViewTargets(
        unlockedVaultIds: List<Long>,
        activeVaultId: Long?
    ): List<Long> {
        val unlocked = unlockedVaultIds.distinct()
        val active = activeVaultId?.takeIf(unlocked::contains) ?: return unlocked
        return buildList(unlocked.size) {
            add(active)
            unlocked.forEach { vaultId ->
                if (vaultId != active) add(vaultId)
            }
        }
    }
}
