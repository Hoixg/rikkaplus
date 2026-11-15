const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '../..');
const source = path.join(__dirname, 'launcher-source.jpg');
const debugSource = path.join(__dirname, 'launcher-debug-source.jpg');
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

async function generateLauncherResources(imageSource, resourceRoot, includeBackground) {
  const metadata = await sharp(imageSource).metadata();
  if (metadata.width !== metadata.height) throw new Error('Launcher artwork must be square');

  for (const [density, scale] of Object.entries(densities)) {
    const directory = path.join(resourceRoot, `mipmap-${density}`);
    await fs.mkdir(directory, { recursive: true });
    const legacySize = 48 * scale;
    const artworkSize = Math.round(legacySize * 64 / 72);
    const padding = (legacySize - artworkSize) / 2;
    await sharp(imageSource).autoOrient().resize(artworkSize, artworkSize)
      .extend({
        top: Math.floor(padding), bottom: Math.ceil(padding),
        left: Math.floor(padding), right: Math.ceil(padding),
        background: '#000000',
      })
      .png().toFile(path.join(directory, 'ic_launcher.png'));
    // Keep the complete artwork within the adaptive icon's circular mask.
    await sharp(imageSource).autoOrient().resize(64 * scale, 64 * scale).ensureAlpha()
      .extend({
        top: 22 * scale, bottom: 22 * scale, left: 22 * scale, right: 22 * scale,
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      }).png().toFile(path.join(directory, 'ic_launcher_foreground.png'));
    if (includeBackground) {
      await sharp({ create: { width: 108 * scale, height: 108 * scale, channels: 3, background: '#000000' } })
        .png().toFile(path.join(directory, 'ic_launcher_background.png'));
    }
  }
}

async function main() {
  await generateLauncherResources(source, path.join(root, 'app/src/main/res'), true);
  // Debug overrides only the artwork and shares the existing black background/adaptive-icon XML.
  await generateLauncherResources(debugSource, path.join(root, 'app/src/debug/res'), false);
  await sharp(source).autoOrient().resize(512, 512).png().toFile(path.join(root, 'docs/icon.png'));
  console.log('Updated release/debug launcher resources for five densities and the 512px README icon.');
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
