# 6점 촉각 모듈 — 앱에서 솔레노이드를 움직이는 방법

1셀(6점) 점자 모듈. 아두이노 + HC-06(블루투스 Classic, SPP) + TB6612 모터 드라이버 + 솔레노이드 6개.
스케치는 [braille_solenoid.ino](braille_solenoid.ino), 앱 쪽 짝은 `front/packages/itda/src/capsule/tactile.ts`.

## 연결

| 항목 | 값 |
|---|---|
| 방식 | Bluetooth **Classic** SPP (BLE 아님) |
| 기기 이름 | `HC-06` — 앱은 폰에 **페어링된** 기기 중 이름에 `HC-06`이 든 것을 고른다 |
| UUID | `00001101-0000-1000-8000-00805F9B34FB` (표준 SPP) |
| 보드레이트 | 9600 |
| 동시 연결 | **1개** — PC 시리얼 터미널이 붙어 있으면 앱이 연결하지 못한다 |
| 권한 | Android 12+ `BLUETOOTH_CONNECT`(근처 기기) — 앱이 연결 시 요청한다 |

폰에서 HC-06을 먼저 페어링해 두어야 한다(기본 PIN `0713`). 앱은 페어링 목록에서 찾기만 하고 검색·페어링은 하지 않는다. 

## 프로토콜

**한 줄 = 명령 하나.** `\n`으로 끝낸다. 모듈은 명령마다 **응답 한 줄**을 돌려준다.

| 보내는 줄 | 하는 일 | 응답 |
|---|---|---|
| `ON 1 3 5` | 1·3·5점을 올린다 (다른 점은 그대로) | `OK ON 1 3 5` |
| `OFF 2` | 2점을 내린다 | `OK OFF 2` |
| `ALLON` | 여섯 점 전부 올린다 | `OK ALLON` |
| `ALLOFF` | 전부 내린다 | `OK ALLOFF` |
| 그 밖의 줄 | 아무것도 하지 않는다 | `ERR <받은 줄>` |

- 점 번호는 1~6 한 자리 숫자, 공백으로 구분. `1 3 5`처럼 여러 개 가능.
- 잘못된 글자가 하나라도 섞이면 **통째로 거절**한다 — 반쯤 실행되는 일은 없다.
- `ON`은 이미 올라온 점을 건드리지 않는다. 특정 패턴만 보이게 하려면 **`ALLOFF` → `ON …`** 순서로 보낸다.

### 자동 내림 (발열 방지)

솔레노이드는 켜 둔 채로 두면 뜨거워진다. 그래서 **점을 올린 뒤 5초가 지나면 모듈이 스스로 전부 내린다.**
기준은 "마지막 명령 뒤에 올라온 점이 남아 있는가"다 — `ON`이든 일부 `OFF`든 명령이 성공하고 점이 하나라도
남아 있으면 그 시점부터 5초를 다시 센다. 자동으로 내릴 때 블루투스로는 아무 줄도 보내지 않는다
(앱이 "명령 하나 → 응답 한 줄"을 기대하기 때문). USB 시리얼에만 `AUTO ALLOFF`를 남긴다.

앱도 같은 5초에 `ALLOFF`를 보내서(`services/tactileDevice.ts`의 `HOLD_MS`) 화면 상태를 모듈과 맞춘다.
5초보다 오래 보여 줘야 하면 앱이 주기적으로 `ON`을 다시 보내야 한다.

## 앱에서 쓰는 법

직접 문자열을 만들지 말고 `@ieum/itda`의 명령 타입을 쓴다. 문자열 변환(`toSerialLine`)과 셀 상태 추적은 거기서 한다.

```ts
import { tactileDevice } from "@/services/tactileDevice";

await tactileDevice.connect();                                   // HC-06 찾아 붙고 ALLOFF
await tactileDevice.send({ kind: "lowerAll" });                  // ALLOFF
await tactileDevice.send({ kind: "raise", dots: [4] });          // ON 4   ← 초성 ㄱ(⠈)
await tactileDevice.send({ kind: "raise", dots: [1, 2, 4] });    // ON 1 2 4
await tactileDevice.send({ kind: "lower", dots: [2] });          // OFF 2
tactileDevice.cell;                                              // 지금 올라온 패턴 (6비트 Cell)
```

`send()`는 모듈의 응답 문자열(`OK ON 4`)을 돌려주고, 끊겨 있으면 `TactileUnavailableError`를 던진다.
`tactileDevice`는 앱에 하나뿐인 인스턴스다 — HC-06이 동시 연결 1개라서, 화면과 잇다(음성)가 각자 만들면 서로를 밀어낸다.
연결 상태를 화면에 그릴 때는 `useTactileStore`를 구독한다 (홈·설정·장치 화면이 그렇게 한다).

점 번호 ↔ 6비트 셀 변환은 `@ieum/domain`의 `dotsToCell` / `cellToDots`.

## 배선

```
HC-06 TXD → D10 (SoftwareSerial RX)
HC-06 RXD ← D9  (SoftwareSerial TX, HC-06은 3.3V 로직 — 분압 저항 권장)
D2 ~ D7   → TB6612 xIN1  (1점 ~ 6점 순서)
TB6612    xIN2 = GND, PWMx = HIGH, STBY = HIGH
```

## 폰 없이 시험하기

아두이노 IDE 시리얼 모니터(9600)에서 같은 명령을 그대로 친다. `HC-06 Ready`가 보이면 스케치가 살아 있다.

```
ON 4        → OK ON 4        (4점 올라옴, 5초 뒤 AUTO ALLOFF)
ALLOFF      → OK ALLOFF
```

앱에서 보낸 명령은 시리얼 모니터에 `BT RX: ON 4`처럼 에코된다. 응답은 `OK`인데 솔레노이드가 안 움직이면
통신은 정상이고 TB6612 배선(STBY/PWM HIGH, xIN1 연결)을 볼 차례다.
