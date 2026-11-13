/** Support, privacy and exchange types (support-service, user-service, promotion-service). */

export type FaqCategory = 'ORDERS' | 'PAYMENTS' | 'RETURNS' | 'ACCOUNT' | 'SELLERS' | 'OTHER'

export interface FaqArticle {
  id: string
  slug: string
  category: FaqCategory
  title: string
  body?: string
  keywords?: string[]
  helpfulYes?: number
  helpfulNo?: number
  updatedAt?: string
}

export type TicketStatus = 'OPEN' | 'PENDING_CUSTOMER' | 'PENDING_AGENT' | 'RESOLVED' | 'CLOSED'

export interface TicketMessage {
  id: string
  authorType: 'CUSTOMER' | 'AGENT' | 'BOT' | 'SYSTEM'
  authorId?: string | null
  body: string
  createdAt: string
}

export interface Ticket {
  id: string
  orderId?: string | null
  shipmentId?: string | null
  category: string
  subject: string
  status: TicketStatus
  priority: string
  createdAt: string
  updatedAt: string
  csat?: number | null
  messages?: TicketMessage[]
}

export interface CreateTicketRequest {
  category: string
  subject: string
  body: string
  orderId?: string
}

export interface ChatAction {
  type: 'CREATE_TICKET' | 'TRACK_ORDER' | 'START_RETURN'
  orderId?: string
}

export interface ChatReply {
  reply: string
  suggestions?: string[]
  articles?: { slug: string; title: string }[]
  action?: ChatAction
}

export interface ExportJob {
  jobId: string
  status: 'PENDING' | 'RUNNING' | 'READY' | 'FAILED' | 'EXPIRED'
  downloadUrl?: string | null
  expiresAt?: string | null
}

export interface DeletionStatus {
  scheduledFor?: string | null
  status?: string
}

export interface ExchangeProgram {
  id: string
  title: string
  brand?: string | null
  maxValue: number
  currency: string
}

export type DeviceCondition = 'WORKING' | 'SCREEN_DAMAGED' | 'NOT_WORKING'

export interface ExchangeQuote {
  id: string
  programId?: string
  quotedValue: number
  currency?: string
  expiresAt?: string
  status?: string
}
