import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import * as Linking from "expo-linking";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { announce, hapticError } from "@/a11y";
import { releaseBaseUrl, UPDATE_OPEN_FAILED_MESSAGE, UPDATE_PROMPT_MESSAGE } from "@/services/appRelease";
import { useFontScale, useTheme } from "@/theme";

/**
 * 새 버전 알림 — 앱을 켠 뒤 탭 화면에서 한 번(같은 버전은 하루 한 번) 뜬다.
 * 설치는 브라우저의 다운로드 페이지(`EXPO_PUBLIC_APP_RELEASE_URL`)에서 한다. 앱이 APK를 직접 받지 않는다.
 */
export default function AppUpdate() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { version } = useLocalSearchParams<{ version?: string }>();
  const pageUrl = releaseBaseUrl(process.env.EXPO_PUBLIC_APP_RELEASE_URL);

  useEffect(() => {
    announce(UPDATE_PROMPT_MESSAGE);
  }, []);

  const close = () => {
    if (router.canGoBack()) router.back();
    else router.replace("/(tabs)");
  };

  const open = async () => {
    if (pageUrl === null) {
      close();
      return;
    }
    try {
      await Linking.openURL(pageUrl);
      close();
    } catch {
      void hapticError();
      announce(UPDATE_OPEN_FAILED_MESSAGE);
    }
  };

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={110} accessibilityLabel="이음 캐릭터" />
        <Text
          accessibilityRole="header"
          style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}
        >
          새 버전이 있어요
        </Text>
        <Text style={{ fontSize: 18 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
          {version ? `이음 ${version} 버전을 설치할 수 있어요.` : "새 이음 앱을 설치할 수 있어요."}
          {"\n"}설치 페이지를 열까요?
        </Text>
      </View>
      <View style={styles.actions}>
        <AppButton
          label="설치 페이지 열기"
          accessibilityHint="브라우저에서 이음 앱 설치 페이지를 열어요"
          onPress={() => void open()}
        />
        <AppButton label="나중에" variant="secondary" accessibilityHint="오늘은 다시 묻지 않아요" onPress={close} />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
  actions: { gap: 12 },
});
