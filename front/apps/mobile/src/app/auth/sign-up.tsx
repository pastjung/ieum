import { useEffect, useState } from "react";
import { ScrollView, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { signUpSchema, PASSWORD_POLICY_TEXT, USERNAME_POLICY_TEXT } from "@ieum/contracts";
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

/**
 * 가입 폼 스키마 — 음성·화면 두 모드가 함께 쓴다.
 *
 * 비밀번호 확인은 서버가 받지 않는 입력이라 계약(`signUpSchema`)이 아니라 여기에 둔다.
 * 두 모드가 같은 규칙을 쓰도록 한 곳에서만 정의한다.
 */
const signUpFormSchema = signUpSchema
  .extend({ passwordConfirm: z.string() })
  .refine((v) => v.password === v.passwordConfirm, {
    path: ["passwordConfirm"],
    message: "비밀번호가 서로 달라요",
  });
type SignUpFormInput = z.infer<typeof signUpFormSchema>;

/* ─── 음성 중심: 한 화면에 한 입력, 4단계 (V01) ─── */

const steps = [
  {
    key: "username",
    title: "아이디를\n입력하세요",
    label: "아이디",
    hint: USERNAME_POLICY_TEXT,
    announceText: `회원가입 1단계. 아이디를 입력하세요. ${USERNAME_POLICY_TEXT}여야 해요.`,
  },
  {
    key: "password",
    title: "비밀번호를\n입력하세요",
    label: "비밀번호",
    hint: PASSWORD_POLICY_TEXT,
    announceText: `2단계. 비밀번호를 입력하세요. ${PASSWORD_POLICY_TEXT}여야 해요.`,
  },
  {
    key: "passwordConfirm",
    title: "비밀번호를\n다시 입력하세요",
    label: "비밀번호 확인",
    hint: "같은 비밀번호를 한 번 더 입력해요",
    announceText: "3단계. 확인을 위해 같은 비밀번호를 한 번 더 입력하세요.",
  },
  {
    key: "email",
    title: "이메일을\n입력하세요",
    label: "이메일",
    hint: "인증 메일을 받을 주소예요",
    announceText: "마지막 4단계. 이메일을 입력하세요. 인증 메일을 받을 주소예요.",
  },
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
  /** 중복 확인 결과 문구 */
  const [usernameCheck, setUsernameCheck] = useState<string | null>(null);
  /**
   * 확인을 통과한 아이디 자체를 기억한다. 단순한 boolean으로 두면 A로 확인하고 B로 가입하는
   * 구멍이 열린다 — 지금 입력값과 같을 때만 통과로 친다.
   */
  const [checkedUsername, setCheckedUsername] = useState<string | null>(null);
  const { control, trigger, getValues, setValue, formState } = useForm<SignUpFormInput>({
    resolver: zodResolver(signUpFormSchema),
    defaultValues: { username: "", password: "", passwordConfirm: "", email: "" },
    mode: "onSubmit",
  });

  const current = steps[step]!;

  useEffect(() => {
    announce(notice ?? current.announceText);
  }, [current, notice]);

  /** 확인 결과는 화면과 낭독 양쪽에 남긴다. 음성이 꺼져 있어도 사용자가 알 수 있어야 한다 */
  const setUsernameCheckAndAnnounce = (message: string) => {
    setUsernameCheck(message);
    announce(message);
  };

  /** 아이디 중복 확인 */
  const checkUsername = async () => {
    const valid = await trigger("username");
    if (!valid) {
      void hapticError();
      setCheckedUsername(null);
      setUsernameCheck(null);
      const message = formState.errors.username?.message;
      if (message) announce(message);
      return;
    }
    try {
      const result = await authService.checkUsernameAvailability(getValues("username"));
      if (!result.available) {
        void hapticError();
        setCheckedUsername(null);
        return setUsernameCheckAndAnnounce("이미 쓰고 있는 아이디예요. 다른 아이디로 해 주세요.");
      }
      // 서버가 정규화한 값으로 입력을 맞춘다. 그래야 확인한 아이디와 가입할 아이디가 같다
      setValue("username", result.username);
      setCheckedUsername(result.username);
      setUsernameCheckAndAnnounce("사용 가능한 아이디예요.");
    } catch (error) {
      void hapticError();
      setCheckedUsername(null);
      setUsernameCheckAndAnnounce(describeAuthError(error));
    }
  };

  const nextStep = async () => {
    const valid = await trigger(current.key);
    if (!valid) {
      void hapticError();
      const message = formState.errors[current.key]?.message;
      if (message) announce(message);
      return;
    }
    // 확인을 통과한 그 아이디여야 넘어간다. 확인 후 값을 고쳤으면 통과가 아니다
    if (current.key === "username" && getValues("username") !== checkedUsername) {
      void hapticError();
      setUsernameCheckAndAnnounce("중복 확인을 먼저 눌러 주세요.");
      return;
    }
    if (step < steps.length - 1) {
      setNotice(null);
      setStep(step + 1);
      return;
    }
    // passwordConfirm은 서버가 받지 않는다. 비밀번호 원문을 필요 없이 한 번 더 실어 보내지 않는다
    const { username, password, email } = getValues();
    try {
      await authService.signUp({ username, password, email });
      router.replace({ pathname: "/auth/verify-email", params: { email } });
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
      // notice로 넘기면 아래 낭독 effect가 읽고 화면에도 남는다. announce만 하면
      // 음성이 꺼진 사용자에게는 진동 말고 아무 신호도 남지 않는다
      setNotice(describeAuthError(error));
    }
  };

  return (
    <ScreenShell>
      <BackHeader
        title={`회원가입 · ${step + 1} / ${steps.length}`}
        onBack={() => {
          // 1단계에서만 화면을 떠난다. 그 위에서는 이전 입력으로 돌아가야 한다 —
          // 기본 동작(router.back)은 여기서 입력을 전부 버리는 것과 같다
          if (step === 0) {
            router.back();
            return;
          }
          setNotice(null);
          setStep(step - 1);
        }}
      />
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
            hint={current.hint}
            value={field.value}
            onChangeText={(text) => {
              field.onChange(text);
              // 확인했던 아이디가 아니게 됐다. 결과를 남겨 두면 거짓이 된다
              if (current.key === "username") {
                setUsernameCheck(null);
                setCheckedUsername(null);
              }
            }}
            secureTextEntry={current.key === "password" || current.key === "passwordConfirm"}
            keyboardType={current.key === "email" ? "email-address" : "default"}
            autoCapitalize="none"
            autoFocus
            errorMessage={fieldState.error?.message}
          />
        )}
      />
      {current.key === "username" && (
        <View style={{ alignItems: "flex-end", gap: 8 }}>
          <AppButton
            label="중복 확인"
            variant="compact"
            accessibilityHint="이 아이디를 쓸 수 있는지 확인해요"
            onPress={() => void checkUsername()}
          />
          {usernameCheck != null && (
            <Text
              accessibilityLiveRegion="polite"
              style={{ fontSize: 15, fontWeight: "700", color: theme.color.primary, textAlign: "right" }}
            >
              {usernameCheck}
            </Text>
          )}
        </View>
      )}
      <View style={{ marginTop: "auto", gap: 12 }}>
        {notice != null && (
          <Text
            accessibilityLiveRegion="polite"
            style={{ fontSize: 16, fontWeight: "700", lineHeight: 26, color: theme.color.primary }}
          >
            {notice}
          </Text>
        )}
        <AppButton label={step < steps.length - 1 ? "다음" : "가입하기"} onPress={() => void nextStep()} />
      </View>
    </ScreenShell>
  );
}

/* ─── 화면 중심: 단일 폼 (L01) ─── */

function ScreenSignUp() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const [usernameCheck, setUsernameCheck] = useState<string | null>(null);
  /** 확인을 통과한 아이디. 지금 입력값과 같을 때만 통과로 친다 */
  const [checkedUsername, setCheckedUsername] = useState<string | null>(null);
  /** 서버가 거절한 이유. 입력칸 오류와 달리 붙일 자리가 없어 따로 띄운다 */
  const [formError, setFormError] = useState<string | null>(null);
  const { control, handleSubmit, trigger, formState, getValues, setValue } =
    useForm<SignUpFormInput>({
      resolver: zodResolver(signUpFormSchema),
      defaultValues: { username: "", email: "", password: "", passwordConfirm: "" },
    });

  const setUsernameCheckAndAnnounce = (message: string) => {
    setUsernameCheck(message);
    announce(message);
  };

  /** 음성 모드와 같은 동작. 근거는 VoiceSignUp의 checkUsername 주석 참고 */
  const checkUsername = async () => {
    const valid = await trigger("username");
    if (!valid) {
      void hapticError();
      setCheckedUsername(null);
      setUsernameCheck(null);
      const message = formState.errors.username?.message;
      if (message) announce(message);
      return;
    }
    try {
      const result = await authService.checkUsernameAvailability(getValues("username"));
      if (!result.available) {
        void hapticError();
        setCheckedUsername(null);
        return setUsernameCheckAndAnnounce("이미 쓰고 있는 아이디예요. 다른 아이디로 해 주세요.");
      }
      setValue("username", result.username);
      setCheckedUsername(result.username);
      setUsernameCheckAndAnnounce("사용 가능한 아이디예요.");
    } catch (error) {
      void hapticError();
      setCheckedUsername(null);
      setUsernameCheckAndAnnounce(describeAuthError(error));
    }
  };

  useEffect(() => {
    announce(`이음 계정 만들기예요. 아이디, 이메일, 비밀번호를 입력하세요. 비밀번호는 ${PASSWORD_POLICY_TEXT}여야 해요.`);
  }, []);

  const onSubmit = handleSubmit(
    async ({ username, email, password }) => {
      setFormError(null);
      // 확인을 통과한 그 아이디여야 가입한다. 확인 후 값을 고쳤으면 통과가 아니다
      if (username !== checkedUsername) {
        void hapticError();
        setUsernameCheckAndAnnounce("중복 확인을 먼저 눌러 주세요.");
        return;
      }
      try {
        await authService.signUp({ username, email, password });
        router.replace({ pathname: "/auth/verify-email", params: { email } });
      } catch (error) {
        void hapticError();
        const message = describeAuthError(error);
        setFormError(message);
        announce(message);
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
            <BigInput
              label="아이디"
              accessibilityLabel="아이디"
              hint={USERNAME_POLICY_TEXT}
              value={field.value}
              onChangeText={(text) => {
                field.onChange(text);
                // 확인했던 아이디가 아니게 됐다. 결과를 남겨 두면 거짓이 된다
                setUsernameCheck(null);
                setCheckedUsername(null);
              }}
              autoCapitalize="none"
              errorMessage={fieldState.error?.message}
            />
          )}
        />
        <View style={{ alignItems: "flex-end", gap: 8, marginTop: -6 }}>
          <AppButton
            label="중복 확인"
            variant="compact"
            accessibilityHint="이 아이디를 쓸 수 있는지 확인해요"
            onPress={() => void checkUsername()}
          />
          {usernameCheck != null && (
            <Text
              accessibilityLiveRegion="polite"
              style={{ fontSize: 14 * fontScale, fontWeight: "700", color: theme.color.primary, textAlign: "right" }}
            >
              {usernameCheck}
            </Text>
          )}
        </View>
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
            <BigInput label="비밀번호" accessibilityLabel="비밀번호" hint={PASSWORD_POLICY_TEXT} value={field.value} onChangeText={field.onChange} secureTextEntry errorMessage={fieldState.error?.message} />
          )}
        />
        <Controller
          control={control}
          name="passwordConfirm"
          render={({ field, fieldState }) => (
            <BigInput label="비밀번호 확인" accessibilityLabel="비밀번호 확인" value={field.value} onChangeText={field.onChange} secureTextEntry errorMessage={fieldState.error?.message} />
          )}
        />
        {formError != null && (
          <Text
            accessibilityLiveRegion="polite"
            style={{ fontSize: 15 * fontScale, fontWeight: "700", lineHeight: 25, color: theme.color.primary }}
          >
            {formError}
          </Text>
        )}
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
