import { ApiError } from "@ieum/api-client";
import {
  learnerLectureSchema,
  mediaTokenSchema,
  type LearnerLecture,
  type LearningSession,
  type LectureStatus,
  type MediaToken,
} from "@ieum/contracts";
import { dotsToCell } from "@ieum/domain";
import { api } from "./apiClient";

export interface ClassInfo {
  lectureId: string;
  title: string;
  status: LectureStatus;
}

function toClassInfo(lecture: LearnerLecture): ClassInfo {
  return { lectureId: lecture.lectureId, title: lecture.title, status: lecture.status };
}

const delay = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** 수업(LIVE) 서비스 — 입장·스냅샷·음성 토큰은 실제 API, 문제 출제와 복습 후보는 아직 목이다 */
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

  /* ─── 아래는 아직 목이다. 대응 엔드포인트(문제 출제·복습 연결)가 없다 ─── */

  async getClassSession(): Promise<LearningSession> {
    await delay(300);
    return {
      id: "class-abbrev-4",
      title: "약자 4회차",
      problems: [
        { id: "c1", prompt: "'그래서'를 입력하세요", target: "그래서", expectedCells: [dotsToCell([1]), dotsToCell([2, 3, 4])] },
        { id: "c2", prompt: "'그리고'를 입력하세요", target: "그리고", expectedCells: [dotsToCell([1]), dotsToCell([1, 3, 6])] },
        { id: "c3", prompt: "'그러나'를 입력하세요", target: "그러나", expectedCells: [dotsToCell([1]), dotsToCell([1, 4])] },
        { id: "c4", prompt: "'그러면'을 입력하세요", target: "그러면", expectedCells: [dotsToCell([1]), dotsToCell([2, 5])] },
      ],
    };
  },

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
