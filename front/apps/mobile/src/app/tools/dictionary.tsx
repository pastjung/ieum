import { useEffect, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { BigInput } from "@/components/BigInput";
import { AppButton } from "@/components/AppButton";
import { BrailleCellView } from "@/components/BrailleCellView";
import { lookupWord, type DictEntry } from "@/services/dictionary";
import { describeCell, playCells, stopPlayback } from "@/services/tactilePlayer";
import { announce, speak } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** V25 점자 사전 (확장) */
export default function Dictionary() {
  const theme = useTheme();
  const fontScale = useFontScale();
  const [query, setQuery] = useState("");
  const [entry, setEntry] = useState<DictEntry | null>(null);

  // 화면을 떠나면 진행 중인 촉지를 멈추고 점을 내린다.
  useEffect(() => stopPlayback, []);

  /** 단어의 셀을 모듈에 한 칸씩 보여 준다. 연결이 없으면 모듈이 주는 안내를 그대로 읽는다. */
  const feel = async () => {
    if (!entry) return;
    try {
      announce(`${entry.word}, 장치로 보여 드릴게요. 셀 ${entry.cells.length}개예요.`);
      await playCells(entry.cells, {
        // 요소 이름이 있으면 함께 읽는다 — "두 번째 칸, 4번과 5번, 약자 바"
        onCell: (index, cell) =>
          announce(entry.labels[index] ? `${describeCell(index, cell)}, ${entry.labels[index]}` : describeCell(index, cell)),
      });
    } catch (error) {
      announce(error instanceof Error ? error.message : "장치에 보내지 못했어요.");
    }
  };
  const [message, setMessage] = useState<string | null>(null);
  const [searching, setSearching] = useState(false);

  useEffect(() => {
    announce("점자 사전이에요. 한글 단어나 낱자를 입력하고 찾기를 누르세요.");
  }, []);

  /** 등록된 문제면 그 정답 칸을, 없으면 서버가 AI 점역기로 바꾼 칸을 받는다 (`GET /braille-dictionary`) */
  const search = async () => {
    if (searching || query.trim().length === 0) return;
    stopPlayback(); // 이전 단어를 촉지하던 중이면 멈춘다
    setSearching(true);
    setMessage(null);
    const result = await lookupWord(query);
    setSearching(false);
    if (result.kind === "found") {
      setEntry(result.entry);
      announce(`${result.entry.word}. ${result.entry.breakdown}. 셀 ${result.entry.cells.length}개예요.`);
      return;
    }
    setEntry(null);
    const text = result.kind === "unsupported" ? "한글 단어나 낱자만 찾을 수 있어요." : result.message;
    setMessage(text);
    announce(text);
  };

  return (
    <ScreenShell>
      <BackHeader title="점자 사전" />
      <BigInput
        accessibilityLabel="찾을 단어"
        value={query}
        onChangeText={(t) => {
          setQuery(t);
          setMessage(null);
        }}
      />
      <AppButton label={searching ? "찾는 중…" : "찾기"} variant="secondary" onPress={() => void search()} />
      {entry && (
        <View style={[styles.card, { backgroundColor: theme.color.surface, borderColor: theme.color.border, borderRadius: radius.card }]}>
          <View style={styles.cells}>
            {entry.cells.map((cell, i) => (
              <BrailleCellView
                key={i}
                cell={cell}
                caption={entry.labels[i] ?? undefined}
                accessibilityLabel={entry.labels[i] ? `${i + 1}번째 칸, ${entry.labels[i]}` : `${i + 1}번째 칸`}
              />
            ))}
          </View>
          <Text style={{ fontSize: 15 * fontScale, color: theme.color.textMuted }}>
            {entry.breakdown} · 셀 {entry.cells.length}개
          </Text>
        </View>
      )}
      {message && (
        <Text accessibilityLiveRegion="polite" style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, textAlign: "center" }}>
          {message}
        </Text>
      )}
      {entry && (
        <View style={{ flexDirection: "row", gap: 10 }}>
          <View style={{ flex: 1 }}>
            <AppButton label="소리로 듣기" variant="secondary" onPress={() => speak(`${entry.word}. ${entry.breakdown}`, { force: true })} />
          </View>
          <View style={{ flex: 1 }}>
            <AppButton
              label="장치로 촉지"
              variant="secondary"
              accessibilityHint="점자 장치에 한 칸씩 올려 손끝으로 읽어요"
              onPress={() => void feel()}
            />
          </View>
        </View>
      )}
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, padding: 20, gap: 14, alignItems: "center" },
  cells: { flexDirection: "row", flexWrap: "wrap", justifyContent: "center", gap: 14 },
});
