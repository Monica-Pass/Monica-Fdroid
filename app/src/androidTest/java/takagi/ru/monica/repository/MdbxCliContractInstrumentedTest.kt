package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.mdbx_ffi.*

/** Uses only CLI-generated synthetic fixtures, with the APK's independently built native runtime. */
@RunWith(AndroidJUnit4::class)
class MdbxCliContractInstrumentedTest {
    @Test fun cliObjectsVersionsBlobsAndPortableCopiesRoundtripThroughPackagedRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "mdbx-cli-contract-input")
        val manifest = Json.parseToJsonElement(File(input, "manifest.json").readText()).jsonObject
        val password = manifest.getValue("password").jsonPrimitive.content
        val expected = manifest.getValue("objects").jsonArray
        val attachment = manifest.getValue("attachment").jsonObject
        val parent = manifest.getValue("parent").jsonPrimitive.content
        val child = manifest.getValue("child").jsonPrimitive.content
        val output = File(context.filesDir, "mdbx-cli-contract-output").apply { mkdirs() }
        Mdbx2NativeRuntime.ensureLoaded()
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        for (name in listOf("fixture.mdbx", "portable.mdbx")) {
            val original = File(input, name)
            val before = hash(original)
            val working = File(output, "work-${UUID.randomUUID()}.mdbx")
            original.copyTo(working)
            File(input, "$name.blobs").copyRecursively(File("${working.absolutePath}.blobs"))
            assertTrue(runCatching { openVault(working.absolutePath, "wrong-synthetic-password", "negative-control") }.isFailure)
            val native = openVault(working.absolutePath, password, "android-contract-reader")
            val androidId = UUID.randomUUID().toString()
            val deleted = UUID.randomUUID().toString()
            val cliToken = expected.map { it.jsonObject }.single {
                it.getValue("type").jsonPrimitive.content == "api-token" &&
                    it.getValue("version").jsonPrimitive.int == 1
            }
            val cliId = cliToken.getValue("id").jsonPrimitive.content
            val cliPayload = buildJsonObject {
                Json.parseToJsonElement(cliToken.getValue("payload").jsonPrimitive.content).jsonObject.forEach { (key, value) -> put(key, value) }
                put("note", "Android roundtrip")
            }.toString()
            val androidPayload = """{"schema":"monica.gateway.credential.v1","name":"android-fixture","provider":"github","api_base":"https://api.github.com","token":"synthetic-android-no-access-token","note":"Android edited","androidExtension":{"null":null,"bits":[0,false]}}"""
            try {
                val categories = native.listCollectionSummaries(100u, null).items
                assertEquals(parent, categories.single { it.collectionId == child }.groupId)
                for (element in expected) {
                    val objectData = element.jsonObject
                    val id = objectData.getValue("id").jsonPrimitive.content
                    val collection = objectData.getValue("collection").jsonPrimitive.content
                    val summary = native.listObjectSummaries(collection, null, 100u, null).items.single { it.objectId == id }
                    assertEquals(objectData.getValue("version").jsonPrimitive.int.toUInt(), summary.payloadSchemaVersion)
                    val record = native.revealObjectWithLimits(id, MdbxObjectDisclosureLimits(4uL * 1024uL * 1024uL)).`object`
                    assertNotNull(record)
                    record!!
                    assertEquals(id, record.objectId)
                    assertEquals(collection, record.collectionId)
                    assertEquals(objectData.getValue("type").jsonPrimitive.content, record.objectTypeId)
                    assertEquals(objectData.getValue("title").jsonPrimitive.content, record.title)
                    assertEquals(Json.parseToJsonElement(objectData.getValue("payload").jsonPrimitive.content), Json.parseToJsonElement(record.payloadJson))
                }
                val bytes = attachment.getValue("bytes").jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()
                assertArrayEquals(bytes, native.readAttachmentContent(attachment.getValue("id").jsonPrimitive.content, 1024uL * 1024uL))
                // Android-owned v1 data takes the ordinary engine write path;
                // unknown/future CLI records above remain unchanged.
                native.executeWriteOperation(UUID.randomUUID().toString(), "android-contract-create", listOf(
                    MdbxWriteCommand.CreateEntry(androidId, child, "api-token", "android-fixture", androidPayload)))
                native.executeWriteOperation(UUID.randomUUID().toString(), "android-contract-move", listOf(
                    MdbxWriteCommand.MoveEntry(androidId, child, parent)))
                native.executeWriteOperation(UUID.randomUUID().toString(), "android-contract-edit", listOf(
                    MdbxWriteCommand.UpdateEntry(androidId, parent, "api-token", "android-fixture-edited", androidPayload)))
                native.executeWriteOperation(UUID.randomUUID().toString(), "android-contract-cli-edit", listOf(
                    MdbxWriteCommand.UpdateEntry(cliId, parent, "api-token", "CLI fixture edited by Android", cliPayload)))
                native.executeWriteOperation(UUID.randomUUID().toString(), "android-contract-cli-move", listOf(
                    MdbxWriteCommand.MoveEntry(cliId, parent, child)))
                native.executeWriteOperation(UUID.randomUUID().toString(), "android-contract-tombstone", listOf(
                    MdbxWriteCommand.CreateEntry(deleted, child, "login", "deleted-fixture", "{}"),
                    MdbxWriteCommand.DeleteEntry(deleted, child)))
                assertFalse(native.listObjectSummaries(child, null, 100u, null).items.any { it.objectId == deleted })
            } finally { native.close() }
            val returned = File(output, "android-$name")
            check(!returned.exists()) { "Use a fresh synthetic output directory" }
            createPortableBackup(working.absolutePath, returned.absolutePath)
            File("${working.absolutePath}.blobs").copyRecursively(File("${returned.absolutePath}.blobs"))
            val reopened = openVault(returned.absolutePath, password, "android-independent-copy-reader")
            try {
                assertEquals(parent, reopened.revealObject(androidId).`object`!!.collectionId)
                assertEquals(Json.parseToJsonElement(androidPayload), Json.parseToJsonElement(reopened.revealObject(androidId).`object`!!.payloadJson))
            } finally { reopened.close() }
            File(output, "android-$name.json").writeText(buildJsonObject {
                put("id", androidId); put("collection", parent); put("payload", androidPayload)
                put("deleted", deleted); put("cliId", cliId); put("cliPayload", cliPayload)
                put("cliCollection", child)
            }.toString())
            assertEquals(before, hash(original))
        }
    }
}
