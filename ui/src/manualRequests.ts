import type { JobOrderForm, Session } from './api'
export interface PendingManualRequest { requestId: string; form: JobOrderForm; canStartNew: boolean; receiptId?: string }
const key = (session: Session) => `picasso.manual-request.v1.${session.mode}.${session.user}`
export function loadManualRequest(session: Session): PendingManualRequest | null {
 const raw = sessionStorage.getItem(key(session))
 if(raw === null) return null
 const value = JSON.parse(raw) as PendingManualRequest
 if(!value || typeof value.requestId !== 'string' || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.requestId) ||
    !value.form || typeof value.form.workMasterId !== 'string' || typeof value.canStartNew !== 'boolean')
  throw new Error('보관된 수동 요청을 읽지 못했습니다. 저장소를 지우고 재제출하기 전에 실행 이력을 확인하세요')
 return value
}
export function saveManualRequest(session: Session, value: PendingManualRequest) {
 sessionStorage.setItem(key(session), JSON.stringify(value))
}
export function clearManualRequest(session: Session) { sessionStorage.removeItem(key(session)) }
