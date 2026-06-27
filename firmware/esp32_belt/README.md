# SixthSense Belt Firmware (ESP32 + NimBLE)

The belt is a **dumb actuator**: it accepts a 4-byte BLE packet and drives three
vibration motors. No AI, no navigation, no Wi-Fi.

## Arduino IDE setup

1. Install the **Arduino IDE** (2.x).
2. Add the **ESP32 board package**:
   - Arduino IDE → **Settings** → *Additional boards manager URLs*:
     `https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`
   - **Tools → Board → Boards Manager** → search **esp32** → install *"esp32 by Espressif Systems"*.
3. Install the BLE library:
   - **Tools → Manage Libraries** → search **NimBLE-Arduino** → install (by h2zero).
4. Select your board under **Tools → Board → ESP32 Arduino** (e.g. *ESP32 Dev Module*),
   pick the serial **Port**, and set **Upload Speed** to 921600 (or 115200 if it fails).

> `analogWrite()` is used for PWM so the sketch works on both ESP32 Arduino core
> 2.x and 3.x. If you prefer explicit LEDC control, swap to `ledcSetup`/`ledcWrite`
> (core 2.x) or `ledcAttach`/`ledcWrite` (core 3.x).

## Wiring table (3 motors via ULN2803A)

| Signal | ESP32 | ULN2803A | Motor / Power |
|---|---|---|---|
| Left motor PWM | GPIO 25 | IN 1 (pin 1) | — |
| Center motor PWM | GPIO 26 | IN 2 (pin 2) | — |
| Right motor PWM | GPIO 27 | IN 3 (pin 3) | — |
| Left motor drive | — | OUT 1 (pin 18) | Left motor (−) |
| Center motor drive | — | OUT 2 (pin 17) | Center motor (−) |
| Right motor drive | — | OUT 3 (pin 16) | Right motor (−) |
| Motor V+ | — | COM (pin 10) | All motors (+) and supply + |
| Ground | GND | GND (pin 9) | Supply − |

Pin numbers above are the standard ULN2803A DIP pinout (inputs 1–8 on pins 1–8,
outputs on pins 18–11, COM = pin 10, GND = pin 9).

### ULN2803A notes
- **Never** connect a vibration motor straight to a GPIO pin. GPIOs source only a
  few mA and the motor's inductive kickback can destroy the pin.
- The ULN2803A is an open-collector sink array: the motor's **+** goes to V+, its
  **−** goes to a ULN2803A output, and the ESP32 GPIO drives the matching input.
- Tie **COM (pin 10) to motor V+** so the internal clamp diodes have a return path
  for inductive spikes.
- **Common ground is mandatory**: ESP32 GND, ULN2803A GND, and motor-supply GND
  must all be tied together.
- Many blue "vibration motor modules" already include a driver transistor — if so,
  you can drive the module's `IN` directly from a GPIO. Verify before wiring.
- Power motors from a USB power bank / battery rail, not the ESP32 3.3V pin, unless
  the current draw is small and tested.

## Test packet examples

`[m0, m1, m2, pattern]`

| Packet (hex) | Meaning |
|---|---|
| `C8 00 00 00` | strong **left** buzz, steady |
| `00 B4 00 02` | center **double pulse** — curb/step ahead |
| `00 00 DC 00` | strong **right** buzz, steady |
| `1E 1E 1E 00` | low **all-clear** hum on all motors |
| `00 50 00 01` | low-confidence **caution pulse** (center) |

## Testing with nRF Connect (before the Android app)

1. Flash this sketch; open **Serial Monitor** at 115200 to see logs.
2. In **nRF Connect** (mobile), scan and find **`SixthSense-Belt`**; tap **Connect**.
3. Expand the service `6e400001-…` and find characteristic `6e400002-…`.
4. Tap the **write** (up-arrow) icon, choose **Byte Array**, and send e.g. `C8000000`.
5. Confirm the left motor buzzes; try `00B40002` for the center double-pulse.
6. Vary the first three bytes to confirm PWM intensity changes motor strength.

Firmware test ladder: blink → BLE advertise → appears in nRF Connect → write packet →
correct motor buzz → intensity scales → patterns work → Android app connects & writes.
