import { useState, type FormEvent } from 'react';
import { userApi } from '../domains/user/api/userApi';
import type { User } from '../domains/user/model/types';

/** 백엔드 1-1 회원가입 연동 예시 페이지 */
export function SignupPage() {
  const [result, setResult] = useState<User | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    const form = new FormData(e.currentTarget);

    try {
      const user = await userApi.signup({
        email: String(form.get('email')),
        password: String(form.get('password')),
        name: String(form.get('name')),
        role: 'TEACHER',
      });
      setResult(user);
    } catch (err) {
      setError(err instanceof Error ? err.message : '알 수 없는 오류');
    }
  }

  return (
    <main style={{ padding: 32, fontFamily: 'sans-serif', maxWidth: 400 }}>
      <h1>회원가입 (강사)</h1>
      <form onSubmit={handleSubmit} style={{ display: 'grid', gap: 8 }}>
        <input name="email" type="email" placeholder="이메일" required />
        <input name="password" type="password" placeholder="비밀번호 (8자 이상)" required minLength={8} />
        <input name="name" placeholder="이름" required />
        <button type="submit">가입</button>
      </form>
      {result && (
        <p role="status">
          가입 완료: #{result.userId} {result.name} ({result.role})
        </p>
      )}
      {error && <p role="alert" style={{ color: 'crimson' }}>{error}</p>}
    </main>
  );
}
