/**
 * Dominant colours of a local image (logo, product shot, mood board), for seeding a palette.
 * Runs on a 48x48 downscale, drops near-white / near-black / grey pixels, buckets the rest and
 * returns the biggest buckets as hex, most common first. Local files only, so no CORS to worry about.
 */
export async function extractColours(file: File, max = 6): Promise<string[]> {
  const url = URL.createObjectURL(file);
  try {
    const img = await loadImage(url);
    const size = 48;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d');
    if (!ctx) return [];
    ctx.drawImage(img, 0, 0, size, size);
    const { data } = ctx.getImageData(0, 0, size, size);

    const buckets = new Map<string, { r: number; g: number; b: number; n: number }>();
    for (let i = 0; i < data.length; i += 4) {
      const a = data[i + 3] ?? 0;
      if (a < 128) continue;
      const r = data[i] ?? 0;
      const g = data[i + 1] ?? 0;
      const b = data[i + 2] ?? 0;
      const hi = Math.max(r, g, b);
      const lo = Math.min(r, g, b);
      if (hi > 240 && lo > 225) continue; // paper / white
      if (hi < 28) continue; // ink / black
      if (hi - lo < 24) continue; // grey
      const key = `${r >> 5}-${g >> 5}-${b >> 5}`;
      const e = buckets.get(key) ?? { r: 0, g: 0, b: 0, n: 0 };
      e.r += r;
      e.g += g;
      e.b += b;
      e.n += 1;
      buckets.set(key, e);
    }

    const hex = (v: number) => Math.round(v).toString(16).padStart(2, '0');
    const picked: { r: number; g: number; b: number }[] = [];
    const out: string[] = [];
    for (const e of [...buckets.values()].sort((x, y) => y.n - x.n)) {
      const c = { r: e.r / e.n, g: e.g / e.n, b: e.b / e.n };
      // Skip near-duplicates of a colour already picked so the result spans the image's hues.
      if (picked.some((p) => Math.hypot(p.r - c.r, p.g - c.g, p.b - c.b) < 60)) continue;
      picked.push(c);
      out.push(`#${hex(c.r)}${hex(c.g)}${hex(c.b)}`);
      if (out.length >= max) break;
    }
    return out;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('Could not read that image.'));
    img.src = src;
  });
}
