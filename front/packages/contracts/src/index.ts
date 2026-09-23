import { z } from "zod";

/** 점자 셀: 6비트 정수 (0~63) */
export const cellSchema = z.number().int().min(0).max(63);

export const signInSchema = z.object({
  username: z
    .string()
    .min(4, "아이디는 4자 이상이에요")
    .regex(/^[a-z0-9_]+$/i, "영문, 숫자, 밑줄만 쓸 수 있어요"),
  password: z.string().min(8, "비밀번호는 8자 이상이에요"),
});
export type SignInInput = z.infer<typeof signInSchema>;

export const signUpSchema = z.object({
  username: signInSchema.shape.username,
  password: signInSchema.shape.password,
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
