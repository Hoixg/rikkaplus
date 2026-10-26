const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '../..');
const source = path.join(__dirname, 'launcher-source.jpg');
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

async function main() {
  const metadata = await sharp(source).metadata();
  if (metadata.width !== metadata.height) throw new Error('Launcher artwork must be square');

  for (const [density, scale] of Object.entries(densities)) {
    const directory = path.join(root, 'app/src/main/res', `mipmap-${density}`);
    await fs.mkdir(directory, { recursive: true });
    await sharp(source).autoOrient().resize(48 * scale, 48 * scale)
      .png().toFile(path.join(directory, 'ic_launcher.png'));
    // Adaptive layers are 108dp; the launcher displays the central 72dp.
    await sharp(source).autoOrient().resize(72 * scale, 72 * scale).ensureAlpha()
      .extend({
        top: 18 * scale, bottom: 18 * scale, left: 18 * scale, right: 18 * scale,
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      }).png().toFile(path.join(directory, 'ic_launcher_foreground.png'));
    await sharp({ create: { width: 108 * scale, height: 108 * scale, channels: 3, background: '#000000' } })
      .png().toFile(path.join(directory, 'ic_launcher_background.png'));
  }
  await sharp(source).autoOrient().resize(512, 512).png().toFile(path.join(root, 'docs/icon.png'));
  console.log('Updated launcher resources for five densities and the 512px README icon.');
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
