import { cellToDots, ordinalKo, type Cell } from "@ieum/domain";
import { labelDots, TactileUnavailableError } from "@ieum/itda";
import { tactileDevice } from "./tactileDevice";

/**
 * 셀 여러 개를 1셀 모듈에 **차례로** 보여 준다.
 *
 * 모듈이 한 칸뿐이라 한 번에 한 셀만 보여 줄 수 있다. 칸마다 올렸다가 손끝으로 읽을 시간을 주고 내린다.
 * 같은 셀이 연달아 오면 한 번처럼 느껴지므로, 칸 사이에는 전부 내린 채 잠깐 쉰다.
 * 안내 문구는 화면이 정한다(`onCell`) — 여기는 기기만 다룬다.
 */
export const CELL_HOLD_MS = 2_500;
export const CELL_GAP_MS = 400;

/** 진행 중인 재생을 구분하는 번호. 새 재생이나 중단이 오면 올라가고, 옛 재생은 그걸 보고 멈춘다. */
let generation = 0;

export interface PlayOptions {
  holdMs?: number;
  /** 칸을 올리기 직전에 부른다. "첫 번째 칸, 1번과 3번" 같은 안내를 읽는 데 쓴다. */
  onCell?: (index: number, cell: Cell) => void;
}

/** 안내용 문장. 빈 셀은 "빈칸"으로 읽는다. */
export function describeCell(index: number, cell: Cell): string {
  const dots = cellToDots(cell);
  return dots.length === 0 ? `${ordinalKo(index)} 칸, 빈칸` : `${ordinalKo(index)} 칸, ${labelDots(dots)}`;
}

/**
 * @throws TactileUnavailableError 연결이 없거나 보내다 끊겼을 때. 메시지는 그대로 읽어 줄 수 있다.
 */
export async function playCells(cells: readonly Cell[], options: PlayOptions = {}): Promise<void> {
  if (!tactileDevice.connected) {
    throw new TactileUnavailableError("점자 장치 연결 후에 쓸 수 있어요. 설정에서 연결할 수 있어요.");
  }

  generation += 1;
  const mine = generation;
  const live = () => mine === generation;
  const hold = options.holdMs ?? CELL_HOLD_MS;

  try {
    for (const [index, cell] of cells.entries()) {
      if (!live()) return;
      options.onCell?.(index, cell);

      await tactileDevice.send({ kind: "lowerAll" });
      const dots = cellToDots(cell);
      if (dots.length > 0) await tactileDevice.send({ kind: "raise", dots });
      await wait(hold);

      if (!live()) return;
      if (index < cells.length - 1) {
        await tactileDevice.send({ kind: "lowerAll" });
        await wait(CELL_GAP_MS);
      }
    }
  } finally {
    // 끝났든 도중에 끊겼든 마지막엔 내린다. 실패해도 펌웨어가 5초 뒤 스스로 내린다.
    if (live()) await tactileDevice.send({ kind: "lowerAll" }).catch(() => undefined);
  }
}

/** 화면을 떠날 때 부른다 — 진행 중인 재생을 멈추고 점을 내린다. */
export function stopPlayback(): void {
  generation += 1;
  if (tactileDevice.connected) void tactileDevice.send({ kind: "lowerAll" }).catch(() => undefined);
}

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
