export type Envelope<T> = {
  code: number
  message: string
  requestId?: string
  data: T
}

export type StaffLogin = {
  token: string
  expiresIn: number
  staffId: string
  typ: string
  name: string
  username: string
  scopeType: string
  storeIds: string[]
}

const TOKEN_KEY = 'admin_token'
const NAME_KEY = 'admin_name'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function getStaffName(): string {
  return localStorage.getItem(NAME_KEY) ?? ''
}

export function setSession(token: string, name: string) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(NAME_KEY, name)
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(NAME_KEY)
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const res = await fetch(path, { ...init, headers })
  const body = (await res.json()) as Envelope<T>
  if (!res.ok || body.code !== 0) {
    throw new Error(body.message || `HTTP ${res.status}`)
  }
  return body.data
}

export async function staffLogin(code = 'dev-staff'): Promise<StaffLogin> {
  const data = await request<StaffLogin>('/api/v1/staff/auth/wechat', {
    method: 'POST',
    body: JSON.stringify({ code }),
  })
  setSession(data.token, data.name)
  return data
}
