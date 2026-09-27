import { useEffect, useState } from 'react'
import { fetchReleaseInfo } from '../domains/app-release/api/releaseApi'
import { APK_PATH, formatReleaseDate, type ReleaseInfo } from '../domains/app-release/model/releaseInfo'
import { buttonStyle, cardStyle } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

type LoadState = { kind: 'loading' } | { kind: 'ready'; info: ReleaseInfo } | { kind: 'unavailable' }

const INSTALL_STEPS = [
  {
    title: '알 수 없는 앱 설치 허용',
    body: '내려받은 ieum.apk를 열면 설치를 허용할지 물어요. 설정으로 가서 지금 쓰는 브라우저의 "이 출처 허용"을 켜 주세요.',
  },
  {
    title: 'Play 프로텍트 경고',
    body: '"Play 프로텍트" 경고가 나오면 "세부정보 더보기"를 누르고 "무시하고 설치"를 눌러 주세요. Play 스토어 밖에서 받은 앱이라 나오는 경고예요.',
  },
  {
    title: '첫 실행',
    body: '설치가 끝나면 "열기"를 눌러 이음을 켜세요. 로그인한 뒤 선생님이 알려 준 참여 코드로 수업에 들어가요.',
  },
] as const

const sectionTitle = { margin: 0, fontSize: 18, fontWeight: 800 } as const

/**
 * 학습자 앱 다운로드 페이지 `/app` (설계 §5.5). 로그인 없이 연다 — 인증 가드 밖 라우트.
 * 강사가 수업 화면에 QR을 띄우면 학생이 폰으로 찍어 이 페이지를 연다.
 * APK·latest.json은 호스트 nginx가 `/app/` 아래에서 먼저 제공한다.
 */
export function AppDownloadPage() {
  const [state, setState] = useState<LoadState>({ kind: 'loading' })

  useEffect(() => {
    const previousTitle = document.title
    document.title = '이음 앱 내려받기'
    let cancelled = false
    void fetchReleaseInfo().then((info) => {
      if (!cancelled) setState(info ? { kind: 'ready', info } : { kind: 'unavailable' })
    })
    return () => {
      cancelled = true
      document.title = previousTitle
    }
  }, [])

  return (
    <main
      style={{
        minHeight: '100vh',
        background: theme.color.canvas,
        fontFamily: theme.font.sans,
        color: theme.color.text,
        padding: '32px 16px',
        boxSizing: 'border-box',
      }}
    >
      <div style={{ maxWidth: 560, margin: '0 auto', display: 'flex', flexDirection: 'column', gap: 16 }}>
        <section style={{ ...cardStyle, padding: 28, display: 'flex', flexDirection: 'column', gap: 16 }}>
          <h1 style={{ margin: 0, fontSize: 28, fontWeight: 900, letterSpacing: '-.02em' }}>이음 학습자 앱</h1>
          <p style={{ margin: 0, fontSize: 15, lineHeight: 1.7, color: theme.color.textSub }}>
            점자를 배우는 안드로이드 앱이에요. 이 페이지를 휴대폰에서 열고 아래 버튼을 눌러 설치해요.
          </p>

          <div role="status" aria-live="polite" style={{ fontSize: 15, lineHeight: 1.7 }}>
            {state.kind === 'loading' && <p style={{ margin: 0 }}>버전 정보를 확인하고 있어요.</p>}
            {state.kind === 'unavailable' && (
              <>
                <p style={{ margin: 0, fontSize: 20, fontWeight: 800 }}>준비 중이에요</p>
                <p style={{ margin: 0, color: theme.color.textSub }}>앱 파일을 올리는 중이에요. 잠시 뒤 다시 들어와 주세요.</p>
              </>
            )}
            {state.kind === 'ready' && (
              <>
                <p style={{ margin: 0, fontWeight: 700 }}>
                  버전 {state.info.version} · {formatReleaseDate(state.info.releasedAt)} 배포
                </p>
                {state.info.notes && (
                  <p style={{ margin: 0, color: theme.color.textSub }}>바뀐 점: {state.info.notes}</p>
                )}
              </>
            )}
          </div>

          {state.kind === 'ready' && (
            <a
              href={APK_PATH}
              download="ieum.apk"
              style={{
                ...buttonStyle('primary'),
                width: '100%',
                minHeight: 64,
                fontSize: 20,
                boxSizing: 'border-box',
              }}
            >
              이음 앱 내려받기
            </a>
          )}
        </section>

        <section style={{ ...cardStyle, padding: 28, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <h2 style={sectionTitle}>설치 방법</h2>
          <ol style={{ margin: 0, paddingLeft: 22, display: 'flex', flexDirection: 'column', gap: 12, lineHeight: 1.7 }}>
            {INSTALL_STEPS.map((step) => (
              <li key={step.title}>
                <strong>{step.title}</strong>
                <br />
                <span style={{ color: theme.color.textSub }}>{step.body}</span>
              </li>
            ))}
          </ol>
        </section>

        <section style={{ ...cardStyle, padding: 28, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <h2 style={sectionTitle}>휴대폰으로 열기</h2>
          <figure style={{ margin: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
            <img
              src="/app-qr.svg"
              width={240}
              height={240}
              alt="이 페이지 주소 i-eum.site/app 으로 가는 QR 코드"
              style={{ maxWidth: '100%', height: 'auto' }}
            />
            <figcaption style={{ fontSize: 14, color: theme.color.textSub, textAlign: 'center' }}>
              휴대폰 카메라로 찍으면 이 페이지가 열려요 · <span style={{ fontFamily: theme.font.mono }}>i-eum.site/app</span>
            </figcaption>
          </figure>
        </section>
      </div>
    </main>
  )
}
