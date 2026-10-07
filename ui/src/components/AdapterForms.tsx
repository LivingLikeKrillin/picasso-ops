import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Adapter } from '../api'

/** 빈칸이면 null 로 보낸다. 선택 입력의 빈칸은 «없음» 이다. */
const optional = (value: string) => (value.trim() === '' ? null : value.trim())

interface ProductProps {
  busy: boolean
  onDeclare: (vendor: string, name: string) => void
}

/** 어댑터 제품 선언(엔지니어 모드). 같은 제품을 다시 선언하면 registry 가 이미 있는 제품을 그대로 둔다. */
export function ProductForm({ busy, onDeclare }: ProductProps) {
  const [vendor, setVendor] = useState('')
  const [name, setName] = useState('')
  const ready = vendor.trim() !== '' && name.trim() !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (ready) onDeclare(vendor.trim(), name.trim())
  }

  return (
    <form aria-label="제품 선언" onSubmit={submit}>
      <label>
        vendor
        <input value={vendor} onChange={(event) => setVendor(event.target.value)} />
      </label>
      <label>
        name
        <input value={name} onChange={(event) => setName(event.target.value)} />
      </label>
      <button type="submit" disabled={!ready || busy}>
        제품 선언
      </button>
    </form>
  )
}

interface BuildProps {
  adapters: Adapter[]
  busy: boolean
  onDeclare: (adapter: Adapter, version: string, contractSemver: string) => void
}

/** 빌드 선언(엔지니어 모드). 제품은 목록에서 고른다. 계약 semver 의 형식은 registry 가 본다. */
export function BuildForm({ adapters, busy, onDeclare }: BuildProps) {
  const [adapterId, setAdapterId] = useState('')
  const [version, setVersion] = useState('')
  const [contractSemver, setContractSemver] = useState('')
  const adapter = adapters.find((candidate) => String(candidate.adapterId) === adapterId) ?? null
  const ready = adapter !== null && version.trim() !== '' && contractSemver.trim() !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (ready) onDeclare(adapter, version.trim(), contractSemver.trim())
  }

  return (
    <form aria-label="빌드 선언" onSubmit={submit}>
      <label>
        제품
        <select value={adapterId} onChange={(event) => setAdapterId(event.target.value)}>
          <option value="">고르십시오</option>
          {adapters.map((candidate) => (
            <option key={candidate.adapterId} value={candidate.adapterId}>
              {candidate.vendor}/{candidate.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        버전
        <input value={version} onChange={(event) => setVersion(event.target.value)} />
      </label>
      <label>
        계약 semver
        <input value={contractSemver} onChange={(event) => setContractSemver(event.target.value)} />
      </label>
      <button type="submit" disabled={!ready || busy}>
        빌드 선언
      </button>
    </form>
  )
}

interface InstanceProps {
  adapters: Adapter[]
  busy: boolean
  onRegister: (instanceId: string, adapterVersionId: number, fleetEndpoint: string | null) => void
}

/**
 * 인스턴스 등록(엔지니어 모드). 빌드는 목록에서 고른다. 사이트는 운영 서비스가 `SITE_ID` 로 채우므로 입력란이 없다(스펙 §9).
 * 플릿 주소는 플릿 경유일 때만 넣는다.
 */
export function InstanceForm({ adapters, busy, onRegister }: InstanceProps) {
  const [instanceId, setInstanceId] = useState('')
  const [buildId, setBuildId] = useState('')
  const [fleetEndpoint, setFleetEndpoint] = useState('')
  const ready = instanceId.trim() !== '' && buildId !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (ready) onRegister(instanceId.trim(), Number(buildId), optional(fleetEndpoint))
  }

  return (
    <form aria-label="인스턴스 등록" onSubmit={submit}>
      <label>
        instance_id
        <input value={instanceId} onChange={(event) => setInstanceId(event.target.value)} />
      </label>
      <label>
        빌드
        <select value={buildId} onChange={(event) => setBuildId(event.target.value)}>
          <option value="">고르십시오</option>
          {adapters.flatMap((adapter) =>
            adapter.versions.map((build) => (
              <option key={build.adapterVersionId} value={build.adapterVersionId}>
                {adapter.vendor}/{adapter.name} {build.version}
              </option>
            )),
          )}
        </select>
      </label>
      <label>
        플릿 주소
        <input value={fleetEndpoint} onChange={(event) => setFleetEndpoint(event.target.value)} />
      </label>
      <button type="submit" disabled={!ready || busy}>
        인스턴스 등록
      </button>
    </form>
  )
}
