import { act, create } from "react-test-renderer";
import type { GestureResponderEvent } from "react-native";
import { useMultiTouchDots, type MultiTouchDotsOptions } from "@/components/useMultiTouchDots";
import { dotsToCell, type Cell } from "@ieum/domain";

// jest.mock 은 호이스팅되므로 팩토리가 참조하는 변수는 이름이 `mock`으로 시작해야 한다
const mockSpeak = jest.fn();
const mockHapticTap = jest.fn();

jest.mock("@/a11y", () => ({
  speak: (...args: unknown[]) => mockSpeak(...args),
  hapticTap: (...args: unknown[]) => mockHapticTap(...args),
}));

/**
 * 점 하나를 덮는 터치 이벤트를 만든다. 훅은 창 좌표로 어느 점을 짚었는지 판정하므로
 * `onMeasureDot`으로 등록한 사각형 안쪽을 가리키면 된다.
 */
function touchEvent(points: { x: number; y: number }[]): GestureResponderEvent {
  return {
    nativeEvent: { touches: points.map((p) => ({ pageX: p.x, pageY: p.y })) },
  } as unknown as GestureResponderEvent;
}

const DOT_1_CENTER = { x: 50, y: 50 };
/**
 * 점 밖. 가로 패드는 가운데 컨트롤("다시 듣기"·"다시 보내기")까지 `padHandlers` 안에
 * 두므로, 그 버튼을 누르는 터치가 이 좌표로 들어온다. 등록한 사각형(0,0)~(100,100)에
 * `SLOP`(12)를 더해도 닿지 않는 자리다.
 */
const OUTSIDE_DOTS = { x: 400, y: 400 };

type Harness = ReturnType<typeof useMultiTouchDots>;

/** 훅을 실제로 돌려 `padHandlers`를 꺼내 온다. 화면은 그리지 않는다 */
function renderHook(onCommitCell: (cell: Cell) => void, options?: MultiTouchDotsOptions) {
  const box: { current: Harness | null } = { current: null };

  function Probe({ opts }: { opts?: MultiTouchDotsOptions }) {
    const api = useMultiTouchDots(onCommitCell, opts);
    box.current = api;
    // 1번 점을 (0,0)~(100,100)에 등록한다
    api.onMeasureDot(1, { x: 0, y: 0, size: 100 });
    return null;
  }

  let renderer: ReturnType<typeof create>;
  act(() => {
    renderer = create(<Probe opts={options} />);
  });

  return {
    get api() {
      if (box.current === null) throw new Error("훅이 아직 실행되지 않았어요");
      return box.current;
    },
    rerender(next?: MultiTouchDotsOptions) {
      act(() => {
        renderer.update(<Probe opts={next} />);
      });
    },
  };
}

beforeEach(() => {
  // 확정 잔상(AFTERGLOW_MS)의 타이머가 테스트가 끝난 뒤에 state를 건드리지 않도록 가짜
  // 타이머를 쓴다. 이 테스트가 보는 것은 잔상이 아니라 잠금 동작이다
  jest.useFakeTimers();
  mockSpeak.mockClear();
  mockHapticTap.mockClear();
});

afterEach(() => {
  jest.useRealTimers();
});

/**
 * 대기·실패 중에는 `play.tsx`가 입력을 받지 않는다. 예전에는 `commit`이 `if (locked) return;`
 * 으로 조용히 버렸는데, 패드에는 잠금 개념이 없어 점마다 진동이 나가고 확정 시
 * "1번 입력"까지 낭독됐다 — 시각장애인 사용자는 그것을 입력이 기록됐다는 뜻으로 읽는다.
 * 실패 상태에서 답을 고치려던 사용자가 고쳤다고 믿고 재시도를 누르면 옛 답이 전송된다.
 */
describe("점자 패드 잠금", () => {
  it("잠기지 않았으면 낭독·진동·확정이 모두 일어난다", () => {
    const onCommitCell = jest.fn();
    const hook = renderHook(onCommitCell);

    act(() => {
      hook.api.padHandlers.onTouchStart(touchEvent([DOT_1_CENTER]));
      hook.api.padHandlers.onTouchEnd(touchEvent([]));
    });

    expect(mockHapticTap).toHaveBeenCalled();
    expect(mockSpeak).toHaveBeenCalledWith("1번 입력");
    expect(onCommitCell).toHaveBeenCalledWith(dotsToCell([1]));
  });

  it("잠겨 있으면 낭독도 진동도 확정도 하지 않는다", () => {
    const onCommitCell = jest.fn();
    const hook = renderHook(onCommitCell, { disabled: true });

    act(() => {
      hook.api.padHandlers.onTouchStart(touchEvent([DOT_1_CENTER]));
      hook.api.padHandlers.onTouchEnd(touchEvent([]));
    });

    expect(mockHapticTap).not.toHaveBeenCalled();
    expect(mockSpeak).not.toHaveBeenCalled();
    expect(onCommitCell).not.toHaveBeenCalled();
  });

  it("무시할 때 침묵하지 않고 제스처마다 한 번 알린다", () => {
    // 침묵하면 그것도 "아무 일도 없었다"와 구별되지 않는다. 다만 손가락을 움직일 때마다
    // 떠들면 안 되므로 한 제스처에 한 번이다
    const onBlocked = jest.fn();
    const hook = renderHook(jest.fn(), { disabled: true, onBlocked });

    act(() => {
      hook.api.padHandlers.onTouchStart(touchEvent([DOT_1_CENTER]));
      hook.api.padHandlers.onTouchMove(touchEvent([DOT_1_CENTER]));
      hook.api.padHandlers.onTouchMove(touchEvent([DOT_1_CENTER]));
    });

    expect(onBlocked).toHaveBeenCalledTimes(1);

    act(() => {
      hook.api.padHandlers.onTouchEnd(touchEvent([]));
      hook.api.padHandlers.onTouchStart(touchEvent([DOT_1_CENTER]));
    });

    expect(onBlocked).toHaveBeenCalledTimes(2);
  });

  it("잠겨 있어도 점 밖 터치에는 안내하지 않는다", () => {
    // 가로 패드는 가운데 컨트롤까지 padHandlers 안에 있다. 점 밖 터치에도 안내하면
    // `failed` 상태에서 "다시 보내기"를 누른 사용자가 오류 진동과 함께
    // "지금은 입력할 수 없어요"를 듣는다 — 정확히 옳은 조작을 막았다고 말하는 셈이다
    const onBlocked = jest.fn();
    const hook = renderHook(jest.fn(), { disabled: true, onBlocked });

    act(() => {
      hook.api.padHandlers.onTouchStart(touchEvent([OUTSIDE_DOTS]));
      hook.api.padHandlers.onTouchMove(touchEvent([OUTSIDE_DOTS]));
      hook.api.padHandlers.onTouchEnd(touchEvent([]));
    });

    expect(onBlocked).not.toHaveBeenCalled();
  });

  it("점 밖 터치 뒤에 점을 짚으면 그때는 안내한다", () => {
    // 점 밖 터치를 무시한 것이 그 뒤의 진짜 점 입력까지 삼키면 안 된다
    const onBlocked = jest.fn();
    const hook = renderHook(jest.fn(), { disabled: true, onBlocked });

    act(() => {
      hook.api.padHandlers.onTouchStart(touchEvent([OUTSIDE_DOTS]));
      hook.api.padHandlers.onTouchEnd(touchEvent([]));
      hook.api.padHandlers.onTouchStart(touchEvent([DOT_1_CENTER]));
    });

    expect(onBlocked).toHaveBeenCalledTimes(1);
  });

  it("제스처 도중에 잠기면 모아 둔 점이 나중에 확정되지 않는다", () => {
    // 다른 손으로 제출을 누른 경우다. 잠금 전에 짚어 둔 점이 살아남으면 잠금이 풀린 뒤
    // 사용자가 의도하지 않은 칸이 들어간다
    const onCommitCell = jest.fn();
    const hook = renderHook(onCommitCell, { disabled: false });

    act(() => {
      hook.api.padHandlers.onTouchStart(touchEvent([DOT_1_CENTER]));
    });
    hook.rerender({ disabled: true });
    hook.rerender({ disabled: false });

    act(() => {
      hook.api.padHandlers.onTouchEnd(touchEvent([]));
    });

    expect(onCommitCell).not.toHaveBeenCalled();
  });
});
