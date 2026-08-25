package takagi.ru.monica.repository

internal suspend fun <T> commitRoomThenMirror(
    roomCommit: suspend () -> T,
    mirrorCommit: suspend (T) -> Unit,
    rollbackRoom: suspend (T) -> Unit,
    rollbackMirror: suspend (T) -> Unit
): T {
    val value = roomCommit()
    try {
        mirrorCommit(value)
        return value
    } catch (error: Throwable) {
        rollbackWithoutMasking(error) { rollbackMirror(value) }
        rollbackWithoutMasking(error) { rollbackRoom(value) }
        throw error
    }
}

internal suspend fun <T> commitMirrorThenRoom(
    mirrorCommit: suspend () -> Unit,
    roomCommit: suspend () -> T,
    rollbackMirror: suspend () -> Unit
): T {
    return try {
        mirrorCommit()
        roomCommit()
    } catch (error: Throwable) {
        rollbackWithoutMasking(error, rollbackMirror)
        throw error
    }
}

private suspend fun rollbackWithoutMasking(
    primary: Throwable,
    rollback: suspend () -> Unit
) {
    try {
        rollback()
    } catch (rollbackError: Throwable) {
        if (rollbackError !== primary) primary.addSuppressed(rollbackError)
    }
}
