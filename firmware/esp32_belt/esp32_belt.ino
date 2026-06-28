/*
 * SixthSense haptic belt firmware (ESP32 + NimBLE) — 4-MOTOR WAIST BELT.
 *
 * The belt is a DUMB ACTUATOR. It advertises a BLE service, accepts a fixed
 * 5-byte packet [m0, m1, m2, m3, pattern], and drives FOUR vibration motors
 * arranged around the waist. It performs NO navigation, NO AI, and stores no
 * state beyond the latest command.
 *
 * Motor layout (the side where the obstacle is, is what buzzes):
 *   m0 = LEFT       — one motor on the left of the waist   (obstacle on the left)
 *   m1 = CENTER_L ┐  two motors close together at the FRONT/center; BOTH buzz
 *   m2 = CENTER_R ┘  for an obstacle straight ahead
 *   m3 = RIGHT      — one motor on the right of the waist  (obstacle on the right)
 *   pattern: 0 = steady, 1 = single/caution pulse, 2 = double pulse (curb/step)
 * Each value is an intensity 0..255 (PWM duty). The phone (BeltMapper.beltPacket4)
 * sets CENTER_L == CENTER_R, so "ahead" lights both center motors.
 *
 * UUIDs (Nordic UART Service), matched by the Android BeltClient:
 *   service        6e400001-b5a3-f393-e0a9-e50e24dcca9e
 *   characteristic 6e400002-b5a3-f393-e0a9-e50e24dcca9e  (Write / Write-No-Response)
 *
 * ── WIRING (these are DRIVER-INCLUDED modules: "PWM Vibration Motor Switch
 *    Module, DC 5V" — each has an onboard transistor, so drive the SIG/IN pin
 *    DIRECTLY from a GPIO; no ULN2803/MOSFET needed) ──
 *   module VCC ─► 5V rail  (ESP32 5V/VIN from USB, or a power bank for the wearable
 *                           run; 4 motors can pull a few hundred mA — give it headroom)
 *   module GND ─► ESP32 GND (COMMON GROUND is required)
 *   module SIG/IN ─► ESP32 GPIO (PWM)   — one GPIO per motor, see MOTOR_PINS below
 *   (ESP32 GPIO logic is 3.3V; these modules switch fine from 3.3V. If a module
 *    needs 5V logic, add a level shifter on SIG.)
 *
 * Requires: ESP32 Arduino core 3.x + "NimBLE-Arduino" library (see README.md).
 */

#include <Arduino.h>
#include <NimBLEDevice.h>

static const char* SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
static const char* CHAR_UUID    = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";

// ── Motor config ───────────────────────────────────────────────────────────
#define NUM_MOTORS 4
// PWM-capable GPIOs wired to each module's SIG pin, in packet order:
//   index 0 = LEFT, 1 = CENTER_L, 2 = CENTER_R, 3 = RIGHT.
// Avoid strapping pins (0, 2, 12, 15) and input-only pins (34-39).
const int MOTOR_PINS[NUM_MOTORS] = {25, 26, 27, 33};

// Latest command from BLE.
volatile uint8_t  gIntensity[NUM_MOTORS] = {0, 0, 0, 0};
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
    analogWrite(MOTOR_PINS[i], value);  // 8-bit (0..255) on ESP32 Arduino core 3.x
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
    if (v.length() < NUM_MOTORS + 1) {
      Serial.printf("[belt] short packet (%d bytes, need %d) ignored\n",
                    v.length(), NUM_MOTORS + 1);
      return;
    }
    for (int i = 0; i < NUM_MOTORS; i++) gIntensity[i] = v[i];
    uint8_t pattern = v[NUM_MOTORS];
    if (pattern > 2) pattern = 0;
    if (pattern != gPattern) gPatternStart = millis();
    gPattern = pattern;
    Serial.printf("[belt] L=%u CL=%u CR=%u R=%u pattern=%u\n",
                  gIntensity[0], gIntensity[1], gIntensity[2], gIntensity[3], gPattern);
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
  Serial.println("[belt] SixthSense 4-motor belt starting");

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

  Serial.println("[belt] advertising as 'SixthSense-Belt' (4 motors)");
}

void loop() {
  applyOutputs(patternGate());
  delay(10);  // ~100 Hz pattern resolution; cheap and smooth
}
