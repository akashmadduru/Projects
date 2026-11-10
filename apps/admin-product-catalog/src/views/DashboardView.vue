<script setup lang="ts">
import { computed, onMounted } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { useProducts } from '@/composables/useProducts';

/**
 * A summary, not a second data table: total/active/inactive counts built
 * from one page-0 call to `useProducts`. `Product` carries no
 * `createdAt`/`updatedAt`, so there is no "recently added" panel here —
 * fabricating one from data the API doesn't provide would be worse than
 * omitting it.
 */
const catalog = useProducts();

const status = computed(() => catalog.state.value.status);
const errorMessage = computed<string>(() =>
  catalog.state.value.status === 'error' ? catalog.state.value.message : '',
);
const errorCorrelationId = computed<string | null>(() =>
  catalog.state.value.status === 'error' ? catalog.state.value.correlationId : null,
);

const totalCount = computed<number>(() => catalog.totalElements.value);
const activeCount = computed<number>(() =>
  catalog.state.value.status === 'success'
    ? catalog.state.value.products.filter((product) => product.active).length
    : 0,
);
const inactiveCount = computed<number>(() =>
  catalog.state.value.status === 'success'
    ? catalog.state.value.products.filter((product) => !product.active).length
    : 0,
);

onMounted(() => {
  void catalog.load(0);
});
</script>

<template>
  <section>
    <h2 class="mb-4 text-lg font-medium">Catalog summary</h2>

    <div
      v-if="status === 'loading'"
      class="data-card p-8 text-center"
      data-test="dashboard-loading"
      role="status"
      aria-live="polite"
    >
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading summary…</p>
    </div>

    <div v-else-if="status === 'error'" data-test="dashboard-error">
      <ProblemAlert :message="errorMessage" :correlation-id="errorCorrelationId" />
      <button class="btn btn-outline mt-4" type="button" @click="catalog.reload()">
        Try again
      </button>
    </div>

    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-3" data-test="dashboard-stats">
      <div class="stat-card">
        <p class="text-xs uppercase tracking-wide opacity-60">Total products</p>
        <p class="mt-2 text-3xl font-semibold tabular-nums" data-test="stat-total">
          {{ totalCount }}
        </p>
      </div>
      <div class="stat-card">
        <p class="text-xs uppercase tracking-wide opacity-60">Active</p>
        <p class="mt-2 text-3xl font-semibold tabular-nums" data-test="stat-active">
          {{ activeCount }}
        </p>
      </div>
      <div class="stat-card">
        <p class="text-xs uppercase tracking-wide opacity-60">Inactive</p>
        <p class="mt-2 text-3xl font-semibold tabular-nums" data-test="stat-inactive">
          {{ inactiveCount }}
        </p>
      </div>
    </div>
  </section>
</template>
