package takagi.ru.monica

import org.junit.Test

/**
 * 纯类加载冒烟测试：强制 VM 校验历史上触发过 java.lang.VerifyError 的超大
 * Compose 类。不依赖任何应用状态，解锁与否均可运行。
 *
 * adb 跑法：
 * adb shell am instrument -w -e class takagi.ru.monica.VerifySmokeTest \
 *   takagi.ru.monica.test/androidx.test.runner.AndroidJUnitRunner
 */
class VerifySmokeTest {

    @Test
    fun totpListContentClassVerifies() {
        Class.forName("takagi.ru.monica.ui.TotpListContentKt")
    }
}
