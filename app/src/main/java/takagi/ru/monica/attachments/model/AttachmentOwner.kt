package takagi.ru.monica.attachments.model

/**
 * Stable owner reference for an attachment.
 *
 * Password and secure-item ids come from different Room tables and may have the same numeric value,
 * so callers must always carry the owner kind together with the id.
 */
data class AttachmentOwner(
    val kind: Kind,
    val id: Long
) {
    init {
        require(id > 0L) { "Attachment owner id must be positive" }
    }

    enum class Kind {
        PASSWORD,
        SECURE_ITEM
    }

    companion object {
        fun password(id: Long): AttachmentOwner = AttachmentOwner(Kind.PASSWORD, id)

        fun secureItem(id: Long): AttachmentOwner = AttachmentOwner(Kind.SECURE_ITEM, id)
    }

    val passwordId: Long?
        get() = id.takeIf { kind == Kind.PASSWORD }

    val secureItemId: Long?
        get() = id.takeIf { kind == Kind.SECURE_ITEM }
}

fun Attachment.withOwner(owner: AttachmentOwner): Attachment = copy(
    parentPasswordId = owner.passwordId,
    parentSecureItemId = owner.secureItemId
)
