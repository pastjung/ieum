import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import { theme } from '../../../shared/ui/theme'
import { dotLabel } from '../model/braille'
import type { CellCompare } from '../model/answerView'

const c = theme.color
const mono = theme.font.mono

/** 점 배치: 왼쪽 위→아래 1·2·3, 오른쪽 위→아래 4·5·6 */
const DOT_ORDER = [1, 4, 2, 5, 3, 6]
const DOT = 12
const WIDTH = 640
const GAP = 6

type DotState = 'on' | 'off' | 'missing' | 'extra'

function dotStyle(state: DotState): CSSProperties {
  const base: CSSProperties = {
    width: DOT,
    height: DOT,
    borderRadius: '50%',
    boxSizing: 'border-box',
  }
  switch (state) {
    case 'on':
      return { ...base, background: c.text }
    case 'extra':
      // 정답에 없는 점을 더 찍었다 — 찍은 점이지만 노랑으로 구분한다
      return { ...base, background: c.warningLine, border: `2px solid ${c.warningText}` }
    case 'missing':
      // 정답에는 있는데 찍지 않았다 — 빈 점을 빨간 점선으로 표시한다
      return { ...base, border: `2px dashed ${c.danger}` }
    default:
      return { ...base, border: `2px solid ${c.borderStrong}` }
  }
}

function CompareCell({
  pattern,
  missing = [],
  extra = [],
}: {
  pattern: number | null
  missing?: readonly number[]
  extra?: readonly number[]
}) {
  if (pattern === null) {
    return (
      <div
        style={{
          width: 44,
          height: 58,
          boxSizing: 'border-box',
          border: `1.5px dashed ${c.borderStrong}`,
          borderRadius: theme.radius.control,
          display: 'grid',
          placeItems: 'center',
          fontSize: 11,
          color: c.textMuted,
        }}
      >
        없음
      </div>
    )
  }
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: `repeat(2, ${DOT}px)`,
        gridAutoRows: `${DOT}px`,
        gap: 5,
        padding: '8px 9px',
        background: c.fill,
        borderRadius: theme.radius.control,
      }}
    >
      {DOT_ORDER.map((dot) => {
        const state: DotState = extra.includes(dot)
          ? 'extra'
          : missing.includes(dot)
            ? 'missing'
            : pattern & (1 << (dot - 1))
              ? 'on'
              : 'off'
        return <span key={dot} style={dotStyle(state)} />
      })}
    </div>
  )
}

interface Props {
  id: string
  /** 팝오버를 붙일 카드. 카드 바로 아래에 띄우고, 아래 공간이 모자라면 위로 띄운다 */
  anchor: HTMLElement | null
  studentName: string
  correct: boolean
  rows: readonly CellCompare[]
  onClose: () => void
}

interface Position {
  left: number
  width: number
  top?: number
  bottom?: number
  maxHeight: number
}

function place(anchor: HTMLElement): Position {
  const rect = anchor.getBoundingClientRect()
  const width = Math.min(WIDTH, window.innerWidth - 32)
  const left = Math.min(Math.max(rect.left, 16), window.innerWidth - width - 16)
  const below = window.innerHeight - rect.bottom - 16
  const above = rect.top - 16
  return below >= 220 || below >= above
    ? { left, width, top: rect.bottom + GAP, maxHeight: below - GAP }
    : { left, width, bottom: window.innerHeight - rect.top + GAP, maxHeight: above - GAP }
}

/**
 * 학생 답안 상세 — 칸마다 학생 답과 정답을 위아래로 놓고 빠진 점·더 찍은 점을 표시한다.
 * 학생 목록은 자체 스크롤 영역이라 카드 안에 두면 잘리므로 body에 띄운다(카드 아래 위치에 고정).
 * Esc·바깥 클릭·닫기 버튼으로 닫고, 닫으면 여는 버튼으로 초점을 돌려주는 것은 부르는 쪽 책임이다.
 */
export function AnswerDetailPopover({ id, anchor, studentName, correct, rows, onClose }: Props) {
  const panelRef = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<Position | null>(null)

  useLayoutEffect(() => {
    if (!anchor) return
    const update = () => setPosition(place(anchor))
    update()
    window.addEventListener('resize', update)
    // 학생 목록·페이지 어느 쪽이 스크롤돼도 카드를 따라간다
    window.addEventListener('scroll', update, true)
    return () => {
      window.removeEventListener('resize', update)
      window.removeEventListener('scroll', update, true)
    }
  }, [anchor])

  useEffect(() => {
    panelRef.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    const onPointer = (e: PointerEvent) => {
      const target = e.target as Node
      if (panelRef.current?.contains(target) || anchor?.contains(target)) return
      onClose()
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('pointerdown', onPointer)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('pointerdown', onPointer)
    }
  }, [anchor, onClose])

  if (!anchor || !position) return null
  const wrongCount = rows.filter((r) => !r.ok).length

  return createPortal(
    <div
      id={id}
      ref={panelRef}
      role="dialog"
      aria-label={`${studentName} 답안 상세`}
      tabIndex={-1}
      style={{
        position: 'fixed',
        left: position.left,
        top: position.top,
        bottom: position.bottom,
        width: position.width,
        maxHeight: position.maxHeight,
        overflowY: 'auto',
        boxSizing: 'border-box',
        zIndex: 50,
        background: c.surface,
        border: `1.5px solid ${c.border}`,
        borderRadius: theme.radius.panel,
        boxShadow: '0 12px 32px rgba(0, 0, 0, .18)',
        padding: 16,
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
        outline: 'none',
      }}
    >
      <div
        style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}
      >
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, minWidth: 0 }}>
          <strong style={{ fontSize: 15 }}>{studentName}</strong>
          <span
            style={{
              fontSize: 13,
              fontWeight: 700,
              color: correct ? c.primaryDark : c.warningText,
            }}
          >
            {correct ? '정답' : `오답 · 틀린 칸 ${wrongCount}개`}
          </span>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="상세 닫기"
          style={{
            border: 'none',
            background: 'transparent',
            fontSize: 18,
            lineHeight: 1,
            cursor: 'pointer',
            color: c.textSub,
            padding: 4,
          }}
        >
          ✕
        </button>
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, fontSize: 12, color: c.textSub }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <span style={dotStyle('on')} /> 찍은 점
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <span style={dotStyle('missing')} /> 빠진 점
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <span style={dotStyle('extra')} /> 더 찍은 점
        </span>
      </div>

      <div style={{ display: 'flex', gap: 10, overflowX: 'auto', paddingBottom: 4 }}>
        {/* 행 이름 — 칸이 많으면 오른쪽 칸들만 가로로 스크롤된다 */}
        <div
          aria-hidden="true"
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 8,
            paddingTop: 22,
            fontSize: 12,
            fontWeight: 700,
            color: c.textSub,
            flexShrink: 0,
          }}
        >
          <span style={{ height: 58, display: 'flex', alignItems: 'center' }}>학생</span>
          <span style={{ height: 58, display: 'flex', alignItems: 'center' }}>정답</span>
        </div>
        <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', gap: 10 }}>
          {rows.map((row) => (
            <li
              key={row.cellIndex}
              aria-label={`${row.cellIndex}번째 칸: ${row.note}${
                row.input === null ? '' : `, 학생 ${dotLabel([row.input])}`
              }${row.expected === null ? '' : `, 정답 ${dotLabel([row.expected])}`}`}
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: 8,
                padding: '0 4px 4px',
                borderRadius: theme.radius.control,
                background: row.ok ? 'transparent' : c.warningSoft,
                flexShrink: 0,
              }}
            >
              <span style={{ fontSize: 11, fontWeight: 700, color: c.textSub, height: 14 }}>
                {row.cellIndex}칸
              </span>
              <CompareCell pattern={row.input} missing={row.missing} extra={row.extra} />
              <CompareCell pattern={row.expected} />
              <span
                style={{
                  fontFamily: row.ok ? mono : undefined,
                  fontSize: 11,
                  fontWeight: 700,
                  color: row.ok ? c.textMuted : c.danger,
                  maxWidth: 72,
                  textAlign: 'center',
                  wordBreak: 'keep-all',
                }}
              >
                {row.note}
              </span>
            </li>
          ))}
        </ol>
      </div>
    </div>,
    document.body,
  )
}
