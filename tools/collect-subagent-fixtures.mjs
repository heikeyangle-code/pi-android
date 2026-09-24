#!/usr/bin/env node
/**
 * The **pi-subagents payload fixtures**: what the `subagent-async` widget actually carries,
 * captured from the extension's own projection so the card is checked against real bytes
 * instead of against a shape someone read out of its source.
 *
 * ## What problem this solves
 *
 * `app/src/main/kotlin/app/pi/ui/extension/ExtensionWidgetLines.kt` turns
 * `PI_SUBAGENT_ASYNC_JSON:{…}` into the rows the card paints. Two things went wrong while it
 * was written by reading the extension's code, and both were *invisible to its own tests*:
 *
 *  1. A scripted call spawns N agents inside **one** async job (its `label` is a joined agent
 *     list, the tasks are in `children`) — the card counted jobs, so a run of four agents
 *     rendered as "1 运行中" with the tasks missing entirely.
 *  2. A hand-written fixture agrees with the parser that reads it. Every wrong assumption about
 *     the payload passed, because the test spoke the same invented shape.
 *
 * This script removes the second problem: the JSON below is produced by
 * `projectAsyncStatusSnapshot()` from the installed package, driven over constructed job states.
 * `WidgetPayloadCheck.kt` reads those files; `--check` re-derives them so an extension upgrade
 * that renames a field turns CI red instead of quietly emptying the card. The first problem was
 * only findable once the real four-task payload existed.
 *
 * ## Why it can run with no model, no network and no npm install
 *
 * The projection is a pure function (`src/runs/shared/async-status-projection.js`): jobs in,
 * snapshot out, no provider, no turn loop, no clock (every case passes its own `generatedAt`).
 * Its only import chain pulls `yaml`, which the caller's package must already have installed.
 *
 * Usage:
 *   node tools/collect-subagent-fixtures.mjs --pkg <path-to-pi-subagents-package> [--check]
 *   node tools/collect-subagent-fixtures.mjs --pkg … --write     # refresh the committed copies
 *
 * `--check` is the CI half: it re-derives every case and compares it byte for byte with the
 * committed file, exactly like `tools/collect-tool-fixtures.mjs --check` does for pi's tools.
 */
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(new URL('..', import.meta.url).pathname);
const OUT_DIR = path.join(ROOT, 'app/src/test/resources/pi-subagent-fixtures');

const argv = process.argv.slice(2);
const arg = (name) => {
  const i = argv.indexOf(name);
  return i === -1 ? null : argv[i + 1];
};
const pkg = arg('--pkg');
const check = argv.includes('--check');
const write = argv.includes('--write');
if (!pkg) {
  console.error('collect-subagent-fixtures: --pkg <path-to-pi-subagents-package> is required');
  process.exit(2);
}

/** A job as the runtime tracker holds it. Only the fields the projection reads. */
const job = (over = {}) => ({
  asyncId: 'job-1',
  sessionId: 'session-1',
  mode: 'subagent',
  status: 'running',
  startedAt: 1_790_200_606_123,
  updatedAt: 1_790_200_638_560,
  ...over,
});

const CASES = [
  {
    id: 'single-agent',
    why: 'the simplest job: one agent, no children — the folded card is one row and the badge is 1',
    jobs: [
      job({
        agents: ['oracle'],
        turnCount: 4,
        toolCount: 6,
        currentTool: 'read',
        currentToolStartedAt: 1_790_200_630_000,
        activityState: 'running',
      }),
    ],
  },
  {
    id: 'scripted-parallel',
    why:
      'a scripted call: FOUR agents inside ONE job (the label is a joined list, the tasks are ' +
      'children). This is the case that rendered as "1 运行中" with every task missing',
    jobs: [
      job({
        agents: ['researcher', 'researcher', 'researcher', 'researcher'],
        turnCount: 2,
        toolCount: 3,
        currentTool: 'web_search',
        currentToolStartedAt: 1_790_200_630_000,
        activityState: 'running',
        steps: [
          { agent: 'researcher', label: 'catbox-recovery', status: 'running', workflowKey: 'k1', index: 0, turnCount: 3, toolCount: 7, currentTool: 'web_search', activityState: 'running' },
          { agent: 'researcher', label: 'st-image-embed', status: 'running', workflowKey: 'k2', index: 1, turnCount: 2, toolCount: 5, activityState: 'thinking' },
          { agent: 'researcher', label: 'st-card-optim', status: 'completed', workflowKey: 'k3', index: 2, turnCount: 4, toolCount: 9 },
          { agent: 'researcher', label: 'cn-community', status: 'failed', workflowKey: 'k4', index: 3, turnCount: 1, toolCount: 2 },
        ],
      }),
    ],
  },
  {
    id: 'mixed-states',
    why:
      'three jobs at once, one of them failed — the card takes its border and stripe from the ' +
      'most severe state, which a `maxBy` over a worst-first list got wrong once',
    jobs: [
      job({ asyncId: 'job-a', agents: ['scout'], status: 'running', activityState: 'running' }),
      job({ asyncId: 'job-b', agents: ['tester'], status: 'completed', turnCount: 5, toolCount: 9 }),
      job({ asyncId: 'job-c', agents: ['linter'], status: 'failed', turnCount: 2, toolCount: 5 }),
      job({ asyncId: 'job-d', agents: ['docs'], status: 'paused' }),
    ],
  },
  {
    id: 'more-than-the-panel-draws',
    why:
      'six jobs: the card lists four and summarises the rest (`+N 个更多`), borrowing the ' +
      "extension's own MAX_WIDGET_JOBS",
    jobs: Array.from({ length: 6 }, (_, i) =>
      job({ asyncId: `job-${i}`, agents: [`agent${i}`], status: 'running', activityState: 'running' }),
    ),
  },
  {
    id: 'workflow-with-steps',
    why:
      'workflow mode: the extension projects `workflowGraph` stages and `hostSteps` into child ' +
      'rows the card does NOT draw yet — captured so those rows can be built from a real sample ' +
      'rather than from a guess',
    jobs: [
      job({
        asyncId: 'job-workflow',
        mode: 'workflow',
        agents: ['planner'],
        steps: [
          { agent: 'planner', label: 'plan', status: 'completed', workflowKey: 'w1', index: 0 },
          { agent: 'builder', label: 'build', status: 'running', workflowKey: 'w2', index: 1, currentTool: 'edit' },
          { agent: 'tester', label: 'verify', status: 'pending', workflowKey: 'w3', index: 2 },
        ],
        workflowGraph: {
          nodes: [
            { id: 'w1', label: 'plan', agent: 'planner', status: 'completed', flatIndex: 0 },
            { id: 'w2', label: 'build', agent: 'builder', status: 'running', flatIndex: 1 },
            { id: 'w3', label: 'verify', agent: 'tester', status: 'planned', flatIndex: 2 },
          ],
        },
      }),
    ],
  },
  {
    id: 'nothing-running',
    why: 'an empty runs array: the card must say so and draw no border, not fall over',
    jobs: [],
  },
  {
    id: 'unknown-state',
    why:
      "a state the extension never names — it falls through to its own error glyph, and the card " +
      'must not invent one',
    jobs: [job({ agents: ['odd'], status: 'something-new' })],
  },
];

const mod = await import(pathToFileURL(path.join(pkg, 'src/runs/shared/async-status-projection.js')));
const version = JSON.parse(fs.readFileSync(path.join(pkg, 'package.json'), 'utf8')).version;

const produced = new Map();
for (const testCase of CASES) {
  const snapshot = mod.projectAsyncStatusSnapshot(testCase.jobs, { generatedAt: 1_790_200_638_621 });
  // One line, exactly as the widget carries it (`JSON.stringify` with no spacing).
  produced.set(testCase.id, {
    id: testCase.id,
    why: testCase.why,
    line: 'PI_SUBAGENT_ASYNC_JSON:' + JSON.stringify(snapshot),
  });
}

if (write) {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  for (const [id, value] of produced) {
    fs.writeFileSync(path.join(OUT_DIR, `${id}.json`), JSON.stringify(value, null, 2) + '\n');
  }
  fs.writeFileSync(path.join(OUT_DIR, 'version.json'), JSON.stringify({ piSubagents: version }, null, 2) + '\n');
  console.log(`collect-subagent-fixtures: wrote ${produced.size} case(s) for pi-subagents@${version}`);
  process.exit(0);
}

if (check) {
  const problems = [];
  const pinned = JSON.parse(fs.readFileSync(path.join(OUT_DIR, 'version.json'), 'utf8'));
  if (pinned.piSubagents !== version) {
    problems.push(`pi-subagents moved ${pinned.piSubagents} -> ${version}; re-run with --write and read the diff`);
  }
  for (const [id, value] of produced) {
    const file = path.join(OUT_DIR, `${id}.json`);
    if (!fs.existsSync(file)) {
      problems.push(`${id}: fixture missing`);
      continue;
    }
    const committed = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (committed.line !== value.line) {
      problems.push(`${id}: the extension now emits different bytes\n  committed: ${committed.line.slice(0, 160)}\n  produced:  ${value.line.slice(0, 160)}`);
    }
  }
  if (problems.length) {
    console.error('collect-subagent-fixtures: FAILED\n  ' + problems.join('\n  '));
    process.exit(1);
  }
  console.log(`collect-subagent-fixtures: OK (${produced.size} case(s) still what pi-subagents@${version} emits)`);
  process.exit(0);
}

for (const [id, value] of produced) {
  console.log(`${id}  (${value.line.length} chars)`);
  console.log(`  ${value.line.slice(0, 200)}…`);
}
console.log('\npass --write to refresh app/src/test/resources/pi-subagent-fixtures/');
