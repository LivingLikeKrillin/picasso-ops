import { describe, expect, it } from 'vitest'
import type { Execution, SettledExecution, SettledPage } from './api'
import { appendOlder, CATCH_UP_PAGES, catchUp, olderCount, olderCursor } from './executionHistory'
import type { ExecutionHistory } from './executionHistory'

/** 정착 차례 [seq] 의 정착 이력 한 줄. 실행 본문은 차례만 다르다. */
const row = (seq: number): SettledExecution => ({
  settleSeq: seq,
  instanceId: 'mw-1',
  settledAt: `s${seq}`,
  snapshot: true,
  execution: { executionId: `exec-${seq}`, jobOrderId: `JO-${seq}` } as Execution,
})

/** 정착 기록 대역. 실행 호스트처럼 커서 앞의 이력을 최근부터 [limit] 개 주고 다음 커서와 남은 수를 붙인다. */
function store(seqs: number[], limit: number) {
  const all = [...seqs].sort((a, b) => b - a)
  const reads: number[] = []
  const read = async (before: number): Promise<SettledPage> => {
    reads.push(before)
    const older = all.filter((seq) => seq < before)
    const page = older.slice(0, limit)
    const rest = older.length - page.length
    return { settled: page.map(row), nextBefore: rest > 0 ? page[page.length - 1] : null, olderTotal: rest }
  }
  /** 주기 읽기(최근 [recent] 개)의 커서. */
  const live = (recent: number) => (all.length > recent ? all[recent - 1] : null)
  const add = (...more: number[]) => {
    all.push(...more)
    all.sort((a, b) => b - a)
  }
  return { all, read, reads, live, add }
}

describe('이전 실행 이력', () => {
  it('더 읽은 이력은 주기 읽기의 커서에서 시작해 커서 앞의 정착 이력을 겹치거나 빠짐 없이 잇는다', async () => {
    const { all, read, live } = store([1, 2, 4, 5, 7, 8, 9, 11, 12, 13, 15], 3)
    const anchor = live(2)!
    let history: ExecutionHistory | null = null
    let cursor = olderCursor(history, anchor)
    while (cursor !== null) {
      history = appendOlder(history, anchor, await read(cursor))
      cursor = olderCursor(history, anchor)
    }
    expect(history!.anchor).toBe(anchor)
    expect(history!.items.map((r) => r.settleSeq)).toEqual(all.filter((seq) => seq < anchor))
    expect([history!.nextBefore, olderCount(history, 99)]).toEqual([null, 0])
  })

  it('새 정착으로 주기 읽기의 커서가 밀리면 밀려난 이력만 채워 주기 읽기와 맞닿고 그대로면 다시 읽지 않는다', async () => {
    for (const [added, limit, pages] of [[[16], 2, 1], [[16, 18, 19], 2, 2], [[16, 17, 18, 19, 20, 21, 22], 3, 3]] as const) {
      const s = store([1, 3, 4, 6, 8, 9, 10], limit)
      const anchor = s.live(2)!
      const history = appendOlder(null, anchor, await s.read(anchor))
      expect(await catchUp(history, anchor, s.read)).toBe(history)
      const before = s.reads.length
      s.add(...added)
      const live = s.live(2)!
      const caught = (await catchUp(history, live, s.read))!
      expect(caught.anchor).toBe(live)
      // 주기 읽기(커서 이상)와 더 읽은 이력(커서 미만)을 이으면 정착 이력의 최근부터 같은 차례다.
      const shown = [...s.all.filter((seq) => seq >= live), ...caught.items.map((r) => r.settleSeq)]
      expect(shown).toEqual(s.all.slice(0, shown.length))
      expect([caught.nextBefore, caught.olderTotal]).toEqual([history.nextBefore, history.olderTotal])
      expect(s.reads.length - before).toBe(pages)
    }
  })

  it('따라잡을 이력이 상한보다 많거나 커서가 없어지거나 뒤로 가면 더 읽은 이력을 버린다', async () => {
    const s = store([1, 2, 3, 4], 1)
    const history = appendOlder(null, 3, await s.read(3))
    expect(await catchUp(history, null, s.read)).toBeNull()
    expect(await catchUp(history, 2, s.read)).toBeNull()
    s.add(...Array.from({ length: CATCH_UP_PAGES + 2 }, (_, i) => 10 + i))
    expect(await catchUp(history, s.live(1)!, s.read)).toBeNull()
  })

  it('더 읽은 것이 없을 때 이어 읽을 커서와 남은 수는 주기 읽기의 것이다', () => {
    expect(olderCursor(null, 7)).toBe(7)
    expect(olderCursor(null, null)).toBeNull()
    expect(olderCount(null, 4)).toBe(4)
    const history = appendOlder(null, 7, { settled: [row(5)], nextBefore: 5, olderTotal: 2 })
    expect([olderCursor(history, 7), olderCount(history, 4)]).toEqual([5, 2])
    expect(appendOlder(history, 9, { settled: [row(3), row(1)], nextBefore: null, olderTotal: 0 })).toEqual({
      anchor: 7, items: [row(5), row(3), row(1)], nextBefore: null, olderTotal: 0,
    })
  })
})
