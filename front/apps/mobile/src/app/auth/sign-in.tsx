import { useEffect } from "react";
import { Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { signInSchema, type SignInInput } from "@ieum/contracts";
import { ScreenShell } from "@/components/ScreenShell";
import { BigInput } from "@/components/BigInput";
import { AppButton } from "@/components/AppButton";
import { authService } from "@/services/authService";
import { announce, hapticError } from "@/a11y";
import { useTheme } from "@/theme";

/** V03 로그인 */
export default function SignIn() {
  const router = useRouter();
  const theme = useTheme();
  const { control, handleSubmit } = useForm<SignInInput>({
    resolver: zodResolver(signInSchema),
    defaultValues: { username: "", password: "" },
  });

  useEffect(() => {
    announce("다시 만나서 반가워요. 아이디와 비밀번호를 입력하세요.");
  }, []);

  const onSubmit = handleSubmit(
    async (values) => {
      const { userName } = await authService.signIn(values);
      router.replace({ pathname: "/auth/welcome", params: { userName } });
    },
    (errors) => {
      void hapticError();
      const first = errors.username?.message ?? errors.password?.message;
      if (first) announce(first);
    },
  );

  return (
    <ScreenShell>
      <Text accessibilityRole="header" style={{ fontSize: 28, fontWeight: "900", lineHeight: 40, color: theme.color.text, marginTop: 8 }}>
        다시 만나서{"\n"}반가워요
      </Text>
      <Controller
        control={control}
        name="username"
        render={({ field, fieldState }) => (
          <BigInput
            label="아이디"
            accessibilityLabel="아이디"
            value={field.value}
            onChangeText={field.onChange}
            autoCapitalize="none"
            errorMessage={fieldState.error?.message}
          />
        )}
      />
      <Controller
        control={control}
        name="password"
        render={({ field, fieldState }) => (
          <BigInput
            label="비밀번호"
            accessibilityLabel="비밀번호"
            value={field.value}
            onChangeText={field.onChange}
            secureTextEntry
            focused
            errorMessage={fieldState.error?.message}
          />
        )}
      />
      <View style={{ marginTop: "auto", gap: 12 }}>
        <AppButton label="로그인" onPress={() => void onSubmit()} />
        <AppButton
          label="비밀번호를 잊었어요"
          variant="compact"
          onPress={() => announce("비밀번호 재설정은 준비 중이에요. 다음 업데이트에서 만나요.")}
        />
        <AppButton label="회원가입" variant="secondary" onPress={() => router.push("/auth/sign-up")} />
      </View>
    </ScreenShell>
  );
}
