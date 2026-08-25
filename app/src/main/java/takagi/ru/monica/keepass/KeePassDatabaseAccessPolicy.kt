package takagi.ru.monica.keepass

import android.content.Context

internal class KeePassDatabaseReadOnlyException(
    val databaseId: Long
) : IllegalStateException("KeePass database $databaseId is read-only")

internal interface KeePassDatabaseAccessPolicy {
    fun isReadOnly(databaseId: Long): Boolean

    fun setReadOnly(databaseId: Long, readOnly: Boolean)

    fun requireWritable(databaseId: Long) {
        if (isReadOnly(databaseId)) throw KeePassDatabaseReadOnlyException(databaseId)
    }
}

internal class InMemoryKeePassDatabaseAccessPolicy : KeePassDatabaseAccessPolicy {
    private val readOnlyDatabases = mutableSetOf<Long>()

    @Synchronized
    override fun isReadOnly(databaseId: Long): Boolean = databaseId in readOnlyDatabases

    @Synchronized
    override fun setReadOnly(databaseId: Long, readOnly: Boolean) {
        if (readOnly) readOnlyDatabases += databaseId else readOnlyDatabases -= databaseId
    }
}

internal class SharedPreferencesKeePassDatabaseAccessPolicy(
    context: Context
) : KeePassDatabaseAccessPolicy {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun isReadOnly(databaseId: Long): Boolean {
        return preferences.getBoolean(key(databaseId), false)
    }

    override fun setReadOnly(databaseId: Long, readOnly: Boolean) {
        val editor = preferences.edit()
        if (readOnly) editor.putBoolean(key(databaseId), true) else editor.remove(key(databaseId))
        editor.apply()
    }

    private fun key(databaseId: Long) = "database_${databaseId}_read_only"

    private companion object {
        const val PREFERENCES_NAME = "keepass_database_access_policy"
    }
}

internal class KeePassDatabaseRuntimeLock(
    private val loadedDatabaseInvalidator: (Long) -> Unit,
    private val nativeSessionInvalidator: (Long) -> Unit,
    private val projectionInvalidator: (Long) -> Unit,
    private val activeDatabaseClearer: (Long) -> Unit
) {
    fun lock(databaseId: Long) {
        loadedDatabaseInvalidator(databaseId)
        nativeSessionInvalidator(databaseId)
        projectionInvalidator(databaseId)
        activeDatabaseClearer(databaseId)
    }
}
