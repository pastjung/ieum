import { useRef, useState } from "react";
import { dotsToCell, type Cell, type Dot } from "@ieum/domain";
import { hapticTap, speak } from "@/a11y";

/**
 * 6점 입력 공통 제스처: 점을 동시에 짚었다가 모두 떼면 한 칸 확정.
 * 세로 패드(V14)와 가로 두 손 패드(V14b)가 공유한다.
 */
export function useDotGesture(onCommitCell: (cell: Cell) => void) {
  const [active, setActive] = useState<Set<Dot>>(new Set());
  const gathered = useRef<Set<Dot>>(new Set());

  const pressIn = (dot: Dot) => {
    void hapticTap();
    speak(`${dot}번`);
    gathered.current.add(dot);
    setActive((prev) => new Set(prev).add(dot));
  };

  const pressOut = (dot: Dot) => {
    setActive((prev) => {
      const nextSet = new Set(prev);
      nextSet.delete(dot);
      if (nextSet.size === 0 && gathered.current.size > 0) {
        const dots = [...gathered.current].sort((a, b) => a - b) as Dot[];
        gathered.current = new Set();
        onCommitCell(dotsToCell(dots));
      }
      return nextSet;
    });
  };

  return { active, pressIn, pressOut };
}
