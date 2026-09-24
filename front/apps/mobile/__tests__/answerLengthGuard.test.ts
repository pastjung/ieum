import { describeTooManyCells } from "@/services/answerLengthGuard";

describe("제출 전 칸 수 검사", () => {
  it("기대 칸 수와 같으면 막지 않는다", () => {
    expect(describeTooManyCells(2, 2)).toBeNull();
  });

  it("모자란 답은 막지 않는다", () => {
    // 초보 학습자의 가장 흔한 오답이다. 빈 칸으로 채워 보내도 로컬·서버 판정이 같다
    expect(describeTooManyCells(1, 2)).toBeNull();
  });

  it("넘치는 답은 막는다", () => {
    // 잘라 보내면 화면은 "틀렸어요", 서버는 정답으로 세어 결과 화면 점수가 갈라진다
    expect(describeTooManyCells(3, 2)).not.toBeNull();
  });

  it("막을 때는 몇 칸까지인지·지금 몇 칸인지·어떻게 지우는지를 알려 준다", () => {
    const message = describeTooManyCells(3, 2);

    expect(message).toBe("이 문제는 2칸까지예요. 지금 3칸 입력했어요. 지우기 버튼을 누르면 마지막 칸이 지워져요.");
  });
});
