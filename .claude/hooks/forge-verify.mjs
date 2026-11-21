// Decides "verified". Maps changed files to the checks that cover them and runs them.
// usage: node .claude/hooks/forge-verify.mjs [--changed] [--base <sha>] [--dir <checkout>] [--dry-run]
// Exit 0 only if every selected check passed. Prints one line per check plus a summary.
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { git, norm, projectDir, existsSync } from './lib.mjs';

const args = process.argv.slice(2);
const opt = (n) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : undefined; };
const dir = norm(path.resolve(opt('--dir') || process.cwd()));
const dry = args.includes('--dry-run');
const base = opt('--base') || git(['merge-base', 'HEAD', 'origin/dev'], dir) || 'HEAD~1';

let files;
if (args.includes('--changed')) {
  const committed = git(['diff', '--name-only', `${base}...HEAD`], dir).split('\n');
  const working = git(['status', '--porcelain'], dir).split('\n').map((l) => l.slice(3).split(' -> ').pop());
  files = [...new Set([...committed, ...working].map((f) => f.trim()).filter(Boolean))];
} else {
  files = ['services/', 'apps/product-catalog/', 'apps/admin-product-catalog/'];
}

const gradle = new Set();
const apps = new Set();
for (const f of files) {
  let m;
  if ((m = f.match(/^services\/([^/]+)\/\1-(app|api)\//))) gradle.add(`:services:${m[1]}:${m[1]}-app:test`);
  else if ((m = f.match(/^services\/gateway\//))) gradle.add(':services:gateway:test');
  else if ((m = f.match(/^libs\/([^/]+)\//))) gradle.add(`:libs:${m[1]}:test`);
  else if (f.startsWith('apps/product-catalog/')) apps.add('apps/product-catalog');
  else if (f.startsWith('apps/admin-product-catalog/')) apps.add('apps/admin-product-catalog');
  else if (f === 'services/' ) { /* full run: every module */ gradle.add('test'); }
}

const checks = [];
// CI runs this check; run it whenever an input of the generated env reference changes.
if (files.some((f) => /(^|\/)application[^/]*\.ya?ml$|config(\.example)?\.env$|^docs\/reference\/(env-descriptions\.json|environment-variables\.md)$|^deploy\/(aws|local)\//.test(f) || f === 'services/'))
  checks.push({ name: 'env reference --check', cmd: 'node', argv: [path.join(dir, 'deploy', 'local', 'scripts', 'env-reference.mjs'), '--check'], cwd: dir });
// The gradle script lives in the main checkout; it is pointed at the checkout under test.
const script = path.join(projectDir(), '.openforge', 'scripts', 'gradle.sh');
if (gradle.size) checks.push({ name: `gradle ${[...gradle].join(' ')}`, cmd: 'bash', argv: [script, '-C', dir, ...gradle], cwd: dir });
for (const a of apps) {
  const appDir = path.join(dir, a);
  const tsc = a.includes('admin') ? ['vue-tsc', '--noEmit'] : ['tsc', '--noEmit'];
  checks.push({ name: `${a}: vitest`, cmd: 'npx', argv: ['vitest', 'run'], cwd: appDir });
  checks.push({ name: `${a}: ${tsc[0]}`, cmd: 'npx', argv: tsc, cwd: appDir });
}

console.log(`forge-verify: ${files.length} changed file(s) in ${dir} (base ${base.slice(0, 12)})`);
if (!checks.length) { console.log('forge-verify: no code changes that need checks. PASS'); process.exit(0); }
let failed = 0;
for (const c of checks) {
  if (dry) { console.log(`  would run: ${c.name}`); continue; }
  if (!existsSync(c.cwd)) { console.log(`  FAIL ${c.name}: ${c.cwd} missing`); failed++; continue; }
  const r = spawnSync(c.cmd, c.argv, { cwd: c.cwd, encoding: 'utf8', shell: process.platform === 'win32', maxBuffer: 64 * 1024 * 1024 });
  const out = `${r.stdout || ''}${r.stderr || ''}`.trim().split('\n');
  const tail = out.filter((l) => /gradle_rc=|BUILD|Tests? |Test Files|error|FAIL/i.test(l)).slice(-8);
  const ok = r.status === 0;
  if (!ok) failed++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'} ${c.name} (exit ${r.status})`);
  for (const l of tail) console.log(`      ${l.replace(/\x1b\[[0-9;]*m/g, '')}`);
}
console.log(`forge-verify: ${failed ? `FAIL (${failed} of ${checks.length} checks failed)` : `PASS (${checks.length} checks)`}`);
process.exit(failed ? 1 : 0);
