import { useEffect } from "react";
import { Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { AppButton } from "@/components/AppButton";
import { authService } from "@/services/authService";
import { describeAuthError } from "@/services/authErrors";
import { announce, hapticError } from "@/a11y";
import { useTheme } from "@/theme";

/** V02 이메일 인증 안내 */
export default function VerifyEmail() {
  const router = useRouter();
  const theme = useTheme();
  const { email } = useLocalSearchParams<{ email?: string }>();
  const target = email ?? "가입한 이메일";

  useEffect(() => {
    announce(`메일함을 확인해 주세요. ${target}으로 인증 링크를 보냈어요.`);
  }, [target]);

  return (
    <ScreenShell>
      <View style={{ flex: 1, alignItems: "center", justifyContent: "center", gap: 18 }}>
        <Text accessibilityRole="header" style={{ fontSize: 30, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
          메일함을{"\n"}확인해 주세요
        </Text>
        <Text style={{ fontSize: 17, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
          {target}으로 인증 링크를 보냈어요.
        </Text>
      </View>
      <View style={{ gap: 12 }}>
        <AppButton label="인증 확인했어요" onPress={() => router.replace("/auth/sign-in")} />
        <AppButton
          label="메일 다시 보내기"
          variant="secondary"
          disabled={!email}
          onPress={() => {
            if (!email) return;
            void authService
              .requestEmailVerification(email)
              .then(() => announce("인증 메일을 다시 보냈어요. 메일함을 확인해 주세요."))
              .catch((error) => {
                void hapticError();
                announce(describeAuthError(error));
              });
          }}
        />
      </View>
    </ScreenShell>
  );
}
