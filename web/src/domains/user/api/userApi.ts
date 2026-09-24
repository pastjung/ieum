import { api } from '../../../shared/api/client';
import type { SignupInput, User } from '../model/types';

/** user 도메인 API — 화면(page)이 아니라 도메인 폴더가 API를 소유한다 */
export const userApi = {
  signup: (input: SignupInput) => api.post<User>('/auth/signup', input),
  getUser: (userId: number) => api.get<User>(`/users/${userId}`),
};
