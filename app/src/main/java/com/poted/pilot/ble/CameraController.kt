package com.poted.pilot.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.poted.pilot.gps.GpsFeeder
import com.poted.pilot.log.AppLog
import com.poted.pilot.protocol.CameraStatus
import com.poted.pilot.protocol.CmdType
import com.poted.pilot.protocol.Commands
import com.poted.pilot.protocol.Frame
import com.poted.pilot.protocol.FrameReassembler
import com.poted.pilot.protocol.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

enum class Phase(val label: String) {
    IDLE("Rozłączono"),
    CONNECTING("Łączenie…"),
    HANDSHAKE("Uzgadnianie protokołu…"),
    WAITING_APPROVAL("Potwierdź na kamerze"),
    CONNECTED("Połączono"),
    RECONNECTING("Ponowne łączenie…"),
    FAILED("Błąd"),
}

data class Connection(
    val phase: Phase = Phase.IDLE,
    val address: String? = null,
    val name: String? = null,
    val message: String? = null,
    val verifyCode: Int? = null,
    val cameraDeviceId: Long? = null,
    val version: String? = null,
)

private class RejectedException(msg: String) : Exception(msg)

/**
 * Port of connect_logic.c / command_logic.c / status_logic.c from the DJI demo.
 *
 * Session: GATT connect -> MTU -> discover FFF0 -> enable FFF4 notify -> 0019 handshake
 * -> 0000 version -> 1D05 subscribe. When the link drops unexpectedly, it reconnects with
 * autoConnect=true (which keeps working with the screen off) and repeats the handshake with
 * verify_mode=0 so the camera does not ask again.
 */
@SuppressLint("MissingPermission")
class CameraController private constructor(private val context: Context) {
    companion object {
        private const val TAG = "Cam"
        private const val WAKE_WINDOW_MS = 45_000L
        @Volatile private var instance: CameraController? = null

        fun get(context: Context): CameraController =
            instance ?: synchronized(this) {
                instance ?: CameraController(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(context)
    val scanner = Scanner(context)
    private val waker = Waker(context)

    private val _connection = MutableStateFlow(Connection(address = prefs.lastAddress, name = prefs.lastName))
    val connection: StateFlow<Connection> = _connection
    private val _status = MutableStateFlow<CameraStatus?>(null)
    val status: StateFlow<CameraStatus?> = _status
    private val _modeName = MutableStateFlow<Commands.ModeName?>(null)
    val modeName: StateFlow<Commands.ModeName?> = _modeName
    private val _busy = MutableStateFlow(false)
    /** True while a record start/stop command is in flight or a wake-then-record is pending. */
    val busy: StateFlow<Boolean> = _busy
    private val _asleep = MutableStateFlow(false)
    /**
     * Camera reported power_mode=3 or stopped pushing status. The DJI FAQ says nothing may be
     * sent to a sleeping camera: commands get queued and run when it wakes up (minutes later).
     */
    val asleep: StateFlow<Boolean> = _asleep
    private val _notice = MutableStateFlow<Notice?>(null)
    /** Short user-facing message (errors, hints), shown in the app and the notification. */
    val notice: StateFlow<Notice?> = _notice

    data class Notice(val text: String, val error: Boolean, val at: Long = System.currentTimeMillis())

    private var sessionJob: Job? = null
    private var watchdogJob: Job? = null
    @Volatile private var client: GattClient? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Frame>>()
    @Volatile private var handshakeFrames = Channel<Frame>(Channel.UNLIMITED)
    private val seq = AtomicInteger(Random.nextInt(1, 0x8000))
    private var lastStatusKey: String? = null
    @Volatile private var lastStatusAt = 0L
    @Volatile private var sleepAckAt = 0L
    /** Start recording as soon as the camera is awake again (set by START while asleep). */
    @Volatile private var startAfterWakeUntil = 0L
    private val haptics = Haptics(context)
    val gps = GpsFeeder(context)
    @Volatile private var wakeGeneration = 0

    init {
        startGpsPump()
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (d.address != _connection.value.address) return
                val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                AppLog.i(TAG, "bond state ${bondName(prev)} -> ${bondName(st)}")
            }
        }, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val reassembler = FrameReassembler(
        onFrame = { f, raw -> onFrame(f, raw) },
        onError = { reason, raw -> AppLog.w("RX", "bad data: $reason [${raw.toHex()}]") },
        onForeign = { raw -> AppLog.v("RX", "DUML frame ignored (${raw.size}B)") },
    )

    private fun update(block: (Connection) -> Connection) {
        _connection.value = block(_connection.value)
    }

    private fun notice(text: String, error: Boolean = false) {
        if (error) AppLog.w(TAG, "notice: $text") else AppLog.i(TAG, "notice: $text")
        _notice.value = Notice(text, error)
    }

    // ---------------------------------------------------------------- public API

    fun connect(address: String, name: String?) {
        scanner.stop()
        sessionJob?.cancel()
        closeClient()
        startAfterWakeUntil = 0
        resetSessionState()
        prefs.lastAddress = address
        if (name != null) prefs.lastName = name
        update { Connection(phase = Phase.CONNECTING, address = address, name = name ?: prefs.lastName) }
        AppLog.i(TAG, "connect to $address (${name ?: "?"}), paired=${prefs.isPaired(address)}")
        sessionJob = scope.launch { sessionLoop(address) }
    }

    fun disconnect() {
        AppLog.i(TAG, "disconnect requested by user")
        sessionJob?.cancel()
        sessionJob = null
        startAfterWakeUntil = 0
        closeClient()
        resetSessionState()
        update { it.copy(phase = Phase.IDLE, message = null, verifyCode = null) }
    }

    fun forgetPairing() {
        _connection.value.address?.let { prefs.setPaired(it, false) }
        AppLog.i(TAG, "pairing flag cleared; next connection will use verify_mode=1")
    }

    val isConnected: Boolean get() = _connection.value.phase == Phase.CONNECTED

    fun toggleRecord() {
        if (_busy.value) return
        val phase = _connection.value.phase
        if (_asleep.value || (phase == Phase.RECONNECTING && startAfterWakeUntil > System.currentTimeMillis())) {
            wakeThenRecord()
            return
        }
        if (!isConnected) {
            notice("Brak połączenia z kamerą", error = true)
            return
        }
        sendRecord(start = _status.value?.isRecording != true)
    }

    private fun sendRecord(start: Boolean) {
        _busy.value = true
        scope.launch {
            try {
                AppLog.i(TAG, if (start) "START recording" else "STOP recording")
                val (set, id) = Commands.RECORD
                val r = sendCommand(set, id, CmdType.CMD_RESPONSE_OR_NOT, Commands.recordControl(start), timeoutMs = 3000)
                if (r == null) {
                    notice("Kamera nie odpowiedziała na ${if (start) "START" else "STOP"}", error = true)
                    return@launch
                }
                val ret = Commands.retCode(r.payload)
                AppLog.i(TAG, "record ${if (start) "start" else "stop"} ret_code=$ret")
                if (ret != 0) {
                    notice("Kamera odrzuciła ${if (start) "START" else "STOP"} (kod $ret)", error = true)
                    return@launch
                }
                // Keep the button disabled until a status push confirms the change (max 2 s).
                withTimeoutOrNull(2000) {
                    while (_status.value?.isRecording != start) delay(100)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "record failed", e)
                notice("Błąd wysyłania: ${e.message}", error = true)
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * START pressed while the camera sleeps: send the wake-up advertisement and record once the
     * camera reconnects and reports power_mode=0. Nothing is sent to the sleeping camera.
     */
    private fun wakeThenRecord() {
        startAfterWakeUntil = System.currentTimeMillis() + WAKE_WINDOW_MS
        _busy.value = true
        notice("Kamera uśpiona — wybudzam, nagrywanie ruszy po wybudzeniu")
        wake()
        scope.launch {
            delay(WAKE_WINDOW_MS)
            if (startAfterWakeUntil != 0L && System.currentTimeMillis() >= startAfterWakeUntil) {
                startAfterWakeUntil = 0
                _busy.value = false
                notice("Nie udało się wybudzić kamery. Włącz ją przyciskiem na kamerze.", error = true)
            }
        }
    }

    fun switchMode(mode: Int) = fire("mode switch -> ${Commands.modeName(mode)}") {
        val (set, id) = Commands.MODE_SWITCH
        val r = sendCommand(set, id, CmdType.CMD_RESPONSE_OR_NOT, Commands.modeSwitch(mode))
        val ret = r?.let { Commands.retCode(it.payload) }
        AppLog.i(TAG, "mode switch ret_code=${ret ?: "no response"}")
        if (ret != 0) notice("Zmiana trybu nie powiodła się", error = true)
    }

    fun quickSwitch() = fire("QS key") {
        val (set, id) = Commands.KEY_REPORT
        val r = sendCommand(set, id, CmdType.CMD_RESPONSE_OR_NOT, Commands.keyReport(keyCode = 0x02))
        AppLog.i(TAG, "QS ret_code=${r?.let { Commands.retCode(it.payload) } ?: "no response"}")
    }

    fun sleep() = fire("power mode -> sleep") {
        val (set, id) = Commands.POWER_MODE
        val r = sendCommand(set, id, CmdType.CMD_RESPONSE_OR_NOT, Commands.powerMode(3))
        val ret = r?.let { Commands.retCode(it.payload) }
        AppLog.i(TAG, "sleep ret_code=${ret ?: "no response"}")
        if (ret == 0) {
            sleepAckAt = System.currentTimeMillis()
            setAsleep(true, "sleep ACK")
        }
    }

    /**
     * Wake-up sequence. Stage 1: advertise while the BLE link stays up (what the ESP32 demo does).
     * Stage 2, if the camera still reports sleep: drop the link and advertise again, in case the
     * camera only listens for the wake packet when nobody is connected; the session loop then
     * reconnects with autoConnect once the camera is up.
     */
    fun wake() {
        val addr = _connection.value.address ?: prefs.lastAddress
        if (addr == null) {
            AppLog.w(TAG, "wake: no known camera")
            return
        }
        val gen = ++wakeGeneration
        val device = bluetoothAdapter()?.getRemoteDevice(addr)
        AppLog.i(TAG, "wake stage 1 (link up), phase=${_connection.value.phase}, bond=${bondName(device?.bondState ?: -1)}")
        waker.wake(addr) {
            scope.launch {
                delay(3000)
                if (gen != wakeGeneration) return@launch
                val phase = _connection.value.phase
                when {
                    phase == Phase.IDLE || phase == Phase.FAILED -> connect(addr, _connection.value.name)
                    phase == Phase.CONNECTED && _asleep.value -> {
                        AppLog.i(TAG, "wake stage 2: still asleep, dropping the link and advertising again")
                        closeClient()
                        waker.wake(addr) { AppLog.i(TAG, "wake stage 2 done; phase=${_connection.value.phase}") }
                    }
                    else -> AppLog.i(TAG, "wake: camera responded (phase=$phase, asleep=${_asleep.value})")
                }
            }
        }
    }

    /**
     * Experiment for the wake-up packet: Android advertises from a random address that differs
     * from the one used for the GATT link. If the camera only accepts the wake packet from the
     * remote it knows, bonding (which hands it this phone's IRK) may let it resolve the address.
     */
    fun bondExperiment() {
        val addr = _connection.value.address ?: prefs.lastAddress ?: return
        val d = bluetoothAdapter()?.getRemoteDevice(addr) ?: return
        AppLog.i(TAG, "bond experiment: current bond=${bondName(d.bondState)}")
        if (d.bondState == BluetoothDevice.BOND_NONE) {
            val ok = d.createBond()
            AppLog.i(TAG, "createBond() -> $ok")
        }
    }

    private fun bluetoothAdapter() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun bondName(s: Int) = when (s) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> s.toString()
    }

    /** Sends the latest phone fix to the camera once per second (0017, no reply). */
    private fun startGpsPump() {
        scope.launch {
            var lastLogged = 0L
            while (true) {
                delay(1000)
                if (!gps.state.value.running || !isConnected || _asleep.value) continue
                val payload = gps.payload() ?: continue
                try {
                    val (set, id) = Commands.GPS_PUSH
                    val n = gps.state.value.sentCount
                    if (n == 0L || System.currentTimeMillis() - lastLogged > 60_000) {
                        lastLogged = System.currentTimeMillis()
                        val loc = gps.state.value.location
                        AppLog.i(TAG, "GPS push #${n + 1}: %.6f, %.6f ±%.0f m, sats=%d".format(
                            loc?.latitude, loc?.longitude, loc?.accuracy, gps.state.value.satellitesUsed))
                    }
                    sendCommand(set, id, CmdType.CMD_NO_RESPONSE, payload, quiet = true)
                    gps.markSent()
                } catch (e: Exception) {
                    AppLog.w(TAG, "GPS push failed: ${e.message}")
                }
            }
        }
    }

    private fun fire(what: String, block: suspend () -> Unit) {
        if (!isConnected) {
            notice("Brak połączenia z kamerą", error = true)
            return
        }
        if (_asleep.value) {
            notice("Kamera uśpiona — najpierw ją wybudź", error = true)
            return
        }
        AppLog.i(TAG, what)
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                AppLog.e(TAG, "$what failed", e)
            }
        }
    }

    private fun setAsleep(value: Boolean, why: String) {
        if (_asleep.value == value) return
        _asleep.value = value
        AppLog.i(TAG, if (value) "camera asleep ($why)" else "camera awake ($why)")
    }

    /** Status pushes arrive at ~2 Hz; silence for several seconds means the camera went to sleep. */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        lastStatusAt = System.currentTimeMillis()
        watchdogJob = scope.launch {
            while (true) {
                delay(1000)
                if (isConnected && System.currentTimeMillis() - lastStatusAt > 5000) {
                    setAsleep(true, "no status push for ${(System.currentTimeMillis() - lastStatusAt) / 1000}s")
                }
            }
        }
    }

    // ---------------------------------------------------------------- session

    private suspend fun sessionLoop(address: String) {
        var attempt = 0
        var everConnected = false
        while (true) {
            // First try a direct connection; afterwards let the stack wait for the camera.
            val autoConnect = attempt > 0 || everConnected
            try {
                runSession(address, autoConnect) { everConnected = true; attempt = 0 }
            } catch (e: TimeoutCancellationException) {
                // withTimeout inside the session, not cancellation of the loop itself.
                AppLog.w(TAG, "session timeout: ${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: RejectedException) {
                AppLog.e(TAG, "camera rejected the connection: ${e.message}")
                prefs.setPaired(address, false)
                closeClient()
                resetSessionState()
                update { it.copy(phase = Phase.FAILED, message = "Kamera odrzuciła połączenie", verifyCode = null) }
                return
            } catch (e: Exception) {
                AppLog.w(TAG, "session ended: ${e.javaClass.simpleName}: ${e.message}")
            }
            closeClient()
            resetSessionState()
            attempt++
            val backoff = when {
                attempt <= 1 -> 1_000L
                attempt <= 3 -> 3_000L
                attempt <= 6 -> 10_000L
                else -> 30_000L
            }
            update { it.copy(phase = Phase.RECONNECTING, message = "Próba $attempt za ${backoff / 1000}s", verifyCode = null) }
            delay(backoff)
        }
    }

    private suspend fun runSession(address: String, autoConnect: Boolean, onEstablished: () -> Unit) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        handshakeFrames = Channel(Channel.UNLIMITED)
        reassembler.reset()
        val c = GattClient(context, device, ::onNotify, ::onLinkLost)
        client = c

        update {
            it.copy(
                phase = if (autoConnect) Phase.RECONNECTING else Phase.CONNECTING,
                message = if (autoConnect) "Czekam na kamerę w zasięgu" else null,
            )
        }
        if (autoConnect) c.connect(true) else withTimeout(25_000) { c.connect(false) }
        update { it.copy(phase = Phase.CONNECTING, message = "Link BLE nawiązany") }
        delay(400)
        try {
            c.requestMtu(512)
        } catch (e: TimeoutCancellationException) {
            AppLog.w(TAG, "MTU request timed out, continuing with mtu=${c.mtu}")
        }
        c.discover()
        c.enableNotifications()
        AppLog.i(TAG, "GATT ready, mtu=${c.mtu}, write props=0x%02X".format(c.writeChar!!.properties))

        handshake(address)
        prefs.setPaired(address, true)
        onEstablished()
        update { it.copy(phase = Phase.CONNECTED, message = null, verifyCode = null) }

        queryVersion()
        subscribeStatus()
        startWatchdog()

        val st = c.disconnected.await()
        throw IOException("link lost (status=$st)")
    }

    /** connect_logic_protocol_connect() */
    private suspend fun handshake(address: String) {
        val paired = prefs.isPaired(address)
        val verifyMode = if (paired) Commands.VERIFY_MODE_NONE else Commands.VERIFY_MODE_REQUIRED
        val code = Random.nextInt(10000)
        val myId = prefs.deviceId
        update {
            it.copy(
                phase = if (paired) Phase.HANDSHAKE else Phase.WAITING_APPROVAL,
                verifyCode = code,
                message = if (paired) null else "Zaakceptuj połączenie na ekranie kamery",
            )
        }
        AppLog.i(TAG, "handshake: device_id=0x%08X mac=%s verify_mode=%d verify_data=%d".format(
            myId, prefs.mac.toHex(":"), verifyMode, code))

        val (set, id) = Commands.CONNECTION
        sendCommand(set, id, CmdType.CMD_WAIT_RESULT,
            Commands.connectionRequest(myId, prefs.mac, verifyMode, code), awaitReply = false)

        val timeout = if (paired) 30_000L else 90_000L
        try {
            withTimeout(timeout) {
                while (true) {
                    val f = handshakeFrames.receive()
                    if (f.isResponse) {
                        val r = Commands.parseConnectionResponse(f.payload)
                        AppLog.i(TAG, "connection ACK: ret_code=${r?.retCode} camera device_id=${r?.deviceId?.let { "0x%X".format(it) }}")
                        if (r != null && r.retCode != 0) {
                            prefs.setPaired(address, false)
                            throw IOException("camera answered ret_code=${r.retCode}")
                        }
                        if (!paired) update { it.copy(phase = Phase.WAITING_APPROVAL) }
                        continue
                    }
                    val req = Commands.parseConnectionRequest(f.payload)
                    if (req == null) {
                        AppLog.w(TAG, "unparseable connection request from camera")
                        continue
                    }
                    AppLog.i(TAG, "camera connection request: device_id=0x%X verify_mode=%d verify_data=%d".format(
                        req.deviceId, req.verifyMode, req.verifyData))
                    update { it.copy(cameraDeviceId = req.deviceId) }
                    if (req.verifyMode != Commands.VERIFY_MODE_RESULT) {
                        AppLog.w(TAG, "unexpected verify_mode ${req.verifyMode}, waiting further")
                        continue
                    }
                    if (req.verifyData != 0) throw RejectedException("verify_data=${req.verifyData}")
                    // Reply with the camera's own SEQ, as a response frame.
                    val ack = Frame(CmdType.ACK_NO_RESPONSE, f.seq, set, id, Commands.connectionResponse(myId))
                    writeFrame(ack, noResponse = true)
                    AppLog.i(TAG, "protocol connection established")
                    return@withTimeout
                }
            }
        } catch (e: TimeoutCancellationException) {
            if (paired) {
                // Camera may have lost its pairing record; ask for confirmation next time.
                prefs.setPaired(address, false)
            }
            throw IOException("handshake timeout (${timeout / 1000}s)")
        }
    }

    private suspend fun queryVersion() {
        val (set, id) = Commands.VERSION
        val r = sendCommand(set, id, CmdType.CMD_WAIT_RESULT, ByteArray(0))
        val v = r?.let { Commands.parseVersion(it.payload) }
        if (v != null) {
            AppLog.i(TAG, "version: ack=${v.ack} product='${v.productId}' sdk='${v.sdkVersion}'")
            update { it.copy(version = "${v.productId} ${v.sdkVersion}".trim()) }
        } else {
            AppLog.w(TAG, "version query: no/invalid response")
        }
    }

    private suspend fun subscribeStatus() {
        val (set, id) = Commands.STATUS_SUBSCRIBE
        sendCommand(set, id, CmdType.CMD_NO_RESPONSE, Commands.statusSubscribe())
        AppLog.i(TAG, "subscribed to status push (mode 3, 2 Hz)")
    }

    // ---------------------------------------------------------------- I/O

    private suspend fun sendCommand(
        cmdSet: Int,
        cmdId: Int,
        cmdType: Int,
        payload: ByteArray,
        timeoutMs: Long = 5000,
        awaitReply: Boolean = cmdType and 0x1F != 0,
        quiet: Boolean = false,
    ): Frame? {
        var s = seq.incrementAndGet() and 0xFFFF
        if (s == 0) s = seq.incrementAndGet() and 0xFFFF
        val f = Frame(cmdType, s, cmdSet, cmdId, payload)
        val d = if (awaitReply) CompletableDeferred<Frame>().also { pending[s] = it } else null
        try {
            writeFrame(f, noResponse = cmdType and 0x1F == 0, quiet = quiet)
        } catch (e: Exception) {
            pending.remove(s)
            throw e
        }
        if (d == null) return null
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        if (r == null) {
            pending.remove(s)
            AppLog.w("RX", "timeout waiting for ACK of ${f.key} seq=$s")
        }
        return r
    }

    private suspend fun writeFrame(f: Frame, noResponse: Boolean, quiet: Boolean = false) {
        val c = client ?: throw IOException("not connected")
        val bytes = f.encode()
        if (quiet) AppLog.v("TX", "$f [${bytes.toHex()}]") else AppLog.i("TX", "$f [${bytes.toHex()}]")
        c.write(bytes, noResponse)
    }

    private fun onNotify(data: ByteArray) {
        AppLog.v("RX", "notify ${data.size}B")
        reassembler.feed(data)
    }

    private fun onFrame(f: Frame, raw: ByteArray) {
        val isStatus = !f.isResponse && f.cmdSet == 0x1D && (f.cmdId == 0x02 || f.cmdId == 0x06)
        if (!isStatus) AppLog.i("RX", "$f [${raw.toHex()}]") else AppLog.v("RX", "$f [${raw.toHex()}]")

        if (f.cmdSet == 0x00 && f.cmdId == 0x19) {
            handshakeFrames.trySend(f)
            return
        }
        if (f.isResponse) {
            val d = pending.remove(f.seq)
            if (d != null) d.complete(f) else AppLog.v("RX", "ACK without pending request: $f")
            return
        }
        when {
            f.cmdSet == 0x1D && f.cmdId == 0x02 -> onStatus(f.payload, raw)
            f.cmdSet == 0x1D && f.cmdId == 0x06 -> onNewStatus(f.payload)
            else -> AppLog.w("RX", "unhandled command from camera: $f${if (f.wantsReply) " (wants reply)" else ""}")
        }
    }

    private fun onStatus(p: ByteArray, raw: ByteArray) {
        val s = CameraStatus.parse(p) ?: run {
            AppLog.w("RX", "status push too short: ${p.size}B")
            return
        }
        val prev = _status.value
        _status.value = s
        lastStatusAt = System.currentTimeMillis()
        // The camera may push a few stale power_mode=0 frames while falling asleep (DJI FAQ).
        val falling = System.currentTimeMillis() - sleepAckAt < 3000
        if (!falling) setAsleep(s.powerMode == 3, "power_mode=${s.powerMode}")
        if (prev != null && prev.isRecording != s.isRecording) {
            haptics.recordChanged(s.isRecording)
            if (s.isRecording) _notice.value = null
        }
        if (startAfterWakeUntil != 0L && s.powerMode != 3 && !falling) {
            val valid = System.currentTimeMillis() < startAfterWakeUntil
            startAfterWakeUntil = 0
            _busy.value = false
            if (valid && !s.isRecording) {
                AppLog.i(TAG, "camera awake, starting the recording requested while asleep")
                sendRecord(start = true)
            }
        }
        val key = "mode=${Commands.modeName(s.cameraMode)} status=${s.statusName} bat=${s.batteryPercent}% " +
            "power=${s.powerMode} temp=${s.tempOver} user=${s.userMode} res=${CameraStatus.resolutionName(s.videoResolution)} " +
            "fps=${CameraStatus.fpsName(s.fpsIdx)} eis=${s.eisMode} next=${s.modeNextFlag}"
        val fullKey = "$key ratio=${s.photoRatio} cd=${s.countdownSec} tl=${s.timelapseInterval}/${s.timelapseDurationSec} " +
            "photos=${s.remainPhotoNum} loop=${s.loopRecordSec} pcd=${s.photoCountdownMs}"
        if (fullKey != lastStatusKey) {
            if (prev == null) AppLog.i("RX", "first status push (${p.size}B): [${raw.toHex()}]")
            AppLog.i(TAG, "status: $fullKey free=${s.remainCapacityMb}MB remain=${s.remainTimeSec}s rec=${s.recordTimeSec}s")
            lastStatusKey = fullKey
        }
    }

    private fun onNewStatus(p: ByteArray) {
        val m = Commands.parseNewStatus(p) ?: return
        if (m != _modeName.value) AppLog.i(TAG, "mode name: '${m.name}' params: '${m.param}'")
        _modeName.value = m
    }

    private fun onLinkLost(status: Int) {
        AppLog.w(TAG, "BLE link lost, status=$status")
        handshakeFrames.close(IOException("link lost"))
        failPending()
    }

    private fun failPending() {
        val err = IOException("link lost")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    private fun closeClient() {
        client?.close()
        client = null
    }

    private fun resetSessionState() {
        watchdogJob?.cancel()
        watchdogJob = null
        _asleep.value = false
        failPending()
        _status.value = null
        _modeName.value = null
        // A wake-then-record survives the reconnect that waking the camera causes.
        _busy.value = startAfterWakeUntil > System.currentTimeMillis()
        lastStatusKey = null
    }
}
