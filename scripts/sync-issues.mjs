#!/usr/bin/env node
// Syncs GitHub Issues from docs/roadmap/backlog.md, the single source of truth for the backlog.
//
// Usage (from the repository root, with an authenticated `gh` CLI):
//   node scripts/sync-issues.mjs          # dry run: prints what would change
//   node scripts/sync-issues.mjs --run    # applies the changes
//
// What it manages: title, body, type/priority labels and milestone of every OW-NNN entry.
// Entries marked **Cerrada** are closed ("not planned" when the text says "Fusionada",
// "completed" otherwise). Missing issues are created. Labels outside the managed set
// (for example `bug`) are left untouched. Issues are matched by the "OW-NNN ·" title prefix.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const BACKLOG = 'docs/roadmap/backlog.md';
const TYPE_LABELS = ['feature', 'architecture', 'security', 'testing', 'devops', 'documentation', 'performance', 'refactor'];
const PRIORITY_LABELS = ['P0', 'P1', 'P2', 'P3'];
const MANAGED_LABELS = new Set([...TYPE_LABELS, ...PRIORITY_LABELS]);

const run = process.argv.includes('--run');
const gh = (args, input) => execFileSync('gh', args, { encoding: 'utf8', input }).trim();
const pause = () => new Promise((resolve) => setTimeout(resolve, 1200)); // stay below GitHub's secondary rate limit

const repo = gh(['repo', 'view', '--json', 'nameWithOwner', '--jq', '.nameWithOwner']);
const blob = `https://github.com/${repo}/blob/main/`;
const text = fs.readFileSync(BACKLOG, 'utf8').replace(/\r\n/g, '\n');

const STATUS_TEXT = {
  Ready: '**Estado:** Ready: refinada y en el milestone actual. Se puede empezar.',
  Planned: '**Estado:** Planned: refinada, pero en un milestone futuro. Se revisa al empezar su milestone.',
  'Por detallar': '**Estado:** Por detallar: existe el objetivo, no el detalle. Se refina antes de empezar su milestone.',
};
const FOOTER = `\n\n---\n\nFuente: [\`${BACKLOG}\`](${blob}${BACKLOG}) · `
  + `Cumple además la [Definition of Done](${blob}docs/roadmap/definition-of-done.md). `
  + `Crear tags no forma parte de esta issue: ver el [proceso de release](${blob}docs/development/versioning.md#proceso-de-release).`;

// ---- Parse the backlog --------------------------------------------------------------------

function sectionHeadingAt(index) {
  const before = text.slice(0, index);
  const headings = [...before.matchAll(/^## (.+)$/gm)];
  return headings.length ? headings[headings.length - 1][1].replace(/\s*\(.*\)\s*$/, '').trim() : null;
}

const entries = [];

// Detailed entries: "### OW-NNN · Title", a metadata line, a blank line and the body.
const detailed = /^### (OW-(\d{3})) · (.+)\n(.+)\n\n([\s\S]*?)(?=\n### |\n---\n|\n## |(?![\s\S]))/gm;
for (const m of text.matchAll(detailed)) {
  const [, code, id, title, meta, body] = m;
  const priority = meta.match(/\b(P[0-3])\b/)?.[1];
  const milestone = meta.match(/Milestone: (.+?) · \*\*/)?.[1];
  const status = meta.match(/\*\*(Ready|Planned|Cerrada)\*\*/)?.[1];
  if (!priority || !milestone || !status) throw new Error(`Malformed metadata line for ${code}: ${meta}`);
  const labels = [...meta.matchAll(/`([^`]+)`/g)].map((x) => x[1]);
  entries.push({ id, code, title: `${code} · ${title.trim()}`, labels: [...labels, priority], milestone, status, rawBody: body.trim() });
}

// Summary rows (entries not detailed yet): "| OW-NNN | Title | `types` | P2 | Por detallar |".
const summary = /^\| (OW-(\d{3})) \| ([^|]+) \| ([^|]+) \| (P[0-3]) \| ([^|]+) \|$/gm;
for (const m of text.matchAll(summary)) {
  const [, code, id, title, labelCell, priority, status] = m;
  const labels = [...labelCell.matchAll(/`([^`]+)`/g)].map((x) => x[1]);
  entries.push({
    id, code, title: `${code} · ${title.trim()}`, labels: [...labels, priority],
    milestone: sectionHeadingAt(m.index), status: status.trim(),
    rawBody: `Alcance del milestone en el [roadmap](roadmap.md#milestones).`,
  });
}

entries.sort((a, b) => Number(a.id) - Number(b.id));
for (const e of entries) {
  const unknown = e.labels.filter((l) => !MANAGED_LABELS.has(l));
  if (unknown.length) throw new Error(`${e.code} uses unknown labels: ${unknown.join(', ')}`);
}

// ---- Current state on GitHub --------------------------------------------------------------

const issues = JSON.parse(gh(['issue', 'list', '--repo', repo, '--state', 'all', '--limit', '500',
  '--json', 'number,title,body,labels,milestone,state']));
const byCode = new Map();
for (const issue of issues) {
  const code = issue.title.match(/^(OW-\d{3}) ·/)?.[1];
  if (code) byCode.set(code, issue);
}

// ---- Create missing issues first, so every OW-NNN reference can be linked -----------------

for (const e of entries.filter((entry) => !byCode.has(entry.code) && entry.status !== 'Cerrada')) {
  if (!run) {
    console.log(`[create] ${e.title}`);
    byCode.set(e.code, { number: null, title: '', body: '', labels: [], milestone: null, state: 'OPEN' });
    continue;
  }
  const url = gh(['issue', 'create', '--repo', repo, '--title', e.title, '--body', 'Pendiente de sincronizar.',
    '--milestone', e.milestone, '--label', e.labels.join(',')]);
  const number = Number(url.split('/').pop());
  console.log(`[created] #${number} ${e.title}`);
  byCode.set(e.code, { number, title: e.title, body: '', labels: e.labels.map((name) => ({ name })), milestone: { title: e.milestone }, state: 'OPEN' });
  await pause();
}

// ---- Render bodies ------------------------------------------------------------------------

function absolutizeLinks(body) {
  return body.replace(/\]\(([^)\s]+)\)/g, (match, target) => {
    if (/^https?:/.test(target)) return match;
    const [file, anchor] = target.split('#');
    const resolved = path.posix.normalize(path.posix.join(path.posix.dirname(BACKLOG), file || path.posix.basename(BACKLOG)));
    return `](${blob}${resolved}${anchor ? `#${anchor}` : ''})`;
  });
}

function linkCodes(body) {
  return body.replace(/OW-\d{3}/g, (code) => {
    const number = byCode.get(code)?.number;
    return number ? `${code} (#${number})` : code;
  });
}

function render(e) {
  const content = linkCodes(absolutizeLinks(e.rawBody));
  return e.status === 'Cerrada' ? content + FOOTER : `${STATUS_TEXT[e.status]}\n\n${content}${FOOTER}`;
}

// ---- Apply --------------------------------------------------------------------------------

let changes = 0;
for (const e of entries) {
  const issue = byCode.get(e.code);
  if (!issue) {
    console.log(`[skip] ${e.code} is closed in the backlog and does not exist on GitHub`);
    continue;
  }
  const body = render(e);
  const current = issue.labels.map((l) => l.name);
  const add = e.labels.filter((l) => !current.includes(l));
  const remove = current.filter((l) => MANAGED_LABELS.has(l) && !e.labels.includes(l));
  const edits = [];
  if (issue.title !== e.title) edits.push('title');
  if ((issue.body ?? '').replace(/\r\n/g, '\n').trim() !== body.trim()) edits.push('body');
  if (issue.milestone?.title !== e.milestone) edits.push('milestone');
  if (add.length || remove.length) edits.push('labels');
  const close = e.status === 'Cerrada' && issue.state === 'OPEN';
  if (e.status !== 'Cerrada' && issue.state === 'CLOSED') {
    console.log(`[warn] #${issue.number} ${e.code} is closed on GitHub but open in the backlog; reopen it manually if intended`);
  }
  if (!edits.length && !close) continue;

  changes++;
  const ref = issue.number ? `#${issue.number}` : '(new)';
  console.log(`[${run ? 'updated' : 'update'}] ${ref} ${e.code}: ${[...edits, ...(close ? ['close'] : [])].join(', ')}`
    + `${add.length ? ` +[${add}]` : ''}${remove.length ? ` -[${remove}]` : ''}`);
  if (!run) continue;

  if (edits.length) {
    const args = ['issue', 'edit', String(issue.number), '--repo', repo, '--title', e.title, '--body-file', '-', '--milestone', e.milestone];
    if (add.length) args.push('--add-label', add.join(','));
    if (remove.length) args.push('--remove-label', remove.join(','));
    gh(args, body);
  }
  if (close) {
    const reason = /Fusionada/.test(e.rawBody) ? 'not planned' : 'completed';
    gh(['issue', 'close', String(issue.number), '--repo', repo, '--reason', reason, '--comment', linkCodes(absolutizeLinks(e.rawBody))]);
  }
  await pause();
}

console.log(changes ? `${changes} issue(s) ${run ? 'updated' : 'would change (use --run to apply)'}` : 'No changes: GitHub matches the backlog.');
