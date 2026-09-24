import { PermissionsAndroid, Platform } from "react-native";
import { EMPTY_CELL, type Cell } from "@ieum/domain";
import {
  applyTo,
  TactileUnavailableError,
  toSerialLine,
  type TactileCommand,
  type TactileDevice,
} from "@ieum/itda";
import { ItdaTactileModule } from "../../modules/itda-tactile";
import { useTactileStore } from "@/stores/tactileStore";

/** 모듈 이름이 다르면 여기만 바꾼다. 페어링 목록에서 이름에 이 문자열이 들어간 기기를 고른다. */
export const TACTILE_NAME_HINT = "HC-06";

/**
 * 점을 올려 둘 수 있는 최대 시간. 솔레노이드는 켜 둔 채로 두면 뜨거워진다.
 * 펌웨어(iot/braille_solenoid)도 같은 값으로 스스로 내리지만, 앱도 내려서 화면 상태를 맞춘다.
 */
export const HOLD_MS = 5_000;

/**
 * 아두이노 6점 모듈(HC-06)에 붙는 `TactileDevice` 구현.
 *
 * 판정(무엇을 보낼지)은 `@ieum/itda`가 하고, 여기서는 네이티브 모듈에 줄을 넘기기만 한다.
 * 네이티브에서 올라온 오류는 사용자에게 읽어 줄 수 있게 `TactileUnavailableError`로 감싼다.
 */
export class Hc06TactileDevice implements TactileDevice {
  private current: Cell = EMPTY_CELL;
  /** HOLD_MS 뒤에 전부 내리는 예약. 올릴 때 걸고, 내릴 때 푼다. */
  private holdTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly nameHint: string = TACTILE_NAME_HINT) {}

  get name(): string {
    return ItdaTactileModule.connectedDeviceName() ?? this.nameHint;
  }

  get cell(): Cell {
    return this.current;
  }

  get connected(): boolean {
    return ItdaTactileModule.isConnected();
  }

  async connect(): Promise<string> {
    try {
      await requireNearbyDevicesPermission();
      const name = await ItdaTactileModule.connect(this.nameHint);
      // 모듈의 실제 상태를 모르므로 모두 내린 채로 시작해 화면 패턴과 맞춘다.
      await ItdaTactileModule.send("ALLOFF", this.nameHint);
      this.current = EMPTY_CELL;
      return name;
    } catch (error) {
      throw asTactileError(error);
    } finally {
      this.publish();
    }
  }

  async send(command: TactileCommand): Promise<string> {
    try {
      const reply = await ItdaTactileModule.send(toSerialLine(command), this.nameHint);
      this.current = applyTo(command, this.current);
      this.scheduleAutoLower();
      return reply;
    } catch (error) {
      throw asTactileError(error);
    } finally {
      // 보내다 끊기는 경우가 있어 보낼 때마다 실제 상태를 다시 확인한다.
      this.publish();
    }
  }

  disconnect(): void {
    this.clearHold();
    ItdaTactileModule.disconnect();
    this.current = EMPTY_CELL;
    this.publish();
  }

  /**
   * 명령 뒤에 올라온 점이 하나라도 남아 있으면 **지금부터** HOLD_MS를 다시 세고, 없으면 예약을 푼다.
   *
   * 펌웨어와 같은 기준이다. "사과"를 올린 4초 뒤 "감자"를 올리면 감자도 온전히 5초를 얻고,
   * `OFF 2`처럼 일부만 내려도 남은 점은 새로 5초를 얻는다. 기준을 "명령 종류"로 두면
   * 일부 내림에서 예약이 사라져 펌웨어가 내린 뒤에도 앱은 올라온 줄로 알게 된다.
   */
  private scheduleAutoLower(): void {
    this.clearHold();
    if (this.current === EMPTY_CELL) return;

    this.holdTimer = setTimeout(() => {
      this.holdTimer = null;
      void this.send({ kind: "lowerAll" }).catch(() => {
        // 이미 끊겼으면 내릴 것도 없다 — 펌웨어가 같은 시간에 스스로 내린다.
      });
    }, HOLD_MS);
  }

  private clearHold(): void {
    if (this.holdTimer) clearTimeout(this.holdTimer);
    this.holdTimer = null;
  }

  /** 네이티브가 말하는 실제 상태를 화면용 스토어에 옮겨 적는다. */
  private publish(): void {
    const connected = ItdaTactileModule.isConnected();
    useTactileStore
      .getState()
      .set({ connected, name: connected ? ItdaTactileModule.connectedDeviceName() : null });
  }

  /** 페어링된 기기 목록 — 이름이 달라 못 찾을 때 설정 화면에서 보여 준다. */
  async pairedDevices(): Promise<string[]> {
    try {
      await requireNearbyDevicesPermission();
      return await ItdaTactileModule.pairedDevices();
    } catch (error) {
      throw asTactileError(error);
    }
  }
}

/**
 * 앱에 하나뿐인 모듈 연결.
 *
 * HC-06은 한 번에 한 연결만 받는다. 잇다(음성)와 설정 화면이 각자 연결하면 서로를 밀어내므로
 * 인스턴스를 하나만 두고 같이 쓴다.
 */
export const tactileDevice = new Hc06TactileDevice();

/**
 * 안드로이드 12(API 31)부터는 '근처 기기' 권한을 런타임에 받아야 **페어링 목록조차** 못 본다.
 * 네이티브 모듈은 권한을 확인만 하므로, 묻는 일은 여기서 한다.
 */
async function requireNearbyDevicesPermission(): Promise<void> {
  if (Platform.OS !== "android") return;
  if (typeof Platform.Version === "number" && Platform.Version < 31) return;

  const granted = await PermissionsAndroid.requestMultiple([
    PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
    PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
  ]);
  if (granted[PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT] !== "granted") {
    throw new TactileUnavailableError(
      "'근처 기기' 권한이 필요해요. 설정에서 허용한 뒤 다시 시도해 주세요.",
    );
  }
}

function asTactileError(error: unknown): TactileUnavailableError {
  const message =
    error instanceof Error && error.message
      ? error.message
      : "촉각 모듈과 통신하지 못했어요. 연결을 확인해 주세요.";
  return new TactileUnavailableError(message, { cause: error });
}
