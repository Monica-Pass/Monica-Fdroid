package takagi.ru.monica.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordCustomIconSupportTest {

    @Test
    fun webDomainCannotInheritAnIconFromAnAssociatedAppPackage() {
        assertNull(
            resolveAutoMatchedSimpleIconSlugFromSlugs(
                availableSlugs = setOf("github"),
                website = "https://linux.do",
                title = "Linux.do论坛",
                appPackageName = "com.github.android"
            )
        )
    }

    @Test
    fun appOnlyEntryCanStillUseTheAssociatedAppIconSlug() {
        assertEquals(
            "github",
            resolveAutoMatchedSimpleIconSlugFromSlugs(
                availableSlugs = setOf("github"),
                website = "",
                title = "GitHub",
                appPackageName = "com.github.android"
            )
        )
    }
}
