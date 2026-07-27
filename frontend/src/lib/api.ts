import axios, { type AxiosInstance, type AxiosRequestConfig, type AxiosError, type InternalAxiosRequestConfig } from 'axios'

/**
 * Trình duyệt: gọi /api/v1 cùng origin → Next.js proxy (next.config rewrites).
 * Tránh lỗi CORS khi dev trên cổng 3001 trong khi Docker chiếm 3000.
 * Server (SSR/API routes): gọi thẳng backend qua API_URL.
 */
function resolveApiBaseUrl(): string {
  if (typeof window !== 'undefined') {
    return '/api/v1'
  }
  const serverUrl =
    process.env.API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'
  return `${serverUrl.replace(/\/$/, '')}/api/v1`
}

/** Config mở rộng để track số lần retry trên mỗi request. */
type RetryConfig = InternalAxiosRequestConfig & { _retryCount?: number }

/**
 * Retry với exponential backoff — chỉ cho GET và lỗi transient (5xx / network).
 * POST/PUT/PATCH/DELETE không được retry tự động để tránh double-submit.
 */
async function retryWithBackoff(error: AxiosError): Promise<unknown> {
  const config = error.config as RetryConfig | undefined
  if (!config) return Promise.reject(error)

  const method = (config.method ?? '').toUpperCase()
  const status = error.response?.status
  const isNetwork = !error.response && Boolean(error.request)
  const isServerError = status !== undefined && status >= 500

  // Chỉ retry GET, chỉ khi là lỗi mạng hoặc 5xx, tối đa 3 lần
  if (method !== 'GET' || (!isNetwork && !isServerError)) {
    return Promise.reject(error)
  }

  config._retryCount = (config._retryCount ?? 0) + 1
  if (config._retryCount > 3) return Promise.reject(error)

  const delayMs = 1000 * Math.pow(2, config._retryCount - 1) // 1s, 2s, 4s
  await new Promise((r) => setTimeout(r, delayMs))
  return api(config)
}

/**
 * Axios instance chính — dùng cho tất cả API calls.
 * Tự động đính kèm Bearer token và xử lý 401.
 */
const api: AxiosInstance = axios.create({
  baseURL: resolveApiBaseUrl(),
  timeout: 10_000,
  headers: {
    'Content-Type': 'application/json',
  },
  paramsSerializer: {
    indexes: null,
  },
})

// ── Request interceptor: đính kèm JWT token ──────────────
api.interceptors.request.use(
  async (config) => {
    if (config.data instanceof FormData) {
      delete config.headers['Content-Type']
    }
    if (typeof window !== 'undefined') {
      // Try localStorage first (fast, local), fallback to getSession() (network call)
      const localToken = localStorage.getItem('access_token')
      if (localToken) {
        config.headers.Authorization = `Bearer ${localToken}`
      } else {
        try {
          const { getSession } = await import('next-auth/react')
          const session = await getSession()
          const token = (session as any)?.accessToken
          if (token) {
            config.headers.Authorization = `Bearer ${token}`
          }
        } catch {
          // Ignore error and proceed without token
        }
      }
    }
    return config
  },
  (error) => Promise.reject(error)
)

// ── Response interceptor: retry + xử lý lỗi tập trung ───
api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    // Thử retry trước (GET + 5xx/network) — nếu thành công, không xử lý lỗi bên dưới
    const retryResult = await retryWithBackoff(error).catch(() => null)
    if (retryResult !== null) return retryResult

    const originalRequest = error.config as AxiosRequestConfig & { _retry?: boolean }

    // Chỉ 401 (Unauthorized) mới đăng xuất — 403 (Forbidden) là lỗi phân quyền,
    // không phải session expired, KHÔNG được signOut người dùng.
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true
      if (typeof window !== 'undefined') {
        const hasToken = localStorage.getItem('access_token')
        localStorage.removeItem('access_token')
        const isLoginPage = window.location.pathname.startsWith('/login')
        if (hasToken && !isLoginPage) {
          try {
            const { signOut } = await import('next-auth/react')
            signOut({ callbackUrl: '/login?error=session_expired' })
          } catch {
            // If next-auth is unavailable or signOut fails, still clear token locally.
          }
        }
      }
    }

    const data = error.response?.data as {
      message?: string
      errors?: Record<string, string> | string[]
      error?: string
    } | undefined

    const validationFromRecord = data?.errors && !Array.isArray(data.errors) && Object.keys(data.errors).length > 0
      ? Object.values(data.errors)[0]
      : undefined
    const validationFromArray = Array.isArray(data?.errors) && data.errors.length > 0
      ? data.errors.filter(Boolean).join('; ')
      : undefined

    let message =
      validationFromRecord ||
      validationFromArray ||
      data?.message ||
      data?.error ||
      error.message ||
      'Đã xảy ra lỗi, vui lòng thử lại'

    if (error.response?.status === 401) {
      message = 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.'
      if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
        import('next-auth/react').then(({ signOut }) => {
          signOut({ callbackUrl: '/login' })
        })
      }
    }

    if (error.response?.status === 403) {
      // Lỗi phân quyền — KHÔNG signOut, KHÔNG xoá token.
      // Hiển thị message từ backend (nếu có) hoặc thông báo mặc định.
      message = data?.message || 'Bạn không có quyền thực hiện hành động này.'
    }

    const customError = new Error(message) as Error & { status?: number }
    customError.status = error.response?.status
    return Promise.reject(customError)
  }
)

export default api

/**
 * Public API instance — dùng cho các endpoint không cần auth (/public/...).
 * Không có response interceptor 401→signOut nên guest user không bị redirect login.
 */
export const publicApi = axios.create({
  baseURL: resolveApiBaseUrl(),
  timeout: 10_000,
  headers: { 'Content-Type': 'application/json' },
  paramsSerializer: { indexes: null },
})



// Response interceptor: chỉ reject lỗi — KHÔNG signOut khi 401
publicApi.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const data = error.response?.data as { message?: string; error?: string } | undefined
    const message = data?.message || data?.error || error.message || 'Đã xảy ra lỗi'
    const customError = new Error(message) as Error & { status?: number }
    customError.status = error.response?.status
    return Promise.reject(customError)
  }
)


export async function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const res = await api.get<T>(url, config)
  return res.data
}

export async function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const res = await api.post<T>(url, data, config)
  return res.data
}

export async function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const res = await api.put<T>(url, data, config)
  return res.data
}

export async function patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const res = await api.patch<T>(url, data, config)
  return res.data
}

export async function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const res = await api.delete<T>(url, config)
  return res.data
}

export async function postLongTimeout<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const res = await api.post<T>(url, data, { ...config, timeout: 60_000 })
  return res.data
}

export type FileUploadResult = {
  url: string
  fileName: string
}

export async function uploadAvatar(file: File): Promise<FileUploadResult> {
  const formData = new FormData()
  formData.append('file', file)
  const res = await api.post<FileUploadResult>('/files/avatar', formData, {
    timeout: 60_000,
  })
  return res.data
}

export async function uploadStadiumImage(file: File): Promise<FileUploadResult> {
  const formData = new FormData()
  formData.append('file', file)
  const res = await api.post<FileUploadResult>('/files/stadium', formData, {
    timeout: 60_000,
  })
  return res.data
}

export async function uploadDocument(file: File): Promise<FileUploadResult> {
  const formData = new FormData()
  formData.append('file', file)
  const res = await api.post<FileUploadResult>('/files/document', formData, {
    timeout: 60_000,
  })
  return res.data
}

/**
 * Tạo idempotency key (UUID v4 đơn giản) để gắn vào header X-Idempotency-Key.
 * Gọi một lần khi user mở form đặt sân — không gọi lại mỗi submit.
 *
 * @example
 * const idemKey = useRef(generateIdempotencyKey())
 * await post('/bookings', body, { headers: { 'X-Idempotency-Key': idemKey.current } })
 */
export function generateIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  // Fallback cho môi trường không có crypto.randomUUID (Node.js < 14.17)
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}
