package sk.escpos.esc_flutter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/**
 * Native USB-serial bridge for the Flutter UI.
 *
 * Flutter has no USB access of its own, so Dart calls into this Kotlin code over a
 * MethodChannel named "escpos/usb":
 *
 *  - list()                     -> List<String>, description of each USB-serial adapter found
 *  - write({data: bytes, baud}) -> number of bytes sent; asks for USB permission if needed
 *
 * Dart builds the ESC/POS bytes (lib/main.dart); this side only moves them to the printer.
 *
 * Why not a pub.dev package: the popular `usb_serial` package uses a driver that doesn't
 * support the newer PL2303GC/GS chips (PID 0x23A3). usb-serial-for-android does.
 */
class MainActivity : FlutterActivity() {

    /** Our own broadcast action; Android sends it back with the user's permission answer. */
    private val actionUsbPermission = "sk.escpos.esc_flutter.USB_PERMISSION"
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    /**
     * USB I/O blocks, so it must not run on the UI thread (MethodChannel calls arrive there).
     * A single-thread executor also keeps print jobs in order.
     */
    private val io = Executors.newSingleThreadExecutor()

    /**
     * The port stays open between prints (it is touched only from [io]).
     *
     * Why: usb-serial-for-android purges the PL2303's internal FIFO on close().
     * Bytes the printer has not taken yet would be lost — typically the end of
     * the receipt including the cut command.
     */
    private var port: UsbSerialPort? = null
    private var portBaud = 0

    /** Registers the "escpos/usb" channel handler when the Flutter engine starts. */
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "escpos/usb")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "list" -> result.success(
                        // The default prober knows PL2303 (incl. GC/GS), CH340, FTDI and CP210x
                        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).map {
                            val d = it.device
                            "${d.productName ?: d.deviceName} " +
                                "(VID %04X, PID %04X, %s)".format(
                                    d.vendorId, d.productId, it.javaClass.simpleName)
                        })
                    "write" -> write(
                        call.argument<ByteArray>("data")!!, // Dart Uint8List arrives as ByteArray
                        call.argument<Int>("baud") ?: 115200,
                        result)
                    else -> result.notImplemented()
                }
            }
    }

    /**
     * Handles "write": finds the adapter, gets permission, then sends in the background.
     * [result] must be answered exactly once and on the UI thread.
     */
    private fun write(data: ByteArray, baud: Int, result: MethodChannel.Result) {
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
            ?: return result.error("NO_DEVICE", "Tlačiareň (USB-serial) nenájdená", null)

        withPermission(driver.device) { granted ->
            if (!granted) {
                result.error("NO_PERMISSION", "Povolenie USB zamietnuté", null)
                return@withPermission
            }
            io.execute {
                try {
                    writeWithDsr(openPort(driver, baud), data)
                    runOnUiThread { result.success(data.size) }
                } catch (e: Exception) {
                    closePort() // start clean next time
                    runOnUiThread { result.error("IO", e.message, null) }
                }
            }
        }
    }

    /**
     * Returns the open port, opening and configuring it on first use
     * (or when the baud rate changed).
     */
    private fun openPort(driver: UsbSerialDriver, baud: Int): UsbSerialPort {
        port?.let { if (portBaud == baud) return it }
        closePort()

        val connection = usbManager.openDevice(driver.device)
            ?: throw IllegalStateException("Nepodarilo sa otvoriť zariadenie")
        val p = driver.ports[0] // PL2303 has a single port
        p.open(connection)

        // Same as the printer's self-test: "115,200 8 N 1"
        p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        // The printer says "H/W" flow control, but it signals "ready" on DSR, while CTS
        // stays low. With RTS/CTS on, the adapter would wait for CTS forever and send nothing.
        // The PL2303 can't do DTR/DSR flow control in hardware, so we turn hardware flow
        // off and check DSR ourselves in writeWithDsr().
        p.setFlowControl(UsbSerialPort.FlowControl.NONE)

        // Tell the printer that we (the host) are ready.
        p.dtr = true
        p.rts = true

        // Opening the port and switching the baud rate can glitch the line; without a pause
        // the first byte (ESC of "ESC @") got garbled and the printer printed "@".
        Thread.sleep(100)

        port = p
        portBaud = baud
        return p
    }

    /**
     * Software DTR/DSR flow control: sends small chunks and before each one waits until
     * the printer reports ready (DSR on). DSR goes off when the printer's buffer is full or
     * it is in an error state (no paper, cover open), so we fail with a clear message
     * after 5 s instead of hanging.
     */
    private fun writeWithDsr(p: UsbSerialPort, data: ByteArray) {
        val chunk = 64
        for (start in data.indices step chunk) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!p.dsr) {
                if (System.currentTimeMillis() > deadline)
                    throw IllegalStateException("Tlačiareň nie je pripravená (DSR vypnuté) – papier/kryt?")
                Thread.sleep(10)
            }
            p.write(data.copyOfRange(start, minOf(start + chunk, data.size)), 3_000)
        }
    }

    private fun closePort() {
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    override fun onDestroy() {
        io.execute { closePort() }
        io.shutdown()
        super.onDestroy()
    }

    /**
     * Calls [onResult] with true if we may use [device]. If permission is missing, shows the
     * system "Allow access to USB device?" dialog and waits for the answer. Not needed when
     * the app was opened by plugging in the adapter (USB_DEVICE_ATTACHED filter in the manifest).
     */
    private fun withPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) return onResult(true)

        // One-shot receiver for the answer.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                unregisterReceiver(this)
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter(actionUsbPermission)
        // Android 13+ requires saying whether a runtime receiver is exported; ours is app-internal.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        // Android 12+: the PendingIntent must declare mutability; the system adds extras
        // to it, so it must be MUTABLE. Android 14+: a mutable PendingIntent must be explicit,
        // hence setPackage().
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(actionUsbPermission).setPackage(packageName)
        usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
    }
}
