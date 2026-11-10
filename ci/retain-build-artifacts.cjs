// Keep download files from the two newest successful builds of this workflow.
// Release/tag history and workflow logs stay available. Active runs are protected.
module.exports = async function retainBuildArtifacts({ github, context, core }) {
  const repo = context.repo;
  const { data: current } = await github.rest.actions.getWorkflowRun({ ...repo, run_id: context.runId });
  const runs = await github.paginate(github.rest.actions.listWorkflowRuns, {
    ...repo, workflow_id: current.workflow_id, per_page: 100
  });
  const builds = new Map(runs.filter(run => run.status === 'completed' && run.conclusion === 'success').map(run => [run.id, run]));
  // This job only runs after a successful build and publication (or a manual build).
  builds.set(current.id, current);
  const kept = [...builds.values()].sort((a, b) => b.run_number - a.run_number).slice(0, 2);
  const keepIds = new Set(kept.map(run => run.id));
  const keepNumbers = new Set(kept.map(run => run.run_number));
  const runsById = new Map(runs.map(run => [run.id, run]));
  runsById.set(current.id, current);
  const knownNumbers = new Set([...runsById.values()].map(run => run.run_number));
  const protectedNumbers = new Set(runs.filter(run => run.id !== current.id && run.status !== 'completed').map(run => run.run_number));
  const artifacts = await github.paginate(github.rest.actions.listArtifactsForRepo, { ...repo, per_page: 100 });
  // Fail before deleting anything if the just-built APK is missing.
  if (!artifacts.some(artifact => artifact.workflow_run?.id === current.id && artifact.name === 'supla-magazyn-apk' && !artifact.expired)) {
    throw new Error('Current APK artifact missing; retention stopped.');
  }
  const releases = await github.paginate(github.rest.repos.listReleases, { ...repo, per_page: 100 });
  const oldReleases = releases.filter(release => {
    const match = /^magazyn-build-([1-9]\d*)$/.exec(release.tag_name);
    if (!match || release.draft) return false;
    const number = Number(match[1]);
    return knownNumbers.has(number) && !keepNumbers.has(number) && !protectedNumbers.has(number);
  });
  core.info(`Keeping build files: ${kept.map(run => run.run_number).join(', ')}`);
  for (const release of oldReleases) {
    const assets = await github.paginate(github.rest.repos.listReleaseAssets, { ...repo, release_id: release.id, per_page: 100 });
    for (const asset of assets) {
      if (!['supla-magazyn.apk', 'supla-magazyn.apk.sha256'].includes(asset.name)) continue;
      await deleteIfPresent(() => github.rest.repos.deleteReleaseAsset({ ...repo, asset_id: asset.id }));
      core.info(`Removed ${release.tag_name}/${asset.name}`);
    }
  }
  for (const artifact of artifacts) {
    const run = runsById.get(artifact.workflow_run?.id);
    if (!run || keepIds.has(run.id) || (run.id !== current.id && run.status !== 'completed')) continue;
    await deleteIfPresent(() => github.rest.actions.deleteArtifact({ ...repo, artifact_id: artifact.id }));
    core.info(`Removed workflow artifact ${artifact.id} (${artifact.name}, build ${run.run_number})`);
  }
  await core.summary.addHeading('Build file retention').addRaw(`Files retained for builds: ${kept.map(run => run.run_number).join(', ')}. `)
    .addRaw('Older APK release attachments and workflow artifacts removed; release history, tags and workflow logs preserved.').write();
};

async function deleteIfPresent(remove) {
  try {
    await remove();
  } catch (error) {
    if (error.status !== 404) throw error;
  }
}
