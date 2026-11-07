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

/**
 * Minimal example: print text on an ESC/POS receipt printer and cut the paper.
 *
 * Connection:  phone --USB OTG--> PL2303 USB-serial adapter --RS-232--> printer
 *
 * The flow is:
 *  1. find the USB-serial adapter          (findDriver)
 *  2. get the user's permission to use it  (requestPermission + permissionReceiver)
 *  3. open and configure the serial port   (openPort)
 *  4. build the ESC/POS bytes              (buildReceipt)
 *  5. send them, respecting printer "busy" (writeWithDsr)
 *
 * The whole app is this one file; the UI is built in code to keep it short.
 */
class MainActivity : Activity() {

    /** Our own broadcast action; Android sends it back with the user's permission answer. */
    private val actionUsbPermission = "sk.escpos.kotlin.USB_PERMISSION"

    private lateinit var usbManager: UsbManager
    private lateinit var baudInput: EditText
    private lateinit var textInput: EditText
    private lateinit var log: TextView

    /**
     * USB I/O blocks, so it must not run on the UI thread.
     * A single-thread executor also keeps print jobs in order when the button is tapped quickly.
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

    /** Receives the result of [requestPermission]; on success the print is retried. */
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

    /** Adapter unplugged -> drop the stale port; the next print opens a fresh one. */
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            io.execute { closePort() }
            log("Prevodník odpojený")
        }
    }

    // ---------------------------------------------------------------- lifecycle / UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Simple UI: baud rate, text, a button and a log.
        baudInput = EditText(this).apply {
            hint = "Baud rate"
            setText("115200") // must match the printer's setting (see its self-test printout)
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
            fitsSystemWindows = true // keep content below the status bar (edge-to-edge on Android 15+)
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

    /** Android 13+ requires saying whether a runtime receiver is exported; ours are app-internal. */
    private fun registerLocalReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    // ---------------------------------------------------------------- USB device + permission

    /**
     * Finds the first USB-serial adapter. The default prober knows PL2303 (incl. the newer
     * PL2303GC/GS chips), CH340, FTDI and CP210x by their USB vendor/product IDs.
     */
    private fun findDriver(): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()

    /**
     * Shows the system "Allow access to USB device?" dialog; the answer arrives in
     * [permissionReceiver]. Not needed when the app was opened by plugging in the adapter
     * (USB_DEVICE_ATTACHED filter in the manifest grants permission automatically).
     */
    private fun requestPermission(device: UsbDevice) {
        // Android 12+: the PendingIntent must declare mutability; the system adds extras
        // to it, so it must be MUTABLE. Android 14+: a mutable PendingIntent must be explicit,
        // hence setPackage().
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(actionUsbPermission).setPackage(packageName)
        val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    // ---------------------------------------------------------------- printing

    /** Button handler: checks device + permission on the UI thread, then sends in background. */
    private fun printReceipt() {
        val driver = findDriver()
        if (driver == null) {
            log("Tlačiareň (USB-serial) nenájdená")
            return
        }
        if (!usbManager.hasPermission(driver.device)) {
            log("Žiadam o povolenie USB…")
            requestPermission(driver.device) // printReceipt() is called again once granted
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
                closePort() // start clean next time
                runOnUiThread { log("Chyba: ${e.message}") }
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

    /** Current modem lines, for debugging (e.g. "RTS, DTR, DSR" = printer ready, CTS unused). */
    private fun lines(p: UsbSerialPort): String =
        try { p.controlLines.joinToString() } catch (e: Exception) { "? (${e.message})" }

    private fun closePort() {
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    // ---------------------------------------------------------------- ESC/POS

    /**
     * Builds the receipt as raw ESC/POS bytes.
     *
     * ESC/POS is a simple byte stream: plain text is printed as is, commands start with
     * ESC (0x1B) or GS (0x1D) followed by a letter and parameters. Text is printed when
     * a line feed (\n) arrives.
     */
    private fun buildReceipt(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun cmd(vararg b: Int) = b.forEach { out.write(it) }
        fun txt(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

        cmd(0x1B, 0x40)             // ESC @   – initialize printer (reset formatting)
        cmd(0x1B, 0x61, 1)          // ESC a 1 – center (0 = left, 2 = right)
        cmd(0x1D, 0x21, 0x11)       // GS ! n  – character size: high nibble = width, low = height
                                    //           0x11 = double width + double height
        txt("ESC/POS TEST\n")
        cmd(0x1D, 0x21, 0x00)       // GS ! 0  – back to normal size
        cmd(0x1B, 0x61, 0)          // ESC a 0 – left align
        txt(stripDiacritics(text) + "\n")
        cmd(0x1B, 0x64, 8)          // ESC d n – feed n lines; the print head sits above the
                                    //           cutter, and with a presenter fewer than 8 lines
                                    //           leave the receipt stuck in the exit slot
        cmd(0x1D, 0x56, 0x00)       // GS V 0  – full cut (0x01 = partial cut)
        return out.toByteArray()
    }

    /**
     * The printer uses a single-byte code page (not UTF-8), so non-ASCII letters would print
     * as garbage. Simplest fix for a demo: "č" -> "c". For real diacritics select a code page
     * with ESC t n and encode the text in that code page.
     */
    private fun stripDiacritics(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD) // "č" -> "c" + "ˇ"
            .replace(Regex("\\p{M}"), "")                              // drop the "ˇ"

    private fun log(msg: String) {
        log.append(msg + "\n")
    }
}
