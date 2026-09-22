import { useEffect, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { BigInput } from "@/components/BigInput";
import { AppButton } from "@/components/AppButton";
import { BrailleCellView } from "@/components/BrailleCellView";
import { lookupWord, sampleWords, type DictEntry } from "@/services/dictionary";
import { announce, speak } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** V25 점자 사전 (확장) */
export default function Dictionary() {
  const theme = useTheme();
  const fontScale = useFontScale();
  const [query, setQuery] = useState("");
  const [entry, setEntry] = useState<DictEntry | null>(null);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    announce(`점자 사전이에요. 단어를 입력하고 찾기를 누르세요. 지금은 ${sampleWords.join(", ")} 단어를 찾을 수 있어요.`);
  }, []);

  const search = () => {
    const found = lookupWord(query);
    setEntry(found);
    setNotFound(!found && query.trim().length > 0);
    if (found) {
      announce(`${found.word}. ${found.breakdown}. 셀 ${found.cells.length}개예요.`);
    } else {
      announce("아직 사전에 없는 단어예요. 다른 단어를 찾아보세요.");
    }
  };

  return (
    <ScreenShell>
      <BackHeader title="점자 사전" />
      <BigInput
        accessibilityLabel="찾을 단어"
        value={query}
        onChangeText={(t) => {
          setQuery(t);
          setNotFound(false);
        }}
        focused
      />
      <AppButton label="찾기" variant="secondary" onPress={search} />
      {entry && (
        <View style={[styles.card, { backgroundColor: theme.color.surface, borderColor: theme.color.border, borderRadius: radius.card }]}>
          <View style={styles.cells}>
            {entry.cells.map((cell, i) => (
              <BrailleCellView key={i} cell={cell} accessibilityLabel={`${i + 1}번째 칸`} />
            ))}
          </View>
          <Text style={{ fontSize: 15 * fontScale, color: theme.color.textMuted }}>
            {entry.breakdown} · 셀 {entry.cells.length}개
          </Text>
        </View>
      )}
      {notFound && (
        <Text accessibilityLiveRegion="polite" style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, textAlign: "center" }}>
          아직 사전에 없는 단어예요.
        </Text>
      )}
      {entry && (
        <View style={{ flexDirection: "row", gap: 10 }}>
          <View style={{ flex: 1 }}>
            <AppButton label="소리로 듣기" variant="secondary" onPress={() => speak(`${entry.word}. ${entry.breakdown}`, { force: true })} />
          </View>
          <View style={{ flex: 1 }}>
            <AppButton label="장치로 촉지" variant="secondary" onPress={() => announce("점자 장치 연결 후에 쓸 수 있어요.")} />
          </View>
        </View>
      )}
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, padding: 20, gap: 14, alignItems: "center" },
  cells: { flexDirection: "row", gap: 14 },
});
