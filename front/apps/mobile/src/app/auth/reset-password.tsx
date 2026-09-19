import { useEffect } from "react";
import { Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { BigInput } from "@/components/BigInput";
import { AppButton } from "@/components/AppButton";
import { announce, hapticError } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

const schema = z.object({ email: z.string().email("이메일 형식을 확인해 주세요") });
type Input = z.infer<typeof schema>;

/** V28 비밀번호 재설정 */
export default function ResetPassword() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { control, handleSubmit } = useForm<Input>({
    resolver: zodResolver(schema),
    defaultValues: { email: "" },
  });

  useEffect(() => {
    announce("비밀번호 재설정이에요. 가입한 이메일을 알려 주세요. 재설정 링크를 보내 드려요.");
  }, []);

  const onSubmit = handleSubmit(
    ({ email }) => {
      announce(`${email}로 재설정 링크를 보냈어요. 메일함을 확인해 주세요.`);
      router.back();
    },
    (errors) => {
      void hapticError();
      if (errors.email?.message) announce(errors.email.message);
    },
  );

  return (
    <ScreenShell>
      <BackHeader title="비밀번호 재설정" />
      <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text }}>
        가입한 이메일을{"\n"}알려 주세요
      </Text>
      <Controller
        control={control}
        name="email"
        render={({ field, fieldState }) => (
          <BigInput
            accessibilityLabel="이메일"
            value={field.value}
            onChangeText={field.onChange}
            keyboardType="email-address"
            autoCapitalize="none"
            focused
            errorMessage={fieldState.error?.message}
          />
        )}
      />
      <Text style={{ fontSize: 16 * fontScale, color: theme.color.textMuted, lineHeight: 27 }}>재설정 링크를 보내 드려요.</Text>
      <View style={{ marginTop: "auto" }}>
        <AppButton label="재설정 메일 보내기" onPress={() => void onSubmit()} />
      </View>
    </ScreenShell>
  );
}
