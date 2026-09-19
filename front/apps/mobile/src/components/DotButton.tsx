import { useRef } from "react";
import { Text, View } from "react-native";
import type { Dot } from "@ieum/domain";
import { useTheme } from "@/theme";
import type { DotRect } from "./useMultiTouchDots";

interface Props {
  dot: Dot;
  size: number;
  on: boolean;
  /** 배치 후 창 기준 위치 보고 (멀티터치 판정용) */
  onMeasure: (dot: Dot, rect: DotRect) => void;
  style?: object;
}

/**
 * 6점 패드의 점 표시 (92px 이상 계약).
 * 터치는 패드 컨테이너가 통째로 받으므로 이 뷰 자체는 터치를 받지 않는다.
 */
export function DotButton({ dot, size, on, onMeasure, style }: Props) {
  const theme = useTheme();
  const ref = useRef<View>(null);
  return (
    <View
      ref={ref}
      pointerEvents="none"
      onLayout={() => {
        // 회전 등 재배치 후 최종 위치 기준으로 측정
        requestAnimationFrame(() => {
          ref.current?.measureInWindow((x, y, w) => onMeasure(dot, { x, y, size: w }));
        });
      }}
      style={[
        {
          position: "absolute",
          width: size,
          height: size,
          borderRadius: size / 2,
          borderWidth: 3,
          alignItems: "center",
          justifyContent: "center",
          borderColor: on ? theme.color.primary : theme.color.border,
          backgroundColor: on ? theme.color.primary : "transparent",
        },
        style,
      ]}
    >
      <Text style={{ fontSize: size > 100 ? 32 : 26, fontWeight: "900", color: on ? theme.color.onPrimary : theme.color.text }}>
        {dot}
      </Text>
    </View>
  );
}
