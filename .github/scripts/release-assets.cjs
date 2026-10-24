// Keep each 1.0 release page limited to the one APK produced by the build.
module.exports = async ({ github, context, core, tag, cleanup = false }) => {
  let release;
  try {
    ({ data: release } = await github.rest.repos.getReleaseByTag({ ...context.repo, tag }));
  } catch (error) {
    if (cleanup && error.status === 404) return;
    throw error;
  }
  const assets = await github.paginate(github.rest.repos.listReleaseAssets, {
    ...context.repo,
    release_id: release.id,
    per_page: 100,
  });
  const expected = 'app-github-release.apk';
  const apks = assets.filter((asset) => asset.name.toLowerCase().endsWith('.apk'));
  if (cleanup) {
    for (const asset of apks.filter((asset) => asset.name !== expected)) {
      await github.rest.repos.deleteReleaseAsset({ ...context.repo, asset_id: asset.id });
      core.info(`Removed obsolete APK: ${asset.name}`);
    }
  } else if (apks.length !== 1 || apks[0].name !== expected || apks[0].size <= 0) {
    throw new Error(`Expected one universal APK (${expected}), found: ${apks.map((asset) => asset.name).join(', ')}`);
  } else {
    core.info(`Verified one universal APK: ${expected}`);
  }
};
