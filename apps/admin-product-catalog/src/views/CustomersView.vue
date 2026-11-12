<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, fmtDate, listUsers, statusBadge } from '@/lib/adminApi';
import { ASSIGNABLE_ROLES, USER_STATUSES, type AdminUser } from '@/types/commerce';

/** Customer / staff directory backed by auth-service. ADMIN only. */
const PAGE_SIZE = 20;

const rows = ref<readonly AdminUser[]>([]);
const loading = ref(false);
const error = ref<{ message: string; correlationId: string | null } | null>(null);
const page = ref(0);
const totalPages = ref(1);
const totalElements = ref(0);
const q = ref('');
const status = ref('');
const role = ref('');

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const res = await listUsers({
      q: q.value.trim() || undefined,
      status: status.value || undefined,
      role: role.value || undefined,
      page: page.value,
      size: PAGE_SIZE,
    });
    rows.value = res.content;
    totalPages.value = Math.max(res.page.totalPages, 1);
    totalElements.value = res.page.totalElements;
  } catch (e: unknown) {
    error.value = errorInfo(e);
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

function applyFilter(): void {
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
    <div class="mb-4 flex flex-wrap items-end justify-between gap-3">
      <h1 class="text-2xl font-semibold">Customers</h1>
      <form class="flex flex-wrap items-center gap-2" @submit.prevent="applyFilter">
        <input
          v-model="q"
          class="input input-bordered input-sm w-56"
          type="search"
          placeholder="Search email or name"
          aria-label="Search customers"
          data-test="customers-search"
        />
        <select v-model="status" class="select select-bordered select-sm" aria-label="Filter by status" @change="applyFilter">
          <option value="">All statuses</option>
          <option v-for="s in USER_STATUSES" :key="s" :value="s">{{ s }}</option>
        </select>
        <select v-model="role" class="select select-bordered select-sm" aria-label="Filter by role" @change="applyFilter">
          <option value="">All roles</option>
          <option v-for="r in ASSIGNABLE_ROLES" :key="r" :value="r">{{ r }}</option>
        </select>
        <button class="btn btn-sm" type="submit">Search</button>
      </form>
    </div>

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading customers…</p>
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <div v-else-if="rows.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No matching accounts</h2>
    </div>

    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Customers, page {{ page + 1 }}</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Email</th>
              <th scope="col">Roles</th>
              <th scope="col">Status</th>
              <th scope="col">Joined</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in rows" :key="u.id" data-test="customers-row" class="hover">
              <td>
                <RouterLink :to="`/customers/${u.id}`" class="link link-hover font-medium">
                  {{ u.displayName || '(no name)' }}
                </RouterLink>
              </td>
              <td class="text-sm">
                {{ u.email }}
                <span v-if="!u.emailVerified" class="badge badge-ghost badge-xs ml-1">unverified</span>
              </td>
              <td class="text-xs">
                <span v-for="r in u.roles" :key="r" class="badge badge-outline badge-sm mr-1">{{ r }}</span>
              </td>
              <td><span class="badge badge-sm" :class="statusBadge(u.status)">{{ u.status }}</span></td>
              <td class="text-sm">{{ fmtDate(u.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="flex items-center justify-between gap-4 border-t border-base-content/10 p-4">
        <span class="text-sm opacity-70">{{ totalElements }} accounts · page {{ page + 1 }} of {{ totalPages }}</span>
        <div class="join">
          <button class="btn btn-sm join-item" type="button" :disabled="page === 0" @click="go(-1)">Previous</button>
          <button class="btn btn-sm join-item" type="button" :disabled="page + 1 >= totalPages" @click="go(1)">
            Next
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
