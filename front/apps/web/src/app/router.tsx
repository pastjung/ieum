import { createBrowserRouter, Navigate } from 'react-router-dom'
import { AppActionRedirectPage } from '../pages/AppActionRedirectPage'
import { AppDownloadPage } from '../pages/AppDownloadPage'
import { LectureCreatePage } from '../pages/LectureCreatePage'
import { LectureListPage } from '../pages/LectureListPage'
import { LiveLecturePage } from '../pages/LiveLecturePage'
import { LoginPage } from '../pages/LoginPage'
import { SignupPage } from '../pages/SignupPage'
import { AppShell } from './AppShell'
import { RequireInstructor } from './RequireInstructor'

export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/lectures" replace /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/signup', element: <SignupPage /> },
  // 학습자 앱 다운로드 — 로그인 없이 연다. /app/latest.json·/app/ieum.apk 는 호스트 nginx가 먼저 처리한다
  { path: '/app', element: <AppDownloadPage /> },
  {
    element: <RequireInstructor />,
    children: [
      {
        element: <AppShell />,
        children: [
          { path: '/lectures', element: <LectureListPage /> },
          { path: '/lectures/new', element: <LectureCreatePage /> },
        ],
      },
      // 03 실시간 수업은 흰 상단 바 대신 자체 어두운 수업 바를 쓴다
      { path: '/lectures/:lectureId/live', element: <LiveLecturePage /> },
    ],
  },
  // 계정 메일 링크가 착지하는 경로. 백엔드의 MAIL_LINK_* 환경변수가 이 주소를 가리키므로
  // 둘 중 하나만 바꾸면 링크가 끊긴다. 앱의 라우트 구조(auth/...)와도 맞춰 둔다.
  {
    path: '/auth/verify-email',
    element: (
      <AppActionRedirectPage
        appPath="auth/verify-email"
        title="이메일 인증"
        description="잇다 앱에서 인증을 마무리하고 있어요. 앱이 열리면 잠시 기다려 주세요."
      />
    ),
  },
  {
    path: '/auth/reset-password',
    element: (
      <AppActionRedirectPage
        appPath="auth/reset-password/confirm"
        title="비밀번호 재설정"
        description="잇다 앱에서 새 비밀번호를 입력할 수 있어요. 앱이 열리면 잠시 기다려 주세요."
      />
    ),
  },
])
