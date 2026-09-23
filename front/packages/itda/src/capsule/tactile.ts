import { cellToDots, dotsToCell, EMPTY_CELL, type Cell, type Dot } from "@ieum/domain";

/**
 * 6점 촉각 모듈(아두이노 + HC-06)에 내리는 명령.
 *
 * 프로토콜은 `iot/braille_solenoid/braille_solenoid.ino`와 짝이다.
 * ```
 * ON 1 3 5   → OK ON 1 3 5
 * OFF 2      → OK OFF 2
 * ALLON      → OK ALLON
 * ALLOFF     → OK ALLOFF
 * ```
 *
 * 실제 블루투스 연결은 앱 계층(네이티브 모듈)이 맡고, 이 파일은 **기기 없이도 도는 순수 로직**이다.
 * (mobile 코드 규약: "BLE 로직은 화면과 분리된 모듈로 작성한다")
 */
export type TactileCommand =
  | { readonly kind: "raise"; readonly dots: readonly Dot[] }
  | { readonly kind: "lower"; readonly dots: readonly Dot[] }
  | { readonly kind: "raiseAll" }
  | { readonly kind: "lowerAll" };

/** 아두이노가 받는 한 줄(줄바꿈 제외). */
export function toSerialLine(command: TactileCommand): string {
  switch (command.kind) {
    case "raise":
      return `ON ${command.dots.join(" ")}`;
    case "lower":
      return `OFF ${command.dots.join(" ")}`;
    case "raiseAll":
      return "ALLON";
    case "lowerAll":
      return "ALLOFF";
  }
}

export const LINE_END = "\n";

/** 이 명령을 적용한 뒤 모듈이 갖게 될 6비트 패턴. */
export function applyTo(command: TactileCommand, cell: Cell): Cell {
  switch (command.kind) {
    case "raise":
      return cell | dotsToCell(command.dots);
    case "lower":
      return cell & ~dotsToCell(command.dots);
    case "raiseAll":
      return 0b111111;
    case "lowerAll":
      return EMPTY_CELL;
  }
}

/** "1번, 3번, 5번" */
export function labelDots(dots: readonly Dot[]): string {
  return dots.map((dot) => `${dot}번`).join(", ");
}

/** 음성 응답용 설명 — "3번 점을 올렸어요." */
export function describeCommand(command: TactileCommand): string {
  switch (command.kind) {
    case "raise":
      return `${labelDots(command.dots)} 점을 올렸어요.`;
    case "lower":
      return `${labelDots(command.dots)} 점을 내렸어요.`;
    case "raiseAll":
      return "모든 점을 올렸어요.";
    case "lowerAll":
      return "모든 점을 내렸어요.";
  }
}

// --- DotNumber concept 값 규약 ----------------------------------------------

/**
 * "1,3,5"처럼 쉼표로 이은 오름차순 번호이거나, 번호 없이 전부를 뜻하는 `ALL`.
 * 점 번호는 한 자리(1~6)라 "135번"처럼 띄어쓰기가 사라진 인식 결과도 자리마다 끊어 읽는다.
 */
export const DOT_ALL = "all";

export function isDot(value: number): value is Dot {
  return Number.isInteger(value) && value >= 1 && value <= 6;
}

export function encodeDots(dots: Iterable<number>): string {
  return [...new Set(dots)].sort((a, b) => a - b).join(",");
}

/** `ALL`이면 빈 목록. 범위 밖 숫자는 버린다. */
export function decodeDots(value: string): Dot[] {
  if (value === DOT_ALL) return [];
  const dots = value
    .split(",")
    .map((part) => Number.parseInt(part.trim(), 10))
    .filter(isDot);
  return [...new Set(dots)].sort((a, b) => a - b);
}

// --- 기기 포트 ---------------------------------------------------------------

/** 모듈을 못 쓰는 상황(전원·페어링·권한). 메시지를 사용자에게 그대로 읽어 준다. */
export class TactileUnavailableError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "TactileUnavailableError";
  }
}

export interface TactileDevice {
  readonly name: string;
  /** 모듈이 지금 올리고 있는 패턴. */
  readonly cell: Cell;
  readonly connected: boolean;
  /** @returns 연결된 기기 이름 */
  connect(): Promise<string>;
  /** @returns 모듈이 답한 한 줄 (예: `OK ON 3`) */
  send(command: TactileCommand): Promise<string>;
  disconnect(): void;
}

/** 기기 없이 도는 구현 — 유닛 테스트와 하드웨어 없는 개발용. */
export class InMemoryTactileDevice implements TactileDevice {
  readonly name = "가상 모듈";
  readonly sentLines: string[] = [];

  private current: Cell = EMPTY_CELL;
  private open = false;

  constructor(private readonly reachable: boolean = true) {}

  get cell(): Cell {
    return this.current;
  }

  get dots(): Dot[] {
    return cellToDots(this.current);
  }

  get connected(): boolean {
    return this.open;
  }

  async connect(): Promise<string> {
    if (!this.reachable) {
      throw new TactileUnavailableError("촉각 모듈을 찾을 수 없어요. 전원과 페어링을 확인해 주세요.");
    }
    this.open = true;
    return this.name;
  }

  async send(command: TactileCommand): Promise<string> {
    if (!this.open) await this.connect();
    const line = toSerialLine(command);
    this.sentLines.push(line);
    this.current = applyTo(command, this.current);
    return `OK ${line}`;
  }

  disconnect(): void {
    this.open = false;
  }
}
