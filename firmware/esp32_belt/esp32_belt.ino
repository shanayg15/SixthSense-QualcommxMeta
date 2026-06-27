/*
 * SixthSense haptic belt firmware (ESP32 + NimBLE).
 *
 * The belt is a DUMB ACTUATOR. It advertises a BLE service, accepts a fixed
 * 4-byte packet [m0, m1, m2, pattern], and drives three vibration motors. It
 * performs NO navigation, NO AI, and stores no state beyond the latest command.
 *
 * Packet: [m0, m1, m2, pattern]
 *   m0 = left   intensity 0..255
 *   m1 = center intensity 0..255
 *   m2 = right  intensity 0..255
 *   pattern: 0 = steady, 1 = single/caution pulse, 2 = double pulse (curb/step)
 *
 * UUIDs (Nordic UART Service), matched by the Android BeltClient:
 *   service        6e400001-b5a3-f393-e0a9-e50e24dcca9e
 *   characteristic 6e400002-b5a3-f393-e0a9-e50e24dcca9e  (Write / Write-No-Response)
 *
 * ── WIRING (NEVER drive vibration motors directly from a GPIO pin) ──
 * Use a ULN2803A Darlington array (or one N-MOSFET per motor) between the ESP32
 * and the motors. GPIO pins source only a few mA; motors need more and produce
 * inductive kickback that can destroy the pin.
 *
 *   ESP32 GPIO (PWM) ─► ULN2803A input (1B..3B)
 *   ULN2803A output (1C..3C) ─► motor (−)
 *   motor (+) ─► motor V+ (e.g. battery / power-bank rail)
 *   ULN2803A COM pin ─► motor V+   (clamp-diode return path for inductive loads)
 *   ESP32 GND ─► ULN2803A GND ─► motor power GND   (common ground is required)
 *
 * Many "vibration motor modules" already include a driver transistor; if so you
 * may drive the module's IN pin directly — verify before wiring.
 *
 * Requires: ESP32 Arduino core + "NimBLE-Arduino" library (see README.md).
 */

#include <Arduino.h>
#include <NimBLEDevice.h>

static const char* SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
static const char* CHAR_UUID    = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";

// ── Motor config ───────────────────────────────────────────────────────────
#define NUM_MOTORS 3
// PWM-capable GPIOs wired to the ULN2803A inputs (left, center, right).
// Adjust to your board; avoid strapping pins (0, 2, 12, 15) where possible.
const int MOTOR_PINS[NUM_MOTORS] = {25, 26, 27};

// Latest command from BLE.
volatile uint8_t  gIntensity[NUM_MOTORS] = {0, 0, 0};
volatile uint8_t  gPattern = 0;
volatile uint32_t gPatternStart = 0;

// Pattern timing (ms).
const uint32_t PULSE_ON   = 300;  // pattern 1: on/off cadence
const uint32_t PULSE_OFF  = 300;
const uint32_t DBL_ON     = 120;  // pattern 2: on, off, on, long pause
const uint32_t DBL_GAP    = 120;
const uint32_t DBL_PAUSE  = 400;

NimBLECharacteristic* gChar = nullptr;

void applyOutputs(bool gateOn) {
  for (int i = 0; i < NUM_MOTORS; i++) {
    uint8_t value = gateOn ? gIntensity[i] : 0;
    analogWrite(MOTOR_PINS[i], value);  // 8-bit (0..255) on ESP32 Arduino core
  }
}

// Returns whether motors should be ON right now for the active pattern.
bool patternGate() {
  uint32_t t = millis() - gPatternStart;
  switch (gPattern) {
    case 1: {  // single / caution pulse
      uint32_t period = PULSE_ON + PULSE_OFF;
      return (t % period) < PULSE_ON;
    }
    case 2: {  // double pulse for curb / step
      uint32_t cycle = DBL_ON + DBL_GAP + DBL_ON + DBL_PAUSE;
      uint32_t p = t % cycle;
      if (p < DBL_ON) return true;
      if (p < DBL_ON + DBL_GAP) return false;
      if (p < DBL_ON + DBL_GAP + DBL_ON) return true;
      return false;
    }
    default:   // 0 = steady
      return true;
  }
}

class BeltCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* characteristic) {
    NimBLEAttValue v = characteristic->getValue();
    if (v.length() < 4) {
      Serial.printf("[belt] short packet (%d bytes) ignored\n", v.length());
      return;
    }
    for (int i = 0; i < NUM_MOTORS; i++) gIntensity[i] = v[i];
    uint8_t pattern = v[3];
    if (pattern > 2) pattern = 0;
    if (pattern != gPattern) gPatternStart = millis();
    gPattern = pattern;
    Serial.printf("[belt] L=%u C=%u R=%u pattern=%u\n",
                  gIntensity[0], gIntensity[1], gIntensity[2], gPattern);
  }
};

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server) {
    Serial.println("[belt] central connected");
  }
  void onDisconnect(NimBLEServer* server) {
    Serial.println("[belt] central disconnected; stopping motors and re-advertising");
    for (int i = 0; i < NUM_MOTORS; i++) gIntensity[i] = 0;
    gPattern = 0;
    applyOutputs(false);
    NimBLEDevice::startAdvertising();
  }
};

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("[belt] SixthSense belt starting");

  for (int i = 0; i < NUM_MOTORS; i++) {
    pinMode(MOTOR_PINS[i], OUTPUT);
    analogWrite(MOTOR_PINS[i], 0);
  }

  NimBLEDevice::init("SixthSense-Belt");
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  NimBLEService* service = server->createService(SERVICE_UUID);
  gChar = service->createCharacteristic(
      CHAR_UUID,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  gChar->setCallbacks(new BeltCallbacks());
  service->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  NimBLEDevice::startAdvertising();

  Serial.println("[belt] advertising as 'SixthSense-Belt'");
}

void loop() {
  applyOutputs(patternGate());
  delay(10);  // ~100 Hz pattern resolution; cheap and smooth
}
