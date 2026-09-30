package takagi.ru.monica.autofill_ng

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/** Service-lifetime index of IDs only: no OTP keys, codes or credential values. */
internal class AutofillOtpBindingCache(private val scope: CoroutineScope) {
    private var pending: Deferred<Set<Long>>? = null

    @Synchronized
    fun getOrLoad(loader: suspend () -> Set<Long>): Deferred<Set<Long>> {
        pending?.takeUnless { it.isCancelled }?.let { return it }
        // A request's short wait can expire without cancelling this shared background scan.
        return scope.async(Dispatchers.IO) { loader() }.also { pending = it }
    }

    @Synchronized
    fun invalidate() {
        pending = null
    }
}
