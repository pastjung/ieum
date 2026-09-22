import { StyleSheet, Text, View } from "react-native";
import { cellHasDot, type Cell, type Dot } from "@ieum/domain";
import { useTheme } from "@/theme";

interface Props {
  cell: Cell;
  /** 강조 색으로 표시 (내 입력) */
  emphasized?: boolean;
  /** 누락 점 표시 (점선 테두리) */
  markDots?: Dot[];
  caption?: string;
  accessibilityLabel?: string;
}

/** 점자 한 칸을 2x3 점 그리드로 시각화 (V16 정답/입력 비교) */
export function BrailleCellView({
  cell,
  emphasized,
  markDots,
  caption,
  accessibilityLabel,
}: Props) {
  const theme = useTheme();
  const color = emphasized ? theme.color.primary : theme.color.text;
  // 시각 배치: 왼 열 1,2,3 / 오른 열 4,5,6 (행 우선으로 그리기: 1·4, 2·5, 3·6)
  const rows: Dot[][] = [
    [1, 4],
    [2, 5],
    [3, 6],
  ];
  return (
    <View style={styles.wrap} accessibilityLabel={accessibilityLabel ?? caption}>
      <View style={[styles.grid, { borderColor: color }]}>
        {rows.map((row) => (
          <View key={row.join("-")} style={styles.row}>
            {row.map((dot) => {
              const on = cellHasDot(cell, dot);
              const marked = markDots?.includes(dot);
              return (
                <View
                  key={dot}
                  style={[
                    styles.dot,
                    on
                      ? { backgroundColor: color }
                      : {
                          borderWidth: 2.5,
                          borderColor: color,
                          borderStyle: marked ? "dashed" : "solid",
                        },
                  ]}
                />
              );
            })}
          </View>
        ))}
      </View>
      {caption != null && (
        <Text
          style={{
            fontSize: 13.5,
            color: emphasized ? theme.color.primary : theme.color.textMuted,
            fontWeight: emphasized ? "700" : "400",
          }}
        >
          {caption}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { alignItems: "center", gap: 8 },
  grid: {
    borderWidth: 2,
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 14,
    gap: 10,
  },
  row: { flexDirection: "row", gap: 10 },
  dot: { width: 22, height: 22, borderRadius: 11 },
});
