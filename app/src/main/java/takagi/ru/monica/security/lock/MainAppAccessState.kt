package takagi.ru.monica.security.lock

/**
 * Main application startup authentication state.
 *
 * Scope:
 * - Used only by main-process startup and foreground restoration.
 * - Secondary entry points apply the same developer bypass through DeveloperVerificationPolicy.
 */
data class MainAppAccessState(
    val isFirstTime: Boolean,
    val bypassEnabled: Boolean,
    val canRestoreSession: Boolean,
    val reason: String
) {
    val canEnterMainApp: Boolean
        get() = bypassEnabled || canRestoreSession
}
