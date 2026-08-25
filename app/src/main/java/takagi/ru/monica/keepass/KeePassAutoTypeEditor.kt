package takagi.ru.monica.keepass

import app.keemobile.kotpass.constants.AutoTypeObfuscation
import app.keemobile.kotpass.models.AutoTypeData
import java.util.Locale

internal data class KeePassAutoTypeRuleDraft(
    val id: Long,
    val window: String,
    val sequence: String,
)

internal data class KeePassAutoTypeDraft(
    val enabled: Boolean,
    val obfuscation: AutoTypeObfuscation,
    val defaultSequence: String,
    val rules: List<KeePassAutoTypeRuleDraft>,
) {
    fun toPatch(): KeePassAutoTypePatch = KeePassAutoTypePatch(
        enabled = enabled,
        obfuscation = obfuscation.name,
        defaultSequence = defaultSequence,
        items = rules.map { rule ->
            KeePassAutoTypeItemPatch(
                window = rule.window.trim(),
                keystrokeSequence = rule.sequence,
            )
        },
    )
}

internal enum class KeePassAutoTypeDraftError {
    WINDOW_REQUIRED,
    DUPLICATE_WINDOW,
}

internal object KeePassAutoTypeEditor {
    fun from(data: AutoTypeData?): KeePassAutoTypeDraft {
        val source = data ?: AutoTypeData(enabled = false)
        return KeePassAutoTypeDraft(
            enabled = source.enabled,
            obfuscation = source.obfuscation,
            defaultSequence = source.defaultSequence.orEmpty(),
            rules = source.items.mapIndexed { index, item ->
                KeePassAutoTypeRuleDraft(
                    id = index.toLong() + 1L,
                    window = item.window,
                    sequence = item.keystrokeSequence,
                )
            },
        )
    }

    fun validate(draft: KeePassAutoTypeDraft): KeePassAutoTypeDraftError? {
        if (draft.rules.any { it.window.trim().isBlank() }) {
            return KeePassAutoTypeDraftError.WINDOW_REQUIRED
        }
        val windows = draft.rules.map { it.window.trim().lowercase(Locale.ROOT) }
        if (windows.size != windows.distinct().size) {
            return KeePassAutoTypeDraftError.DUPLICATE_WINDOW
        }
        return null
    }

    fun newRule(existing: List<KeePassAutoTypeRuleDraft>): KeePassAutoTypeRuleDraft {
        val nextId = (existing.maxOfOrNull { it.id } ?: 0L) + 1L
        return KeePassAutoTypeRuleDraft(nextId, "", "{USERNAME}{TAB}{PASSWORD}{ENTER}")
    }
}
