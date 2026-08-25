package takagi.ru.monica.ui.screens

import java.util.Locale
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.keepass.KeePassTotpCodec

/**
 * The five fields that make a native KDBX entry immediately useful in Monica.
 * All other fields stay first-class custom fields and are never discarded.
 */
internal enum class NativeEntryStandardSlot {
    TITLE,
    USERNAME,
    PASSWORD,
    URL,
    NOTES,
}

internal data class NativeEntryEditorField(
    val id: Long,
    val name: String,
    val value: String,
    val protected: Boolean,
    val order: Int,
    val slot: NativeEntryStandardSlot? = null,
)

internal fun NativeEntryEditorField.toCustomFieldDraft(): CustomFieldDraft = CustomFieldDraft(
    id = id,
    title = name,
    value = value,
    isProtected = protected,
)

internal data class NativeEntryEditorDraft(
    val fields: List<NativeEntryEditorField>,
) {
    val customFields: List<NativeEntryEditorField>
        get() = fields.filter { it.slot == null }

    fun standard(slot: NativeEntryStandardSlot): NativeEntryEditorField? =
        fields.firstOrNull { it.slot == slot }

    fun toFieldChanges(): List<KeePassFieldChange> = fields
        .sortedBy { it.order }
        .map { field ->
            KeePassFieldChange(
                name = field.name.trim(),
                value = field.value,
                protected = field.protected,
            )
        }
}

internal enum class NativeEntryDraftError {
    TITLE_REQUIRED,
    FIELD_NAME_REQUIRED,
    DUPLICATE_FIELD_NAME,
}

internal fun newNativeEntryEditorDraft(): NativeEntryEditorDraft = NativeEntryEditorDraft(
    fields = listOf(
        NativeEntryEditorField(1L, "Title", "", protected = false, order = 0, slot = NativeEntryStandardSlot.TITLE),
        NativeEntryEditorField(2L, "UserName", "", protected = false, order = 1, slot = NativeEntryStandardSlot.USERNAME),
        NativeEntryEditorField(3L, "Password", "", protected = true, order = 2, slot = NativeEntryStandardSlot.PASSWORD),
        NativeEntryEditorField(4L, "URL", "", protected = false, order = 3, slot = NativeEntryStandardSlot.URL),
        NativeEntryEditorField(5L, "Notes", "", protected = false, order = 4, slot = NativeEntryStandardSlot.NOTES),
    ),
)

internal fun buildNativeEntryEditorDraft(
    fields: List<KeePassFieldChange>,
): NativeEntryEditorDraft {
    val usedSlots = mutableSetOf<NativeEntryStandardSlot>()
    return NativeEntryEditorDraft(
        fields = fields.filterNot { field -> isNativeTotpFieldName(field.name) }.mapIndexed { index, field ->
            val slot = nativeEntryStandardSlot(field.name)
                ?.takeIf { usedSlots.add(it) }
            NativeEntryEditorField(
                id = index.toLong() + 1L,
                name = field.name,
                value = field.value,
                protected = field.protected,
                order = index,
                slot = slot,
            )
        },
    )
}

internal fun isNativeTotpFieldName(name: String): Boolean {
    return name.trim().lowercase(Locale.ROOT) in NATIVE_TOTP_FIELD_NAMES
}

internal fun mergeNativeTotpFields(
    fields: List<KeePassFieldChange>,
    data: TotpData?,
    title: String,
): List<KeePassFieldChange> {
    val retained = fields.filterNot { field -> isNativeTotpFieldName(field.name) }
    if (data == null) return retained
    return retained + KeePassTotpCodec.toKeePassFields(data, title).map { (name, value) ->
        KeePassFieldChange(
            name = name,
            value = value,
            protected = name.equals(KeePassTotpCodec.FIELD_OTP, ignoreCase = true) ||
                name.equals(KeePassTotpCodec.FIELD_TOTP_SEED, ignoreCase = true),
        )
    }
}

internal fun parseNativeTotpFields(
    fields: List<KeePassFieldChange>,
): TotpData? {
    fun value(vararg names: String): String = fields.firstOrNull { field ->
        names.any { name -> field.name.equals(name, ignoreCase = true) }
    }?.value.orEmpty()

    return KeePassTotpCodec.parse(
        KeePassTotpCodec.Fields(
            otp = value("otp"),
            seed = value("TOTP Seed", "TOTPSeed"),
            settings = value("TOTP Settings", "TOTPSettings"),
            period = value("TOTP Period", "TOTPPeriod"),
            digits = value("TOTP Digits", "TOTPDigits"),
            algorithm = value("TOTP Algorithm", "TOTPAlgorithm"),
            counter = value("HOTP Counter", "HOTPCounter"),
            type = value("OTP Type", "OTPType", "TOTP Type", "TOTPType"),
            issuer = value("Title", "Name"),
            accountName = value("UserName", "Login", "User"),
            link = value("URL", "URI", "Website"),
        ),
    )
}

internal fun newNativeCustomField(
    existing: List<NativeEntryEditorField>,
    name: String = "",
): NativeEntryEditorField {
    val nextId = (existing.minOfOrNull { it.id } ?: 0L) - 1L
    val nextOrder = (existing.maxOfOrNull { it.order } ?: -1) + 1
    return NativeEntryEditorField(
        id = nextId,
        name = name,
        value = "",
        protected = false,
        order = nextOrder,
        slot = null,
    )
}

internal fun ensureNativeEntryEditorStandardFields(
    draft: NativeEntryEditorDraft,
): NativeEntryEditorDraft {
    val result = draft.fields.toMutableList()
    var nextOrder = (result.maxOfOrNull { it.order } ?: -1) + 1
    var nextId = (result.maxOfOrNull { it.id } ?: 0L) + 1L
    NativeEntryStandardSlot.entries.forEach { slot ->
        if (result.none { it.slot == slot }) {
            val (name, protected) = when (slot) {
                NativeEntryStandardSlot.TITLE -> "Title" to false
                NativeEntryStandardSlot.USERNAME -> "UserName" to false
                NativeEntryStandardSlot.PASSWORD -> "Password" to true
                NativeEntryStandardSlot.URL -> "URL" to false
                NativeEntryStandardSlot.NOTES -> "Notes" to false
            }
            result += NativeEntryEditorField(
                id = nextId++,
                name = name,
                value = "",
                protected = protected,
                order = nextOrder++,
                slot = slot,
            )
        }
    }
    return NativeEntryEditorDraft(result)
}

internal fun validateNativeEntryEditorDraft(
    draft: NativeEntryEditorDraft,
): NativeEntryDraftError? {
    val title = draft.standard(NativeEntryStandardSlot.TITLE)?.value.orEmpty()
    if (title.isBlank()) return NativeEntryDraftError.TITLE_REQUIRED
    if (draft.fields.any { it.name.trim().isBlank() }) {
        return NativeEntryDraftError.FIELD_NAME_REQUIRED
    }
    val normalizedNames = draft.fields.map { it.name.trim().lowercase(Locale.ROOT) }
    if (normalizedNames.size != normalizedNames.distinct().size) {
        return NativeEntryDraftError.DUPLICATE_FIELD_NAME
    }
    return null
}

internal fun nativeEntryStandardSlot(name: String): NativeEntryStandardSlot? {
    return when (name.trim().lowercase(Locale.ROOT)) {
        "title", "name" -> NativeEntryStandardSlot.TITLE
        "username", "user", "login" -> NativeEntryStandardSlot.USERNAME
        "password", "pass", "pwd", "密码", "口令" -> NativeEntryStandardSlot.PASSWORD
        "url", "website", "uri" -> NativeEntryStandardSlot.URL
        "notes", "note", "comment" -> NativeEntryStandardSlot.NOTES
        else -> null
    }
}

private val NATIVE_TOTP_FIELD_NAMES = setOf(
    KeePassTotpCodec.FIELD_OTP,
    KeePassTotpCodec.FIELD_TOTP_SEED,
    KeePassTotpCodec.FIELD_TOTP_SETTINGS,
    KeePassTotpCodec.FIELD_TOTP_PERIOD,
    KeePassTotpCodec.FIELD_TOTP_DIGITS,
    KeePassTotpCodec.FIELD_TOTP_ALGORITHM,
    KeePassTotpCodec.FIELD_OTP_TYPE,
    KeePassTotpCodec.FIELD_HOTP_COUNTER,
    "TOTPSeed",
    "TOTPSettings",
    "TOTPPeriod",
    "TOTPDigits",
    "TOTPAlgorithm",
    "OTPType",
    "TOTP Type",
    "TOTPType",
    "HOTPCounter",
).mapTo(linkedSetOf()) { it.lowercase(Locale.ROOT) }
