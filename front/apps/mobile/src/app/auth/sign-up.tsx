import { useEffect, useState } from "react";
import { ScrollView, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { signUpSchema, type SignUpInput } from "@ieum/contracts";
import { ApiError } from "@ieum/api-client";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { BigInput } from "@/components/BigInput";
import { AppButton } from "@/components/AppButton";
import { authService } from "@/services/authService";
import { describeAuthError } from "@/services/authErrors";
import { useSettingsStore } from "@/stores/settingsStore";
import { announce, hapticError } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/* ─── 음성 중심: 한 화면에 한 입력, 3단계 (V01) ─── */

const steps = [
  { key: "username", title: "아이디를\n입력하세요", label: "아이디", announceText: "회원가입 1단계. 아이디를 입력하세요." },
  { key: "password", title: "비밀번호를\n입력하세요", label: "비밀번호", announceText: "2단계. 비밀번호를 입력하세요. 8자 이상이에요." },
  { key: "email", title: "이메일을\n입력하세요", label: "이메일", announceText: "마지막 3단계. 이메일을 입력하세요." },
] as const;

function VoiceSignUp() {
  const router = useRouter();
  const theme = useTheme();
  const [step, setStep] = useState(0);
  /**
   * 단계를 되돌리며 함께 읽어 줄 문장. 단계 이동은 아래 effect가 낭독하므로,
   * catch에서 바로 announce하면 그 낭독이 곧장 덮어써 버린다 — 상태로 넘겨 한 번만 읽는다
   */
  const [notice, setNotice] = useState<string | null>(null);
  const { control, trigger, getValues, setValue, formState } = useForm<SignUpInput>({
    resolver: zodResolver(signUpSchema),
    defaultValues: { username: "", password: "", email: "" },
    mode: "onSubmit",
  });

  const current = steps[step]!;

  useEffect(() => {
    announce(notice ?? current.announceText);
  }, [current, notice]);

  const nextStep = async () => {
    const valid = await trigger(current.key);
    if (!valid) {
      void hapticError();
      const message = formState.errors[current.key]?.message;
      if (message) announce(message);
      return;
    }
    if (step < steps.length - 1) {
      setNotice(null);
      setStep(step + 1);
      return;
    }
    const values = getValues();
    try {
      await authService.signUp(values);
      router.replace({ pathname: "/auth/verify-email", params: { email: values.email } });
    } catch (error) {
      void hapticError();
      // 음성 가입은 한 화면에 한 입력이라 단계를 되돌릴 UI가 없다. 안내만 하고 3단계에
      // 머물면 사용자는 아이디를 바꿀 방법이 없다. 백엔드가 아이디·이메일 충돌을 한 code로
      // 합쳤으므로(열거 방지) 첫 입력인 아이디 단계로 보내는 것이 안전한 일반화다
      if (error instanceof ApiError && error.code === "ACCOUNT_IDENTIFIER_UNAVAILABLE") {
        setValue("username", "");
        setStep(0);
        setNotice(`${describeAuthError(error)} 1단계로 돌아왔어요. 아이디를 다시 입력하세요.`);
        return;
      }
      announce(describeAuthError(error));
    }
  };

  return (
    <ScreenShell>
      <BackHeader title={`회원가입 · ${step + 1} / ${steps.length}`} />
      <Text accessibilityRole="header" style={{ fontSize: 28, fontWeight: "900", lineHeight: 40, color: theme.color.text }}>
        {current.title}
      </Text>
      <Controller
        key={current.key}
        control={control}
        name={current.key}
        render={({ field, fieldState }) => (
          <BigInput
            accessibilityLabel={current.label}
            value={field.value}
            onChangeText={field.onChange}
            secureTextEntry={current.key === "password"}
            keyboardType={current.key === "email" ? "email-address" : "default"}
            autoCapitalize="none"
            autoFocus
            focused
            errorMessage={fieldState.error?.message}
          />
        )}
      />
      <View style={{ marginTop: "auto", gap: 12 }}>
        <AppButton label={step < steps.length - 1 ? "다음" : "가입하기"} onPress={() => void nextStep()} />
      </View>
    </ScreenShell>
  );
}

/* ─── 화면 중심: 단일 폼 (L01) ─── */

const screenSchema = signUpSchema
  .extend({ passwordConfirm: z.string() })
  .refine((v) => v.password === v.passwordConfirm, {
    path: ["passwordConfirm"],
    message: "비밀번호가 서로 달라요",
  });
type ScreenInput = z.infer<typeof screenSchema>;

function ScreenSignUp() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { control, handleSubmit } = useForm<ScreenInput>({
    resolver: zodResolver(screenSchema),
    defaultValues: { username: "", email: "", password: "", passwordConfirm: "" },
  });

  useEffect(() => {
    announce("이음 계정 만들기예요. 아이디, 이메일, 비밀번호를 입력하세요.");
  }, []);

  const onSubmit = handleSubmit(
    async ({ username, email, password }) => {
      try {
        await authService.signUp({ username, email, password });
        router.replace({ pathname: "/auth/verify-email", params: { email } });
      } catch (error) {
        void hapticError();
        announce(describeAuthError(error));
      }
    },
    (errors) => {
      void hapticError();
      const first =
        errors.username?.message ?? errors.email?.message ?? errors.password?.message ?? errors.passwordConfirm?.message;
      if (first) announce(first);
    },
  );

  return (
    <ScreenShell>
      <BackHeader title="회원가입" />
      <ScrollView contentContainerStyle={{ gap: 14, paddingBottom: 20 }} showsVerticalScrollIndicator={false}>
        <Text accessibilityRole="header" style={{ fontSize: 24 * fontScale, fontWeight: "900", color: theme.color.text }}>
          이음 계정 만들기
        </Text>
        <Controller
          control={control}
          name="username"
          render={({ field, fieldState }) => (
            <BigInput label="아이디" accessibilityLabel="아이디" value={field.value} onChangeText={field.onChange} autoCapitalize="none" errorMessage={fieldState.error?.message} />
          )}
        />
        <Controller
          control={control}
          name="email"
          render={({ field, fieldState }) => (
            <BigInput label="이메일" accessibilityLabel="이메일" value={field.value} onChangeText={field.onChange} keyboardType="email-address" autoCapitalize="none" errorMessage={fieldState.error?.message} />
          )}
        />
        <Controller
          control={control}
          name="password"
          render={({ field, fieldState }) => (
            <BigInput label="비밀번호 · 8자 이상" accessibilityLabel="비밀번호" value={field.value} onChangeText={field.onChange} secureTextEntry errorMessage={fieldState.error?.message} />
          )}
        />
        <Controller
          control={control}
          name="passwordConfirm"
          render={({ field, fieldState }) => (
            <BigInput label="비밀번호 확인" accessibilityLabel="비밀번호 확인" value={field.value} onChangeText={field.onChange} secureTextEntry errorMessage={fieldState.error?.message} />
          )}
        />
        <AppButton label="가입하기" onPress={() => void onSubmit()} />
        <View style={{ flexDirection: "row", justifyContent: "center", gap: 8, alignItems: "center" }}>
          <Text style={{ fontSize: 15 * fontScale, color: theme.color.textMuted }}>이미 계정이 있나요?</Text>
          <Text
            accessibilityRole="link"
            accessibilityLabel="로그인으로 가기"
            onPress={() => router.replace("/auth/sign-in")}
            style={{ fontSize: 15 * fontScale, fontWeight: "800", color: theme.color.primary }}
          >
            로그인
          </Text>
        </View>
      </ScrollView>
    </ScreenShell>
  );
}

/** V01(음성 중심 3단계) · L01(화면 중심 단일 폼) 회원가입 */
export default function SignUp() {
  const mode = useSettingsStore((s) => s.mode);
  return mode === "screen" ? <ScreenSignUp /> : <VoiceSignUp />;
}
