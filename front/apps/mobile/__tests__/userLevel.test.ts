import { authService } from "../src/services/authService";
import { LEVEL_LABEL, LEVEL_ORDER } from "../src/services/levelLabels";
import { api } from "../src/services/apiClient";
import { levelSchema } from "@ieum/contracts";

jest.mock("../src/services/apiClient", () => ({
  api: { post: jest.fn(), get: jest.fn(), put: jest.fn() },
}));

const put = api.put as jest.Mock;

function userSummary(level: string | null) {
  return {
    userId: "1",
    username: "chwogus",
    email: "a@b.com",
    role: "LEARNER",
    emailVerified: true,
    level,
    createdAt: "2026-09-25T00:00:00Z",
  };
}

describe("학습 단계 설정", () => {
  beforeEach(() => put.mockReset());

  it("PUT /users/me/level 에 고른 단계를 보낸다", async () => {
    put.mockResolvedValue(userSummary("WORD"));

    await authService.changeLevel("WORD");

    expect(put).toHaveBeenCalledWith("/users/me/level", { level: "WORD" });
  });

  it("갱신된 사용자 요약을 돌려준다", async () => {
    // 홈 배지가 이 값을 읽는다. 빈 응답이면 GET /users/me 를 한 번 더 불러야 한다
    put.mockResolvedValue(userSummary("SENTENCE"));

    const user = await authService.changeLevel("SENTENCE");

    expect(user.level).toBe("SENTENCE");
  });

  it("응답이 계약과 다르면 던진다", async () => {
    put.mockResolvedValue({ userId: "1" });

    await expect(authService.changeLevel("WORD")).rejects.toThrow();
  });
});

describe("단계 이름표", () => {
  it("계약의 모든 단계에 한글 이름이 있다", () => {
    // 이름이 빠진 단계가 있으면 화면에 enum 코드가 그대로 노출된다
    for (const level of levelSchema.options) {
      expect(LEVEL_LABEL[level]).toBeTruthy();
    }
  });

  it("고르는 순서가 커리큘럼 순서를 따른다", () => {
    // curriculum_stages 는 자음·모음·약자·단어·약어·문장 순이다 (id 1~6).
    // 설정 화면이 이 순서로 보여줘야 학습자가 자기 위치를 가늠할 수 있다
    expect(LEVEL_ORDER).toEqual([
      "CONSONANT",
      "VOWEL",
      "CONTRACTION",
      "WORD",
      "CONTRACTION_WORD",
      "SENTENCE",
    ]);
  });

  it("빠지거나 더 들어간 단계가 없다", () => {
    expect([...LEVEL_ORDER].sort()).toEqual([...levelSchema.options].sort());
  });
});
