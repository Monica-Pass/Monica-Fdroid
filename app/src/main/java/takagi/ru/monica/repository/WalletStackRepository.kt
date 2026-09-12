package takagi.ru.monica.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.data.WalletStack

private val Context.walletStackDataStore by preferencesDataStore(name = "card_wallet_stacks")

class WalletStackRepository internal constructor(private val store: DataStore<Preferences>) {
    val stacks: Flow<List<WalletStack>> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { decode(it[STACKS_KEY]) }
        .distinctUntilChanged()

    suspend fun create(memberIds: List<Long>): String {
        val members = memberIds.filter { it > 0 }.distinct()
        require(members.size >= 2)
        val stack = WalletStack(UUID.randomUUID().toString(), members)
        update { existing ->
            existing.mapNotNull { it.withMembers(it.memberIds - members.toSet()) } + stack
        }
        return stack.id
    }

    suspend fun addMembers(stackId: String, memberIds: List<Long>) {
        val incoming = memberIds.filter { it > 0 }.toSet()
        if (incoming.isEmpty()) return
        update { existing ->
            if (existing.none { it.id == stackId }) return@update existing
            existing.mapNotNull { stack ->
                if (stack.id == stackId) stack.withMembers(stack.memberIds + incoming)
                else stack.withMembers(stack.memberIds - incoming)
            }
        }
    }

    suspend fun updateStack(stackId: String, orderedMemberIds: List<Long>) {
        update { existing ->
            existing.mapNotNull { stack ->
                if (stack.id != stackId) stack
                else stack.withMembers(orderedMemberIds.filter { it in stack.memberIds })
            }
        }
    }

    suspend fun setCover(stackId: String, memberId: Long) {
        update { existing ->
            existing.map { stack ->
                if (stack.id == stackId && memberId in stack.memberIds) stack.copy(coverId = memberId)
                else stack
            }
        }
    }

    suspend fun dissolve(stackId: String) {
        update { existing -> existing.filterNot { it.id == stackId } }
    }

    private suspend fun update(transform: (List<WalletStack>) -> List<WalletStack>) {
        store.edit { preferences ->
            val before = decode(preferences[STACKS_KEY])
            val after = transform(before)
            if (before != after) preferences[STACKS_KEY] = json.encodeToString(after)
        }
    }

    companion object {
        private val STACKS_KEY = stringPreferencesKey("stacks_v1")
        private val json = Json { ignoreUnknownKeys = true }

        fun get(context: Context): WalletStackRepository =
            WalletStackRepository(context.applicationContext.walletStackDataStore)

        private fun decode(encoded: String?): List<WalletStack> {
            if (encoded.isNullOrBlank()) return emptyList()
            val decoded = try {
                json.decodeFromString<List<WalletStack>>(encoded)
            } catch (_: IllegalArgumentException) {
                return emptyList()
            }
            val claimedIds = mutableSetOf<Long>()
            val groupIds = mutableSetOf<String>()
            return decoded.mapNotNull { stack ->
                if (stack.id.isBlank() || !groupIds.add(stack.id)) return@mapNotNull null
                stack.withMembers(stack.memberIds.filterNot { it in claimedIds })
                    ?.also { claimedIds.addAll(it.memberIds) }
            }
        }
    }
}
