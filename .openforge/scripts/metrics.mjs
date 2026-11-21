#!/usr/bin/env node
// OpenForge metrics. Facts only: every number comes from metrics.json (CEO-recorded from harness
// completion notices), the verdict files, or git. Nothing is taken from an agent's self-report.
//
//   node .openforge/scripts/metrics.mjs add <id> role=<agent> tokens=<n> tools=<n> ms=<n> round=<n> outcome=<pass|fail|approve|request_changes|error>
//   node .openforge/scripts/metrics.mjs event <id> <intervention|recovery|escaped_defect|gate_fail> "<note>"
//   node .openforge/scripts/metrics.mjs report [--json]
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '..', '..');
const workDir = path.join(root, '.openforge', 'work');
const [cmd, id, ...rest] = process.argv.slice(2);
const file = (w) => path.join(workDir, w, 'metrics.json');
const load = (w) => { try { return JSON.parse(fs.readFileSync(file(w), 'utf8')); } catch { return { runs: [], events: [] }; } };
const save = (w, m) => fs.writeFileSync(file(w), JSON.stringify(m, null, 1) + '\n');
const readJson = (p) => { try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch { return null; } };

if (cmd === 'add') {
  const m = load(id); const r = { at: new Date().toISOString() };
  for (const kv of rest) { const [k, v] = kv.split('='); r[k] = /^\d+$/.test(v) ? +v : v; }
  m.runs.push(r); save(id, m); console.log(`recorded ${r.role} for ${id}`);
} else if (cmd === 'event') {
  const m = load(id); m.events.push({ at: new Date().toISOString(), type: rest[0], note: rest.slice(1).join(' ') });
  save(id, m); console.log(`recorded ${rest[0]} for ${id}`);
} else if (cmd === 'report') {
  const rows = [];
  for (const w of fs.readdirSync(workDir).filter((d) => /^F-\d{8}-\d{2}$/.test(d)).sort()) {
    const m = load(w); const st = readJson(path.join(workDir, w, 'state.json')) || {};
    if (!m.runs.length) continue;
    const verdicts = [];
    const hist = path.join(workDir, w, 'history');
    if (fs.existsSync(hist)) for (const f of fs.readdirSync(hist)) { const v = readJson(path.join(hist, f)); if (v) verdicts.push(v); }
    const sev = { blocker: 0, major: 0, minor: 0, nit: 0 };
    const seen = new Set();
    for (const v of verdicts) for (const f of v.findings || []) {
      const k = `${v.reviewer}|${f.file}|${f.issue?.slice(0, 80)}`; if (seen.has(k)) continue; seen.add(k);
      if (f.severity in sev) sev[f.severity]++;
    }
    const sum = (k, pred = () => true) => m.runs.filter(pred).reduce((n, r) => n + (r[k] | 0), 0);
    const byRole = (re) => (r) => re.test(r.role || '');
    const rounds = Math.max(0, ...m.runs.filter(byRole(/reviewer|security/)).map((r) => r.round | 0));
    const firstPass = m.runs.filter((r) => (r.round | 0) <= 1 && /reviewer|security/.test(r.role));
    const final = readJson(path.join(workDir, w, 'review.json')) || {};
    const acs = Object.values(final.ac_check || {});
    let prodLines = null;
    try {
      if (st.base_sha && st.worktree) {
        const wt = path.isAbsolute(st.worktree) ? st.worktree : path.join(root, st.worktree);
        const out = execFileSync('git', ['diff', '--numstat', `${st.base_sha}..HEAD`], { cwd: wt, encoding: 'utf8' });
        prodLines = out.split('\n').filter(Boolean).map((l) => l.split('\t'))
          .filter(([, , f]) => f && !/(^|\/)src\/test\/|__tests__\/|\.(test|spec)\.[cm]?[jt]sx?$|^docs\/|\.md$|^\.openforge\//.test(f))
          .reduce((n, [a, d]) => n + (a | 0) + (d | 0), 0);
      }
    } catch { /* worktree removed */ }
    const ev = (t) => m.events.filter((e) => e.type === t).length;
    rows.push({
      id: w, step: st.step, tokens: sum('tokens'), tool_calls: sum('tools'), agent_minutes: +(sum('ms') / 60000).toFixed(1),
      tokens_planning: sum('tokens', byRole(/cpo|cto|architect|em$/)), tokens_impl: sum('tokens', byRole(/engineer/)),
      tokens_qa: sum('tokens', byRole(/qa/)), tokens_review: sum('tokens', byRole(/reviewer|security/)),
      review_rounds: rounds, first_review_approved: firstPass.length > 0 && firstPass.every((r) => r.outcome === 'approve'),
      findings: sev, acs_met: acs.filter((x) => x === 'met').length, acs_total: acs.length, prod_lines: prodLines,
      interventions: ev('intervention'), recoveries: ev('recovery'), escaped_defects: ev('escaped_defect'), gate_fails: ev('gate_fail'),
      done: /^(done|ready|ready-owner-approval|pr-ready)/.test(st.step || ''),
    });
  }
  if (process.argv.includes('--json')) { console.log(JSON.stringify(rows, null, 1)); process.exit(0); }
  const t = (k) => rows.reduce((n, r) => n + (r[k] || 0), 0);
  console.log('| Objective | Step | Tokens | Rounds | 1st review approved | Major+ found | ACs met | Prod lines | Interventions | Recoveries |');
  console.log('|---|---|--:|--:|:-:|--:|--:|--:|--:|--:|');
  for (const r of rows) console.log(`| ${r.id} | ${r.step} | ${r.tokens.toLocaleString('en')} | ${r.review_rounds} | ${r.first_review_approved ? 'yes' : 'no'} | ${r.findings.blocker + r.findings.major} | ${r.acs_met}/${r.acs_total} | ${r.prod_lines ?? '-'} | ${r.interventions} | ${r.recoveries} |`);
  const done = rows.filter((r) => r.done);
  console.log(`\nObjectives: ${rows.length}, done ${done.length} (${rows.length ? Math.round(100 * done.length / rows.length) : 0}%). Tokens ${t('tokens').toLocaleString('en')}; ` +
    `per done objective ${done.length ? Math.round(done.reduce((n, r) => n + r.tokens, 0) / done.length).toLocaleString('en') : '-'}. ` +
    `First-review approval ${rows.filter((r) => r.first_review_approved).length}/${rows.length}. Interventions ${t('interventions')}, recoveries ${t('recoveries')}, escaped defects ${t('escaped_defects')}.`);
} else {
  console.log('usage: metrics.mjs add|event|report (see header)'); process.exit(2);
}
