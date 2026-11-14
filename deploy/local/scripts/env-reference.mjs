#!/usr/bin/env node
// Generates docs/reference/environment-variables.md from the code, so the catalogue cannot drift.
//
//   node deploy/local/scripts/env-reference.mjs           regenerate the file
//   node deploy/local/scripts/env-reference.mjs --check   exit 1 if the file is stale (used by CI)
//
// WHAT IS SCANNED (the code is the source of truth, never memory):
//   services/*/src/main/resources/application*.yml   ${VAR:default} / ${VAR} placeholders
//   services/**/*.java and libs/**/*.java            ${VAR:default} inside @Value / @DefaultValue
//   deploy/local/docker-compose.yml                  what local Compose sets
//   deploy/local/.env.example                        what the local template documents
//   deploy/aws/helm/ecommerce/{values.yaml,templates} what the AWS chart sets
//   deploy/aws/config.example.env                    the deploy script's own settings
//   apps/**                                          VITE_* build variables
//
// WHAT IS CURATED BY HAND: docs/reference/env-descriptions.json (one sentence per variable, the
// secret/required overrides, and the group it is listed under). A variable with no description is
// listed under "Undescribed" and makes --check fail, so a new variable cannot ship undocumented.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const OUT = path.join(root, 'docs/reference/environment-variables.md');
const DESC = path.join(root, 'docs/reference/env-descriptions.json');
const check = process.argv.includes('--check');

const read = (p) => (fs.existsSync(p) ? fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n') : '');
const skip = new Set(['node_modules', 'build', '.git', '.gradle', 'oracleJdk-27', '.terraform', 'dist', 'test']);

function walk(dir, accept, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skip.has(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, accept, out);
    else if (accept(p)) out.push(p);
  }
  return out;
}

const rel = (p) => path.relative(root, p).split(path.sep).join('/');
const serviceOf = (p) => rel(p).split('/')[1] ?? '';

// ${NAME:default} or ${NAME} where NAME is an upper-case env-style identifier.
const PLACEHOLDER = /\$\{([A-Z][A-Z0-9_]*)(?::([^}]*))?\}/g;

// -- 1. what the code reads -----------------------------------------------------------------------
const vars = new Map(); // name -> { services:Set, defaults:Map(service->default), required:Set(service) }
function note(name, service, def, file) {
  if (!vars.has(name)) vars.set(name, { services: new Set(), defaults: new Map(), required: new Set(), files: new Set() });
  const v = vars.get(name);
  v.services.add(service);
  v.files.add(file);
  if (def === undefined) v.required.add(service);
  else v.defaults.set(service, def);
}

const codeFiles = [
  ...walk(path.join(root, 'services'), (p) => /src\/main\/resources\/application[^/]*\.yml$/.test(p.split(path.sep).join('/'))),
  ...walk(path.join(root, 'services'), (p) => p.endsWith('.java') && p.split(path.sep).join('/').includes('/src/main/')),
  ...walk(path.join(root, 'libs'), (p) => p.endsWith('.java') && p.split(path.sep).join('/').includes('/src/main/')),
];
for (const f of codeFiles) {
  const text = read(f);
  const service = f.includes(`${path.sep}libs${path.sep}`) ? 'libs' : serviceOf(f);
  for (const line of text.split('\n')) {
    if (/^\s*(#|\/\/|\*|\/\*)/.test(line)) continue; // comments
    for (const m of line.matchAll(PLACEHOLDER)) note(m[1], service, m[2], rel(f));
  }
}

// -- 2. where each environment sets it -----------------------------------------------------------
const compose = read(path.join(root, 'deploy/local/docker-compose.yml'));
const envExample = read(path.join(root, 'deploy/local/.env.example'));
const helmFiles = [
  path.join(root, 'deploy/aws/helm/ecommerce/values.yaml'),
  ...walk(path.join(root, 'deploy/aws/helm/ecommerce/templates'), (p) => p.endsWith('.yaml')),
].map(read).join('\n');
const awsConfig = read(path.join(root, 'deploy/aws/config.example.env'));
const word = (name) => new RegExp(`(^|[^A-Z0-9_])${name}([^A-Z0-9_]|$)`);
const inCompose = (n) => word(n).test(compose);
const inEnvExample = (n) => word(n).test(envExample);
const inHelm = (n) => word(n).test(helmFiles);

// Variables the AWS chart or Compose sets that no application.yml placeholder mentions (Spring's
// relaxed binding makes e.g. MANAGEMENT_SERVER_PORT work without an explicit placeholder).
const extra = new Set();
for (const text of [compose, helmFiles]) {
  for (const m of text.matchAll(/(?:^|[\s{,-])name:\s*([A-Z][A-Z0-9_]{3,})\b/gm)) extra.add(m[1]);
  for (const m of text.matchAll(/^\s{4,8}([A-Z][A-Z0-9_]{3,}):/gm)) extra.add(m[1]);
}
// Settings of the Kafka / Postgres / Jaeger containers in Compose are image configuration, not
// platform configuration, so they are not catalogued.
const CONTAINER = /^(KAFKA_(?!BOOTSTRAP)|POSTGRES_|COLLECTOR_|GOMEMLIMIT|MEMORY_MAX_|ZOO_|REDIS_(?!HOST|PORT))/;
for (const n of extra) if (!vars.has(n) && !CONTAINER.test(n) && (inCompose(n) || inHelm(n))) note(n, 'platform', '', 'deploy');

// -- 3. frontend and deploy-script variables -----------------------------------------------------
const vite = new Map();
for (const f of walk(path.join(root, 'apps'), (p) => /\.(ts|tsx|vue|js|mjs|example)$/.test(p) || p.endsWith('.env.example'))) {
  for (const m of read(f).matchAll(/\b(VITE_[A-Z0-9_]+)\b/g)) {
    if (!vite.has(m[1])) vite.set(m[1], new Set());
    vite.get(m[1]).add(rel(f).split('/')[1]);
  }
}
const deployVars = [...awsConfig.matchAll(/^#?\s*([A-Z][A-Z0-9_]+)=(.*)$/gm)].map((m) => [m[1], m[2].trim()]);

// -- 4. curated descriptions ---------------------------------------------------------------------
const desc = JSON.parse(read(DESC) || '{"vars":{},"vite":{},"deploy":{}}');
const descOf = (n) => desc.vars?.[n];

const SECRET = /(PASSWORD|SECRET|_KEY\b|TOKEN|PRIVATE)/;
const isSecret = (n) => (descOf(n)?.secret !== undefined ? descOf(n).secret : SECRET.test(n) && !/PUBLIC|PUBLISHABLE|KEYSTORE_ALIAS|_KEY_ID/.test(n));

const isSecretSetting = (n) => SECRET.test(n) && !/PUBLISH|CLIENT_ID|KEYSTORE_ALIAS/.test(n);
const names = [...vars.keys()].sort();
const undescribed = names.filter((n) => !descOf(n));
const stale = Object.keys(desc.vars ?? {}).filter((n) => !vars.has(n));

// -- 5. render -----------------------------------------------------------------------------------
const GROUP_ORDER = ['Platform', 'Identity and tokens', 'Database', 'Messaging and cache', 'Payments', 'Email, SMS and push',
  'Edge, CORS and rate limits', 'Service discovery', 'Business rules', 'Observability', 'Other'];
const groupOf = (n) => descOf(n)?.group ?? 'Other';

const short = (s) => (s.length > 38 ? s.slice(0, 35) + '...' : s);
const code = (s) => (s === '' ? '`""`' : '`' + s.replace(/\|/g, '\\|') + '`');

function defaultCell(n) {
  const v = vars.get(n);
  if (descOf(n)?.defaultText) return '_' + descOf(n).defaultText + '_';
  if ([...v.files].every((x) => x === 'deploy')) return '_Spring default_';
  if (v.defaults.size === 0) return '**none**';
  const uniq = [...new Set(v.defaults.values())];
  if (isSecret(n)) return uniq.every((d) => d === '') ? '`""`' : '_dev value only; none in prod_';
  if (uniq.length === 1) return code(short(uniq[0]));
  return uniq.slice(0, 2).map((d) => code(short(d))).join(', ') + ' (varies)';
}
const requiredCell = (n) => {
  const v = vars.get(n);
  if (v.required.size) return 'yes, in prod';
  return descOf(n)?.requiredInProd ? 'yes' : '';
};
const whereCell = (n) => [inEnvExample(n) ? '.env' : inCompose(n) ? 'compose' : '', inHelm(n) ? 'helm' : ''].filter(Boolean).join(' + ') || '-';
const svcCell = (n) => {
  if (descOf(n)?.usedBy) return descOf(n).usedBy;
  const s = [...vars.get(n).services].filter((x) => x !== 'libs').map((x) => x.replace(/-service$/, ''));
  if (s.length >= 9) return 'all services';
  return s.join(', ') || 'platform';
};

let md = `# Environment variables

> **Generated file. Do not edit by hand.** Regenerate with \`node deploy/local/scripts/env-reference.mjs\`.
> The variable list, defaults, "required" flags and the **Set in** column are read from the code and
> deployment files; the one-line descriptions are curated in
> [env-descriptions.json](env-descriptions.json). CI fails if this file is stale or a variable has no
> description (\`--check\`).

How to read the tables:

- **Default**: the value the service uses when the variable is unset. **none** means there is no default.
- **Required**: \`yes, in prod\` means at least one service refuses to start in the \`prod\` profile without it.
- **Set in**: where this repository sets it for you: \`.env\` = the local template
  [deploy/local/.env.example](../../deploy/local/.env.example), \`compose\` = set directly in
  [docker-compose.yml](../../deploy/local/docker-compose.yml), \`helm\` = the AWS chart
  ([values.yaml](../../deploy/aws/helm/ecommerce/values.yaml) and templates). \`-\` means nothing sets it: you
  only need it to override a default.
- **Secret**: a lock marks a value that must never be committed or logged. On AWS these come from
  Secrets Manager, not from \`config.env\`.

**Precedence (highest wins):** real environment variable, then the ConfigMap mounted at \`/config\` on AWS,
then the packaged \`application.yml\`. A variable set to an empty string is treated as set but blank.

On AWS you normally set nothing here yourself: \`deploy.sh\` renders these from
[deploy/aws/config.example.env](../../deploy/aws/config.example.env). Use this page when overriding a
service-level setting (add it under that service's \`env:\` in \`values.yaml\`). Related:
[cloud setup](../guides/cloud-setup.md), [third-party setup](../guides/integrations.md),
[how a value travels](../architecture/diagrams/17-configuration-flow.md).

`;

for (const g of GROUP_ORDER) {
  const inGroup = names.filter((n) => descOf(n) && groupOf(n) === g);
  if (!inGroup.length) continue;
  md += `## ${g}\n\n| Variable | Used by | Default | Required | Set in | What it does |\n|---|---|---|---|---|---|\n`;
  for (const n of inGroup) {
    md += `| \`${n}\`${isSecret(n) ? ' 🔒' : ''} | ${svcCell(n)} | ${defaultCell(n)} | ${requiredCell(n)} | ${whereCell(n)} | ${descOf(n).text.replace(/\|/g, '\\|')} |\n`;
  }
  md += '\n';
}

if (undescribed.length) {
  md += `## Undescribed\n\nThese variables exist in the code but have no description yet. Add them to \`env-descriptions.json\`.\n\n`;
  md += undescribed.map((n) => `- \`${n}\` (${svcCell(n)})`).join('\n') + '\n\n';
}

md += `## Frontend build variables (Vite)\n\nSet when the app is **built**, not at runtime: they are compiled into the JavaScript bundle. Locally they go in each app's own \`.env\`; on AWS \`deploy.sh\` sets them from your domain.\n\n| Variable | App | What it does |\n|---|---|---|\n`;
for (const [n, apps] of [...vite.entries()].sort()) {
  if (n.startsWith('VITE_PLUGIN')) continue; // tooling-internal, not configuration
  md += `| \`${n}\` | ${[...apps].join(', ')} | ${desc.vite?.[n] ?? '_undescribed_'} |\n`;
}

md += `\n## Deploy script settings (\`deploy/aws/config.env\`)\n\nRead by \`deploy.sh\` / \`destroy.sh\`. Commented-out lines in the template are optional.\n\n| Setting | Template value | What it does |\n|---|---|---|\n`;
for (const [n, v] of deployVars) {
  md += `| \`${n}\`${isSecretSetting(n) ? ' 🔒' : ''} | ${isSecretSetting(n) ? '' : code(short(v))} | ${desc.deploy?.[n] ?? '_undescribed_'} |\n`;
}
md += '\n';

const generated = md;
const missingVite = [...vite.keys()].filter((n) => !n.startsWith('VITE_PLUGIN') && !desc.vite?.[n]);
const missingDeploy = deployVars.map(([n]) => n).filter((n) => !desc.deploy?.[n]);

if (check) {
  const current = read(OUT);
  const problems = [];
  if (current !== generated) problems.push('docs/reference/environment-variables.md is stale (run: node deploy/local/scripts/env-reference.mjs)');
  if (undescribed.length) problems.push(`undescribed variables: ${undescribed.join(', ')}`);
  if (missingVite.length) problems.push(`undescribed VITE_ variables: ${missingVite.join(', ')}`);
  if (missingDeploy.length) problems.push(`undescribed config.env settings: ${missingDeploy.join(', ')}`);
  if (stale.length) problems.push(`descriptions for variables no longer in the code: ${stale.join(', ')}`);
  if (problems.length) {
    console.error('env reference check FAILED:\n - ' + problems.join('\n - '));
    process.exit(1);
  }
  console.log(`env reference OK (${names.length} variables, ${vite.size} frontend, ${deployVars.length} deploy settings)`);
} else {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, generated);
  console.log(`wrote ${rel(OUT)}: ${names.length} variables (${undescribed.length} undescribed, ${stale.length} stale descriptions)`);
  if (undescribed.length) console.log('undescribed:', undescribed.join(' '));
  if (stale.length) console.log('stale:', stale.join(' '));
  if (missingVite.length) console.log('undescribed vite:', missingVite.join(' '));
  if (missingDeploy.length) console.log('undescribed deploy:', missingDeploy.join(' '));
}
