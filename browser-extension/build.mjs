import * as esbuild from 'esbuild';
import { existsSync, cpSync, mkdirSync, readFileSync, writeFileSync, rmSync, statSync } from 'fs';
import { execFileSync } from 'child_process';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const isWatch = process.argv.includes('--watch');

// Parse --target flag (chrome or firefox)
const targetArg = process.argv.find(arg => arg.startsWith('--target='));
const target = targetArg ? targetArg.split('=')[1] : 'chrome';

if (target !== 'chrome' && target !== 'firefox') {
  console.error('❌ Invalid target. Use: --target=chrome or --target=firefox');
  process.exit(1);
}

// esbuild target follows the build target (Firefox 140 is the manifest strict_min_version)
const esbuildTarget = target === 'firefox' ? 'firefox140' : 'chrome110';

// Background service worker — IIFE for both browsers (Firefox doesn't fully support ESM in service workers)
const bgOptions = {
  entryPoints: ['src/background/service-worker.ts'],
  bundle: true,
  outdir: 'dist/background',
  target: esbuildTarget,
  minify: !isWatch,
  sourcemap: isWatch ? 'inline' : false,
  drop: isWatch ? [] : ['debugger'],
};

// Content script — IIFE (content scripts don't support ESM)
const contentOptions = {
  entryPoints: ['src/content/media-probe.ts', 'src/content/yt-main-world-helper.ts'],
  bundle: true,
  outdir: 'dist/content',
  target: esbuildTarget,
  minify: !isWatch,
  sourcemap: isWatch ? 'inline' : false,
  drop: isWatch ? [] : ['debugger'],
};

// Popup — ESM (loaded via <script type="module">)
const popupOptions = {
  entryPoints: ['src/popup/popup.ts'],
  bundle: true,
  outdir: 'dist/popup',
  target: esbuildTarget,
  minify: !isWatch,
  format: 'esm',
  splitting: true,
  drop: isWatch ? [] : ['debugger'],
};

function stripDistPrefix(value) {
  if (typeof value === 'string' && value.startsWith('dist/')) {
    return value.slice(5);
  }
  if (Array.isArray(value)) {
    return value.map(stripDistPrefix);
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, stripDistPrefix(item)]));
  }
  return value;
}

function buildChromeManifest(baseManifest) {
  const m = stripDistPrefix(baseManifest);
  // Bundle uses IIFE, so remove module type
  if (m.background) delete m.background.type;
  return m;
}

function buildFirefoxManifest(baseManifest) {
  const m = stripDistPrefix(baseManifest);

  delete m.minimum_chrome_version;

  if (m.background) delete m.background.type;

  // Firefox rejects manifests containing both service_worker and scripts
  if (m.background?.service_worker) {
    m.background.scripts = [m.background.service_worker];
    delete m.background.service_worker;
  }

  // Firefox doesn't support wildcard ports in optional_host_permissions
  delete m.optional_host_permissions;

  m.browser_specific_settings = {
    gecko: {
      id: 'tempo-stats@extension',
      strict_min_version: '140.0',
      data_collection_permissions: {
        required: ['none']
      }
    },
    gecko_android: {
      strict_min_version: '142.0'
    }
  };

  return m;
}

async function copyStaticAssets(distDir) {
  if (!existsSync(distDir)) mkdirSync(distDir, { recursive: true });

  const sourceManifestPath = resolve(__dirname, 'manifest.json');
  const baseManifest = JSON.parse(readFileSync(sourceManifestPath, 'utf8'));

  const manifest = target === 'firefox'
    ? buildFirefoxManifest(baseManifest)
    : buildChromeManifest(baseManifest);

  writeFileSync(resolve(distDir, 'manifest.json'), JSON.stringify(manifest, null, 2));

  const iconsDir = resolve(__dirname, 'icons');
  if (existsSync(iconsDir)) {
    cpSync(iconsDir, resolve(distDir, 'icons'), { recursive: true });
  }

  const popupDir = resolve(distDir, 'popup');
  if (!existsSync(popupDir)) mkdirSync(popupDir, { recursive: true });

  const popupHtml = resolve(__dirname, 'src/popup/popup.html');
  if (existsSync(popupHtml)) {
    // Conservative whitespace collapse: strip leading indentation and blank lines only.
    // Tags, attributes, and comments are preserved verbatim — no structural parsing.
    const html = readFileSync(popupHtml, 'utf8');
    const collapsed = html
      .split('\n')
      .map(line => line.replace(/^[ \t]+/, ''))
      .filter(line => line.length > 0)
      .join('\n');
    writeFileSync(resolve(popupDir, 'popup.html'), collapsed);
  }

  const popupCss = resolve(__dirname, 'src/popup/popup.css');
  if (existsSync(popupCss)) {
    await esbuild.build({
      entryPoints: [popupCss],
      outfile: resolve(popupDir, 'popup.css'),
      minify: !isWatch,
      bundle: false,
      sourcemap: false,
      logLevel: 'silent',
    });
  }

  const fontsDir = resolve(__dirname, 'src/popup/fonts');
  if (existsSync(fontsDir)) {
    cpSync(fontsDir, resolve(popupDir, 'fonts'), { recursive: true });
  }
}

function packageZip(distDir) {
  const zipName = `tempo-stats-${target}.zip`;
  const zipPath = resolve(__dirname, 'dist', zipName);
  rmSync(zipPath, { force: true });
  execFileSync('zip', ['-r', '-X', `../${zipName}`, '.'], { cwd: distDir, stdio: 'inherit' });
  const { size } = statSync(zipPath);
  console.log(`📦 Packaged → dist/${zipName} (${(size / 1024).toFixed(1)} KB)`);
}

async function build() {
  const distDir = resolve(__dirname, 'dist', target);
  const browserLabel = target === 'firefox' ? 'Firefox' : 'Chrome';

  try {
    if (isWatch) {
      const [bgCtx, contentCtx, popupCtx] = await Promise.all([
        esbuild.context({ ...bgOptions, outdir: resolve(distDir, 'background') }),
        esbuild.context({ ...contentOptions, outdir: resolve(distDir, 'content') }),
        esbuild.context({ ...popupOptions, outdir: resolve(distDir, 'popup') }),
      ]);
      await Promise.all([bgCtx.watch(), contentCtx.watch(), popupCtx.watch()]);
      await copyStaticAssets(distDir);
      console.log(`👀 Watching for changes (${browserLabel})...`);
    } else {
      await Promise.all([
        esbuild.build({ ...bgOptions, outdir: resolve(distDir, 'background') }),
        esbuild.build({ ...contentOptions, outdir: resolve(distDir, 'content') }),
        esbuild.build({ ...popupOptions, outdir: resolve(distDir, 'popup') }),
      ]);
      await copyStaticAssets(distDir);
      console.log(`✅ Build complete → dist/${target}/ (${browserLabel})`);
      packageZip(distDir);
    }
  } catch (err) {
    console.error('Build failed:', err);
    process.exit(1);
  }
}

build();
