import { useEffect, useState } from "react";
import { Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { BigInput } from "@/components/BigInput";
import { AppButton } from "@/components/AppButton";
import { authService } from "@/services/authService";
import { describeAuthError } from "@/services/authErrors";
import { announce, hapticError } from "@/a11y";
import { useTheme } from "@/theme";

const schema = z.object({ email: z.string().email("이메일 형식을 확인해 주세요") });
type Input = z.infer<typeof schema>;

/**
 * 202는 접수만 뜻한다. "보냈어요"라고 단정하면 그 주소로 가입된 계정이 있다는 사실을
 * 알려 주는 셈이라 계정 열거 취약점이 된다 — 발송을 확신할 수 있는 경로에서만 단정한다
 */
const REQUEST_ACCEPTED = "요청을 접수했어요. 가입된 이메일이면 인증 메일이 도착해요. 메일함을 확인해 주세요.";

/* ─── 가입 직후 경로 — 서버가 실제로 인증 메일을 발송한 상태 ─── */

function SentNotice({ email }: { email: string }) {
  const router = useRouter();
  const theme = useTheme();

  useEffect(() => {
    announce(`메일함을 확인해 주세요. ${email}으로 인증 링크를 보냈어요.`);
  }, [email]);

  return (
    <ScreenShell>
      <View style={{ flex: 1, alignItems: "center", justifyContent: "center", gap: 18 }}>
        <Text accessibilityRole="header" style={{ fontSize: 30, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
          메일함을{"\n"}확인해 주세요
        </Text>
        <Text style={{ fontSize: 17, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
          {email}으로 인증 링크를 보냈어요.
        </Text>
      </View>
      <View style={{ gap: 12 }}>
        <AppButton label="인증 확인했어요" onPress={() => router.replace("/auth/sign-in")} />
        <AppButton
          label="메일 다시 보내기"
          variant="secondary"
          onPress={() => {
            void authService
              .requestEmailVerification(email)
              .then(() => announce("인증 메일을 다시 보냈어요. 메일함을 확인해 주세요."))
              .catch((error: unknown) => {
                void hapticError();
                announce(describeAuthError(error));
              });
          }}
        />
      </View>
    </ScreenShell>
  );
}

/* ─── 로그인 403 경로 — 아이디로 로그인해 이메일을 모르고, 발송된 메일도 없다 ─── */

function RequestForm() {
  const router = useRouter();
  const theme = useTheme();
  const [requested, setRequested] = useState(false);
  const { control, handleSubmit } = useForm<Input>({
    resolver: zodResolver(schema),
    defaultValues: { email: "" },
  });

  useEffect(() => {
    announce("이메일 인증이 필요해요. 가입한 이메일을 알려 주시면 인증 메일을 보내 드릴게요.");
  }, []);

  const onSubmit = handleSubmit(
    async ({ email }) => {
      try {
        await authService.requestEmailVerification(email);
        setRequested(true);
        announce(REQUEST_ACCEPTED);
      } catch (error) {
        void hapticError();
        announce(describeAuthError(error));
      }
    },
    (errors) => {
      void hapticError();
      if (errors.email?.message) announce(errors.email.message);
    },
  );

  return (
    <ScreenShell>
      <BackHeader title="이메일 인증" />
      <Text accessibilityRole="header" style={{ fontSize: 26, fontWeight: "900", lineHeight: 38, color: theme.color.text }}>
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
      <Text accessibilityLiveRegion="polite" style={{ fontSize: 16, color: theme.color.textMuted, lineHeight: 27 }}>
        {requested ? REQUEST_ACCEPTED : "인증 메일을 보내 드려요."}
      </Text>
      <View style={{ marginTop: "auto", gap: 12 }}>
        <AppButton label="인증 메일 보내기" onPress={() => void onSubmit()} />
        <AppButton label="인증 확인했어요" variant="secondary" onPress={() => router.replace("/auth/sign-in")} />
      </View>
    </ScreenShell>
  );
}

/**
 * V02 이메일 인증 안내.
 *
 * 가입 직후에는 email 파라미터가 넘어오고 그때만 메일이 실제로 발송돼 있다.
 * 로그인 403(EMAIL_VERIFICATION_REQUIRED)으로 들어오면 아이디로 로그인했기 때문에
 * 이메일을 알 수 없고 발송된 메일도 없다 — "보냈어요"는 사실이 아니고,
 * 재발송이 가장 필요한 사용자가 재발송을 못 하는 상태가 된다
 */
export default function VerifyEmail() {
  const { email } = useLocalSearchParams<{ email?: string }>();
  return email ? <SentNotice email={email} /> : <RequestForm />;
}
