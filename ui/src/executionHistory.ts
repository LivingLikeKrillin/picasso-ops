import type { SettledExecution, SettledPage } from './api'

/**
 * 운영 화면이 더 읽은 정착 이력(HA 설계 기록 §10). `items` 는 최근부터이고 모두 `anchor` 보다 앞의 정착 차례다. `anchor` 는 주기 읽기의
 * 최근 정착 이력 가운데 가장 오래된 것의 차례(`nextBefore`)이고, 주기 읽기의 정착 이력과 `items` 가 그 차례에서 맞닿는다.
 */
export interface ExecutionHistory {
  anchor: number
  items: SettledExecution[]
  nextBefore: number | null
  olderTotal: number
}

/** 한 페이지를 더 읽은 뒤. 처음이면 주기 읽기의 커서 [anchor] 에서 시작한다. */
export function appendOlder(history: ExecutionHistory | null, anchor: number, page: SettledPage): ExecutionHistory {
  const items = history === null ? page.settled : [...history.items, ...page.settled]
  return { anchor: history?.anchor ?? anchor, items, nextBefore: page.nextBefore, olderTotal: page.olderTotal }
}

/** 이어 읽을 커서. 더 읽은 것이 없으면 주기 읽기의 커서다. */
export const olderCursor = (history: ExecutionHistory | null, live: number | null) => (history === null ? live : history.nextBefore)

/** 더 남은 정착 이력 수. */
export const olderCount = (history: ExecutionHistory | null, live: number) => (history === null ? live : history.olderTotal)

/** 따라잡기 한 번에 읽는 페이지의 상한. 주기 사이에 그보다 많이 정착하면 더 읽은 이력을 버린다. */
export const CATCH_UP_PAGES = 10

/**
 * 주기 읽기의 최근 정착 이력이 새 정착으로 밀리면, 밀려난 이력(새 커서 [live] 와 더 읽은 이력 사이)을 채워 넣는다. 정착 차례가 늘기만
 * 하므로 [live] 앞에서 [ExecutionHistory.items] 의 가장 최근 것보다 뒤인 이력이 그 사이다. 주기 읽기가 더 앞으로 가지 않았으면 그대로,
 * 커서가 없어졌거나 뒤로 갔거나(기록이 비었다) 상한 안에 다 못 채우면 `null`(더 읽은 이력을 버림)이다.
 */
export async function catchUp(
  history: ExecutionHistory,
  live: number | null,
  read: (before: number) => Promise<SettledPage>,
): Promise<ExecutionHistory | null> {
  if (live === history.anchor) return history
  if (live === null || live < history.anchor) return null
  const newest = history.items[0]?.settleSeq ?? 0
  const gap: SettledExecution[] = []
  let cursor = live
  for (let pages = 0; pages < CATCH_UP_PAGES; pages++) {
    const page = await read(cursor)
    const fresh = page.settled.filter((row) => row.settleSeq > newest)
    gap.push(...fresh)
    if (fresh.length < page.settled.length || page.nextBefore === null) {
      return { ...history, anchor: live, items: [...gap, ...history.items] }
    }
    cursor = page.nextBefore
  }
  return null
}

/** 같은 실행인가. `exec-N` 은 인스턴스마다 다시 세므로 인스턴스와 짝짓는다. */
export const sameExecution = (a: { instanceId: string; executionId: string }, b: { instanceId: string; executionId: string }) =>
  a.instanceId === b.instanceId && a.executionId === b.executionId
