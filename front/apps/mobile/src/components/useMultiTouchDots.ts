import { useEffect, useRef, useState } from "react";
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

/** 확정된 점 하이라이트(잔상)를 유지하는 시간 — 저시력 사용자가 방금 짚은 점을 눈으로 확인할 여유(약 1초) */
const AFTERGLOW_MS = 1000;

export interface MultiTouchDotsOptions {
  /**
   * 잠금. 화면이 입력을 받을 수 없는 상태(서버 응답 대기·제출 실패 등)에서 켠다.
   *
   * **잠금이 여기까지 내려와야 하는 이유**: 호출부에서 `onCommitCell`을 조용히 버리면
   * 사용자는 확정 낭독("3번 입력")과 점마다의 진동을 그대로 받는다. 시각장애인 사용자는
   * 그 신호를 입력이 기록됐다는 뜻으로 읽는다 — 실패 상태에서 답을 고치려던 사용자가
   * 고쳤다고 믿고 재시도를 누르면 **옛 답이 그대로 전송된다.**
   */
  disabled?: boolean;
  /**
   * 잠긴 상태에서 터치를 무시할 때 제스처마다 한 번 불린다.
   *
   * 무시하면서 침묵하면 그것도 "아무 일도 없었다"와 구별되지 않는다. 호출부가 지금 화면에
   * 떠 있는 상태 문구를 그대로 읽어 준다.
   */
  onBlocked?: () => void;
}

/**
 * 6점 입력 멀티터치 제스처.
 *
 * RN의 responder 시스템은 화면에서 한 번에 하나의 뷰만 터치를 받으므로,
 * 점마다 Pressable을 두면 두 번째 손가락부터 무시된다. 대신 패드 컨테이너
 * 하나가 모든 터치를 받아 창(window) 좌표로 어느 점을 짚었는지 판정한다.
 * 점을 동시에 짚었다가 모든 손가락을 떼면 한 칸이 확정된다.
 */
export function useMultiTouchDots(
  onCommitCell: (cell: Cell) => void,
  options?: MultiTouchDotsOptions,
) {
  const disabled = options?.disabled ?? false;
  const onBlocked = options?.onBlocked;
  const rects = useRef(new Map<Dot, DotRect>());
  const gathered = useRef(new Set<Dot>());
  const prev = useRef(new Set<Dot>());
  const [active, setActive] = useState<Set<Dot>>(new Set());
  const afterglowTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** 이번 제스처에서 이미 안내했는지. 손가락을 모두 떼면 풀린다 */
  const blockedAnnounced = useRef(false);

  const clearAfterglowTimer = () => {
    if (afterglowTimer.current !== null) {
      clearTimeout(afterglowTimer.current);
      afterglowTimer.current = null;
    }
  };

  // 문항 전환 등으로 언마운트될 때 남은 타이머가 해제된 컴포넌트의 state를 건드리지 않도록 정리
  useEffect(() => {
    return clearAfterglowTimer;
  }, []);

  // 제스처 도중에 잠기면(예: 다른 손으로 제출을 눌렀을 때) 모아 둔 점과 잔상이 남는다.
  // 잠기는 순간 걷어내야 잠금이 풀린 뒤 엉뚱한 칸이 확정되지 않는다
  useEffect(() => {
    if (!disabled) return;
    if (afterglowTimer.current !== null) {
      clearTimeout(afterglowTimer.current);
      afterglowTimer.current = null;
    }
    gathered.current = new Set();
    prev.current = new Set();
    blockedAnnounced.current = false;
    setActive(new Set());
  }, [disabled]);

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
    if (disabled) {
      // 낭독도 진동도 내지 않고 버린다 — 그 신호가 곧 "입력됐다"는 뜻이기 때문이다.
      // 대신 제스처마다 한 번 지금 상태를 알린다
      if (!blockedAnnounced.current) {
        blockedAnnounced.current = true;
        onBlocked?.();
      }
      return;
    }
    // 새 터치가 시작되면 이전 칸의 잔상 위에 겹쳐 보이지 않도록 즉시 걷어낸다
    clearAfterglowTimer();
    const now = touchesToDots(e);
    for (const dot of now) {
      if (!prev.current.has(dot)) {
        // 동시 입력 시 점마다 읽으면 음성이 서로 끊기므로, 확정 시점에 한 번에 읽는다
        gathered.current.add(dot);
        void hapticTap();
      }
    }
    prev.current = now;
    setActive(now);
  };

  const end = (e: GestureResponderEvent) => {
    if (disabled) {
      // 손가락을 모두 떼면 다음 제스처에서 다시 안내한다
      blockedAnnounced.current = e.nativeEvent.touches.length > 0;
      return;
    }
    if (e.nativeEvent.touches.length > 0) {
      update(e);
      return;
    }
    prev.current = new Set();
    if (gathered.current.size === 0) {
      setActive(new Set());
      return;
    }
    const dots = [...gathered.current].sort((a, b) => a - b) as Dot[];
    gathered.current = new Set();
    // 확정 자체는 지금 그대로 즉시 처리하고, 하이라이트만 잠시 남긴다(잔상은 순전히 시각 효과)
    setActive(new Set(dots));
    speak(`${dots.join("번, ")}번 입력`);
    onCommitCell(dotsToCell(dots));
    clearAfterglowTimer();
    afterglowTimer.current = setTimeout(() => {
      afterglowTimer.current = null;
      setActive(new Set());
    }, AFTERGLOW_MS);
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
