<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { RouterLink } from 'vue-router';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, fmtDate } from '@/lib/adminApi';
import { listTickets } from '@/lib/trustApi';
import { slaState, priorityBadge } from '@/lib/ticketUi';
import { useAuthStore } from '@/stores/auth';
import { TICKET_PRIORITIES, TICKET_STATUSES, type Ticket } from '@/types/trust';

/** Support queue: filter by status / priority / assignee, SLA and priority badges. */
const PAGE_SIZE = 20;
const auth = useAuthStore();

const rows = ref<readonly Ticket[]>([]);
const loading = ref(false);
const error = ref<{ message: string; correlationId: string | null } | null>(null);
const page = ref(0);
const totalPages = ref(1);
const totalElements = ref(0);
const filters = reactive({ status: '', priority: '', mine: false, q: '' });

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const res = await listTickets({
      status: filters.status || undefined,
      priority: filters.priority || undefined,
      assignee: filters.mine ? (auth.user?.id ?? undefined) : undefined,
      q: filters.q.trim() || undefined,
      page: page.value,
      size: PAGE_SIZE,
    });
    rows.value = res.content;
    totalPages.value = res.totalPages;
    totalElements.value = res.totalElements;
  } catch (e: unknown) {
    error.value = errorInfo(e);
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

function applyFilters(): void {
  page.value = 0;
  void load();
}

function go(delta: number): void {
  page.value = Math.max(0, Math.min(totalPages.value - 1, page.value + delta));
  void load();
}

onMounted(load);
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Support tickets</h1>
      <span v-if="!loading" class="text-sm opacity-70">{{ totalElements }} total</span>
    </div>

    <form class="mb-4 flex flex-wrap items-end gap-3" @submit.prevent="applyFilters">
      <select v-model="filters.status" class="select select-bordered select-sm" aria-label="Status" @change="applyFilters">
        <option value="">All statuses</option>
        <option v-for="s in TICKET_STATUSES" :key="s" :value="s">{{ s }}</option>
      </select>
      <select v-model="filters.priority" class="select select-bordered select-sm" aria-label="Priority" @change="applyFilters">
        <option value="">All priorities</option>
        <option v-for="p in TICKET_PRIORITIES" :key="p" :value="p">{{ p }}</option>
      </select>
      <label class="label cursor-pointer gap-2">
        <input v-model="filters.mine" type="checkbox" class="checkbox checkbox-sm" @change="applyFilters" />
        <span class="label-text">Assigned to me</span>
      </label>
      <input
        v-model="filters.q"
        class="input input-bordered input-sm w-56"
        type="search"
        placeholder="Search subject / email"
        aria-label="Search tickets"
      />
      <button class="btn btn-primary btn-sm" type="submit">Search</button>
    </form>

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading tickets…</p>
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <div v-else-if="rows.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No tickets</h2>
    </div>

    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Tickets, page {{ page + 1 }}</caption>
          <thead>
            <tr>
              <th scope="col">Subject</th>
              <th scope="col">Customer</th>
              <th scope="col">Category</th>
              <th scope="col">Status</th>
              <th scope="col">Priority</th>
              <th scope="col">SLA</th>
              <th scope="col">Updated</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in rows" :key="t.id" data-test="ticket-row" class="hover">
              <td>
                <RouterLink :to="`/support/tickets/${t.id}`" class="link link-hover font-medium">
                  {{ t.subject }}
                </RouterLink>
              </td>
              <td class="text-sm">{{ t.email ?? '-' }}</td>
              <td class="text-xs">{{ t.category }}</td>
              <td><span class="badge badge-sm badge-info">{{ t.status }}</span></td>
              <td><span class="badge badge-sm" :class="priorityBadge(t.priority)">{{ t.priority }}</span></td>
              <td>
                <span
                  class="badge badge-sm"
                  :class="slaState(t) === 'BREACHED' ? 'badge-error' : slaState(t) === 'DUE_SOON' ? 'badge-warning' : 'badge-ghost'"
                >
                  {{ slaState(t) }}
                </span>
              </td>
              <td class="text-sm">{{ fmtDate(t.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="flex items-center justify-between gap-4 border-t border-base-content/10 p-4">
        <span class="text-sm opacity-70">Page {{ page + 1 }} of {{ totalPages }}</span>
        <div class="join">
          <button class="btn btn-sm join-item" type="button" :disabled="page === 0" @click="go(-1)">Previous</button>
          <button class="btn btn-sm join-item" type="button" :disabled="page + 1 >= totalPages" @click="go(1)">Next</button>
        </div>
      </div>
    </div>
  </section>
</template>
