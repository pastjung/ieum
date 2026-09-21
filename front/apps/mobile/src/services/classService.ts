import type { LearningSession } from "@ieum/contracts";
import { dotsToCell } from "@ieum/domain";

const delay = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

export interface ClassInfo {
  code: string;
  title: string;
  teacher: string;
}

/** 수업(LIVE) 목 서비스 — OpenVidu·백엔드 연동 시 이 모듈만 교체 */
export const classService = {
  /** 6자리 참여 코드로 수업 조회. 목에서는 어떤 코드든 성공 */
  async joinByCode(code: string): Promise<ClassInfo> {
    await delay(500);
    return { code, title: "약자 4회차", teacher: "김선생" };
  },

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
