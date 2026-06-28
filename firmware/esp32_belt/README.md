# SixthSense Belt Firmware (ESP32 + NimBLE) — 4-motor waist belt

The belt is a **dumb actuator**: it accepts a **5-byte** BLE packet and drives
**four** vibration motors arranged around the waist. No AI, no navigation, no Wi-Fi.

**Layout — the side where the obstacle is, is what buzzes:**

```
        front of waist
   [CENTER_L][CENTER_R]      <- both buzz for an obstacle STRAIGHT AHEAD
 [LEFT]                [RIGHT]
   ^ obstacle on the left      ^ obstacle on the right
```

Packet `[m0, m1, m2, m3, pattern]` (each intensity 0..255):
`m0 = LEFT`, `m1 = CENTER_L`, `m2 = CENTER_R`, `m3 = RIGHT`,
`pattern: 0 steady · 1 caution pulse · 2 double pulse (curb/step)`.
The phone sets `CENTER_L == CENTER_R`, so "ahead" lights both center motors.

## Arduino IDE setup

1. Install the **Arduino IDE** (2.x).
2. Add the **ESP32 board package** (Settings → *Additional boards manager URLs*):
   `https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`
   then **Tools → Board → Boards Manager** → install *"esp32 by Espressif Systems"* (core **3.x**).
3. **Tools → Manage Libraries** → install **NimBLE-Arduino** (by h2zero).
4. **Tools → Board → ESP32 Arduino → ESP32 Dev Module**, pick the **Port**, Upload Speed 921600
   (or 115200 if it fails).

> `analogWrite()` is used for PWM (works on ESP32 Arduino core 3.x). For explicit LEDC,
> swap to `ledcAttach`/`ledcWrite`.

## Hardware: "PWM Vibration Motor Switch Module, DC 5V" (driver-included)

These modules have an **onboard driver transistor** (3 pins: VCC / GND / SIG), so you
drive each one **directly from a GPIO** — **no ULN2803A / MOSFET needed**. PWM on SIG
sets intensity.

| Module | ESP32 GPIO (SIG) | VCC | GND |
|---|---|---|---|
| LEFT (m0) | GPIO 25 | 5V rail | common GND |
| CENTER_L (m1) | GPIO 26 | 5V rail | common GND |
| CENTER_R (m2) | GPIO 27 | 5V rail | common GND |
| RIGHT (m3) | GPIO 33 | 5V rail | common GND |

### Power & wiring notes
- **VCC = 5V**: power the modules from the ESP32 **5V/VIN** (USB) or a **USB power bank**
  for the wearable run. Four motors can pull a few hundred mA — give the rail headroom.
- **Common ground is mandatory**: every module GND ties to ESP32 GND.
- **GPIO logic is 3.3V**; these modules switch fine from 3.3V. If a specific module needs
  5V logic on SIG, add a level shifter on that line.
- Avoid ESP32 strapping pins (0, 2, 12, 15) and input-only pins (34–39) for the motor SIGs.

## Test packet examples

`[m0, m1, m2, m3, pattern]`

| Packet (hex) | Meaning |
|---|---|
| `C8 00 00 00 00` | strong **LEFT** buzz, steady |
| `00 C8 C8 00 00` | **AHEAD** — both center motors, steady |
| `00 00 00 C8 00` | strong **RIGHT** buzz, steady |
| `00 B4 B4 00 02` | center **double pulse** — curb/step ahead |
| `00 50 50 00 01` | low-confidence **caution pulse** (center) |

## Testing with nRF Connect (before the Android app)

1. Flash this sketch; open **Serial Monitor** at 115200 to see logs.
2. In **nRF Connect** (mobile), scan, find **`SixthSense-Belt`**, tap **Connect**.
3. Expand service `6e400001-…`, find characteristic `6e400002-…`.
4. Tap **write** (up-arrow), choose **Byte Array**, and send one of (5 bytes = 10 hex chars):
   `C800000000` = LEFT · `00C8C80000` = AHEAD (both center) · `000000C800` = RIGHT.
5. Confirm the right motor(s) buzz; vary the bytes to confirm PWM intensity + patterns.

Firmware test ladder: blink → BLE advertise → appears in nRF Connect → write packet →
correct motor buzzes → intensity scales → patterns work → Android app connects & writes.
