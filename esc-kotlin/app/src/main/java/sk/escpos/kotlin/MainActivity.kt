package sk.escpos.kotlin

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val actionUsbPermission = "sk.escpos.kotlin.USB_PERMISSION"

    private lateinit var usbManager: UsbManager
    private lateinit var baudInput: EditText
    private lateinit var textInput: EditText
    private lateinit var log: TextView

    // All USB I/O runs on one background thread, in order.
    private val io = Executors.newSingleThreadExecutor()

    // The port stays open between prints: closing a PL2303 port purges its
    // FIFO, which drops bytes the printer has not accepted yet (RTS/CTS).
    private var port: UsbSerialPort? = null
    private var portBaud = 0

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != actionUsbPermission) return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                log("Povolenie udelené")
                printReceipt()
            } else {
                log("Povolenie zamietnuté")
            }
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            io.execute { closePort() }
            log("Prevodník odpojený")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        baudInput = EditText(this).apply {
            hint = "Baud rate"
            setText("115200")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        textInput = EditText(this).apply {
            hint = "Text na tlač"
            setText("Ahoj z Kotlinu!\nESC/POS cez PL2303")
        }
        log = TextView(this)
        val button = Button(this).apply {
            text = "Tlačiť a odrezať"
            setOnClickListener { printReceipt() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply { text = "Baud rate:" })
            addView(baudInput)
            addView(TextView(context).apply { text = "Text:" })
            addView(textInput)
            addView(button)
            addView(log)
        }
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true // keep content below the status bar
            addView(content)
        })

        registerLocalReceiver(permissionReceiver, IntentFilter(actionUsbPermission))
        registerLocalReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(detachReceiver)
        io.execute { closePort() }
        io.shutdown()
        super.onDestroy()
    }

    private fun registerLocalReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    private fun findDriver(): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(actionUsbPermission).setPackage(packageName)
        val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    private fun printReceipt() {
        val driver = findDriver()
        if (driver == null) {
            log("Tlačiareň (USB-serial) nenájdená")
            return
        }
        if (!usbManager.hasPermission(driver.device)) {
            log("Žiadam o povolenie USB…")
            requestPermission(driver.device)
            return
        }
        val baud = baudInput.text.toString().toIntOrNull() ?: 115200
        val data = buildReceipt(textInput.text.toString())

        io.execute {
            try {
                val p = openPort(driver, baud)
                writeWithDsr(p, data)
                runOnUiThread { log("Odoslané (${data.size} B, $baud baud, signály: ${lines(p)})") }
            } catch (e: Exception) {
                closePort()
                runOnUiThread { log("Chyba: ${e.message}") }
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

    /** Modem lines coming from the printer (CTS/DSR = printer ready). */
    private fun lines(p: UsbSerialPort): String =
        try { p.controlLines.joinToString() } catch (e: Exception) { "? (${e.message})" }

    private fun closePort() {
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    /** ESC/POS commands: init, centered big title, body text, feed and cut. */
    private fun buildReceipt(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun cmd(vararg b: Int) = b.forEach { out.write(it) }
        fun txt(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

        cmd(0x1B, 0x40)             // ESC @   – initialize printer
        cmd(0x1B, 0x61, 1)          // ESC a 1 – center
        cmd(0x1D, 0x21, 0x11)       // GS ! 17 – double width + height
        txt("ESC/POS TEST\n")
        cmd(0x1D, 0x21, 0x00)       // GS ! 0  – normal size
        cmd(0x1B, 0x61, 0)          // ESC a 0 – left
        txt(stripDiacritics(text) + "\n")
        cmd(0x1B, 0x64, 8)          // ESC d 8 – feed 8 lines, else paper jams in the presenter
        cmd(0x1D, 0x56, 0x00)       // GS V 0  – full cut (use 0x01 for partial)
        return out.toByteArray()
    }

    /** Printer's default code page is not UTF-8, so print plain ASCII. */
    private fun stripDiacritics(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")

    private fun log(msg: String) {
        log.append(msg + "\n")
    }
}
