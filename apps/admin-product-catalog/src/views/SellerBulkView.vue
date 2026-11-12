<script setup lang="ts">
import { ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo } from '@/lib/adminApi';
import { BULK_CSV_HEADER, bulkUpload } from '@/lib/sellerApi';
import type { BulkUploadResult } from '@/types/seller';

/** Bulk CSV upload with a per-row error report. Limits (1000 rows / 2 MB) are enforced by the server; the size cap is pre-checked here. */
const MAX_BYTES = 2 * 1024 * 1024;

const file = ref<File | null>(null);
const uploading = ref(false);
const result = ref<BulkUploadResult | null>(null);
const error = ref<{ message: string; correlationId: string | null } | null>(null);
const localError = ref<string | null>(null);

function onPick(event: Event): void {
  const input = event.target as HTMLInputElement;
  const picked = input.files?.[0] ?? null;
  localError.value = null;
  result.value = null;
  if (picked && picked.size > MAX_BYTES) {
    localError.value = 'File is larger than 2 MB.';
    file.value = null;
    return;
  }
  file.value = picked;
}

async function upload(): Promise<void> {
  if (!file.value || uploading.value) return;
  uploading.value = true;
  error.value = null;
  result.value = null;
  try {
    result.value = await bulkUpload(file.value);
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    uploading.value = false;
  }
}

/** Template is generated client-side (the server endpoint needs a bearer header a plain link cannot send). */
function downloadTemplate(): void {
  const sample = `${BULK_CSV_HEADER}\nSKU-001,Sample product,Description,,Acme,499.00,449.00,INR,25,WH-BLR,https://example.com/a.jpg,"{""color"":""red""}"\n`;
  const url = URL.createObjectURL(new Blob([sample], { type: 'text/csv' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = 'bulk-products-template.csv';
  a.click();
  URL.revokeObjectURL(url);
}
</script>

<template>
  <section class="mx-auto max-w-3xl">
    <h1 class="mb-4 text-2xl font-semibold">Bulk upload</h1>
    <div class="data-card p-4">
      <p class="text-sm">
        Upload a CSV (max 1000 rows, 2 MB) with columns
        <code class="text-xs">{{ BULK_CSV_HEADER }}</code>. Rows are matched to your existing listings by SKU.
      </p>
      <div class="mt-3 flex flex-wrap items-center gap-3">
        <input type="file" accept=".csv,text/csv" class="file-input file-input-bordered file-input-sm" data-test="bulk-file" @change="onPick" />
        <button class="btn btn-primary btn-sm" type="button" :disabled="!file || uploading" data-test="bulk-upload" @click="upload">
          <span v-if="uploading" class="loading loading-spinner loading-xs" aria-hidden="true" />
          Upload
        </button>
        <button class="btn btn-ghost btn-sm" type="button" @click="downloadTemplate">Download template</button>
      </div>
      <p v-if="localError" class="mt-2 text-sm text-error">{{ localError }}</p>
    </div>

    <ProblemAlert v-if="error" class="mt-4" :message="error.message" :correlation-id="error.correlationId" />

    <div v-if="result" class="data-card mt-4 p-4" data-test="bulk-result">
      <p class="text-sm">
        <span class="badge badge-success mr-2">{{ result.created }} created</span>
        <span class="badge badge-info mr-2">{{ result.updated }} updated</span>
        <span class="badge" :class="result.errors.length ? 'badge-error' : 'badge-ghost'">{{ result.errors.length }} errors</span>
      </p>
      <div v-if="result.errors.length > 0" class="mt-3 overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Row errors</caption>
          <thead>
            <tr><th scope="col">Row</th><th scope="col">Error</th></tr>
          </thead>
          <tbody>
            <tr v-for="(e, i) in result.errors" :key="i" data-test="bulk-error-row">
              <td class="tabular-nums">{{ e.row }}</td>
              <td class="text-sm">{{ e.message }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>
