import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const MaterialApp(title: 'ESC/POS Flutter', home: PrintPage()));

class PrintPage extends StatefulWidget {
  const PrintPage({super.key});

  @override
  State<PrintPage> createState() => _PrintPageState();
}

class _PrintPageState extends State<PrintPage> {
  final _baud = TextEditingController(text: '115200');
  final _text = TextEditingController(text: 'Ahoj z Flutteru!\nESC/POS cez PL2303');
  final _log = <String>[];
  bool _busy = false;

  /// Native bridge implemented in android/.../MainActivity.kt
  static const _usb = MethodChannel('escpos/usb');

  void _addLog(String msg) => setState(() => _log.add(msg));

  Future<void> _print() async {
    setState(() => _busy = true);
    try {
      final devices = await _usb.invokeListMethod<String>('list') ?? [];
      if (devices.isEmpty) {
        _addLog('Tlačiareň (USB-serial) nenájdená');
        return;
      }
      _addLog('Zariadenie: ${devices.first}');

      final baud = int.tryParse(_baud.text) ?? 115200;
      final data = buildReceipt(_text.text);
      // Native side asks for USB permission if needed, then writes
      final sent = await _usb.invokeMethod<int>('write', {'data': data, 'baud': baud});
      _addLog('Odoslané ($sent B, $baud baud)');
    } on PlatformException catch (e) {
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
              maxLines: null,
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

/// ESC/POS commands: init, centered big title, body text, feed and cut.
Uint8List buildReceipt(String text) {
  final b = BytesBuilder();
  void cmd(List<int> bytes) => b.add(bytes);
  void txt(String s) => b.add(toAscii(s).codeUnits);

  cmd([0x1B, 0x40]); //       ESC @   – initialize printer
  cmd([0x1B, 0x61, 1]); //    ESC a 1 – center
  cmd([0x1D, 0x21, 0x11]); // GS ! 17 – double width + height
  txt('ESC/POS TEST\n');
  cmd([0x1D, 0x21, 0x00]); // GS ! 0  – normal size
  cmd([0x1B, 0x61, 0]); //    ESC a 0 – left
  txt('$text\n');
  cmd([0x1B, 0x64, 8]); //    ESC d 8 – feed 8 lines, else paper jams in the presenter
  cmd([0x1D, 0x56, 0x00]); // GS V 0  – full cut (use 0x01 for partial)
  return b.toBytes();
}

/// Printer's default code page is not UTF-8, so print plain ASCII.
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
