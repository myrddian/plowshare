import { execFileSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Asset maintenance only: normal builds use the checked-in exports on every OS.
if (process.platform !== 'darwin') throw new Error('Icon export requires macOS sips and iconutil.');
const icons = resolve(dirname(fileURLToPath(import.meta.url)), '../assets/icons');
const temporary = await mkdtemp(join(tmpdir(), 'plowshare-icons-'));
const iconset = join(temporary, 'Plowshare.iconset');
try {
  await mkdir(iconset);
  for (const size of [16, 32, 128, 256, 512]) {
    for (const scale of [1, 2]) {
      execFileSync('sips', ['-z', String(size * scale), String(size * scale), join(icons, 'source.png'), '--out', join(iconset, `icon_${size}x${size}${scale === 2 ? '@2x' : ''}.png`)], { stdio: 'ignore' });
    }
  }
  execFileSync('sips', ['-z', '1024', '1024', join(icons, 'source.png'), '--out', join(icons, 'plowshare.png')], { stdio: 'ignore' });
  execFileSync('iconutil', ['-c', 'icns', iconset, '-o', join(icons, 'plowshare.icns')]);
  // ICO supports PNG entries, retaining alpha at all seven Windows shell sizes.
  const sizes = [16, 24, 32, 48, 64, 128, 256];
  const images = [];
  for (const size of sizes) {
    const png = join(temporary, `${size}.png`);
    execFileSync('sips', ['-z', String(size), String(size), join(icons, 'source.png'), '--out', png], { stdio: 'ignore' });
    images.push(await readFile(png));
  }
  const header = Buffer.alloc(6 + 16 * sizes.length);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(sizes.length, 4);
  let offset = header.length;
  sizes.forEach((size, index) => {
    const entry = 6 + index * 16;
    header[entry] = header[entry + 1] = size === 256 ? 0 : size;
    header.writeUInt16LE(1, entry + 4);
    header.writeUInt16LE(32, entry + 6);
    header.writeUInt32LE(images[index].length, entry + 8);
    header.writeUInt32LE(offset, entry + 12);
    offset += images[index].length;
  });
  await writeFile(join(icons, 'plowshare.ico'), Buffer.concat([header, ...images]));
  console.log('Exported Plowshare PNG, macOS ICNS (16–1024px) and Windows ICO (16–256px).');
} finally {
  await rm(temporary, { recursive: true, force: true });
}
