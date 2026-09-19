import { useRef, useState } from "react";
import type { GestureResponderEvent } from "react-native";
import { dotsToCell, type Cell, type Dot } from "@ieum/domain";
import { hapticTap, speak } from "@/a11y";

export interface DotRect {
  x: number;
  y: number;
  size: number;
}

/** 터치 지점이 점 안(반지름 + 여유 12px)인지 판정할 때 쓰는 여유 */
const SLOP = 12;

/**
 * 6점 입력 멀티터치 제스처.
 *
 * RN의 responder 시스템은 화면에서 한 번에 하나의 뷰만 터치를 받으므로,
 * 점마다 Pressable을 두면 두 번째 손가락부터 무시된다. 대신 패드 컨테이너
 * 하나가 모든 터치를 받아 창(window) 좌표로 어느 점을 짚었는지 판정한다.
 * 점을 동시에 짚었다가 모든 손가락을 떼면 한 칸이 확정된다.
 */
export function useMultiTouchDots(onCommitCell: (cell: Cell) => void) {
  const rects = useRef(new Map<Dot, DotRect>());
  const gathered = useRef(new Set<Dot>());
  const prev = useRef(new Set<Dot>());
  const [active, setActive] = useState<Set<Dot>>(new Set());

  /** DotButton이 배치될 때 창 기준 위치를 등록 */
  const onMeasureDot = (dot: Dot, rect: DotRect) => {
    rects.current.set(dot, rect);
  };

  const touchesToDots = (e: GestureResponderEvent): Set<Dot> => {
    const found = new Set<Dot>();
    for (const touch of e.nativeEvent.touches) {
      for (const [dot, r] of rects.current) {
        const radius = r.size / 2 + SLOP;
        const dx = touch.pageX - (r.x + r.size / 2);
        const dy = touch.pageY - (r.y + r.size / 2);
        if (dx * dx + dy * dy <= radius * radius) found.add(dot);
      }
    }
    return found;
  };

  const update = (e: GestureResponderEvent) => {
    const now = touchesToDots(e);
    for (const dot of now) {
      if (!prev.current.has(dot)) {
        gathered.current.add(dot);
        void hapticTap();
        speak(`${dot}번`);
      }
    }
    prev.current = now;
    setActive(now);
  };

  const end = (e: GestureResponderEvent) => {
    if (e.nativeEvent.touches.length > 0) {
      update(e);
      return;
    }
    prev.current = new Set();
    setActive(new Set());
    if (gathered.current.size > 0) {
      const dots = [...gathered.current].sort((a, b) => a - b) as Dot[];
      gathered.current = new Set();
      onCommitCell(dotsToCell(dots));
    }
  };

  /** 패드(또는 가로 패드 전체 행) 컨테이너에 펼쳐 붙이는 터치 핸들러 */
  const padHandlers = {
    onStartShouldSetResponder: () => true,
    onResponderTerminationRequest: () => false,
    onTouchStart: update,
    onTouchMove: update,
    onTouchEnd: end,
    onTouchCancel: end,
  } as const;

  return { active, padHandlers, onMeasureDot };
}
