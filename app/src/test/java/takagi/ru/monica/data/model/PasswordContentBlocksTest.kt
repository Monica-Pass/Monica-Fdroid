package takagi.ru.monica.data.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.CustomFieldDraft

class PasswordContentBlocksTest {
    @Test fun allKindsAndRepeatedKeysSurviveChunkedUnicodeRoundTrip() {
        val blocks = (PasswordContentBlocks.Kind.entries + PasswordContentBlocks.Kind.API_KEY).map { kind ->
            PasswordContentBlocks.create(kind).edited("示例 $kind", PasswordContentBlocks.editableKeys(kind).associateWith {
                " 第一行\n秘密🗝️-$it\n".repeat(350)
            })
        }
        val fields = blocks.fold(emptyList<CustomFieldDraft>()) { fields, block -> PasswordContentBlocks.put(fields, block) }
        assertTrue(fields.all { it.value.length < 2000 && it.isProtected })
        val recovered = PasswordContentBlocks.read(fields.reversed()).map { it.block!! }
        assertEquals(blocks.associate { it.id to it.raw }, recovered.associate { it.id to it.raw })
        assertEquals(2, recovered.count { it.kind == PasswordContentBlocks.Kind.API_KEY })
    }
    @Test fun editingAndDeletionPreserveUnrelatedAndFutureFields() {
        val first = PasswordContentBlocks.create(PasswordContentBlocks.Kind.API_TOKEN)
        val future = Json.parseToJsonElement("""{"counter":9007199254740993,"null":null,"array":[false,0,""]}""")
        val extended = first.copy(raw = JsonObject(first.raw + ("future" to future)))
        val other = PasswordContentBlocks.create(PasswordContentBlocks.Kind.API_KEY)
        val opaque = CustomFieldDraft(title = "future", value = "keep")
        val fields = PasswordContentBlocks.put(PasswordContentBlocks.put(listOf(opaque), extended), other)
        val updated = PasswordContentBlocks.put(fields, extended.edited("Changed", mapOf("token" to "")))
        assertEquals(future, PasswordContentBlocks.read(updated).first { it.block?.id == first.id }.block!!.raw["future"])
        val removed = PasswordContentBlocks.remove(updated, PasswordContentBlocks.token(first.id))
        assertEquals(listOf(other.id), PasswordContentBlocks.read(removed).map { it.block!!.id })
        assertTrue(opaque in removed)
    }
    @Test fun missingDuplicateAndCorruptChunksAreReadOnlyAndCannotBeOverwritten() {
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.SSH_KEY).edited("Key", mapOf("privateKeyOpenSsh" to "x".repeat(6000)))
        val valid = PasswordContentBlocks.put(emptyList(), block)
        listOf(valid.dropLast(1), valid + valid.last(), valid.mapIndexed { i, field -> if (i == 1) field.copy(value = "bad") else field }).forEach { broken ->
            assertNull(PasswordContentBlocks.read(broken).single().block)
            assertThrows(IllegalArgumentException::class.java) { PasswordContentBlocks.put(broken, block) }
            assertThrows(IllegalArgumentException::class.java) { PasswordContentBlocks.remove(broken, PasswordContentBlocks.token(block.id)) }
        }
    }
    @Test fun orderInterleavesRepeatedBlocksWithExistingSectionsWithoutLosingUnknownOrder() {
        val one = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE)
        val two = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE)
        val fields = PasswordContentBlocks.put(PasswordContentBlocks.put(listOf(CustomFieldDraft(title = EntryContentFields.ORDER, value = "FUTURE,NOTES")), one), two)
        val order = listOf(PasswordContentBlocks.token(two.id), "NOTES", PasswordContentBlocks.token(one.id))
        val saved = EntryContentFields.withOrder(fields, order)
        assertEquals(order + "FUTURE", EntryContentFields.order(saved))
        assertEquals(listOf(order[0], "NOTES", "FUTURE"), EntryContentFields.order(PasswordContentBlocks.remove(saved, order[2])))
    }

    @Test fun futureVersionsKindsAndFieldShapesArePreservedReadOnly() {
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.API_TOKEN)
        val variants = listOf(
            JsonObject(block.raw + ("version" to JsonPrimitive(2))),
            JsonObject(block.raw + ("kind" to JsonPrimitive("FUTURE_KIND"))),
            JsonObject(block.raw + ("data" to buildJsonObject { put("token", 123) })),
        )
        variants.forEach { raw ->
            val bytes = raw.toString().toByteArray()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val header = "${PasswordContentBlocks.PREFIX}${block.id}"
            val fields = listOf(
                CustomFieldDraft(title = header, value = """{"version":1,"encoding":"base64","parts":1,"sha256":"$digest"}""", isProtected = true),
                CustomFieldDraft(title = "$header.0000", value = java.util.Base64.getEncoder().encodeToString(bytes), isProtected = true),
            )
            assertNull(PasswordContentBlocks.read(fields).single().block)
            assertThrows(IllegalArgumentException::class.java) { PasswordContentBlocks.put(fields, block) }
            assertThrows(IllegalArgumentException::class.java) { PasswordContentBlocks.remove(fields, PasswordContentBlocks.token(block.id)) }
        }
    }

    @Test fun oversizeEditIsRejectedWithoutChangingExistingData() {
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.GPG_KEY).edited("Existing", mapOf("privateKey" to "synthetic"))
        val fields = PasswordContentBlocks.put(emptyList(), block)
        val original = fields.toList()
        assertThrows(IllegalArgumentException::class.java) {
            PasswordContentBlocks.put(fields, block.edited("Too large", mapOf("privateKey" to "密".repeat(100000))))
        }
        assertEquals(original, fields)
        assertEquals("synthetic", PasswordContentBlocks.read(fields).single().block!!.value("privateKey"))
    }
}
