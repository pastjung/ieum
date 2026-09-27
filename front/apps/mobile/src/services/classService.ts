import { ApiError } from "@ieum/api-client";
import {
  learnerLectureSchema,
  liveAnswerRequestSchema,
  liveAnswerSchema,
  mediaTokenSchema,
  type LearnerCurrentProblem,
  type LearnerLecture,
  type LectureStatus,
  type LiveAnswer,
  type LiveAnswerRequest,
  type MediaToken,
} from "@ieum/contracts";
import { api } from "./apiClient";

export interface ClassInfo {
  lectureId: string;
  title: string;
  status: LectureStatus;
}

/** 문제 상태 조회 결과 — 화면이 대기·풀이·제출 대기를 정하는 데 쓴다 */
export interface LectureProblemSnapshot {
  status: LectureStatus;
  currentProblem: LearnerCurrentProblem | null;
  myResult: LiveAnswer | null;
}

function toClassInfo(lecture: LearnerLecture): ClassInfo {
  return { lectureId: lecture.lectureId, title: lecture.title, status: lecture.status };
}

/** 수업(LIVE) 서비스 — 입장·스냅샷·음성 토큰·현재 문제·답안 제출은 실제 API, 최근 참여와 복습 후보는 아직 목이다 */
export const classService = {
  /** 6자리 참여 코드로 입장한다. 최초 입장이든 재입장이든 같은 스냅샷을 받는다 (API 명세 8.2절) */
  async joinByCode(code: string): Promise<ClassInfo> {
    const payload = await api.post<unknown>("/lecture-participations", { participationCode: code });
    return toClassInfo(learnerLectureSchema.parse(payload));
  },

  /** 재접속·상태 확인용. 수업 상태의 권위는 이 응답이다 (API 명세 8.5절) */
  async getLecture(lectureId: string): Promise<ClassInfo> {
    const payload = await api.get<unknown>(`/lectures/${lectureId}`);
    return toClassInfo(learnerLectureSchema.parse(payload));
  },

  /** OpenVidu 수신 전용 토큰. 재접속할 때마다 새로 받는다 */
  async issueMediaToken(lectureId: string): Promise<MediaToken> {
    const payload = await api.post<unknown>(`/lectures/${lectureId}/media-tokens`);
    return mediaTokenSchema.parse(payload);
  },

  /** 현재 문제와 내 결과. 출제 신호(방 metadata)·입장·재접속 때 부른다. 정답 점형은 오지 않는다 */
  async getLectureProblem(lectureId: string): Promise<LectureProblemSnapshot> {
    const lecture = learnerLectureSchema.parse(await api.get<unknown>(`/lectures/${lectureId}`));
    return { status: lecture.status, currentProblem: lecture.currentProblem, myResult: lecture.myResult };
  },

  /**
   * 현재 문제의 답안을 낸다. 서버가 채점해 결과를 돌려준다.
   * idempotencyKey는 문제마다 새로 만들고, 같은 문제를 다시 보낼 때(응답 유실·재시도)는 같은 값을 쓴다.
   */
  async submitLiveAnswer(
    lectureId: string,
    problemId: string,
    answer: LiveAnswerRequest,
    idempotencyKey: string,
  ): Promise<LiveAnswer> {
    const body = liveAnswerRequestSchema.parse(answer);
    const payload = await api.post<unknown>(`/lectures/${lectureId}/problems/${problemId}/answers`, body, {
      headers: { "Idempotency-Key": idempotencyKey },
    });
    return liveAnswerSchema.parse(payload);
  },

  /* ─── 아래는 아직 목이다. 대응 엔드포인트(최근 참여·복습 연결)가 없다 ─── */

  /** 최근 참여 기록 (목) */
  recentClass: { title: "약자 3회차", when: "어제" },

  /** 수업 오답 복습 후보 (V24, 목) */
  reviewOptions: [
    { id: "step3", label: "3단계 · 자음과 모음", wrongCount: 2 },
    { id: "step2", label: "2단계 · 기본 자음", wrongCount: 1 },
  ],
};

/** 입장 실패 안내. 서버 message를 그대로 읽어 주지 않고 사유별 문구를 쓴다 */
export function joinErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "LECTURE_NOT_JOINABLE") return "입장할 수 있는 수업이 없어요. 참여 코드를 확인해 주세요.";
    if (error.code === "LEARNER_ACCESS_REQUIRED") return "학습자 계정으로만 수업에 입장할 수 있어요.";
    if (error.code === "NETWORK_UNAVAILABLE") return "서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.";
  }
  return "입장하지 못했어요. 다시 시도해 주세요.";
}
