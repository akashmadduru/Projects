/** One key/value input pair (product facet attributes, variant options). */
export interface AttrRow {
  key: string;
  value: string;
}

/**
 * Key/value rows to a map. Fully blank rows are ignored; a half-filled row or a repeated key is
 * an error (returned as a message).
 */
export function rowsToAttrs(rows: readonly AttrRow[]): Record<string, string> | string {
  const out: Record<string, string> = {};
  for (const row of rows) {
    const k = row.key.trim();
    const v = row.value.trim();
    if (k.length === 0 && v.length === 0) continue;
    if (k.length === 0 || v.length === 0) return 'each attribute needs both a name and a value';
    if (Object.prototype.hasOwnProperty.call(out, k)) return `attribute "${k}" is listed twice`;
    out[k] = v;
  }
  return out;
}
