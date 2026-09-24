import { z } from "zod";

/** 점자 셀: 6비트 정수 (0~63) */
export const cellSchema = z.number().int().min(0).max(63);

/**
 * 비밀번호 정책 — 백엔드 `PasswordPolicy`와 같은 값이어야 한다 (API 명세 3.4.2절).
 *
 * 명세가 "프론트 검증은 안내용이며 동일 규칙을 백엔드가 다시 적용한다"고 정했다.
 * 숫자를 여기 한 곳에만 두는 이유는 앞뒤가 갈라지는 것을 막기 위해서다 — 실제로
 * 프론트가 8자, 백엔드가 15자였던 적이 있고, 그때 사용자는 가입 버튼을 누른 뒤에야
 * "비밀번호 정책을 확인해 주세요"라는 문구를 받았다. 무엇을 고쳐야 하는지는 알 수 없었다.
 */
export const PASSWORD_MIN_LENGTH = 15;
export const PASSWORD_MAX_LENGTH = 64;

/** 입력창 아래에 보여 주고 낭독할 안내. 사용자가 읽고 바로 행동할 수 있어야 한다 */
export const PASSWORD_POLICY_TEXT = `${PASSWORD_MIN_LENGTH}자 이상 ${PASSWORD_MAX_LENGTH}자 이하, 흔하거나 유출된 적 없는 비밀번호`;

/**
 * 아이디 정책 — 백엔드 `Username`과 같은 값이어야 한다 (API 명세 3.2.1절).
 *
 * 비밀번호와 같은 이유로 숫자·문구를 여기 한 곳에만 둔다. 실제로 프론트는 4자·`a-z0-9_`,
 * 백엔드는 3~30자·`A-Za-z0-9._-`로 갈라져 있었다. 세 자짜리 아이디를 프론트가 막고,
 * 안내는 "영문, 숫자, 밑줄만 쓸 수 있어요"라고 해서 점·하이픈이 된다는 사실을 숨겼다.
 */
export const USERNAME_MIN_LENGTH = 3;
export const USERNAME_MAX_LENGTH = 30;

/** 입력창 아래에 보여 주고 낭독할 안내 */
export const USERNAME_POLICY_TEXT = `${USERNAME_MIN_LENGTH}~${USERNAME_MAX_LENGTH}자, 영문·숫자와 점·밑줄·하이픈, 처음과 끝은 영문 또는 숫자`;

/**
 * 백엔드 `Username.PATTERN`과 같은 정규식이다. 처음과 끝은 영문 또는 숫자여야 하고
 * 가운데에만 `.`·`_`·`-`를 쓸 수 있다.
 *
 * 길이를 정규식에 넣지 않는 것도 백엔드와 같다 — 넣으면 "길이 위반"과 "문자 위반"의 원인이
 * 한 덩어리가 되어 사용자에게 무엇을 고치라고 말할 수 없다.
 */
const USERNAME_PATTERN = /^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$/;

/**
 * 가입에 쓸 수 있는 아이디인지 본다.
 *
 * `trim()`은 백엔드의 정규화를 따라간 것이다. NFC 정규화는 하지 않는다 — 허용 문자가
 * ASCII뿐이라 정규화로 달라질 값은 어차피 정규식에서 걸린다.
 */
const newUsernameSchema = z
  .string()
  .trim()
  .min(USERNAME_MIN_LENGTH, `아이디는 ${USERNAME_MIN_LENGTH}자 이상이어야 해요`)
  .max(USERNAME_MAX_LENGTH, `아이디는 ${USERNAME_MAX_LENGTH}자를 넘을 수 없어요`)
  .regex(USERNAME_PATTERN, "영문·숫자와 점·밑줄·하이픈만 쓸 수 있고, 처음과 끝은 영문 또는 숫자예요");

export const signInSchema = z.object({
  /**
   * 로그인은 생성 정책을 다시 판정하지 않는다. 비밀번호와 같은 이유다 — 이미 만들어진
   * 계정의 아이디를 프론트가 막을 이유가 없고, 정책이 바뀌면 기존 사용자가 로그인조차
   * 못 하게 된다.
   */
  username: z.string().trim().min(1, "아이디를 입력해 주세요"),
  /**
   * 로그인은 생성 정책을 다시 판정하지 않는다. 이미 만들어진 계정의 비밀번호를 프론트가
   * 막을 이유가 없고, 정책이 바뀌면 기존 사용자가 로그인조차 못 하게 된다.
   * 맞는지 아닌지는 서버만 안다.
   */
  password: z.string().min(1, "비밀번호를 입력해 주세요"),
});
export type SignInInput = z.infer<typeof signInSchema>;

export const signUpSchema = z.object({
  username: newUsernameSchema,
  password: z
    .string()
    .min(PASSWORD_MIN_LENGTH, `비밀번호는 ${PASSWORD_MIN_LENGTH}자 이상이어야 해요`)
    .max(PASSWORD_MAX_LENGTH, `비밀번호는 ${PASSWORD_MAX_LENGTH}자를 넘을 수 없어요`),
  email: z.string().email("이메일 형식을 확인해 주세요"),
});
export type SignUpInput = z.infer<typeof signUpSchema>;

export const problemSchema = z.object({
  id: z.string(),
  /** 읽어 줄 문제 지시문. 예: '사랑'을 점자로 입력하세요 */
  prompt: z.string(),
  /** 정답으로 입력해야 할 단어/글자 */
  target: z.string(),
  /** 정답 셀 배열 (6비트 정수) */
  expectedCells: z.array(cellSchema).min(1),
});
export type Problem = z.infer<typeof problemSchema>;

export const learningSessionSchema = z.object({
  id: z.string(),
  title: z.string(),
  problems: z.array(problemSchema).min(1),
});
export type LearningSession = z.infer<typeof learningSessionSchema>;

export const proficiencyLevelSchema = z.enum(["familiar", "learning", "needs-practice"]);
export type ProficiencyLevel = z.infer<typeof proficiencyLevelSchema>;

export const sessionResultSchema = z.object({
  sessionId: z.string(),
  correctCount: z.number().int().min(0),
  totalCount: z.number().int().min(1),
  skills: z.array(
    z.object({ label: z.string(), level: proficiencyLevelSchema }),
  ),
});
export type SessionResult = z.infer<typeof sessionResultSchema>;

/** users.level — 단계 번호가 아니라 학습 범주다. NULL이면 자가 진단 전 */
export const levelSchema = z.enum([
  "CONSONANT",
  "VOWEL",
  "WORD",
  "SENTENCE",
  "CONTRACTION",
  "CONTRACTION_WORD",
]);
export type Level = z.infer<typeof levelSchema>;

/** 가입·내 정보·로그인 응답이 공유하는 사용자 요약 (API 명세 3.2.1) */
export const userSummarySchema = z.object({
  /** BIGINT의 10진수 문자열. number로 바꾸지 않는다 */
  userId: z.string(),
  username: z.string(),
  email: z.string(),
  role: z.string(),
  emailVerified: z.boolean(),
  level: levelSchema.nullable(),
  createdAt: z.string(),
});
export type UserSummary = z.infer<typeof userSummarySchema>;

/**
 * `GET /username-availability` 응답 (API 명세 3.2.1절).
 *
 * `username`은 서버가 정규화한 값이라 보낸 값과 다를 수 있다. `available`은 조회 시점의
 * 판정일 뿐이며 이후 가입의 성공을 보장하지 않는다 — 그 사이에 선점될 수 있다.
 */
export const usernameAvailabilitySchema = z.object({
  username: z.string(),
  available: z.boolean(),
});
export type UsernameAvailability = z.infer<typeof usernameAvailabilitySchema>;

/** MOBILE 인증 응답 (API 명세 3.3.1). WEB은 refresh 필드가 없다 */
export const authSessionSchema = z.object({
  accessToken: z.string(),
  tokenType: z.string(),
  accessTokenExpiresAt: z.string(),
  refreshToken: z.string(),
  refreshTokenExpiresAt: z.string(),
  user: userSummarySchema,
});
export type AuthSessionResponse = z.infer<typeof authSessionSchema>;

/* ─── 학습 세션 (구현된 백엔드 계약 기준) ──────────────────────────────────
 *
 * API 명세(api-spec.md 3.6절)와 구현이 갈라져 있다. 명세는 curriculumStageId·문항별 제출·
 * 분석/결과 분리를 정의하지만 구현은 curriculumId·일괄 제출 하나다. 여기서는 **구현**에 맞춘다.
 * 배경과 되돌릴 지점은 docs/superpowers/specs/2026-09-24-learning-session-integration-design.md
 * 2.4절에 있다.
 *
 * 식별자를 숫자로 받는 이유: 백엔드가 Long을 JSON 숫자로 보낸다. 명세는 10진수 문자열을
 * 요구하지만 구현이 그렇지 않다. JS 안전 정수 한계(2^53)를 넘으면 조용히 깨진다 — 식별자가
 * 그 크기에 다다를 일이 생기면 백엔드를 문자열로 바꾸고 이 스키마도 함께 옮겨야 한다.
 */
const identifierSchema = z.number().int().nonnegative();

/** 요청으로 보내는 식별자. 백엔드가 @Positive(1 이상)로 검증한다 */
const requestIdentifierSchema = z.number().int().positive();

export const problemDetailSchema = z.object({
  /** 이 문항 "배정"의 식별자. 완료 제출이 요구한다 — problemId로는 대신할 수 없다 */
  problemSessionId: identifierSchema,
  problemId: identifierSchema,
  sequence: z.number().int().positive(),
  targetText: z.string().min(1),
  expectedPatterns: z.array(cellSchema).min(1),
  completed: z.boolean(),
});
export type ProblemDetail = z.infer<typeof problemDetailSchema>;

export const problemsResponseSchema = z.object({
  sessionId: identifierSchema,
  problems: z.array(problemDetailSchema).min(1),
});
export type ProblemsResponse = z.infer<typeof problemsResponseSchema>;

/** 일괄 제출. 백엔드가 @NotEmpty @Size(max=100) @Min(0) @Max(63)으로 검증한다 */
export const attemptInputSchema = z.object({
  problemSessionId: requestIdentifierSchema,
  inputPatterns: z.array(cellSchema).min(1).max(100),
});
export type AttemptInput = z.infer<typeof attemptInputSchema>;

export const completionRequestSchema = z.object({
  attempts: z.array(attemptInputSchema).min(1).max(100),
});
export type CompletionRequest = z.infer<typeof completionRequestSchema>;

export const cellCompletionSchema = z.object({
  cellIndex: z.number().int(),
  expectedPattern: cellSchema,
  inputPattern: cellSchema,
  diffMask: cellSchema,
  missingMask: cellSchema,
  extraMask: cellSchema,
  missingDots: z.array(z.number().int()),
  extraDots: z.array(z.number().int()),
});

export const problemCompletionSchema = z.object({
  problemSessionId: identifierSchema,
  problemId: identifierSchema,
  attemptId: identifierSchema,
  correct: z.boolean(),
  cells: z.array(cellCompletionSchema),
});

export const completionResponseSchema = z.object({
  sessionId: identifierSchema,
  completedAt: z.string(),
  totalProblemCount: z.number().int().nonnegative(),
  correctProblemCount: z.number().int().nonnegative(),
  problemResults: z.array(problemCompletionSchema),
});
export type CompletionResponse = z.infer<typeof completionResponseSchema>;
