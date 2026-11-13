/** Phase 10: support-service, audit, exchange and fx-rates shapes (admin side). */

export type TicketStatus = 'OPEN' | 'PENDING_CUSTOMER' | 'PENDING_AGENT' | 'RESOLVED' | 'CLOSED';
export const TICKET_STATUSES: readonly TicketStatus[] = [
  'OPEN',
  'PENDING_CUSTOMER',
  'PENDING_AGENT',
  'RESOLVED',
  'CLOSED',
];

export type TicketPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';
export const TICKET_PRIORITIES: readonly TicketPriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT'];

export interface Ticket {
  readonly id: string;
  readonly userId: string | null;
  readonly email: string | null;
  readonly orderId: string | null;
  readonly shipmentId: string | null;
  readonly category: string;
  readonly subject: string;
  readonly status: TicketStatus;
  readonly priority: TicketPriority;
  readonly assigneeUserId: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly firstResponseAt: string | null;
  readonly resolvedAt: string | null;
  readonly csat: number | null;
  readonly ref?: string;
  readonly slaBreached?: boolean;
  /** SLA due timestamps - field names are an assumption, tolerated when absent. */
  readonly firstResponseDueAt?: string | null;
  readonly resolutionDueAt?: string | null;
}

export interface TicketMessage {
  readonly id: string;
  readonly ticketId?: string;
  readonly authorType: 'CUSTOMER' | 'AGENT' | 'BOT' | 'SYSTEM';
  readonly authorId: string | null;
  readonly body: string;
  readonly createdAt: string;
  readonly internal?: boolean;
}

export interface TicketDetail extends Ticket {
  readonly messages?: readonly TicketMessage[];
}

export type FaqCategory = 'ORDERS' | 'PAYMENTS' | 'RETURNS' | 'ACCOUNT' | 'SELLERS' | 'OTHER';
export const FAQ_CATEGORIES: readonly FaqCategory[] = [
  'ORDERS',
  'PAYMENTS',
  'RETURNS',
  'ACCOUNT',
  'SELLERS',
  'OTHER',
];

export interface FaqArticle {
  readonly id: string;
  readonly slug: string;
  readonly category: FaqCategory;
  readonly title: string;
  readonly body: string;
  readonly keywords: readonly string[];
  readonly published: boolean;
  readonly helpfulYes?: number;
  readonly helpfulNo?: number;
  readonly updatedAt?: string;
}

export interface FaqArticleRequest {
  readonly slug: string;
  readonly category: FaqCategory;
  readonly title: string;
  readonly body: string;
  readonly keywords: readonly string[];
  readonly published: boolean;
}

export interface AuditEntry {
  readonly id: string;
  readonly actorUserId: string | null;
  /** Comma-separated role names as stored by support-service. */
  readonly actorRoles?: string | null;
  readonly action: string;
  readonly resourceType: string;
  readonly resourceId: string | null;
  readonly before?: unknown;
  readonly after?: unknown;
  readonly ip?: string | null;
  readonly at: string;
}

export interface FxRates {
  readonly base: string;
  readonly rates: Readonly<Record<string, number>>;
  readonly asOf?: string;
}
