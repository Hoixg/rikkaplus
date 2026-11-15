const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '../..');
const source = path.join(__dirname, 'launcher-source.jpg');
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

async function main() {
  const metadata = await sharp(source).metadata();
  if (metadata.width !== metadata.height) throw new Error('Launcher artwork must be square');
  const debugBadge = await fs.readFile(path.join(__dirname, 'debug-badge.svg'));

  for (const [density, scale] of Object.entries(densities)) {
    const directory = path.join(root, 'app/src/main/res', `mipmap-${density}`);
    await fs.mkdir(directory, { recursive: true });
    const legacySize = 48 * scale;
    const artworkSize = Math.round(legacySize * 64 / 72);
    const padding = (legacySize - artworkSize) / 2;
    await sharp(source).autoOrient().resize(artworkSize, artworkSize)
      .extend({
        top: Math.floor(padding), bottom: Math.ceil(padding),
        left: Math.floor(padding), right: Math.ceil(padding),
        background: '#000000',
      })
      .png().toFile(path.join(directory, 'ic_launcher.png'));
    // Keep the complete artwork within the adaptive icon's circular mask.
    await sharp(source).autoOrient().resize(64 * scale, 64 * scale).ensureAlpha()
      .extend({
        top: 22 * scale, bottom: 22 * scale, left: 22 * scale, right: 22 * scale,
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      }).png().toFile(path.join(directory, 'ic_launcher_foreground.png'));
    await sharp({ create: { width: 108 * scale, height: 108 * scale, channels: 3, background: '#000000' } })
      .png().toFile(path.join(directory, 'ic_launcher_background.png'));

    const debugDirectory = path.join(root, 'app/src/debug/res', `mipmap-${density}`);
    await fs.mkdir(debugDirectory, { recursive: true });
    // Reserve a separate footer so the D marker never covers the terminal or other artwork.
    const debugLayer = await sharp({ create: {
      width: 72 * scale, height: 72 * scale, channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    } }).composite([
      { input: await sharp(source).autoOrient().resize(56 * scale, 56 * scale).png().toBuffer(),
        left: 8 * scale, top: 2 * scale },
      { input: await sharp(debugBadge).resize(72 * scale, 72 * scale).png().toBuffer() },
    ]).png().toBuffer();
    await sharp(debugLayer).flatten({ background: '#000000' }).resize(legacySize, legacySize)
      .png().toFile(path.join(debugDirectory, 'ic_launcher.png'));
    await sharp(debugLayer).extend({
      top: 18 * scale, bottom: 18 * scale, left: 18 * scale, right: 18 * scale,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    }).png().toFile(path.join(debugDirectory, 'ic_launcher_foreground.png'));
  }
  await sharp(source).autoOrient().resize(512, 512).png().toFile(path.join(root, 'docs/icon.png'));
  console.log('Updated release/debug launcher resources for five densities and the 512px README icon.');
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
