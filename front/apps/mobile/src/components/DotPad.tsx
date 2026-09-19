import { useRef, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { dotsToCell, type Cell, type Dot } from "@ieum/domain";
import { dotButtonSize, radius, useTheme } from "@/theme";
import { hapticTap, speak } from "@/a11y";

interface Props {
  /** 한 칸 입력이 확정될 때 (모든 손가락을 떼면) 호출 */
  onCommitCell: (cell: Cell) => void;
  /** 데모 모드: 강조할 점들 (사용 안내 화면) */
  highlightDots?: Dot[];
}

/**
 * 세로(V) 6점 입력 패드 — 와이어프레임 V14 배치.
 * 점을 동시에 짚었다가 모두 떼면 한 칸이 입력된다.
 * 점 버튼은 92px 이상(dotButtonSize) 계약을 지킨다.
 */
export function DotPad({ onCommitCell, highlightDots }: Props) {
  const theme = useTheme();
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

  // 와이어프레임 배치: 왼쪽 위→아래 3·2·1, 오른쪽 위→아래 6·5·4
  const positions: Record<Dot, { left?: string; right?: string; top?: string; bottom?: string }> = {
    3: { left: "3%", top: "5%" },
    6: { right: "3%", top: "5%" },
    2: { left: "10%", top: "42%" },
    5: { right: "10%", top: "42%" },
    1: { left: "14%", bottom: "3%" },
    4: { right: "14%", bottom: "3%" },
  };

  return (
    <View
      style={[styles.pad, { borderColor: theme.color.border, borderRadius: radius.card }]}
      accessibilityLabel="점자 입력 영역. 1번부터 6번까지 점 버튼이 있어요. 점을 동시에 짚고 손을 떼면 한 칸이 입력돼요."
    >
      {(Object.keys(positions) as unknown as Dot[]).map((key) => {
        const dot = Number(key) as Dot;
        const pos = positions[dot];
        const isOn = active.has(dot) || highlightDots?.includes(dot);
        return (
          <Pressable
            key={dot}
            accessibilityRole="button"
            accessibilityLabel={`${dot}번 점`}
            accessibilityState={{ selected: active.has(dot) }}
            onPressIn={() => pressIn(dot)}
            onPressOut={() => pressOut(dot)}
            style={[
              styles.dot,
              pos as object,
              {
                borderColor: isOn ? theme.color.primary : theme.color.border,
                backgroundColor: isOn ? theme.color.primary : "transparent",
              },
            ]}
          >
            <Text
              style={{
                fontSize: 32,
                fontWeight: "900",
                color: isOn ? theme.color.onPrimary : theme.color.text,
              }}
            >
              {dot}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  pad: {
    flex: 1,
    minHeight: 0,
    borderWidth: 2,
    borderStyle: "dashed",
    position: "relative",
  },
  dot: {
    position: "absolute",
    width: dotButtonSize,
    height: dotButtonSize,
    borderRadius: dotButtonSize / 2,
    borderWidth: 3,
    alignItems: "center",
    justifyContent: "center",
  },
});
