import { useEffect, useState } from 'react'

/**
 * 메일 링크가 착지하는 중계 페이지.
 *
 * 하는 일은 하나다 — `location.hash`의 action token을 읽어 학습자 앱(`ieum://`)으로 넘긴다.
 * 완료 API 호출은 앱이 한다. 새 비밀번호 입력처럼 사용자가 값을 넣어야 하는 화면은 앱의
 * 접근성 컴포넌트(큰 입력창, 낭독, 햅틱)로 받아야 하기 때문이다.
 *
 * **토큰이 fragment로 오는 것은 우연이 아니다.** 백엔드가 `<base>#token=...` 형태로만 붙이며,
 * fragment는 서버로 전송되지 않아 웹 서버 접근 로그에 토큰이 남지 않는다. 그래서 이 페이지는
 * 토큰을 query로 바꾸거나 자기 서버로 보내지 않는다.
 */
export function AppActionRedirectPage({
  appPath,
  title,
  description,
}: {
  /** 앱에서 이 토큰을 처리할 경로. 예: auth/verify-email */
  appPath: string
  title: string
  description: string
}) {
  const [state, setState] = useState<'opening' | 'no-token' | 'stalled'>('opening')
  const [appUrl, setAppUrl] = useState<string | null>(null)

  useEffect(() => {
    const token = new URLSearchParams(window.location.hash.slice(1)).get('token')
    if (token == null || token === '') {
      setState('no-token')
      return
    }

    const url = `ieum://${appPath}#token=${encodeURIComponent(token)}`
    setAppUrl(url)
    window.location.href = url

    // 앱이 없거나 스킴이 막힌 기기에서는 아무 일도 일어나지 않는다.
    // 빈 화면으로 두지 않고 직접 열 수 있는 링크를 보여 준다.
    const timer = window.setTimeout(() => setState('stalled'), 2000)
    return () => window.clearTimeout(timer)
  }, [appPath])

  return (
    <main style={{ padding: 32, fontFamily: 'sans-serif', lineHeight: 1.7, maxWidth: 640 }}>
      <h1 style={{ fontSize: 24 }}>{title}</h1>

      {state === 'no-token' ? (
        <p role="alert">
          링크에 필요한 정보가 없어요. 메일의 링크를 다시 눌러 주세요. 그래도 안 되면 앱에서 메일을 다시
          요청할 수 있어요.
        </p>
      ) : (
        <>
          <p>{description}</p>
          {state === 'stalled' && appUrl != null && (
            <p role="alert">
              앱이 열리지 않았나요? <a href={appUrl}>여기를 눌러 다시 시도</a>해 주세요. 잇다 앱이 설치되어
              있어야 합니다.
            </p>
          )}
        </>
      )}
    </main>
  )
}
