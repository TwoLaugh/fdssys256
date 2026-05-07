// k6 baseline for the admin decision-log endpoints (Pilot 1).
//
// Asserts the per-endpoint budgets from `tickets/core/01-decision-log.md
// §Performance budget`:
//
//   GET /api/v1/admin/decision-log/{id}                  median 15ms p95 40ms
//   GET /api/v1/admin/decision-log/trace/{id}            median 30ms p95 80ms
//   GET /api/v1/admin/decision-log/{id}/ancestry         median 30ms p95 100ms
//
// Expects a seeded DB with 10,000 decision-log rows across 1,000 traces of
// variable depth (see tickets §Performance budget). The seeder is a separate
// concern; this script reads the seed manifest from BASE_URL/perf/seed.json
// (a small JSON document the seeder writes out) so the test stays untouched
// when seed contents change.
//
// Usage:
//   BASE_URL=http://localhost:8080 \
//   ADMIN_SESSION=$(cat .admin-session-cookie) \
//   k6 run perf/core/decision-log.js
//
// Failure modes:
//   - thresholds.http_req_duration: any per-endpoint p95 above the budget
//   - thresholds.checks: any HTTP status check below 99.9% pass rate

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_SESSION = __ENV.ADMIN_SESSION || '';

const byIdLatency = new Trend('decision_log_by_id_ms', true);
const byTraceLatency = new Trend('decision_log_by_trace_ms', true);
const ancestryLatency = new Trend('decision_log_ancestry_ms', true);

export const options = {
  scenarios: {
    steady_load: {
      executor: 'constant-vus',
      vus: 20,
      duration: '1m',
      gracefulStop: '15s',
    },
  },
  thresholds: {
    'decision_log_by_id_ms':       ['med<15',  'p(95)<40'],
    'decision_log_by_trace_ms':    ['med<30',  'p(95)<80'],
    'decision_log_ancestry_ms':    ['med<30',  'p(95)<100'],
    'checks':                      ['rate>0.999'],
  },
};

export function setup() {
  // The seeder writes /perf/seed.json into the running app's static
  // resources after populating the test DB. It contains arrays of
  // decisionIds and traceIds we can sample from in the load loop.
  const res = http.get(`${BASE_URL}/perf/seed.json`);
  if (res.status !== 200) {
    throw new Error(
      `Seed manifest not found at ${BASE_URL}/perf/seed.json (status ${res.status}). ` +
        `Run the perf seeder before this script.`
    );
  }
  const seed = res.json();
  if (!seed.decisionIds || !seed.traceIds) {
    throw new Error('Seed manifest missing decisionIds / traceIds');
  }
  return seed;
}

export default function (seed) {
  const headers = ADMIN_SESSION
    ? { Cookie: `SESSION=${ADMIN_SESSION}` }
    : {};

  // 1) Single decision lookup
  const decisionId = pick(seed.decisionIds);
  const r1 = http.get(`${BASE_URL}/api/v1/admin/decision-log/${decisionId}`, { headers });
  byIdLatency.add(r1.timings.duration);
  check(r1, {
    'getById 200': (r) => r.status === 200,
    'getById has decisionId': (r) => r.json('decisionId') === decisionId,
  });

  // 2) Trace lookup — 10-row trace
  const traceId = pick(seed.traceIds);
  const r2 = http.get(`${BASE_URL}/api/v1/admin/decision-log/trace/${traceId}`, { headers });
  byTraceLatency.add(r2.timings.duration);
  check(r2, {
    'getByTrace 200': (r) => r.status === 200,
    'getByTrace returns array': (r) => Array.isArray(r.json()),
  });

  // 3) Ancestry walk — 5-deep
  const r3 = http.get(
    `${BASE_URL}/api/v1/admin/decision-log/${decisionId}/ancestry?maxDepth=8`,
    { headers }
  );
  ancestryLatency.add(r3.timings.duration);
  check(r3, {
    'ancestry 200': (r) => r.status === 200,
    'ancestry has cycleDetected field': (r) => r.json('cycleDetected') !== undefined,
  });

  sleep(0.1);
}

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
