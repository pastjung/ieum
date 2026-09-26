import { ApiError, type ApiClient } from '@ieum/api-client'
import { describe, expect, it, vi } from 'vitest'
import { ambiguousCandidates, createLectureApi, toScheduledAtUtc } from '../lectureApi'

function fakeClient() {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
  }
}

describe('lectureApi', () => {
  it('내 수업 목록을 최신순 페이지로 조회한다', async () => {
    const client = fakeClient()
    await createLectureApi(client as unknown as ApiClient).list(0, 20)
    expect(client.get).toHaveBeenCalledWith('/lectures?page=0&size=20')
  })

  it('빈 설명과 시작 시각은 보내지 않는다', async () => {
    const client = fakeClient()
    await createLectureApi(client as unknown as ApiClient).create({
      title: '약자 4회차',
      description: '  ',
    })
    expect(client.post).toHaveBeenCalledWith('/lectures', { title: '약자 4회차' })
  })

  it('설명과 시작 시각이 있으면 함께 보낸다', async () => {
    const client = fakeClient()
    await createLectureApi(client as unknown as ApiClient).create({
      title: 't',
      description: '규칙 중심',
      scheduledAt: '2026-09-26T01:40:00.000Z',
    })
    expect(client.post).toHaveBeenCalledWith('/lectures', {
      title: 't',
      description: '규칙 중심',
      scheduledAt: '2026-09-26T01:40:00.000Z',
    })
  })

  it('상태 변경과 음성 토큰 경로', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    await lectures.get('42')
    await lectures.changeStatus('42', 'OPEN')
    await lectures.issueMediaToken('42')

    expect(client.get).toHaveBeenCalledWith('/lectures/42')
    expect(client.put).toHaveBeenCalledWith('/lectures/42/status', { status: 'OPEN' })
    expect(client.post).toHaveBeenCalledWith('/lectures/42/media-tokens')
  })

  it('발언 허용·회수를 학생별 경로로 보낸다', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    await lectures.setSpeaking('42', '7', true)
    await lectures.setSpeaking('42', '7', false)

    expect(client.put).toHaveBeenNthCalledWith(1, '/lectures/42/participants/7/speaking', {
      allowed: true,
    })
    expect(client.put).toHaveBeenNthCalledWith(2, '/lectures/42/participants/7/speaking', {
      allowed: false,
    })
  })

  it('lectureId와 userId를 경로에 넣을 때 인코딩한다', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    const id = '42/../etc'
    const userId = '7/../1'
    await lectures.get(id)
    await lectures.changeStatus(id, 'OPEN')
    await lectures.issueMediaToken(id)
    await lectures.setSpeaking(id, userId, true)

    const encoded = encodeURIComponent(id)
    expect(client.get).toHaveBeenCalledWith(`/lectures/${encoded}`)
    expect(client.put).toHaveBeenCalledWith(`/lectures/${encoded}/status`, { status: 'OPEN' })
    expect(client.post).toHaveBeenCalledWith(`/lectures/${encoded}/media-tokens`)
    expect(client.put).toHaveBeenCalledWith(
      `/lectures/${encoded}/participants/${encodeURIComponent(userId)}/speaking`,
      {
        allowed: true,
      },
    )
  })

  it('문제 등록은 앞뒤 공백을 지운 targetText를 보낸다', async () => {
    const client = fakeClient()
    await createLectureApi(client as unknown as ApiClient).registerProblem('42', '  초성 ㄱ  ')
    expect(client.post).toHaveBeenCalledWith('/lectures/42/problems', { targetText: '초성 ㄱ' })
  })

  it('출제와 답안 목록 경로', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    await lectures.deliverProblem('42', '26578')
    await lectures.listAnswers('42', '26578')

    expect(client.put).toHaveBeenCalledWith('/lectures/42/current-problem', { problemId: '26578' })
    expect(client.get).toHaveBeenCalledWith('/lectures/42/problems/26578/answers')
  })

  it('문제 경로의 lectureId와 problemId를 인코딩한다', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    const id = '42/../etc'
    const problemId = '7/../1'
    await lectures.registerProblem(id, 'ㄱ')
    await lectures.deliverProblem(id, problemId)
    await lectures.listAnswers(id, problemId)

    const encoded = encodeURIComponent(id)
    expect(client.post).toHaveBeenCalledWith(`/lectures/${encoded}/problems`, { targetText: 'ㄱ' })
    expect(client.put).toHaveBeenCalledWith(`/lectures/${encoded}/current-problem`, { problemId })
    expect(client.get).toHaveBeenCalledWith(
      `/lectures/${encoded}/problems/${encodeURIComponent(problemId)}/answers`,
    )
  })

  it('datetime-local 값을 UTC ISO로 바꾼다', () => {
    const local = '2026-09-26T10:40'
    expect(toScheduledAtUtc(local)).toBe(new Date(local).toISOString())
  })
})

describe('ambiguousCandidates', () => {
  const candidates = [
    { input: '초성 ㄱ', displayText: '초성 ㄱ' },
    { input: '받침 ㄱ', displayText: '받침 ㄱ' },
  ]
  const ambiguous = (body: unknown) => new ApiError(409, 'PROBLEM_TEXT_AMBIGUOUS', 'm', body)

  it('409 PROBLEM_TEXT_AMBIGUOUS 본문의 후보를 돌려준다', () => {
    expect(
      ambiguousCandidates(ambiguous({ code: 'PROBLEM_TEXT_AMBIGUOUS', message: 'm', candidates })),
    ).toEqual(candidates)
  })

  it('다른 오류·ApiError가 아닌 값이면 null', () => {
    expect(
      ambiguousCandidates(new ApiError(404, 'PROBLEM_NOT_FOUND', 'm', { candidates })),
    ).toBeNull()
    expect(ambiguousCandidates(new Error('boom'))).toBeNull()
    expect(ambiguousCandidates(undefined)).toBeNull()
  })

  it('본문이 없거나 candidates가 배열이 아니면 null', () => {
    expect(ambiguousCandidates(ambiguous(undefined))).toBeNull()
    expect(ambiguousCandidates(ambiguous(null))).toBeNull()
    expect(ambiguousCandidates(ambiguous({ candidates: 'x' }))).toBeNull()
  })

  it('형식이 어긋난 후보는 빼고, 남는 것이 없으면 null', () => {
    expect(
      ambiguousCandidates(
        ambiguous({
          candidates: [
            null,
            { input: 3, displayText: 'x' },
            { input: ' ', displayText: 'x' },
            candidates[1],
          ],
        }),
      ),
    ).toEqual([candidates[1]])
    expect(ambiguousCandidates(ambiguous({ candidates: [{ input: '초성 ㄱ' }] }))).toBeNull()
  })
})
