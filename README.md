# Card Printer (Android)

Tap an NFC card -> the app reads its UID, makes a Code 128 barcode, and prints it
on a Niimbot B1 over Bluetooth. Everything runs on the phone. Built for logging in
at work: anyone taps their card and gets a scannable sticker of their ID.

The UID is printed as uppercase hex with no separators, e.g. `0452AABA177281`.

## Install (no PC needed)

Download the newest build on the phone: **[CardPrinter.apk](../../releases/latest/download/CardPrinter.apk)**
and open it. GitHub rebuilds it automatically on every push to `main`.

If an older copy built on a PC is installed, uninstall that first — it was signed with a
different key, so Android won't install over it. Builds from here all share one key, so
later updates install over each other.

## Build it yourself (Android Studio)

1. Open this folder in Android Studio ("Open", pick the `cardprinter` folder).
2. Let it finish the Gradle sync (it downloads the Gradle wrapper + SDK bits itself).
3. Plug the phone in with USB debugging on, pick it in the device dropdown, hit Run.

Command line alternative, if you prefer: run `gradle wrapper` once in this folder,
then `./gradlew installDebug` with the phone connected.

## Before first use

1. **Pair the B1**: Android Settings -> Bluetooth -> pair the printer (it shows up as
   `B1-xxxx`). Turn the printer on first.
2. Open the app, grant the Bluetooth permission, tap **Printer**, choose the B1.
3. Tap a card. With "Auto-print on scan" on, the label prints immediately.

## Controls

- **Printer**: pick / connect the paired B1. The choice is remembered.
- **Print**: reprint the last scanned card.
- **Auto-print on scan**: print automatically the moment a card is read.
- **Density -/+**: thermal darkness (1-5). Raise it if bars look faint.
- **After the code**: what the scanner "types" after the ID - None, **Tab** (jumps to the
  next field) or **Enter** (the default - same as the RFID card reader). This is encoded into the barcode itself,
  so it works even on a scanner you can't configure. The printed number still shows only the
  ID; the Tab/Enter is invisible but in the bars.
- The log at the bottom shows each step and any errors (handy if printing misbehaves).

## Notes / known unknowns

- **Printing talks to the B1 over classic Bluetooth serial (SPP)** — the same way the
  open-source `niimprint` tool drives the B1. If your B1 refuses to pair or connect this
  way from the phone (some units expose only Bluetooth LE), the log will show a connect
  error — tell me and I'll switch the print path to BLE.
- Label size is set for the 50 x 30 mm labels the B1 ships with (384 x 240 dots). Change
  `LABEL_W` / `LABEL_H` in `MainActivity.java` for other labels.
- To print a different ID format (decimal instead of hex), that's a small change in
  `onTagDiscovered` — ask and I'll add a toggle.

Barcode engine and the Niimbot protocol are self-contained; no third-party libraries.
