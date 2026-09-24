import { useEffect, useState } from "react";
import { Text, View } from "react-native";
import * as Linking from "expo-linking";
import { useRouter } from "expo-router";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { signUpSchema, PASSWORD_POLICY_TEXT } from "@ieum/contracts";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { BigInput } from "@/components/BigInput";
import { AppButton } from "@/components/AppButton";
import { authService } from "@/services/authService";
import { describeActionTokenError } from "@/services/authErrors";
import { extractActionToken } from "@/services/actionToken";
import { announce, hapticError, hapticSuccess } from "@/a11y";
import { useTheme } from "@/theme";

/**
 * 새 비밀번호는 가입과 같은 정책을 따른다 (API 명세 3.4.2절). 규칙을 여기서 새로 쓰지 않고
 * 계약의 것을 그대로 가져온다 — 두 곳에 적으면 언젠가 갈라진다.
 */
const schema = z
  .object({
    newPassword: signUpSchema.shape.password,
    newPasswordConfirm: z.string(),
  })
  .refine((v) => v.newPassword === v.newPasswordConfirm, {
    path: ["newPasswordConfirm"],
    message: "비밀번호가 서로 달라요",
  });
type Input = z.infer<typeof schema>;

/** 토큰 없이 들어온 경우. 링크를 직접 열었거나 fragment가 유실됐다 */
function MissingToken() {
  const router = useRouter();
  const theme = useTheme();

  useEffect(() => {
    announce("링크에 필요한 정보가 없어요. 비밀번호 재설정 메일을 새로 요청해 주세요.");
  }, []);

  return (
    <ScreenShell>
      <BackHeader title="비밀번호 재설정" />
      <View style={{ flex: 1, justifyContent: "center", gap: 16 }}>
        <Text accessibilityRole="header" style={{ fontSize: 26, fontWeight: "900", lineHeight: 38, color: theme.color.text }}>
          링크를 다시{"\n"}요청해 주세요
        </Text>
        <Text style={{ fontSize: 17, color: theme.color.textMuted, lineHeight: 29 }}>
          링크에 필요한 정보가 없어요. 메일의 링크를 다시 누르거나, 재설정 메일을 새로 요청해 주세요.
        </Text>
      </View>
      <View style={{ gap: 12 }}>
        <AppButton label="재설정 메일 다시 요청" onPress={() => router.replace("/auth/reset-password")} />
        <AppButton label="로그인 화면으로" variant="secondary" onPress={() => router.replace("/auth/sign-in")} />
      </View>
    </ScreenShell>
  );
}

function ConfirmForm({ token }: { token: string }) {
  const router = useRouter();
  const theme = useTheme();
  const [formError, setFormError] = useState<string | null>(null);
  const { control, handleSubmit } = useForm<Input>({
    resolver: zodResolver(schema),
    defaultValues: { newPassword: "", newPasswordConfirm: "" },
  });

  useEffect(() => {
    announce(`새 비밀번호를 입력하세요. ${PASSWORD_POLICY_TEXT}여야 해요.`);
  }, []);

  const fail = (message: string) => {
    void hapticError();
    setFormError(message);
    announce(message);
  };

  const onSubmit = handleSubmit(
    async ({ newPassword }) => {
      setFormError(null);
      try {
        await authService.completePasswordReset(token, newPassword);
        void hapticSuccess();
        // 계약이 자동 로그인을 금지한다. 비밀번호가 바뀌면 기존 세션도 전부 무효가 되므로
        // 사용자가 새 비밀번호로 직접 로그인해야 한다
        announce("비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요.");
        router.replace("/auth/sign-in");
      } catch (error) {
        fail(describeActionTokenError(error, "재설정 메일을 새로 요청해 주세요."));
      }
    },
    (errors) => {
      fail(errors.newPassword?.message ?? errors.newPasswordConfirm?.message ?? "입력을 확인해 주세요.");
    },
  );

  return (
    <ScreenShell>
      <BackHeader title="비밀번호 재설정" />
      <Text accessibilityRole="header" style={{ fontSize: 26, fontWeight: "900", lineHeight: 38, color: theme.color.text }}>
        새 비밀번호를{"\n"}입력하세요
      </Text>
      <Controller
        control={control}
        name="newPassword"
        render={({ field, fieldState }) => (
          <BigInput
            label="새 비밀번호"
            accessibilityLabel="새 비밀번호"
            hint={PASSWORD_POLICY_TEXT}
            value={field.value}
            onChangeText={field.onChange}
            secureTextEntry
            errorMessage={fieldState.error?.message}
          />
        )}
      />
      <Controller
        control={control}
        name="newPasswordConfirm"
        render={({ field, fieldState }) => (
          <BigInput
            label="새 비밀번호 확인"
            accessibilityLabel="새 비밀번호 확인"
            hint="같은 비밀번호를 한 번 더 입력해요"
            value={field.value}
            onChangeText={field.onChange}
            secureTextEntry
            errorMessage={fieldState.error?.message}
          />
        )}
      />
      <View style={{ marginTop: "auto", gap: 12 }}>
        {formError != null && (
          <Text
            accessibilityLiveRegion="polite"
            style={{ fontSize: 16, fontWeight: "700", lineHeight: 26, color: theme.color.primary }}
          >
            {formError}
          </Text>
        )}
        <AppButton label="비밀번호 바꾸기" onPress={() => void onSubmit()} />
      </View>
    </ScreenShell>
  );
}

/**
 * 메일 링크로 들어오는 비밀번호 재설정 완료 화면.
 *
 * 중계 페이지가 `ieum://auth/reset-password/confirm#token=...` 으로 넘겨준다. 토큰을
 * 라우트 파라미터가 아니라 딥링크 원문의 fragment에서 읽는 이유는 `actionToken.ts` 참고.
 */
export default function ResetPasswordConfirm() {
  const token = extractActionToken(Linking.useURL());
  return token == null ? <MissingToken /> : <ConfirmForm token={token} />;
}
