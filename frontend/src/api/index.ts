import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 10000
})

/**
 * 当前操作角色（顶栏切换）：只有 FINANCE 财务能开票/作废。
 * 角色随每个请求放进 X-Operator-Role 头，由后端强制校验——
 * 销售点开票按钮也会被后端 403 挡下，并提示「只有财务能开」。
 */
const ROLE_STORAGE_KEY = 'salon.operatorRole'
const OPERATOR_STORAGE_KEY = 'salon.operatorName'

export const roleStore = {
  getRole(): OperatorRole {
    return localStorage.getItem(ROLE_STORAGE_KEY) === 'FINANCE' ? 'FINANCE' : 'SALES'
  },
  setRole(role: OperatorRole) {
    localStorage.setItem(ROLE_STORAGE_KEY, role)
  },
  getOperatorName(role: OperatorRole): string {
    return localStorage.getItem(OPERATOR_STORAGE_KEY + '.' + role)
      || (role === 'FINANCE' ? '财务值班员' : '销售值班员')
  },
  setOperatorName(role: OperatorRole, name: string) {
    localStorage.setItem(OPERATOR_STORAGE_KEY + '.' + role, name)
  }
}

api.interceptors.request.use((config) => {
  const role = roleStore.getRole()
  config.headers.set('X-Operator-Role', role)
  config.headers.set('X-Operator-Name', roleStore.getOperatorName(role))
  return config
})

export interface Venue {
  id?: number
  name: string
  capacity: number
  pricePerDay: number
  facilities: string
  activityTypes: string
  description: string
  status: number
  /** 乐观锁版本号：编辑时带回，两人前后脚改名时旧版本提交会收到 409 */
  version?: number
  createdAt?: string
  updatedAt?: string
}

export interface VenueSaveResponse {
  venue: Venue
  breakMessage?: string | null
}

export interface ActivityDemand {
  id?: number
  customerName: string
  customerPhone: string
  demandName: string
  expectedDate: string
  expectedPeople: number
  activityCategory: string
  budgetMin: number
  budgetMax: number
  requiredFacilities: string
  specialRequirements: string
  matchStatus: number
  locked?: number
  lockedVenueId?: number | null
  lockedVenueName?: string | null
  lockedAt?: string | null
  lockBreakReason?: string | null
  /** 当前冻结中的押金金额（结算退回后清空） */
  depositAmount?: number | null
  depositFreezeId?: number | null
  /** 最近一次押金结算说明 */
  depositRefundSummary?: string | null
  /** 是否已开场：0-未开场（已锁定），1-已开场（两岗签到齐全后开场） */
  opened?: number
  openedAt?: string | null
  // ==================== 当前有效结算发票（后端挂载的只读快照，三处对同一张票） ====================
  currentInvoiceId?: number | null
  currentInvoiceNo?: string | null
  currentInvoiceAmount?: number | null
  /** VALID-有效票；无有效票（含旧票已作废）为 null */
  currentInvoiceStatus?: 'VALID' | null
  createdAt?: string
  updatedAt?: string
}

export interface RecommendResult {
  id?: number
  demandId: number
  venueId: number
  venueName: string
  matchScore: number
  capacityScore: number
  facilityScore: number
  activityTypeScore: number
  budgetScore: number
  recommendOrder: number
  reason: string
  createdAt?: string
}

export interface RecommendCalculation {
  results: RecommendResult[]
  warningMessage?: string
  missingResources?: string[]
  matchStatus?: number
}

export interface WeightConfig {
  [key: string]: number
}

/** 踩点试场登记 */
export interface SiteVisit {
  id?: number
  demandId?: number
  venueId: number
  venueName?: string
  visitDate: string
  /** MORNING-上午 / AFTERNOON-下午 */
  timeSlot: 'MORNING' | 'AFTERNOON'
  reservedPeople: number
  createdAt?: string
}

/** 锁定记录 */
export interface LockRecord {
  id?: number
  demandId: number
  demandName?: string
  venueId: number
  venueName: string
  /** LOCKED-有效锁定 / BROKEN-自行破裂 / RELEASED-已解除 / CANCELED-活动已取消 */
  status: 'LOCKED' | 'BROKEN' | 'RELEASED' | 'CANCELED'
  breakReason?: string | null
  lockedAt?: string
  releasedAt?: string
}

/** 客户押金账户 */
export interface CustomerAccount {
  id?: number
  customerName: string
  customerPhone?: string | null
  availableBalance: number
  frozenBalance: number
  updatedAt?: string
}

/**
 * 押金流水台账：
 * RECHARGE-充值；FREEZE-冻结；REFUND-取消活动分档退回（含没收）；UNFREEZE-解除/破裂全额退回
 */
export interface DepositTransaction {
  id?: number
  accountId: number
  customerName: string
  customerPhone?: string | null
  demandId?: number | null
  demandName?: string | null
  venueId?: number | null
  venueName?: string | null
  type: 'RECHARGE' | 'FREEZE' | 'REFUND' | 'UNFREEZE'
  amount: number
  refundAmount: number
  forfeitAmount: number
  refundRate?: number | null
  reason?: string | null
  freezeTransactionId?: number | null
  activityDate?: string | null
  /** 该流水关联的当前有效结算发票（冻结行与结算行挂同一张；旧票作废后不再挂） */
  currentInvoiceId?: number | null
  currentInvoiceNo?: string | null
  currentInvoiceAmount?: number | null
  currentInvoiceStatus?: 'VALID' | null
  createdAt?: string
}

/**
 * 结算发票台账：
 * VALID-有效票（可报销/对账的当前有效票）；RED_VOID-已作废红字票（旧票号留痕、不能再当有效票报）。
 * basisType：FROZEN_OPENED-活动已开场按冻结额；FULLY_REFUNDED-押金全额实退完按实退额。
 */
export interface Invoice {
  id?: number
  invoiceNo: string
  demandId: number
  demandName?: string | null
  accountId: number
  customerName: string
  customerPhone?: string | null
  venueId?: number | null
  venueName?: string | null
  activityDate?: string | null
  amount: number
  basisType: 'FROZEN_OPENED' | 'FULLY_REFUNDED'
  freezeTransactionId?: number | null
  settlementTransactionId?: number | null
  basisReason?: string | null
  status: 'VALID' | 'RED_VOID'
  activeFlag?: number | null
  issuedAt?: string
  issuedBy?: string | null
  voidedAt?: string | null
  voidedBy?: string | null
  voidReason?: string | null
}

/** 当前登录角色：FINANCE-财务（能开票/作废）；SALES-销售（点开票必须被挡下） */
export type OperatorRole = 'FINANCE' | 'SALES'

/** 取消活动退押预估（后端按活动日距离分档计算） */
export interface DepositPreview {
  frozenAmount: number
  refundRate: number
  refundAmount: number
  forfeitAmount: number
  daysToActivity: number
  ruleText: string
}

/** 值守岗位：PRIMARY-主值守 / FLEX-机动 */
export type DutyPost = 'PRIMARY' | 'FLEX'

/** 值守签到记录 */
export interface DutySignin {
  id?: number
  demandId: number
  demandName?: string
  lockRecordId: number
  venueId: number
  venueName: string
  activityDate: string
  post: DutyPost
  staffName: string
  /** SIGNED-在岗 / WITHDRAWN-已撤岗 */
  status: 'SIGNED' | 'WITHDRAWN'
  signedAt?: string
  withdrawnAt?: string | null
}

/** 当天开场条：OPEN-已开场 / VOID-已作废（值守撤岗两岗不齐） */
export interface OpeningRecord {
  id?: number
  demandId: number
  lockRecordId: number
  venueId: number
  venueName: string
  activityDate: string
  status: 'OPEN' | 'VOID'
  openedAt?: string
  voidedAt?: string | null
  voidReason?: string | null
}

/** 当天值守视图：一块场地当天的两岗签到与开场状态（当天页与操作后刷新同用这一份） */
export interface DayDutyView {
  demandId: number
  demandName: string
  customerName: string
  venueId: number
  venueName: string
  activityDate: string
  /** 主值守在岗信息；null 表示该岗空着 */
  primaryPost: { staffName: string; signedAt: string } | null
  /** 机动岗在岗信息；null 表示该岗空着 */
  flexPost: { staffName: string; signedAt: string } | null
  /** 两岗是否都已签到 */
  bothSigned: boolean
  /** 当前能否开场（已锁定 + 两岗齐全 + 未开场） */
  canOpen: boolean
  /** 是否已开场 */
  opened: boolean
  openingId?: number | null
  openedAt?: string | null
}

export const venueApi = {
  getAll: () => api.get<Venue[]>('/venue'),
  getById: (id: number) => api.get<Venue>(`/venue/${id}`),
  create: (data: Venue) => api.post<Venue>('/venue', data),
  update: (id: number, data: Venue) => api.put<VenueSaveResponse>(`/venue/${id}`, data),
  delete: (id: number) => api.delete<VenueSaveResponse>(`/venue/${id}`)
}

export const demandApi = {
  getAll: () => api.get<ActivityDemand[]>('/demand'),
  getById: (id: number) => api.get<ActivityDemand>(`/demand/${id}`),
  create: (data: ActivityDemand) => api.post<ActivityDemand>('/demand', data),
  update: (id: number, data: ActivityDemand) => api.put<ActivityDemand>(`/demand/${id}`, data),
  delete: (id: number) => api.delete(`/demand/${id}`),
  calculateRecommend: (id: number) => api.post<RecommendCalculation>(`/demand/${id}/recommend`),
  getRecommendResults: (id: number) => api.get<RecommendResult[]>(`/demand/${id}/recommend`),
  registerSiteVisit: (id: number, data: SiteVisit) => api.post<SiteVisit>(`/demand/${id}/site-visit`, data),
  getSiteVisit: (id: number) => api.get<SiteVisit>(`/demand/${id}/site-visit`),
  cancelSiteVisit: (id: number) => api.delete(`/demand/${id}/site-visit`),
  confirmLock: (id: number, venueId: number) =>
    api.post<LockRecord>(`/demand/${id}/lock`, null, { params: { venueId } }),
  releaseLock: (id: number) => api.post<LockRecord>(`/demand/${id}/unlock`),
  getLockHistory: (id: number) => api.get<LockRecord[]>(`/demand/${id}/locks`),
  /** 取消活动退押预估（档位金额以后端结算为准） */
  previewCancellation: (id: number) => api.get<DepositPreview>(`/demand/${id}/deposit/preview`),
  /** 客户取消活动：按距活动日远近分档退押，接口不传比例，销售不能手改 */
  cancelActivity: (id: number) => api.post<DepositTransaction>(`/demand/${id}/cancel`),
  getDepositTransactions: (id: number) =>
    api.get<DepositTransaction[]>(`/demand/${id}/deposit/transactions`),
  /** 该需求的发票记录（有效票 + 红字作废票） */
  getInvoices: (id: number) => api.get<Invoice[]>(`/demand/${id}/invoices`)
}

/**
 * 结算发票（财务专用）：后端按押金冻结结清状态核算票面金额，
 * 没结清、已有有效票、非财务角色都会失败并返回中文原因。
 */
export const invoiceApi = {
  /** 发票台账：有效票 + 红字作废票全部留痕 */
  listAll: () => api.get<Invoice[]>('/finance/invoices'),
  listByDemand: (demandId: number) =>
    api.get<Invoice[]>(`/finance/invoices/demand/${demandId}`),
  /** 按押金结清状态开票（金额/客户名全部由后端流水核算，不接收手填） */
  issue: (demandId: number) =>
    api.post<Invoice>(`/finance/invoices/demand/${demandId}/issue`, {}),
  /** 旧票作废成红字（必填原因），作废后才可重开 */
  voidInvoice: (invoiceId: number, voidReason: string) =>
    api.post<Invoice>(`/finance/invoices/${invoiceId}/void`, { voidReason })
}

export const financeApi = {
  listAccounts: () => api.get<CustomerAccount[]>('/finance/accounts'),
  listTransactions: () => api.get<DepositTransaction[]>('/finance/deposit-transactions'),
  recharge: (accountId: number, amount: number, note?: string) =>
    api.post<CustomerAccount>(`/finance/accounts/${accountId}/recharge`, { amount, note })
}

/** 开场当天值守：两岗签到是开场条件（安保规定） */
export const dutyApi = {
  /** 当天页：不传日期默认今天 */
  getDayView: (date?: string) =>
    api.get<DayDutyView[]>('/duty/day', { params: date ? { date } : {} }),
  getDemandDuty: (demandId: number) => api.get<DayDutyView>(`/duty/demand/${demandId}`),
  /** 岗位签到：同岗只能一人在岗，被占用时后端返回 409 及占用人 */
  signIn: (demandId: number, post: DutyPost, staffName: string) =>
    api.post<DutySignin>('/duty/signin', { demandId, post, staffName }),
  /** 中途撤岗：已开场的退回已锁定、当天开场条作废；押金冻结不动 */
  withdraw: (demandId: number, post: DutyPost) =>
    api.post<DutySignin>('/duty/withdraw', { demandId, post }),
  /** 开场：两岗签到齐全才能把已锁定变成已开场 */
  openVenue: (demandId: number) => api.post<OpeningRecord>('/duty/open', { demandId })
}

export const weightApi = {
  getAll: () => api.get<WeightConfig>('/weight'),
  update: (key: string, value: number) => api.put<number>(`/weight/${key}`, null, { params: { value } }),
  reload: () => api.post('/weight/reload')
}

/** 统一提取后端中文错误信息 */
export const resolveError = (error: unknown, fallback = '操作失败，请重试'): string => {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as any
    if (data?.message) return Array.isArray(data.message) ? data.message.join('；') : data.message
    if (error.response?.statusText) return `${fallback}（${error.response.status}）`
  }
  return fallback
}
