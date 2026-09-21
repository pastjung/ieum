import type { LearningSession } from "@ieum/contracts";
import {
  freeSessionVowel,
  homeSummary,
  skillLevels,
  type HomeSummary,
  type LearningThemeId,
} from "./mockData";

const delay = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * 학습 목 서비스 — @ieum/api-client(OpenAPI) 연동 시 이 모듈만 교체한다.
 * 화면은 TanStack Query를 통해서만 이 서비스를 사용한다.
 */
export const learningService = {
  async getHomeSummary(): Promise<HomeSummary> {
    await delay(250);
    return homeSummary;
  },

  async getFreeSession(_theme?: LearningThemeId): Promise<LearningSession> {
    await delay(250);
    return freeSessionVowel;
  },

  async getSkillLevels() {
    await delay(200);
    return skillLevels;
  },
};
