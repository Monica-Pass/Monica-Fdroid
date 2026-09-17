package takagi.ru.monica.ime

import android.app.Application
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29,34], application = Application::class)
class MonicaImeSequentialFillTest {
    private lateinit var service: MonicaInputMethodService
    private val writes = mutableListOf<Pair<Int,String>>()
    private var nextAction: () -> Unit = {}
    private var acceptText = true

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        service = Robolectric.buildService(MonicaInputMethodService::class.java).get()
        connect(1)
    }

    private fun connect(fieldId: Int, packageName: String = "audit.enterprise", password: Boolean = false) {
        val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,arrayOf(InputConnection::class.java)) { _,method,args ->
            when (method.name) {
                "commitText" -> { if (acceptText) writes += fieldId to args!![0].toString(); acceptText }
                "performEditorAction" -> { nextAction(); true }
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) true else null
            }
        } as InputConnection
        ReflectionHelpers.setField(service,"mInputConnection",connection)
        ReflectionHelpers.setField(service,"mStartedInputConnection",connection)
        ReflectionHelpers.setField(service,"mInputEditorInfo",EditorInfo().apply {
            this.packageName=packageName
            this.fieldId=fieldId
            inputType=InputType.TYPE_CLASS_TEXT or if(password) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
        })
    }

    private fun fill(values: List<String> = listOf("account","secret")) {
        MonicaInputMethodService::class.java.getDeclaredMethod("performSequentialImeFill",List::class.java)
            .apply { isAccessible=true }.invoke(service,values)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }

    @Test fun consumingNextWithoutMovingFocusDoesNotAppendThePassword() {
        fill()
        assertEquals(listOf(1 to "account"),writes)
    }

    @Test fun movingToTheNextFieldStillFillsBothValues() {
        nextAction = { connect(2,password=true) }
        fill()
        assertEquals(listOf(1 to "account",2 to "secret"),writes)
    }

    @Test fun changingAppsBetweenStepsStopsThePendingPassword() {
        nextAction = { connect(2,packageName="another.app",password=true) }
        fill()
        assertEquals(listOf(1 to "account"),writes)
    }

    @Test fun restartingTheSameKnownFieldDoesNotCountAsMovingFocus() {
        nextAction = { connect(1) }
        fill()
        assertEquals(listOf(1 to "account"),writes)
    }

    @Test fun restartingAnUnidentifiedFieldDoesNotCountAsMovingFocus() {
        connect(0)
        nextAction = { connect(0) }
        fill()
        assertEquals(listOf(0 to "account"),writes)
    }

    @Test fun webEditorsWithoutFieldIdsCanTransitionToPasswordInput() {
        connect(0)
        nextAction = { connect(0,password=true) }
        fill()
        assertEquals(listOf(0 to "account",0 to "secret"),writes)
    }

    @Test fun editorsWithNoViewIdCanTransitionToPasswordInput() {
        connect(android.view.View.NO_ID)
        nextAction = { connect(android.view.View.NO_ID,password=true) }
        fill()
        assertEquals(listOf(android.view.View.NO_ID to "account",android.view.View.NO_ID to "secret"),writes)
    }

    @Test fun rejectedTextStopsTheSequence() {
        acceptText = false
        nextAction = { throw AssertionError("Do not move after the write was rejected") }
        fill()
        assertTrue(writes.isEmpty())
    }

    @Test fun aSingleExplicitValueDoesNotRequireAMove() {
        nextAction = { throw AssertionError("Single-field insertion must not navigate") }
        fill(listOf("secret"))
        assertEquals(listOf(1 to "secret"),writes)
    }
}
