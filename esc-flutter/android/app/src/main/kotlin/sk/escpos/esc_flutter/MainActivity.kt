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
 * Native bridge "escpos/usb":
 *  - list()                       -> List<String> of found USB-serial devices
 *  - write({data: bytes, baud})   -> number of bytes sent (asks for USB permission if needed)
 */
class MainActivity : FlutterActivity() {

    private val actionUsbPermission = "sk.escpos.esc_flutter.USB_PERMISSION"
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    // All USB I/O runs on one background thread, in order.
    private val io = Executors.newSingleThreadExecutor()

    // The port stays open between prints: closing a PL2303 port purges its
    // FIFO, which drops bytes the printer has not accepted yet (RTS/CTS).
    private var port: UsbSerialPort? = null
    private var portBaud = 0

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "escpos/usb")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "list" -> result.success(
                        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).map {
                            val d = it.device
                            "${d.productName ?: d.deviceName} " +
                                "(VID %04X, PID %04X, %s)".format(
                                    d.vendorId, d.productId, it.javaClass.simpleName)
                        })
                    "write" -> write(
                        call.argument<ByteArray>("data")!!,
                        call.argument<Int>("baud") ?: 115200,
                        result)
                    else -> result.notImplemented()
                }
            }
    }

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
                    closePort()
                    runOnUiThread { result.error("IO", e.message, null) }
                }
            }
        }
    }

    /** Opens the port once (or reopens on baud change) and keeps it open. */
    private fun openPort(driver: UsbSerialDriver, baud: Int): UsbSerialPort {
        port?.let { if (portBaud == baud) return it }
        closePort()
        val connection = usbManager.openDevice(driver.device)
            ?: throw IllegalStateException("Nepodarilo sa otvoriť zariadenie")
        val p = driver.ports[0]
        p.open(connection)
        p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // Printer is "8 N 1 H/W" but signals ready on DSR (CTS stays low), and the
        // PL2303 can't do DTR/DSR in hardware -> no HW flow, DSR checked in writeWithDsr()
        p.setFlowControl(UsbSerialPort.FlowControl.NONE)
        p.dtr = true
        p.rts = true
        Thread.sleep(100) // let the line settle so the first byte is not garbled
        port = p
        portBaud = baud
        return p
    }

    /** Software DTR/DSR flow control: send small chunks only while the printer has DSR on. */
    private fun writeWithDsr(p: UsbSerialPort, data: ByteArray) {
        for (start in data.indices step 64) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!p.dsr) {
                if (System.currentTimeMillis() > deadline)
                    throw IllegalStateException("Tlačiareň nie je pripravená (DSR vypnuté) – papier/kryt?")
                Thread.sleep(10)
            }
            p.write(data.copyOfRange(start, minOf(start + 64, data.size)), 3_000)
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

    private fun withPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) return onResult(true)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                unregisterReceiver(this)
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(actionUsbPermission).setPackage(packageName)
        usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
    }
}
