/** 공통 응답 래퍼 (API 명세서 0-3) */
export interface ApiResponse<T> {
  success: boolean;
  data: T;
}

export interface ApiError {
  success: false;
  error: {
    code: string;
    message: string;
    detail?: Record<string, unknown>;
  };
}
