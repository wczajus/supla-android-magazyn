const { test } = require('node:test');
const assert = require('node:assert/strict');
const retain = require('./retain-build-artifacts.cjs');

function run(number, status = 'completed', conclusion = 'success') {
  return { id: number * 10, run_number: number, workflow_id: 7, status, conclusion };
}

function artifact(id, number, name = 'test-reports') {
  return { id, name, expired: false, workflow_run: { id: number * 10 } };
}

function fixture(current = run(31, 'in_progress', null)) {
  const deleted = { artifacts: [], assets: [] };
  const data = {
    runs: [run(32, 'in_progress', null), current, run(30), run(29, 'completed', 'failure'), run(28)],
    artifacts: [artifact(310, 31, 'supla-magazyn-apk'), artifact(311, 31), artifact(300, 30), artifact(290, 29),
      artifact(280, 28), artifact(320, 32), artifact(900, 90)],
    releases: [31, 30, 29, 28, 32].map(number => ({ id: number, tag_name: `magazyn-build-${number}`, draft: false })),
    assets: new Map([31, 30, 29, 28, 32].map(number => [number, [
      { id: number * 100, name: 'supla-magazyn.apk' },
      { id: number * 100 + 1, name: 'supla-magazyn.apk.sha256' },
      { id: number * 100 + 2, name: 'unrelated.txt' }
    ]]))
  };
  const calls = [];
  const github = {
    rest: {
      actions: {
        getWorkflowRun: async () => ({ data: current }),
        listWorkflowRuns: 'runs', listArtifactsForRepo: 'artifacts',
        deleteArtifact: async ({ artifact_id }) => { deleted.artifacts.push(artifact_id); }
      },
      repos: {
        listReleases: 'releases', listReleaseAssets: 'assets',
        deleteReleaseAsset: async ({ asset_id }) => { deleted.assets.push(asset_id); }
      }
    },
    paginate: async (endpoint, args) => {
      calls.push({ endpoint, args });
      return endpoint === 'assets' ? data.assets.get(args.release_id) || [] : data[endpoint];
    }
  };
  const summary = { addHeading: () => summary, addRaw: () => summary, write: async () => {} };
  return {
    data, deleted, calls, github,
    invoke: () => retain({ github, context: { repo: { owner: 'owner', repo: 'repo' }, runId: current.id }, core: { info: () => {}, summary } })
  };
}

test('keeps two newest successful builds, active runs and artifacts from other workflows', async () => {
  const f = fixture();
  await f.invoke();
  assert.deepEqual(f.deleted.artifacts, [290, 280]);
  assert.deepEqual(f.deleted.assets, [2900, 2901, 2800, 2801]);
  assert.equal(f.calls.find(call => call.endpoint === 'runs').args.workflow_id, 7);
  assert.ok(f.calls.every(call => call.args.per_page === 100));
});

test('out-of-order completion retains higher build numbers', async () => {
  const f = fixture(run(28, 'in_progress', null));
  f.data.runs = [run(31), run(30), run(28, 'in_progress', null)];
  f.data.artifacts = [artifact(280, 28, 'supla-magazyn-apk'), artifact(310, 31), artifact(300, 30)];
  f.data.releases = f.data.releases.filter(release => release.id !== 32);
  await f.invoke();
  assert.deepEqual(f.deleted.artifacts, [280]);
  assert.ok(!f.deleted.assets.includes(3100) && !f.deleted.assets.includes(3000));
});

test('missing or expired current APK stops cleanup before any deletion', async () => {
  for (const expired of [false, true]) {
    const f = fixture();
    if (expired) f.data.artifacts[0].expired = true;
    else f.data.artifacts.shift();
    await assert.rejects(f.invoke(), /Current APK artifact missing/);
    assert.deepEqual(f.deleted, { artifacts: [], assets: [] });
  }
});

test('preserves drafts, unrelated release tags and unrelated attachments', async () => {
  const f = fixture();
  f.data.releases = [
    { id: 28, tag_name: 'magazyn-build-28', draft: true },
    { id: 29, tag_name: 'upstream-v29', draft: false },
    { id: 1, tag_name: 'magazyn-build-1-extra', draft: false }
  ];
  await f.invoke();
  assert.deepEqual(f.deleted.assets, []);
});

test('protects releases from runs created after the workflow snapshot', async () => {
  const f = fixture();
  f.data.releases.push({ id: 33, tag_name: 'magazyn-build-33', draft: false });
  f.data.assets.set(33, [{ id: 3300, name: 'supla-magazyn.apk' }]);
  await f.invoke();
  assert.ok(!f.deleted.assets.includes(3300));
});

test('an already removed file is harmless; other API errors fail the job', async () => {
  const f = fixture();
  f.github.rest.repos.deleteReleaseAsset = async () => { throw { status: 404 }; };
  f.github.rest.actions.deleteArtifact = async () => { throw { status: 404 }; };
  await f.invoke();
  f.github.rest.repos.deleteReleaseAsset = async () => { throw Object.assign(new Error('denied'), { status: 403 }); };
  await assert.rejects(f.invoke(), /denied/);
});

test('first successful build does not require a second build', async () => {
  const f = fixture(run(1, 'in_progress', null));
  f.data.runs = [run(1, 'in_progress', null)];
  f.data.artifacts = [artifact(10, 1, 'supla-magazyn-apk')];
  f.data.releases = [{ id: 1, tag_name: 'magazyn-build-1', draft: false }];
  await f.invoke();
  assert.deepEqual(f.deleted, { artifacts: [], assets: [] });
});
