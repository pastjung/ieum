import { useEffect, useRef, useState } from "react";
import { Pressable, StyleSheet, Text, TextInput, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { AppButton } from "@/components/AppButton";
import { ListRow } from "@/components/ListRow";
import { classService, joinErrorMessage } from "@/services/classService";
import { announce, hapticError } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

const CODE_LEN = 6;

/** V20 수업 참여 · 참여 코드 입력 (수업 탭) */
export default function ClassJoin() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const [code, setCode] = useState("");
  const [joining, setJoining] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<TextInput>(null);

  useEffect(() => {
    announce("수업 참여예요. 선생님이 알려 준 여섯 자리 숫자를 입력하세요.");
  }, []);

  const join = async () => {
    if (code.length < CODE_LEN) {
      void hapticError();
      announce(`숫자 ${CODE_LEN}자리를 모두 입력해 주세요. 지금 ${code.length}자리예요.`);
      return;
    }
    setJoining(true);
    setError(null);
    try {
      const info = await classService.joinByCode(code);
      router.push({ pathname: "/class/waiting", params: { lectureId: info.lectureId, title: info.title } });
    } catch (err) {
      void hapticError();
      const message = joinErrorMessage(err);
      setError(message);
      announce(message);
    } finally {
      setJoining(false);
    }
  };

  return (
    <ScreenShell noBottomPadding>
      <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", color: theme.color.text }}>
        수업 참여
      </Text>
      <Text style={{ fontSize: 24 * fontScale, fontWeight: "900", lineHeight: 36, color: theme.color.text }}>
        선생님이 알려 준{"\n"}여섯 자리 숫자를 입력하세요
      </Text>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`참여 코드 입력. 지금 ${code.length}자리 입력했어요.`}
        accessibilityHint="누르면 숫자 키보드가 열려요"
        onPress={() => inputRef.current?.focus()}
        style={styles.codeRow}
      >
        {Array.from({ length: CODE_LEN }).map((_, i) => (
          <View
            key={i}
            style={[
              styles.codeBox,
              { borderColor: i < code.length ? theme.color.primary : theme.color.border },
            ]}
          >
            <Text style={{ fontSize: 28, fontWeight: "700", color: theme.color.text, fontVariant: ["tabular-nums"] }}>
              {code[i] ?? ""}
            </Text>
          </View>
        ))}
      </Pressable>
      <TextInput
        ref={inputRef}
        value={code}
        onChangeText={(t) => setCode(t.replace(/\D/g, "").slice(0, CODE_LEN))}
        keyboardType="number-pad"
        maxLength={CODE_LEN}
        style={styles.hidden}
        accessibilityLabel="참여 코드"
      />
      <ListRow label="최근 참여" value={`${classService.recentClass.title} · ${classService.recentClass.when}`} />
      {error && (
        <Text accessibilityLiveRegion="polite" style={{ fontSize: 15 * fontScale, fontWeight: "700", color: theme.color.primary, lineHeight: 24 }}>
          {error}
        </Text>
      )}
      <View style={{ marginTop: "auto", paddingBottom: 14 }}>
        <AppButton label={joining ? "입장 중…" : "입장하기"} disabled={joining} onPress={() => void join()} />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  codeRow: { flexDirection: "row", gap: 8, justifyContent: "center" },
  codeBox: {
    width: 46,
    height: 60,
    borderWidth: 2,
    borderRadius: 12,
    alignItems: "center",
    justifyContent: "center",
  },
  hidden: { position: "absolute", opacity: 0, height: 1, width: 1 },
});
