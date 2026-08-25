package takagi.ru.monica.keepass

import org.junit.Assert.assertEquals
import org.junit.Test

class KeePassNativeEntryRouteTest {
    @Test
    fun `recognized native kinds use specialized Monica routes`() {
        assertEquals(KeePassNativeEntryRouteKind.PASSWORD, KeePassNativeEntryRoutePolicy.routeFor(KeePassNativeEntryKind.PASSWORD))
        assertEquals(KeePassNativeEntryRouteKind.TOTP, KeePassNativeEntryRoutePolicy.routeFor(KeePassNativeEntryKind.TOTP))
        assertEquals(KeePassNativeEntryRouteKind.NOTE, KeePassNativeEntryRoutePolicy.routeFor(KeePassNativeEntryKind.NOTE))
        assertEquals(KeePassNativeEntryRouteKind.BANK_CARD, KeePassNativeEntryRoutePolicy.routeFor(KeePassNativeEntryKind.BANK_CARD))
        assertEquals(KeePassNativeEntryRouteKind.DOCUMENT, KeePassNativeEntryRoutePolicy.routeFor(KeePassNativeEntryKind.DOCUMENT))
        assertEquals(KeePassNativeEntryRouteKind.PASSKEY, KeePassNativeEntryRoutePolicy.routeFor(KeePassNativeEntryKind.PASSKEY))
    }

    @Test
    fun `unknown and template entries always stay in generic native route`() {
        assertEquals(KeePassNativeEntryRouteKind.GENERIC, KeePassNativeEntryRoutePolicy.routeFor(KeePassNativeEntryKind.UNKNOWN))
        assertEquals(KeePassNativeEntryRouteKind.GENERIC, KeePassNativeEntryRoutePolicy.routeFor(KeePassNativeEntryKind.TEMPLATE))
    }
}
