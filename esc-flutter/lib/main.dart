// Minimal example: print text on an ESC/POS receipt printer and cut the paper.
//
// Connection:  phone --USB OTG--> PL2303 USB-serial adapter --RS-232--> printer
//
// Split of work:
//  - Dart (this file): UI and building the ESC/POS bytes (buildReceipt).
//  - Kotlin (android/app/src/main/kotlin/.../MainActivity.kt): USB permission, serial
//    port setup and sending the bytes. Dart talks to it over the "escpos/usb" MethodChannel.

import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const MaterialApp(title: 'ESC/POS Flutter', home: PrintPage()));

/// Single screen: baud rate, text, "print and cut" button and a log.
class PrintPage extends StatefulWidget {
  const PrintPage({super.key});

  @override
  State<PrintPage> createState() => _PrintPageState();
}

class _PrintPageState extends State<PrintPage> {
  // Must match the printer's setting (see its self-test printout: "115,200 8 N 1").
  final _baud = TextEditingController(text: '115200');
  final _text = TextEditingController(text: 'Ahoj z Flutteru!\nESC/POS cez PL2303');
  final _log = <String>[];

  /// True while a print is running; disables the button so jobs don't overlap.
  bool _busy = false;

  /// Native bridge implemented in android/app/src/main/kotlin/.../MainActivity.kt.
  /// Methods: `'list'` -> `List<String>`, `'write' {data, baud}` -> `int` (bytes sent).
  static const _usb = MethodChannel('escpos/usb');

  void _addLog(String msg) => setState(() => _log.add(msg));

  /// Button handler: find adapter -> build bytes -> send.
  Future<void> _print() async {
    setState(() => _busy = true);
    try {
      // 1. Is an adapter plugged in? (purely informational, 'write' checks again)
      final devices = await _usb.invokeListMethod<String>('list') ?? [];
      if (devices.isEmpty) {
        _addLog('Tlačiareň (USB-serial) nenájdená');
        return;
      }
      _addLog('Zariadenie: ${devices.first}');

      // 2. Build the receipt.
      final baud = int.tryParse(_baud.text) ?? 115200;
      final data = buildReceipt(_text.text);

      // 3. Send. The native side asks for USB permission if needed (the user sees a
      //    system dialog), so this call can take a while.
      final sent = await _usb.invokeMethod<int>('write', {'data': data, 'baud': baud});
      _addLog('Odoslané ($sent B, $baud baud)');
    } on PlatformException catch (e) {
      // Errors from Kotlin (result.error(...)) arrive here: NO_DEVICE, NO_PERMISSION, IO.
      _addLog('Chyba: ${e.message}');
    } finally {
      setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('ESC/POS Flutter')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _baud,
              decoration: const InputDecoration(labelText: 'Baud rate'),
              keyboardType: TextInputType.number,
            ),
            TextField(
              controller: _text,
              decoration: const InputDecoration(labelText: 'Text na tlač'),
              maxLines: null, // multi-line
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _busy ? null : _print,
              child: const Text('Tlačiť a odrezať'),
            ),
            const SizedBox(height: 16),
            Expanded(child: ListView(children: _log.map(Text.new).toList())),
          ],
        ),
      ),
    );
  }
}

/// Builds the receipt as raw ESC/POS bytes.
///
/// ESC/POS is a simple byte stream: plain text is printed as is, commands start with
/// ESC (0x1B) or GS (0x1D) followed by a letter and parameters. Text is printed when
/// a line feed (\n) arrives.
Uint8List buildReceipt(String text) {
  final b = BytesBuilder();
  void cmd(List<int> bytes) => b.add(bytes);
  void txt(String s) => b.add(toAscii(s).codeUnits);

  cmd([0x1B, 0x40]); //       ESC @   – initialize printer (reset formatting)
  cmd([0x1B, 0x61, 1]); //    ESC a 1 – center (0 = left, 2 = right)
  cmd([0x1D, 0x21, 0x11]); // GS ! n  – character size: high nibble = width, low = height
  //                                    0x11 = double width + double height
  txt('ESC/POS TEST\n');
  cmd([0x1D, 0x21, 0x00]); // GS ! 0  – back to normal size
  cmd([0x1B, 0x61, 0]); //    ESC a 0 – left align
  txt('$text\n');
  cmd([0x1B, 0x64, 8]); //    ESC d n – feed n lines; the print head sits above the cutter,
  //                                    and with a presenter fewer than 8 lines leave the
  //                                    receipt stuck in the exit slot
  cmd([0x1D, 0x56, 0x00]); // GS V 0  – full cut (0x01 = partial cut)
  return b.toBytes();
}

/// The printer uses a single-byte code page (not UTF-8), so non-ASCII letters would print
/// as garbage. Simplest fix for a demo: "č" -> "c", anything else non-ASCII -> "?".
/// For real diacritics select a code page with ESC t n and encode the text in it.
String toAscii(String s) {
  const from = 'áäčďéěíĺľňóôŕřšťúůýžÁÄČĎÉĚÍĹĽŇÓÔŔŘŠŤÚŮÝŽ';
  const to = 'aacdeeillnoorrstuuyzAACDEEILLNOORRSTUUYZ';
  final out = StringBuffer();
  for (final ch in s.split('')) {
    final i = from.indexOf(ch);
    final c = i >= 0 ? to[i] : ch;
    out.write(c.codeUnitAt(0) < 128 ? c : '?');
  }
  return out.toString();
}
