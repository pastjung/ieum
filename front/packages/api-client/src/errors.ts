/** 네트워크 자체가 실패해 HTTP 상태가 없을 때 쓰는 code. 백엔드 code와 겹치지 않는다 */
export const NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE";

/** 응답 본문이 JSON이 아닐 때. 프록시가 끼워 넣은 HTML 에러 페이지 등 */
export const INVALID_RESPONSE = "INVALID_RESPONSE";

/**
 * 백엔드 공통 오류 계약 `{code, message}`를 옮긴 예외.
 *
 * 화면은 `code`로 분기하고 `message`는 그대로 보여 줄 수 있다 — 백엔드가 한국어 사용자 문구로 정의한다.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  /**
   * 오류 응답 본문 원문(JSON). 공통 `{code, message}` 밖의 필드를 읽을 때 쓴다 —
   * 예: 409 `PROBLEM_TEXT_AMBIGUOUS`의 `candidates`. 본문이 없으면 undefined
   */
  readonly body: unknown;

  constructor(status: number, code: string, message: string, body?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.body = body;
  }
}
