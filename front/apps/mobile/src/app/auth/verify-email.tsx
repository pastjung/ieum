import { useEffect, useState } from "react";
import { Text, View } from "react-native";
import * as Linking from "expo-linking";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { BigInput } from "@/components/BigInput";
import { AppButton } from "@/components/AppButton";
import { authService } from "@/services/authService";
import { describeActionTokenError, describeAuthError } from "@/services/authErrors";
import { extractActionToken } from "@/services/actionToken";
import { announce, hapticError, hapticSuccess } from "@/a11y";
import { useTheme } from "@/theme";

const schema = z.object({ email: z.string().email("이메일 형식을 확인해 주세요") });
type Input = z.infer<typeof schema>;

/**
 * 202는 접수만 뜻한다. "보냈어요"라고 단정하면 그 주소로 가입된 계정이 있다는 사실을
 * 알려 주는 셈이라 계정 열거 취약점이 된다 — 발송을 확신할 수 있는 경로에서만 단정한다
 */
const REQUEST_ACCEPTED = "요청을 접수했어요. 가입된 이메일이면 인증 메일이 도착해요. 메일함을 확인해 주세요.";

/* ─── 메일 링크 경로 — 중계 페이지가 ieum://auth/verify-email#token=... 으로 넘겨준 상태 ─── */

function Verifying({ token }: { token: string }) {
  const router = useRouter();
  const theme = useTheme();
  const [state, setState] = useState<"working" | "done" | "failed">("working");
  const [message, setMessage] = useState("인증을 확인하고 있어요. 잠시만 기다려 주세요.");

  useEffect(() => {
    let cancelled = false;
    announce("인증을 확인하고 있어요.");

    void authService
      .completeEmailVerification(token)
      .then(() => {
        if (cancelled) return;
        void hapticSuccess();
        setState("done");
        // 계약이 자동 로그인을 금지한다. 인증만 끝내고 사용자가 직접 로그인한다
        setMessage("이메일 인증이 끝났어요. 이제 로그인할 수 있어요.");
        announce("이메일 인증이 끝났어요. 이제 로그인할 수 있어요.");
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        void hapticError();
        setState("failed");
        const text = describeActionTokenError(error, "인증 메일을 새로 요청해 주세요.");
        setMessage(text);
        announce(text);
      });

    return () => {
      cancelled = true;
    };
  }, [token]);

  return (
    <ScreenShell>
      <View style={{ flex: 1, alignItems: "center", justifyContent: "center", gap: 18 }}>
        <Text accessibilityRole="header" style={{ fontSize: 28, fontWeight: "900", lineHeight: 40, color: theme.color.text, textAlign: "center" }}>
          이메일 인증
        </Text>
        <Text
          accessibilityLiveRegion="polite"
          style={{ fontSize: 17, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}
        >
          {message}
        </Text>
      </View>
      {state !== "working" && (
        <View style={{ gap: 12 }}>
          <AppButton
            label={state === "done" ? "로그인하러 가기" : "로그인 화면으로"}
            onPress={() => router.replace("/auth/sign-in")}
          />
        </View>
      )}
    </ScreenShell>
  );
}

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
  // 딥링크 fragment는 라우트 파라미터로 오지 않는다. 앱을 연 URL 원문에서 직접 읽는다
  const token = extractActionToken(Linking.useURL());

  if (token != null) return <Verifying token={token} />;
  return email ? <SentNotice email={email} /> : <RequestForm />;
}
