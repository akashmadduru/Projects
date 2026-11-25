// PreToolUse(Edit|Write|NotebookEdit): what each role may write. The main session (no agent_type)
// is governed by permission rules ("ask" on governance files); subagents are restricted here.
import path from 'node:path';
import { readStdin, deny, norm, projectDir, GOVERNANCE } from './lib.mjs';
const input = readStdin();
const agent = input.agent_type;
if (!agent || !agent.startsWith('forge-')) process.exit(0);
const raw = input?.tool_input?.file_path || input?.tool_input?.notebook_path || '';
const abs = norm(path.resolve(raw));
const root = projectDir().replace(/\/$/, '');
const rel = abs.toLowerCase().startsWith(root.toLowerCase() + '/') ? abs.slice(root.length + 1) : abs;
if (GOVERNANCE.some((re) => re.test(rel))) deny(`${agent} may not edit governance files (${rel}). Ask the Owner.`);
const inWork = /^\.openforge\/work\//.test(rel);
const inKnowledge = /^\.openforge\/knowledge\//.test(rel);
const inWorktree = /^\.openforge\/worktrees\/[^/]+\//.test(rel);
const isTest = /(\/src\/test\/|\/__tests__\/|\.test\.[jt]sx?$|\.spec\.[jt]sx?$)/.test(rel);
const rules = {
  'forge-reviewer': () => false,
  'forge-security': () => false,
  'forge-cpo': () => inWork,
  'forge-cto': () => inWork,
  'forge-em': () => inWork,
  'forge-architect': () => inWork || inKnowledge,
  'forge-backend-engineer': () => inWorktree,
  'forge-frontend-engineer': () => inWorktree,
  'forge-qa': () => (inWorktree && isTest) || inWork,
};
const allowed = rules[agent];
if (allowed && !allowed()) deny(`${agent} may not write ${rel}. See the role table in .openforge/constitution.md.`);
process.exit(0);
