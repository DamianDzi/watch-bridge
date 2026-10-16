package com.wearos.ancsbridge.health

import android.content.Context
import android.util.Log
import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.ble.BleConnectionManager
import com.wearos.ancsbridge.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Drives health-data sync to the iPhone companion over the custom BLE service,
 * per specs/health-ble-protocol.md.
 *
 * Link up → HELLO(nonce, cursor) → TIME_SYNC reply → batches oldest-first,
 * ≤3 unacked in flight → iOS ACKs successfully saved record ids → watch deletes
 * them → more batches. On link loss all sent flags clear so unacked records
 * resend after reconnect.
 */
class HealthSyncManager(
    private val context: Context,
    private val dao: HealthDao,
    private val ble: BleConnectionManager,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "HealthSyncManager"
        private const val MAX_UNACKED_BATCHES = 3
        private const val PREFS = "health_bridge"
        private const val KEY_INSTALL_NONCE = "install_nonce"
    }

    private var linkReady = false

    // record ids per written-but-unacked batch; drained by ACKs
    private val pendingBatches = mutableListOf<MutableSet<Long>>()
    private var pumping = false

    private val installNonce: Long by lazy {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var nonce = prefs.getLong(KEY_INSTALL_NONCE, -1L)
        if (nonce == -1L) {
            nonce = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
            prefs.edit().putLong(KEY_INSTALL_NONCE, nonce).apply()
        }
        nonce
    }

    fun start() {
        scope.launch {
            ble.healthControlReady.collectLatest {
                onLinkReady()
            }
        }
        scope.launch {
            ble.healthControlEvents.collect { data ->
                handleControlMessage(data)
            }
        }
        scope.launch {
            ble.connectionState.collectLatest { state ->
                if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
                    onLinkLost()
                }
            }
        }
    }

    /** Called by collectors whenever new records land in Room. */
    fun onRecordsAvailable() {
        scope.launch { pumpIfNeeded() }
    }

    private suspend fun onLinkReady() {
        linkReady = true
        pendingBatches.clear()

        // Records previously marked sent but never acked must resend
        dao.clearAllSent()

        val cursor = dao.oldestUnsentId() ?: 0L
        val unsent = dao.unsentCount()
        Log.i(TAG, "Health link ready: HELLO nonce=$installNonce cursor=$cursor unsent=$unsent")
        ble.enqueueWrite(AncsConstants.HEALTH_CONTROL_CHARACTERISTIC_UUID, HealthTlv.hello(installNonce, cursor)) { ok ->
            if (!ok) Log.e(TAG, "HELLO write failed — will retry on next link event")
        }
        ble.enqueueWrite(AncsConstants.HEALTH_CONTROL_CHARACTERISTIC_UUID, HealthTlv.ping())
        pumpIfNeeded()
    }

    private fun handleControlMessage(data: ByteArray) {
        val message = try {
            HealthTlv.decodeControl(data)
        } catch (e: HealthTlv.CodecException) {
            Log.w(TAG, "Malformed control message: ${e.message}")
            return
        }
        when (message) {
            is HealthTlv.ControlMessage.TimeSync ->
                Log.i(TAG, "TIME_SYNC from iPhone: ${message.epochMs} (skew ${System.currentTimeMillis() - message.epochMs} ms)")
            is HealthTlv.ControlMessage.Pong -> Log.d(TAG, "PONG from iPhone")
            is HealthTlv.ControlMessage.Ack -> {
                Log.i(TAG, "ACK for ${message.recordIds.size} records")
                scope.launch {
                    dao.deleteByIds(message.recordIds)
                    pendingBatches.forEach { it.removeAll(message.recordIds.toSet()) }
                    pendingBatches.removeAll { it.isEmpty() }
                    pumpIfNeeded()
                }
            }
            else -> Log.d(TAG, "Control message: $message")
        }
    }

    private fun onLinkLost() {
        if (!linkReady) return
        linkReady = false
        pendingBatches.clear()
        Log.i(TAG, "Health link lost — sent flags clear on next link ready")
    }

    private suspend fun pumpIfNeeded() {
        if (!linkReady || pumping) return
        pumping = true
        try {
            while (pendingBatches.size < MAX_UNACKED_BATCHES) {
                val batchBytes = (ble.negotiatedMtu - 3).coerceIn(20, HealthTlv.DEFAULT_MAX_BATCH_BYTES)
                val records = dao.oldestUnsent(HealthTlv.MAX_RECORDS_PER_BATCH)
                if (records.isEmpty()) break

                // Poison rows are marked sent immediately (dropped) so they can't
                // wedge the pump by being refetched and re-failing forever.
                val failedIds = mutableListOf<Long>()
                val wire = records.mapNotNull { entity ->
                    try {
                        HealthRecordMapper.toWire(entity)
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Unmappable record ${entity.recordId} type=${entity.type}: ${e.message}")
                        failedIds += entity.recordId
                        null
                    }
                }
                if (failedIds.isNotEmpty()) dao.markSent(failedIds, System.currentTimeMillis())
                if (wire.isEmpty()) continue

                // Group records into per-batch lists FIRST, so each in-flight
                // pendingBatches entry tracks exactly the ids of one batch — a
                // failed write restores only its own batch, and the ≤3 cap counts
                // batches, not rounds.
                val groups = mutableListOf<List<HealthTlv.WireRecord>>()
                var current = mutableListOf<HealthTlv.WireRecord>()
                var currentSize = 1 // record_count byte
                for (record in wire) {
                    val size = HealthTlv.encodedSize(record)
                    if (current.isNotEmpty() &&
                        (currentSize + size > batchBytes || current.size >= HealthTlv.MAX_RECORDS_PER_BATCH)) {
                        groups += current
                        current = mutableListOf()
                        currentSize = 1
                    }
                    current += record
                    currentSize += size
                }
                if (current.isNotEmpty()) groups += current

                for (group in groups) {
                    if (pendingBatches.size >= MAX_UNACKED_BATCHES) break
                    val ids = group.map { it.recordId }
                    val idSet = ids.toMutableSet()
                    dao.markSent(ids, System.currentTimeMillis())
                    pendingBatches.add(idSet)
                    ble.enqueueWrite(AncsConstants.HEALTH_DATA_CHARACTERISTIC_UUID, HealthTlv.encodeBatch(group)) { ok ->
                        if (!ok) {
                            Log.e(TAG, "Batch write failed — restoring ${ids.size} records to unsent")
                            scope.launch {
                                dao.clearSentFor(ids)
                                pendingBatches.removeAll { it == idSet }
                            }
                        }
                    }
                }
                if (records.size < HealthTlv.MAX_RECORDS_PER_BATCH) break
            }
        } finally {
            pumping = false
        }
    }
}
