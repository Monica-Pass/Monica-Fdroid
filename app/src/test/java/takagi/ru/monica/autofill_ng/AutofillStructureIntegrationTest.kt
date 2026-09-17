package takagi.ru.monica.autofill_ng

import android.app.Application
import android.app.assist.AssistStructure
import android.content.ComponentName
import android.text.InputType
import android.util.Pair
import android.view.View
import android.view.ViewStructure
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class AutofillStructureIntegrationTest {
    private val parser = EnhancedAutofillStructureParserV2()

    @Before fun installSignaturePermission() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    private fun node(hint: String? = null, inputType: Int = InputType.TYPE_CLASS_TEXT,
                     focused: Boolean = false, hints: Array<String>? = null,
                     html: Map<String, String>? = null, children: Array<AssistStructure.ViewNode>? = null,
                     domain: String? = null): AssistStructure.ViewNode {
        val node = ReflectionHelpers.newInstance(AssistStructure.ViewNode::class.java)
        fun set(name: String, value: Any?) = ReflectionHelpers.setField(node, name, value)
        set("mAutofillId", EditText(RuntimeEnvironment.getApplication()).autofillId)
        set("mAutofillType", View.AUTOFILL_TYPE_TEXT)
        set("mInputType", if (children != null) 0 else inputType)
        set("mClassName", if (domain != null) "android.webkit.WebView" else if (children != null) "android.widget.LinearLayout" else "android.widget.EditText")
        if (hint != null) {
            val text = Class.forName("android.app.assist.AssistStructure\$ViewNodeText").getDeclaredConstructor()
                .apply { isAccessible = true }.newInstance()
            ReflectionHelpers.setField(text,"mHint",hint)
            set("mText", text)
        }
        if (focused) set("mFlags", ReflectionHelpers.getStaticField<Int>(AssistStructure.ViewNode::class.java,"FLAGS_FOCUSED"))
        if (hints != null) set("mAutofillHints",hints)
        if (children != null) set("mChildren",children)
        if (domain != null) { set("mWebDomain",domain); set("mWebScheme","https") }
        if (html != null) set("mHtmlInfo",object: ViewStructure.HtmlInfo() {
            override fun getTag() = "input"
            override fun getAttributes(): MutableList<Pair<String,String>> = html.map { Pair(it.key,it.value) }.toMutableList()
        })
        return node
    }

    private fun structure(vararg roots: AssistStructure.ViewNode): AssistStructure {
        val structure = AssistStructure()
        ReflectionHelpers.setField(structure,"mActivityComponent",ComponentName("audit.enterprise","audit.enterprise.Login"))
        val windows = roots.map { root ->
            ReflectionHelpers.newInstance(AssistStructure.WindowNode::class.java).also {
                ReflectionHelpers.setField(it,"mRoot",root)
                ReflectionHelpers.setField(it,"mTitle","audit.enterprise/Login")
            }
        }
        ReflectionHelpers.setField(structure,"mWindowNodes",ArrayList(windows))
        return structure
    }

    private fun parse(vararg roots: AssistStructure.ViewNode) = parser.parse(structure(*roots))

    @Test fun standardLoginRemainsRecognized() {
        val user = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val pass = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(setOf(user.autofillId,pass.autofillId),parse(node(children=arrayOf(user,pass))).items.map { it.id }.toSet())
    }

    @Test fun enterpriseIdentifiersAreRecognizedWithoutStandardHints() {
        listOf("请输入工号","学号","員工編號","账号","帳號").forEach { hint ->
            val field = node(hint=hint,focused=true)
            assertEquals(hint,listOf(FieldHint.USERNAME),parse(node(children=arrayOf(field))).items.map { it.hint })
        }
    }

    @Test fun nativeLoginIsNotDroppedBecauseThePageHasAnUnrelatedWebView() {
        val user = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val pass = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val web = node(domain="help.example.com",children=emptyArray())
        val parsed = parse(node(children=arrayOf(user,pass,web)))
        assertEquals(setOf(user.autofillId,pass.autofillId),parsed.items.map { it.id }.toSet())
        assertNull(parsed.webDomain)
    }

    @Test fun focusedDialogIsPreferredToTheFormBehindIt() {
        val background = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME))
        val active = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,focused=true)
        val parsed = parse(node(children=arrayOf(background)),node(children=arrayOf(active)))
        assertEquals(listOf(active.autofillId),parsed.items.map { it.id })
    }

    @Test fun phoneVerificationCodeIsNeverAnAccount() {
        val otp = node(hint="手机验证码",inputType=InputType.TYPE_CLASS_NUMBER,focused=true)
        val parsed = parse(node(children=arrayOf(otp)))
        assertEquals(listOf(FieldHint.OTP_CODE),parsed.items.map { it.hint })
    }

    @Test fun accountPasswordLabelsRemainPasswordTargets() {
        listOf("账号密码", "登录密码", "Account password", "Email password", "Mot de passe", "Passe").forEach { hint ->
            val password = node(hint=hint, focused=true)
            assertEquals(hint, listOf(FieldHint.PASSWORD), parse(password).items.map { it.hint })
        }
    }

    @Test fun compatibilityModeDoesNotPromoteOneTimePasswordsToSavedPasswords() {
        listOf("One-time password", "动态密码", "一次性密码").forEach { hint ->
            val otp = node(hint=hint, focused=true)
            val input = structure(otp)
            listOf(false, true).forEach { weak ->
                assertEquals("$hint weak=$weak", listOf(FieldHint.OTP_CODE),
                    parser.parse(input, allowWeakTargets=weak).items.map { it.hint })
            }
        }
    }

    @Test fun nativeSearchIsExcludedEvenBesideAPasswordField() {
        val search = node(hint="搜索工号",inputType=InputType.TYPE_CLASS_NUMBER,focused=true)
        val pass = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse(parse(node(children=arrayOf(search,pass))).items.any { it.id == search.autofillId })
    }

    @Test fun htmlAutocompleteAcceptsSectionTokens() {
        val pass = node(html=mapOf("type" to "text","autocomplete" to "section-login current-password"),focused=true)
        val web = node(domain="login.example.com",children=arrayOf(pass))
        assertEquals(listOf(FieldHint.PASSWORD),parse(node(children=arrayOf(web))).items.map { it.hint })
    }

    @Test fun focusedWebViewKeepsItsOwnOriginAndFields() {
        val first = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME))
        val second = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val firstWeb = node(domain="first.example.com",children=arrayOf(first))
        val secondWeb = node(domain="second.example.com",children=arrayOf(second))
        ReflectionHelpers.setField(firstWeb,"mId",101)
        ReflectionHelpers.setField(secondWeb,"mId",102)
        val parsed = parse(node(children=arrayOf(firstWeb,secondWeb)))
        assertEquals("second.example.com",parsed.webDomain)
        assertEquals(listOf(second.autofillId),parsed.items.map { it.id })
    }

    @Test fun webViewsWithoutAndroidViewIdsStillHaveSeparateScopes() {
        val first = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME))
        val second = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val parsed = parse(node(children=arrayOf(
            node(domain="first.example.com",children=arrayOf(first)),
            node(domain="second.example.com",children=arrayOf(second)))))
        assertEquals("second.example.com",parsed.webDomain)
        assertEquals(listOf(second.autofillId),parsed.items.map { it.id })
        assertFalse(first.autofillId in parsed.scopeAutofillIds)
    }

    @Test fun focusedCrossOriginSubtreeCannotReceiveTheOuterSitesFields() {
        val outer = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME))
        val inner = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val frame = node(children=arrayOf(inner))
        ReflectionHelpers.setField(frame,"mWebDomain","sso.example.org")
        ReflectionHelpers.setField(frame,"mWebScheme","https")
        val parsed = parse(node(domain="outer.example.com",children=arrayOf(outer,frame)))
        assertEquals("sso.example.org",parsed.webDomain)
        assertEquals(listOf(inner.autofillId),parsed.items.map { it.id })
        assertFalse(outer.autofillId in parsed.scopeAutofillIds)
    }

    @Test fun parserSupportsPhoneAndNumericStudentAccountSteps() {
        listOf("手机号" to InputType.TYPE_CLASS_PHONE,"学号" to InputType.TYPE_CLASS_NUMBER).forEach { (hint,type) ->
            val parsed = parse(node(hint=hint,inputType=type,focused=true))
            assertTrue(hint,parsed.items.any { it.hint == FieldHint.USERNAME || it.hint == FieldHint.PHONE_NUMBER })
        }
    }

    @Test fun autocompleteOffRetainsTheExistingPreference() {
        val field = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            html=mapOf("type" to "password","autocomplete" to "off"),focused=true)
        val structure = structure(node(domain="login.example.com",children=arrayOf(field)))
        assertTrue(parser.parse(structure,respectAutofillOff=true).items.isEmpty())
        assertEquals(listOf(FieldHint.PASSWORD),parser.parse(structure,respectAutofillOff=false).items.map { it.hint })
    }

    @Test fun syntheticFocusFallbackDoesNotUndoSearchOrOtpExclusion() {
        val service = org.robolectric.Robolectric.buildService(MonicaAutofillServiceNg::class.java).get()
        val infer = MonicaAutofillServiceNg::class.java.getDeclaredMethod("inferFocusedFieldHint",
            AssistStructure.ViewNode::class.java,Boolean::class.javaPrimitiveType).apply { isAccessible=true }
        listOf("搜索工号","手机验证码").forEach { hint ->
            assertNull(hint,infer.invoke(service,node(hint=hint,inputType=InputType.TYPE_CLASS_NUMBER,focused=true),true))
        }
    }

    @Test fun syntheticFallbackCannotAddFieldsFromAnotherWindow() {
        val field = node(hints=arrayOf(View.AUTOFILL_HINT_USERNAME),focused=true)
        val other = node(inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,focused=true)
        val structure = structure(node(children=arrayOf(other)),node(children=arrayOf(field)))
        val parsed = parser.parse(structure)
        val service = org.robolectric.Robolectric.buildService(MonicaAutofillServiceNg::class.java).get()
        val method = MonicaAutofillServiceNg::class.java.getDeclaredMethod("buildFocusedSyntheticItems",
            AssistStructure::class.java,List::class.java,Boolean::class.javaPrimitiveType,Set::class.java).apply { isAccessible=true }
        val synthetic = method.invoke(service,structure,parsed.items,true,parsed.scopeAutofillIds) as List<*>
        assertTrue(synthetic.isEmpty())
    }

    @Test fun manualRequestCanSelectAnUnlabelledFocusedTextField() {
        val field = node(focused=true)
        val unrelated = node()
        val structure = structure(node(children=arrayOf(field,unrelated)))
        assertTrue(parser.parse(structure).items.isEmpty())
        val manual = parser.parse(structure,allowWeakTargets=true)
        assertEquals(listOf(field.autofillId),manual.items.map { it.id })
        assertEquals(listOf(FieldHint.USERNAME),manual.items.map { it.hint })
        assertTrue(parser.parse(structure,allowWeakTargets=true,requireExplicitWeakLoginSignal=true).items.isEmpty())
    }
}
