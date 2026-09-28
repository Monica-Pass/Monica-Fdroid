package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.CustomFieldDraft
import java.io.File
import kotlinx.coroutines.flow.first

class EntryContentExperimentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativePaymentProjectionIsReadableAndDetailsRevealOnlyOnRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = takagi.ru.monica.repository.MdbxPasswordContentFields.readInto(
            org.json.JSONObject().put("credit_card_number_plain", "4242424242424242")
                .put("credit_card_cvv_plain", "123").put("credit_card_holder", "Alice")
                .put("credit_card_expiry", "09/2030"),
            takagi.ru.monica.data.PasswordEntry(title = "Synthetic payment", website = "", username = "", password = ""),
        )
        compose.setContent { MaterialTheme { Surface {
            CompositionLocalProvider(LocalEntryContentStyle provides true) {
                EntryPaymentDetails(entry.creditCardNumber, entry.creditCardHolder,
                    entry.creditCardExpiry, entry.creditCardCVV)
            }
        } } }
        compose.onNodeWithText("4242424242424242").assertDoesNotExist()
        compose.onNodeWithText("123").assertDoesNotExist()
        compose.onAllNodesWithContentDescription(context.getString(takagi.ru.monica.R.string.custom_field_show_content))
            .onFirst().performClick()
        compose.onNodeWithText("4242424242424242").assertIsDisplayed()
        compose.onNodeWithText("123").assertDoesNotExist()
    }
    private fun screenshot(name: String, tag: String? = null) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "experiment-$name.png")
        file.outputStream().use { (if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun addHiddenFieldAndSwitchStyleKeepsItsData() {
        var fields by mutableStateOf<List<CustomFieldDraft>>(emptyList())
        var onDemand by mutableStateOf(true)
        compose.setContent {
            MaterialTheme { Surface {
                Column(Modifier.padding(12.dp)) {
                    CustomFieldEditorSection(fields, { fields = it }, contentStyle = onDemand, saveTextState = false)
                }
            } }
        }
        compose.onNodeWithTag("entry_content_add").performClick()
        compose.onNodeWithTag("entry_field_kind_1").performClick()
        compose.runOnIdle {
            assertEquals(1, fields.size)
            assertTrue(fields.single().isProtected)
            fields = listOf(fields.single().copy(title = "Recovery code", value = "synthetic-secret"))
            onDemand = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("synthetic-secret", fields.single().value) }
        compose.onNodeWithText("Recovery code").assertExists()
    }

    @Test fun detailMasksSecretUntilExplicitReveal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { Surface {
            CustomFieldDisplayCard(listOf(CustomField(id=1, entryId=1, title="Recovery", value="synthetic-secret", isProtected=true)))
        } } }
        compose.onNodeWithText("synthetic-secret").assertDoesNotExist()
        screenshot("detail-hidden")
        compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.custom_field_show_content)).performClick()
        compose.onNodeWithText("synthetic-secret").assertIsDisplayed()
    }

    @Test fun focusSpringKeepsFieldBoundsAndCursorTextStable() {
        var value by mutableStateOf("alice@")
        compose.setContent { MaterialTheme { Surface {
            CompositionLocalProvider(LocalEntryContentStyle provides true, LocalEntryFieldMotion provides true) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(value, { value = it }, label = { androidx.compose.material3.Text("Account") },
                        singleLine = true, entryContentStyle = true, modifier = Modifier.fillMaxWidth().testTag("focus-field"))
                    OutlinedTextField("", {}, label = { androidx.compose.material3.Text("Other") }, modifier = Modifier.testTag("other"))
                }
            }
        } } }
        val before = compose.onNodeWithTag("focus-field").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("focus-field").performClick().performTextInput("example.invalid")
        compose.waitForIdle()
        val after = compose.onNodeWithTag("focus-field").fetchSemanticsNode().boundsInRoot
        assertEquals(before.width, after.width, 0.1f)
        assertEquals(before.height, after.height, 0.1f)
        compose.runOnIdle { assertEquals("alice@example.invalid", value) }
        screenshot("focused-input")
        compose.onNodeWithTag("other").performClick()
        compose.onNodeWithTag("focus-field").assertTextContains("alice@example.invalid")
    }

    @Test fun stylesAreSelectableAndAddressAcceptsInternationalPostalCodes() {
        var enabled by mutableStateOf(false)
        var postal by mutableStateOf("")
        compose.setContent { MaterialTheme { Surface {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                EntryContentStylePicker(enabled) { enabled = it }
                EntryAddressFields("12 Example Street", "London", "", postal, "UK", {}, {}, {}, { postal=it }, {})
            }
        } } }
        compose.onNodeWithTag("entry_style_true").performClick()
        compose.runOnIdle { assertTrue(enabled) }
        compose.onNodeWithTag("entry_address_postal").performScrollTo().performTextInput("SW1A 1AA")
        compose.runOnIdle { assertEquals("SW1A 1AA", postal) }
        screenshot("style-address")
    }
    @Test fun fullAuthenticatorEditorPreservesExistingNotesAndSteamMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var saved: takagi.ru.monica.data.model.TotpData? = null
        var savedNotes: String? = null
        val source = takagi.ru.monica.data.model.TotpData(
            secret = "JBSWY3DPEHPK3PXP", issuer = "Example", accountName = "alice@example.invalid",
            steamDeviceId = "synthetic-device",
            steamRevocationCode = "synthetic-revocation",
        )
        compose.setContent { MaterialTheme { Surface {
            CompositionLocalProvider(LocalEntryContentStyle provides true, LocalEntryFieldMotion provides true) {
                takagi.ru.monica.ui.screens.AddEditTotpScreen(
                    totpId = null, initialNotes = "Synthetic test entry",
                    initialData = source, initialTitle = "Example authenticator",
                    initialStorageExplicit = true,
                    onSave = { _, notes, data, _, _, done -> saved = data; savedNotes = notes; done(true) },
                    onBatchImport = { _, _, _ -> }, onNavigateBack = {}, onScanQrCode = {},
                )
            }
        } } }
        compose.waitForIdle()
        screenshot("authenticator-editor")
        compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.save)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals("Synthetic test entry", savedNotes)
            assertEquals(source.steamRevocationCode, saved!!.steamRevocationCode)
            assertEquals(source.steamDeviceId, saved!!.steamDeviceId)
        }
    }

    @Test fun fieldDraftSurvivesRecreationIncludingAnUnfinishedHiddenField() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        var fields: List<CustomFieldDraft> = emptyList()
        restoration.setContent {
            var drafts by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = EntryFieldDraftSaver) {
                mutableStateOf(listOf(CustomFieldDraft(id=-9, title="", value="unfinished", isProtected=true, isPreset=true, isRequired=true, presetId="recovery", placeholder="Enter recovery code",
                    secureFieldType=takagi.ru.monica.data.model.SecureCustomFieldType.BOOLEAN)))
            }
            fields = drafts
            MaterialTheme { CustomFieldEditorSection(drafts, { drafts=it }, contentStyle=true) }
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(-9L, fields.single().id)
            assertEquals("unfinished", fields.single().value)
            assertTrue(fields.single().isProtected)
            assertTrue(fields.single().isPreset)
            assertTrue(fields.single().isRequired)
            assertEquals("recovery", fields.single().presetId)
            assertEquals("Enter recovery code", fields.single().placeholder)
            assertEquals(takagi.ru.monica.data.model.SecureCustomFieldType.BOOLEAN, fields.single().secureFieldType)
        }
    }

    @Test fun existingAuthenticatorNotesRemainAccessibleInClassicStyle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = takagi.ru.monica.data.model.TotpData(secret="JBSWY3DPEHPK3PXP",
            issuer="Example issuer", accountName="work@example.invalid")
        val item = takagi.ru.monica.data.SecureItem(itemType=takagi.ru.monica.data.ItemType.TOTP,
            title="Example", itemData="{}", notes="Existing account instructions")
        compose.setContent { MaterialTheme { Surface {
            TotpCodeCard(item=item, parsedTotpData=data, onCopyCode={},
                appSettings=takagi.ru.monica.data.AppSettings(passwordContentEditorEnabled=false),
                sharedTickSeconds=1_000, sharedProgressTimeMillis=1_000_000)
        } } }
        compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.more_options)).performClick()
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.entry_content_details)).performClick()
        compose.onNodeWithText("Example issuer").assertIsDisplayed()
        // The modal initially opens at its half-height anchor; expand it as a user
        // would before scrolling to lower rows at large accessibility font sizes.
        compose.onNodeWithTag("totp_content_detail").performTouchInput { swipeUp() }
        compose.onNodeWithText("Existing account instructions").performScrollTo().assertIsDisplayed()
        screenshot("totp-details-classic", "totp_content_detail")
        compose.onNodeWithText(data.secret).assertDoesNotExist()
    }

    @Test fun paymentInputAcceptsPastedCardAndFourDigitYearAndKeepsSecretsHidden() {
        var number by mutableStateOf("")
        var expiry by mutableStateOf("")
        var cvv by mutableStateOf("")
        compose.setContent { MaterialTheme { Surface {
            CompositionLocalProvider(LocalEntryContentStyle provides true) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                    EntryPaymentFields(number,"Alice",expiry,cvv,{number=it},{},{expiry=it},{cvv=it})
                }
            }
        } } }
        compose.onNodeWithTag("entry_payment_number").performTextInput("4242 4242 4242 4242")
        compose.onNodeWithTag("entry_payment_expiry").performScrollTo().performTextInput("09/2030")
        compose.onNodeWithTag("entry_payment_cvv").performScrollTo().performTextInput("123")
        compose.runOnIdle {
            assertEquals("4242424242424242",number)
            assertEquals("09/2030",expiry)
            assertEquals("123",cvv)
        }
        screenshot("payment-editor")
    }

    @Test fun sharedContactsKeepInternationalPhoneAndMultipleEmails() {
        var emails by mutableStateOf(listOf("alice@example.invalid"))
        var phones by mutableStateOf(listOf(""))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { Surface {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                EntryContactFields(emails,phones,{emails=it},{phones=it})
            }
        } } }
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.add_email)).performClick()
        compose.onNodeWithTag("entry_contact_email_1").performScrollTo().performTextInput("backup@example.invalid")
        compose.onNodeWithTag("entry_contact_phone_0").performScrollTo().performTextInput("+44 20 7946 0000")
        compose.runOnIdle {
            assertEquals(listOf("alice@example.invalid","backup@example.invalid"),emails)
            assertEquals(listOf("+44 20 7946 0000"),phones)
        }
    }

    @Test fun stylePreferencePersistsWithoutChangingDefaultFieldVisibility() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = takagi.ru.monica.utils.SettingsManager(context)
        val before = manager.settingsFlow.first()
        try {
            manager.updatePasswordContentEditorEnabled(true)
            val enabled = kotlinx.coroutines.withTimeout(5_000) { takagi.ru.monica.utils.SettingsManager(context).settingsFlow.first { it.passwordContentEditorEnabled } }
            assertTrue(enabled.passwordContentEditorEnabled)
            assertEquals(before.passwordFieldVisibility, enabled.passwordFieldVisibility)
            manager.updatePasswordContentEditorEnabled(false)
            val classic = kotlinx.coroutines.withTimeout(5_000) { takagi.ru.monica.utils.SettingsManager(context).settingsFlow.first { !it.passwordContentEditorEnabled } }
            assertFalse(classic.passwordContentEditorEnabled)
            assertEquals(before.passwordFieldVisibility, classic.passwordFieldVisibility)
        } finally { manager.updatePasswordContentEditorEnabled(before.passwordContentEditorEnabled) }
    }

    @Test fun experimentalFieldPreservesCopyLargeDisplayShareAndSend() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext
        var shared: android.content.Intent? = null
        val context = object : android.content.ContextWrapper(realContext) {
            override fun startActivity(intent: android.content.Intent) { shared = intent }
        }
        var sent: Pair<String,String>? = null
        var copies = 0
        fun label(id: Int) = realContext.getString(id)
        fun openMenu() = compose.onNodeWithContentDescription(label(takagi.ru.monica.R.string.field_action_more)).performClick()
        compose.setContent { MaterialTheme { Surface {
            CompositionLocalProvider(LocalEntryContentStyle provides true,
                androidx.compose.ui.platform.LocalContext provides context) {
                CustomFieldDisplayCard(listOf(CustomField(id=1,entryId=1,title="Recovery",value="GIFT-1234567890",isProtected=true)),
                    onCopyField={ _, _ -> copies++ }, onCreateSend={ title,value -> sent=title to value })
            }
        } } }
        compose.onNodeWithText("GIFT-1234567890").assertDoesNotExist()
        openMenu()
        compose.onNodeWithText("GIFT-1234567890").assertDoesNotExist()
        compose.onNodeWithText(label(takagi.ru.monica.R.string.copy)).performClick()
        compose.runOnIdle {
            assertEquals(1,copies)
            val clipboard = realContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("GIFT-1234567890", clipboard.primaryClip!!.getItemAt(0).text.toString())
            if (android.os.Build.VERSION.SDK_INT >= 33) assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        }
        openMenu()
        compose.onNodeWithText(label(takagi.ru.monica.R.string.field_action_show_large)).performClick()
        compose.onNodeWithText("GIFT-1234567890").assertIsDisplayed()
        compose.onNodeWithText(label(takagi.ru.monica.R.string.copy)).performClick()
        compose.runOnIdle { assertEquals(2,copies) }
        compose.onNodeWithText(label(takagi.ru.monica.R.string.close)).performClick()
        openMenu()
        compose.onNodeWithText(label(takagi.ru.monica.R.string.send_create_title)).performClick()
        compose.runOnIdle { assertEquals("Recovery" to "GIFT-1234567890",sent) }
        openMenu()
        compose.onNodeWithText(label(takagi.ru.monica.R.string.share)).performClick()
        compose.runOnIdle {
            assertEquals(android.content.Intent.ACTION_CHOOSER,shared!!.action)
            @Suppress("DEPRECATION")
            val payload = shared!!.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
            assertEquals(android.content.Intent.ACTION_SEND,payload.action)
            assertEquals("GIFT-1234567890",payload.getStringExtra(android.content.Intent.EXTRA_TEXT))
        }
        compose.onNodeWithText("GIFT-1234567890").assertDoesNotExist()
        screenshot("field-actions-preserved")
    }

    @Test fun experimentalPaymentDetailsGenerateScannableQrAndCode128() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun label(id: Int) = context.getString(id)
        compose.setContent { MaterialTheme { Surface {
            CompositionLocalProvider(LocalEntryContentStyle provides true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    EntryPaymentDetails("4242424242424242","","","")
                }
            }
        } } }
        compose.onNodeWithContentDescription(label(takagi.ru.monica.R.string.field_action_more)).performClick()
        compose.onNodeWithText(label(takagi.ru.monica.R.string.field_action_show_barcode)).performClick()
        fun decode(): com.google.zxing.Result {
            val description = label(takagi.ru.monica.R.string.field_action_show_barcode)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
            val bitmap=compose.onNodeWithContentDescription(description).performScrollTo().captureToImage().asAndroidBitmap()
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            return com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(
                com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(bitmap.width,bitmap.height,pixels))))
        }
        decode().also { assertEquals("4242424242424242",it.text);assertEquals(com.google.zxing.BarcodeFormat.QR_CODE,it.barcodeFormat) }
        compose.onNodeWithText(label(takagi.ru.monica.R.string.field_barcode_format_linear)).performScrollTo().performClick()
        decode().also { assertEquals("4242424242424242",it.text);assertEquals(com.google.zxing.BarcodeFormat.CODE_128,it.barcodeFormat) }
    }

}
