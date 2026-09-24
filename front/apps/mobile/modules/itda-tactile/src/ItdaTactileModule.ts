import { NativeModule, requireNativeModule } from "expo";

/**
 * HC-06(블루투스 클래식 SPP)로 6점 촉각 모듈과 주고받는 네이티브 모듈.
 *
 * 안드로이드 전용이다 — iOS는 MFi 없이 SPP를 지원하지 않는다.
 * 무엇을 보낼지 정하는 일은 `@ieum/itda`가 하고, 여기서는 줄 단위 통신만 한다.
 */
declare class ItdaTactileNativeModule extends NativeModule {
  /** 페어링된 기기 이름 목록. 모듈 이름이 HC-06이 아닐 때 안내에 쓴다. */
  pairedDevices(): Promise<string[]>;
  /** @returns 연결된 기기 이름 */
  connect(nameHint: string): Promise<string>;
  /** 한 줄을 보내고 모듈 응답 한 줄을 받는다. 연결이 없으면 먼저 붙는다. */
  send(line: string, nameHint: string): Promise<string>;
  isConnected(): boolean;
  connectedDeviceName(): string | null;
  disconnect(): void;
}

export default requireNativeModule<ItdaTactileNativeModule>("ItdaTactile");
