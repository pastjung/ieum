import { useEffect, useState } from "react";
import { Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQueryClient } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { BigInput } from "@/components/BigInput";
import { AppButton } from "@/components/AppButton";
import { authService } from "@/services/authService";
import { describeAuthError } from "@/services/authErrors";
import { announce, hapticError } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V27a 회원 탈퇴 확인 — 계속 사용이 기본 행동(primary) */
export default function Withdraw() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const [password, setPassword] = useState("");
  const queryClient = useQueryClient();

  useEffect(() => {
    announce("회원 탈퇴예요. 정말 떠나시겠어요? 탈퇴하면 되돌릴 수 없어요. 확인을 위해 비밀번호를 입력하세요.");
  }, []);

  const withdraw = async () => {
    // 여기는 이미 쓰고 있는 비밀번호를 확인하는 자리다. 생성 정책(길이)을 다시 들이대면
    // 정책이 바뀔 때 기존 사용자가 탈퇴조차 못 하고, 틀린 자릿수를 안내하게 된다.
    // 맞는지 아닌지는 서버만 알고, 틀리면 403 CURRENT_PASSWORD_MISMATCH로 돌아온다
    if (password.length === 0) {
      void hapticError();
      announce("비밀번호를 입력해 주세요.");
      return;
    }
    try {
      await authService.withdraw(password);
      announce("탈퇴가 완료됐어요. 그동안 함께해서 고마웠어요.");
      // 캐시를 남기면 새 계정으로 가입·로그인해도 홈이 삭제된 계정의 이름을 낭독한다
      queryClient.clear();
      router.replace("/auth/sign-in");
    } catch (error) {
      // 비밀번호 불일치는 403 CURRENT_PASSWORD_MISMATCH다. 화면에 머물러 다시 입력받는다
      void hapticError();
      announce(describeAuthError(error));
    }
  };

  return (
    <ScreenShell>
      <BackHeader title="회원 탈퇴" />
      <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text }}>
        정말{"\n"}떠나시겠어요?
      </Text>
      <Text style={{ fontSize: 16 * fontScale, color: theme.color.textMuted, lineHeight: 29 }}>탈퇴하면 되돌릴 수 없어요.</Text>
      <BigInput
        label="확인을 위해 비밀번호를 입력하세요"
        accessibilityLabel="비밀번호"
        value={password}
        onChangeText={setPassword}
        secureTextEntry
        focused
      />
      <View style={{ marginTop: "auto", gap: 12 }}>
        <AppButton label="탈퇴하기" variant="secondary" onPress={() => void withdraw()} accessibilityHint="계정을 삭제해요. 되돌릴 수 없어요" />
        <AppButton label="계속 사용할래요" onPress={() => router.back()} />
      </View>
    </ScreenShell>
  );
}
