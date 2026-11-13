<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, fmtDate } from '@/lib/adminApi';
import { listAudit } from '@/lib/trustApi';
import type { AuditEntry } from '@/types/trust';

/** Read-only audit trail (ADMIN only). */
const PAGE_SIZE = 25;

const rows = ref<readonly AuditEntry[]>([]);
const loading = ref(false);
const error = ref<{ message: string; correlationId: string | null } | null>(null);
const page = ref(0);
const totalPages = ref(1);
const totalElements = ref(0);
const filters = reactive({ actor: '', resourceType: '', resourceId: '', from: '', to: '' });
const expanded = ref<Record<string, boolean>>({});

/** datetime-local has no zone; interpret as local time and send ISO instants. */
function toIso(local: string): string | undefined {
  if (!local) return undefined;
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const res = await listAudit({
      actor: filters.actor.trim() || undefined,
      resourceType: filters.resourceType.trim() || undefined,
      resourceId: filters.resourceId.trim() || undefined,
      from: toIso(filters.from),
      to: toIso(filters.to),
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

function json(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}

onMounted(load);
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Audit log</h1>
      <span v-if="!loading" class="text-sm opacity-70">{{ totalElements }} events</span>
    </div>

    <form class="mb-4 flex flex-wrap items-end gap-3" @submit.prevent="applyFilters">
      <input v-model="filters.actor" class="input input-bordered input-sm w-72 font-mono" placeholder="Actor user id" aria-label="Actor user id" />
      <input v-model="filters.resourceType" class="input input-bordered input-sm w-40" placeholder="Resource type" aria-label="Resource type" />
      <input v-model="filters.resourceId" class="input input-bordered input-sm w-72 font-mono" placeholder="Resource id" aria-label="Resource id" />
      <label class="text-xs">From
        <input v-model="filters.from" type="datetime-local" class="input input-bordered input-sm ml-1" />
      </label>
      <label class="text-xs">To
        <input v-model="filters.to" type="datetime-local" class="input input-bordered input-sm ml-1" />
      </label>
      <button class="btn btn-primary btn-sm" type="submit">Filter</button>
    </form>

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>
    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>
    <div v-else-if="rows.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No audit events match</h2>
    </div>
    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Audit events, page {{ page + 1 }}</caption>
          <thead>
            <tr>
              <th scope="col">When</th>
              <th scope="col">Actor</th>
              <th scope="col">Action</th>
              <th scope="col">Resource</th>
              <th scope="col">IP</th>
              <th scope="col"><span class="sr-only">Details</span></th>
            </tr>
          </thead>
          <tbody>
            <template v-for="r in rows" :key="r.eventId">
              <tr data-test="audit-row">
                <td class="text-sm">{{ fmtDate(r.at) }}</td>
                <td class="text-xs">
                  <span class="font-mono">{{ r.actorUserId ? r.actorUserId.slice(0, 8) : '-' }}</span>
                  <span v-for="role in r.actorRoles ?? []" :key="role" class="badge badge-outline badge-xs ml-1">{{ role }}</span>
                </td>
                <td class="text-sm font-medium">{{ r.action }}</td>
                <td class="text-xs">{{ r.resourceType }} <span class="font-mono">{{ r.resourceId ?? '' }}</span></td>
                <td class="font-mono text-xs">{{ r.ip ?? '-' }}</td>
                <td class="text-right">
                  <button
                    v-if="r.before !== undefined || r.after !== undefined"
                    class="btn btn-ghost btn-xs"
                    type="button"
                    @click="expanded[r.eventId] = !expanded[r.eventId]"
                  >
                    {{ expanded[r.eventId] ? 'Hide' : 'Diff' }}
                  </button>
                </td>
              </tr>
              <tr v-if="expanded[r.eventId]">
                <td colspan="6">
                  <div class="grid gap-2 md:grid-cols-2">
                    <pre class="overflow-x-auto rounded bg-base-200 p-2 text-xs">before: {{ json(r.before ?? null) }}</pre>
                    <pre class="overflow-x-auto rounded bg-base-200 p-2 text-xs">after: {{ json(r.after ?? null) }}</pre>
                  </div>
                </td>
              </tr>
            </template>
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
