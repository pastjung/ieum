import { api } from '../../../shared/api/client';
import type { SignupInput, User } from '../model/types';

export const userApi = {
  signup: (input: SignupInput) => api.post<User>('/auth/signup', input),
  getUser: (userId: number) => api.get<User>(`/users/${userId}`),
};
