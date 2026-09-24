/*
 * 이음 6점 촉각 모듈 — 1셀, 솔레노이드 × 6 (TB6612 모터 드라이버 경유)
 *
 * 앱(front/packages/itda/src/capsule/tactile.ts)과 같은 프로토콜을 쓴다. 한 줄에 명령 하나.
 *   ON 1 3 5   → OK ON 1 3 5
 *   OFF 2      → OK OFF 2
 *   ALLON      → OK ALLON
 *   ALLOFF     → OK ALLOFF
 *   그 외      → ERR <받은 줄>
 *
 * 발열 방지: 점을 올린 뒤 HOLD_MS(5초)가 지나면 명령이 없어도 전부 내린다.
 *   앱이 죽거나 블루투스가 끊겨도 동작한다. 앱도 같은 시간에 ALLOFF를 보내 화면 상태를 맞춘다.
 *   자동으로 내릴 때는 USB 시리얼에만 "AUTO ALLOFF"를 남기고, 블루투스로는 아무것도 보내지 않는다 —
 *   앱은 "명령 하나 → 응답 한 줄"을 기대하므로 묻지 않은 줄을 끼워 넣으면 다음 응답과 섞인다.
 *
 * 배선
 *   HC-06 TXD → D10 (SoftwareSerial RX)
 *   HC-06 RXD ← D9  (SoftwareSerial TX — HC-06은 3.3V 로직이라 분압 저항 권장)
 *   D2 ~ D7   → TB6612 xIN1 (1점 ~ 6점). xIN2는 GND, PWMx와 STBY는 HIGH(VCC)에 고정.
 *
 * 줄 단위로 읽는다. 앱은 "\n"으로 끝내고 시리얼 모니터는 "\r\n"을 붙일 수 있어 둘 다 받는다.
 * USB 시리얼로 들어온 명령도 똑같이 처리하므로, 폰 없이 시리얼 모니터에서 바로 시험할 수 있다.
 */
#include <SoftwareSerial.h>

SoftwareSerial BT(10, 9);  // RX, TX

const uint8_t DOT_PINS[6] = {2, 3, 4, 5, 6, 7};  // 1점 → D2 … 6점 → D7
const uint8_t LINE_MAX = 32;

// 점을 올려 둘 수 있는 최대 시간(ms). 앱 쪽 HOLD_MS(services/tactileDevice.ts)와 같은 값.
const unsigned long HOLD_MS = 5000;

char btLine[LINE_MAX];
uint8_t btLen = 0;
char pcLine[LINE_MAX];
uint8_t pcLen = 0;

unsigned long raisedAt = 0;  // 마지막으로 점을 올린 시각. 0이면 올라온 점이 없다.

void setAll(uint8_t level) {
  for (uint8_t i = 0; i < 6; i++) digitalWrite(DOT_PINS[i], level);
}

bool anyRaised() {
  for (uint8_t i = 0; i < 6; i++) {
    if (digitalRead(DOT_PINS[i]) == HIGH) return true;
  }
  return false;
}

// 올라온 점이 있으면 지금부터 HOLD_MS를 다시 세고, 없으면 타이머를 푼다.
void trackHold() {
  raisedAt = anyRaised() ? millis() : 0;
}

void reply(Stream& out, const char* line) {
  out.print("OK ");
  out.println(line);
}

void reject(Stream& out, const char* line) {
  out.print("ERR ");
  out.println(line);
}

// 한 줄을 해석해 핀을 움직이고, 보낸 쪽에 결과를 돌려준다.
void handle(const char* line, Stream& out, bool fromBt) {
  if (fromBt) {
    Serial.print("BT RX: ");
    Serial.println(line);
  }

  if (strcmp(line, "ALLON") == 0) {
    setAll(HIGH);
    trackHold();
    reply(out, line);
    return;
  }
  if (strcmp(line, "ALLOFF") == 0) {
    setAll(LOW);
    trackHold();
    reply(out, line);
    return;
  }

  bool on;
  const char* rest;
  if (strncmp(line, "ON ", 3) == 0) {
    on = true;
    rest = line + 3;
  } else if (strncmp(line, "OFF ", 4) == 0) {
    on = false;
    rest = line + 4;
  } else {
    reject(out, line);
    return;
  }

  // "1 3 5" — 공백으로 나뉜 1~6. 그 밖의 글자가 섞이면 통째로 거절한다(반쯤 실행하지 않는다).
  for (const char* p = rest; *p; p++) {
    if (*p != ' ' && (*p < '1' || *p > '6')) {
      reject(out, line);
      return;
    }
  }
  bool any = false;
  for (const char* p = rest; *p; p++) {
    if (*p >= '1' && *p <= '6') {
      digitalWrite(DOT_PINS[*p - '1'], on ? HIGH : LOW);
      any = true;
    }
  }
  if (!any) {
    reject(out, line);
    return;
  }
  trackHold();
  reply(out, line);
}

// 한 글자를 버퍼에 넣는다. 줄이 끝났으면 true. 넘치는 글자는 버린다.
bool collect(char* buf, uint8_t& len, char c) {
  if (c == '\r') return false;
  if (c == '\n') {
    buf[len] = '\0';
    return len > 0;  // 빈 줄은 무시
  }
  if (len < LINE_MAX - 1) buf[len++] = c;
  return false;
}

void setup() {
  for (uint8_t i = 0; i < 6; i++) {
    pinMode(DOT_PINS[i], OUTPUT);
    digitalWrite(DOT_PINS[i], LOW);  // 켜지자마자 전부 내린 상태로
  }
  Serial.begin(9600);
  BT.begin(9600);
  Serial.println("HC-06 Ready");
}

void loop() {
  // 스마트폰 → HC-06 → 아두이노
  while (BT.available()) {
    if (collect(btLine, btLen, BT.read())) {
      handle(btLine, BT, true);
      btLen = 0;
    }
  }
  // PC 시리얼 모니터 → 아두이노 (폰 없이 시험용)
  while (Serial.available()) {
    if (collect(pcLine, pcLen, Serial.read())) {
      handle(pcLine, Serial, false);
      pcLen = 0;
    }
  }

  // 발열 방지 — 올린 뒤 HOLD_MS가 지나면 명령이 없어도 전부 내린다.
  if (raisedAt != 0 && millis() - raisedAt >= HOLD_MS) {
    setAll(LOW);
    raisedAt = 0;
    Serial.println("AUTO ALLOFF");
  }
}
