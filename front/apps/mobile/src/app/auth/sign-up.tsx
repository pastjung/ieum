import { useEffect, useState } from "react";
import { Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { signUpSchema, type SignUpInput } from "@ieum/contracts";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { BigInput } from "@/components/BigInput";
import { AppButton } from "@/components/AppButton";
import { authService } from "@/services/authService";
import { announce, hapticError } from "@/a11y";
import { useTheme } from "@/theme";

const steps = [
  { key: "username", title: "아이디를\n입력하세요", label: "아이디", announceText: "회원가입 1단계. 아이디를 입력하세요." },
  { key: "password", title: "비밀번호를\n입력하세요", label: "비밀번호", announceText: "2단계. 비밀번호를 입력하세요. 8자 이상이에요." },
  { key: "email", title: "이메일을\n입력하세요", label: "이메일", announceText: "마지막 3단계. 이메일을 입력하세요." },
] as const;

/** V01 회원가입 · 3단계 */
export default function SignUp() {
  const router = useRouter();
  const theme = useTheme();
  const [step, setStep] = useState(0);
  const { control, trigger, getValues, formState } = useForm<SignUpInput>({
    resolver: zodResolver(signUpSchema),
    defaultValues: { username: "", password: "", email: "" },
    mode: "onSubmit",
  });

  const current = steps[step]!;

  useEffect(() => {
    announce(current.announceText);
  }, [current]);

  const nextStep = async () => {
    const valid = await trigger(current.key);
    if (!valid) {
      void hapticError();
      const message = formState.errors[current.key]?.message;
      if (message) announce(message);
      return;
    }
    if (step < steps.length - 1) {
      setStep(step + 1);
      return;
    }
    const values = getValues();
    await authService.signUp(values);
    router.replace({ pathname: "/auth/verify-email", params: { email: values.email } });
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
