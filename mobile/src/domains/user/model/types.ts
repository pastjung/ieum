export type UserRole = 'LEARNER' | 'TEACHER' | 'ADMIN';

export interface User {
  userId: number;
  email: string;
  name: string;
  role: UserRole;
}

export interface SignupInput {
  email: string;
  password: string;
  name: string;
  role: UserRole;
}
