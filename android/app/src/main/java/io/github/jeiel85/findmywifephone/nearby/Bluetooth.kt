package io.github.jeiel85.findmywifephone.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NearbyPermissions {
    /** Runtime permissions the nearby gauge needs; none before Android 12 (scanning then uses location). */
    val required: Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun granted(context: Context) = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothOn(context: Context) =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
}

/**
 * Ringing side: broadcasts the rotating [BeaconToken] while the phone rings, so the paired phone
 * can show how close it is. Nothing else is in the advertisement (no name, no connectable GATT).
 */
@SuppressLint("MissingPermission") // checked in start(); SecurityException is caught as well
class BeaconAdvertiser(private val context: Context, private val pairKey: ByteArray) {
    private val main = Handler(Looper.getMainLooper())
    private val advertiser get() = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser
    private var running = false

    private val callback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Log.w(TAG, "advertise failed: $errorCode") }
    }

    private val rotate = object : Runnable {
        override fun run() {
            if (!running) return
            restart()
            val now = System.currentTimeMillis()
            main.postDelayed(this, BeaconToken.WINDOW_MS - now % BeaconToken.WINDOW_MS + 50)
        }
    }

    /** false when Bluetooth is off, unsupported or not permitted — the requester is told so. */
    fun start(): Boolean {
        if (running) return true
        if (!NearbyPermissions.granted(context) || !NearbyPermissions.bluetoothOn(context) || advertiser == null) return false
        running = true
        rotate.run()
        return running
    }

    private fun restart() {
        val adv = advertiser ?: return stop()
        try {
            adv.stopAdvertising(callback)
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(BeaconToken.SERVICE),
                    BeaconToken.token(pairKey, BeaconToken.window(System.currentTimeMillis())))
                .build()
            adv.startAdvertising(settings, data, callback)
        } catch (e: Exception) {
            Log.w(TAG, "advertise refused", e)
            running = false
        }
    }

    fun stop() {
        main.removeCallbacks(rotate)
        if (!running) return
        running = false
        runCatching { advertiser?.stopAdvertising(callback) }
    }

    private companion object { const val TAG = "Beacon" }
}

data class NearbyReading(
    val closeness: Closeness = Closeness.NONE,
    val warmth: Float = 0f,
    val trend: Trend = Trend.STEADY,
    /** Something from the other phone was heard at least once since the scan started. */
    val heard: Boolean = false,
)

/** Searching side: listens for the other phone's token and keeps a smoothed reading. */
@SuppressLint("MissingPermission") // checked in start(); SecurityException is caught as well
class NearbyScanner(private val context: Context, private val pairKey: ByteArray) {
    private val main = Handler(Looper.getMainLooper())
    private val meter = ProximityMeter()
    private val _reading = MutableStateFlow(NearbyReading())
    val reading: StateFlow<NearbyReading> = _reading.asStateFlow()
    private var heard = false
    private var running = false
    private val scanner get() = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(ParcelUuid(BeaconToken.SERVICE))
            val now = System.currentTimeMillis()
            if (!BeaconToken.matches(pairKey, data, now)) return
            meter.add(now, result.rssi)
            heard = true
            publish(now)
        }

        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed: $errorCode") }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            publish(System.currentTimeMillis())
            main.postDelayed(this, 1000)
        }
    }

    private fun publish(now: Long) {
        _reading.value = NearbyReading(meter.closeness(now), meter.warmth(now), meter.trend(now), heard)
    }

    fun start(): Boolean {
        if (running) return true
        if (!NearbyPermissions.granted(context) || !NearbyPermissions.bluetoothOn(context)) return false
        val s = scanner ?: return false
        return try {
            val filter = ScanFilter.Builder().setServiceData(ParcelUuid(BeaconToken.SERVICE), ByteArray(0)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            s.startScan(listOf(filter), settings, callback)
            running = true
            main.post(tick)
            true
        } catch (e: Exception) {
            Log.w(TAG, "scan refused", e)
            false
        }
    }

    fun stop() {
        main.removeCallbacks(tick)
        if (!running) return
        running = false
        runCatching { scanner?.stopScan(callback) }
    }

    private companion object { const val TAG = "NearbyScanner" }
}
