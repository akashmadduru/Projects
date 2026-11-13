import { apiFetch } from '@/lib/http';
import type { PagedModel } from '@/types/commerce';
import type {
  AuditEntry,
  FaqArticle,
  FaqArticleRequest,
  FxRates,
  Ticket,
  TicketDetail,
} from '@/types/trust';

/** Phase 10 admin wrappers: support-service, audit, exchange verification, fx-rates. */

/** Accepts a bare array or a PagedModel (support-service list shape is not pinned). */
export interface PageResult<T> {
  readonly content: readonly T[];
  readonly totalPages: number;
  readonly totalElements: number;
}

function toPage<T>(res: readonly T[] | PagedModel<T>): PageResult<T> {
  if (Array.isArray(res)) {
    const arr = res as readonly T[];
    return { content: arr, totalPages: 1, totalElements: arr.length };
  }
  const paged = res as PagedModel<T>;
  return {
    content: paged.content,
    totalPages: Math.max(paged.page?.totalPages ?? 1, 1),
    totalElements: paged.page?.totalElements ?? paged.content.length,
  };
}

// ---- tickets --------------------------------------------------------------------------------

export async function listTickets(query: {
  status?: string;
  priority?: string;
  assignee?: string;
  q?: string;
  page: number;
  size: number;
}): Promise<PageResult<Ticket>> {
  const res = await apiFetch<readonly Ticket[] | PagedModel<Ticket>>('/api/admin/support/tickets', {
    query,
  });
  return toPage(res);
}

export function getTicket(id: string): Promise<TicketDetail> {
  return apiFetch<TicketDetail>(`/api/admin/support/tickets/${encodeURIComponent(id)}`);
}

export function replyToTicket(id: string, body: string, internal: boolean): Promise<unknown> {
  return apiFetch<unknown>(`/api/admin/support/tickets/${encodeURIComponent(id)}/messages`, {
    method: 'POST',
    body: { body, internal },
  });
}

/** `userId` omitted -> assign to the calling agent. */
export function assignTicket(id: string, userId?: string): Promise<unknown> {
  return apiFetch<unknown>(`/api/admin/support/tickets/${encodeURIComponent(id)}/assign`, {
    method: 'POST',
    body: userId ? { userId } : { me: true },
  });
}

export function setTicketStatus(id: string, status: string): Promise<unknown> {
  return apiFetch<unknown>(`/api/admin/support/tickets/${encodeURIComponent(id)}/status`, {
    method: 'POST',
    body: { status },
  });
}

// ---- FAQ ------------------------------------------------------------------------------------

export async function listArticles(): Promise<readonly FaqArticle[]> {
  const res = await apiFetch<readonly FaqArticle[] | PagedModel<FaqArticle>>(
    '/api/admin/support/articles',
    { query: { page: 0, size: 200 } },
  );
  return toPage(res).content;
}

export function createArticle(body: FaqArticleRequest): Promise<FaqArticle> {
  return apiFetch<FaqArticle>('/api/admin/support/articles', { method: 'POST', body });
}

export function updateArticle(id: string, body: FaqArticleRequest): Promise<FaqArticle> {
  return apiFetch<FaqArticle>(`/api/admin/support/articles/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body,
  });
}

export function deleteArticle(id: string): Promise<void> {
  return apiFetch<void>(`/api/admin/support/articles/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

// ---- audit ----------------------------------------------------------------------------------

export async function listAudit(query: {
  actor?: string;
  resourceType?: string;
  resourceId?: string;
  from?: string;
  to?: string;
  page: number;
  size: number;
}): Promise<PageResult<AuditEntry>> {
  const res = await apiFetch<readonly AuditEntry[] | PagedModel<AuditEntry>>('/api/admin/audit', {
    query,
  });
  return toPage(res);
}

// ---- exchange -------------------------------------------------------------------------------

export function verifyExchange(
  quoteId: string,
  accepted: boolean,
  finalValue?: number,
): Promise<unknown> {
  return apiFetch<unknown>(`/api/admin/exchange/${encodeURIComponent(quoteId)}/verify`, {
    method: 'POST',
    body: accepted ? { accepted, finalValue } : { accepted },
  });
}

// ---- fx-rates -------------------------------------------------------------------------------

export function getFxRates(): Promise<FxRates> {
  return apiFetch<FxRates>('/api/products/fx-rates', { authenticated: false });
}

export function saveFxRates(body: FxRates): Promise<FxRates> {
  return apiFetch<FxRates>('/api/admin/fx-rates', { method: 'PUT', body });
}
