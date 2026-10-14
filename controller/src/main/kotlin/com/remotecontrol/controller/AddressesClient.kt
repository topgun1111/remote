package com.remotecontrol.controller

import android.os.Handler
import android.os.Looper
import com.remotecontrol.shared.AddressesResult
import com.remotecontrol.shared.AddressesWire
import com.remotecontrol.shared.RouteLogic

/**
 * Session 9b: asks the connected Agent for its addresses (`ADDRESSES` -> `ADDRESSES_RESULT`) and fills in the device's
 * remote address when none is set. Replies arrive through [FilesHub] (no new hook in `ConnectionManager`).
 *
 * Rules: the Agent's VPN address (`AddressesWire.suggestRemote`) is saved automatically only if the device has NO remote
 * address and the person did not clear it on purpose (`RouteStore.autoFillAllowed`). An address that is already set is
 * never overwritten; the listener always gets the Agent's VPN endpoint as [Outcome.suggestion], so the UI can offer it when
 * it differs from the saved one and [Outcome.applied] is false. The Agent is authenticated at this point (the request is sent after HELLO_ACK was accepted).
 *
 * Usage: `val addresses = AddressesClient(send, store).also { it.start() }` once; after a successful handshake
 * `addresses.request(routes.key()) { outcome -> ... }`. Listener runs on the main thread. All methods: any thread.
 */
class AddressesClient(
    private val send: (String) -> Boolean,
    private val store: RouteStore,
) : FilesHub.Sink {
    /** [applied] = the suggestion was just saved as the remote address; [suggestion] = the Agent's VPN endpoint, if any. */
    data class Outcome(val result: AddressesResult, val suggestion: Pair<String, Int>?, val applied: Boolean)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pendingKey: String? = null
    @Volatile private var listener: ((Outcome) -> Unit)? = null

    fun start() { FilesHub.register(this) }
    fun stop() { FilesHub.unregister(this); pendingKey = null; listener = null }

    /** Sends the request for the device [key]; false when not connected. Only the newest request is answered. */
    fun request(key: String, onOutcome: (Outcome) -> Unit = {}): Boolean {
        pendingKey = key
        listener = onOutcome
        val ok = send(AddressesWire.request())
        if (!ok) { pendingKey = null; listener = null }
        return ok
    }

    override fun onRawMessage(raw: String): Boolean {
        val r = AddressesWire.parseResult(raw) ?: return false
        val key = pendingKey ?: return true            // unsolicited: consumed, ignored
        pendingKey = null
        val cb = listener
        val suggestion = AddressesWire.suggestRemote(r)
        val current = store.remote(key)
        var applied = false
        if (suggestion != null && current == null && store.autoFillAllowed(key)) {
            applied = store.setRemote(key, RouteLogic.formatEndpoint(suggestion.first, suggestion.second), auto = true)
        }
        if (cb != null) main.post { cb(Outcome(r, suggestion, applied)) }
        return true
    }
}
