package takagi.ru.monica.data.model

import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordPageContentType

/** Read-only index. Unknown or damaged blocks are preserved and never guessed from their text. */
internal fun buildPasswordContentTypeIndex(fields: List<CustomField>): Map<Long, Set<PasswordPageContentType>> =
    fields.groupBy { it.entryId }.mapValues { (_, entryFields) ->
        PasswordContentBlocks.read(entryFields.map(CustomFieldDraft::fromCustomField))
            .mapNotNull { stored ->
                when (stored.block?.kind) {
                    PasswordContentBlocks.Kind.API_KEY -> PasswordPageContentType.API_KEY
                    PasswordContentBlocks.Kind.API_TOKEN -> PasswordPageContentType.API_TOKEN
                    PasswordContentBlocks.Kind.GPG_KEY -> PasswordPageContentType.GPG_KEY
                    else -> null
                }
            }.toSet()
    }.filterValues { it.isNotEmpty() }

/** Selected types are a union: an entry containing both kinds appears once. */
internal fun matchesCredentialContentTypes(
    entry: PasswordEntry,
    selectedTypes: Set<PasswordPageContentType>,
    embeddedTypes: Set<PasswordPageContentType> = emptySet()
): Boolean = selectedTypes.any { type ->
    type in embeddedTypes || when (type) {
        PasswordPageContentType.API_KEY -> entry.isApiKeyEntry()
        PasswordPageContentType.API_TOKEN -> entry.loginType == "API_TOKEN"
        PasswordPageContentType.GPG_KEY -> entry.isGpgKeyEntry()
        else -> false
    }
}
