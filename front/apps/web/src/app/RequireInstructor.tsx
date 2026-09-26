import { useEffect } from 'react'
import { Navigate, Outlet, useNavigate } from 'react-router-dom'
import { setSessionExpiredHandler } from '../shared/api/apiClient'
import { authApi } from '../shared/auth/authApi'

/** 로그인한 INSTRUCTOR만 통과시킨다. API가 401로 세션 만료를 알리면 로그인 화면으로 보낸다 */
export function RequireInstructor() {
  const navigate = useNavigate()
  const user = authApi.currentUser()

  useEffect(() => {
    setSessionExpiredHandler(() => {
      authApi.signOut()
      navigate('/login?expired=1', { replace: true })
    })
    return () => setSessionExpiredHandler(() => {})
  }, [navigate])

  if (!user || user.role !== 'INSTRUCTOR') return <Navigate to="/login" replace />
  return <Outlet />
}
