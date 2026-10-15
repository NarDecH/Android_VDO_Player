/*
 * Download page logic: resolve the newest GitHub release at runtime so the page
 * never goes stale when a new tag is pushed. No build step, no dependencies.
 */
const REPO = 'NarDecH/Android_VDO_Player';
const RELEASE_API = `https://api.github.com/repos/${REPO}/releases/latest`;
const ALL_RELEASES = `https://github.com/${REPO}/releases`;

const ARCH_HINTS = [
  {
    match: /arm64-v8a/,
    label: '64-bit ARM · recommended',
    note: 'Every phone released since roughly 2016.',
    recommended: true,
  },
  {
    match: /armeabi-v7a/,
    label: '32-bit ARM',
    note: 'Older or entry-level devices.',
  },
  {
    match: /x86_64/,
    label: 'x86_64',
    note: 'Emulators and x86 tablets.',
  },
  {
    match: /universal/,
    label: 'Universal',
    note: 'All architectures in one file — pick this if you are unsure.',
  },
];

function humanSize(bytes) {
  if (!bytes && bytes !== 0) return '';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return unit === 0 ? `${bytes} B` : `${value.toFixed(1)} ${units[unit]}`;
}

function humanDate(iso) {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

function hintFor(name) {
  return ARCH_HINTS.find((hint) => hint.match.test(name)) ?? {
    label: 'Additional asset',
    note: '',
  };
}

function assetCard(asset) {
  const hint = hintFor(asset.name);
  const card = document.createElement('div');
  card.className = `asset${hint.recommended ? ' recommended' : ''}`;

  const name = document.createElement('div');
  name.className = 'name';
  name.textContent = asset.name;

  const meta = document.createElement('div');
  meta.className = 'meta';
  meta.textContent = `${hint.label} · ${humanSize(asset.size)}${
    asset.download_count ? ` · ${asset.download_count} downloads` : ''
  }`;

  card.append(name, meta);

  if (hint.note) {
    const note = document.createElement('div');
    note.className = 'hint';
    note.textContent = hint.note;
    card.append(note);
  }

  const link = document.createElement('a');
  link.className = 'btn btn-primary';
  link.href = asset.browser_download_url;
  link.rel = 'noopener';
  link.textContent = 'Download';
  card.append(link);

  return card;
}

function renderRelease(release) {
  const status = document.getElementById('release-status');
  const box = document.getElementById('release-box');

  const assets = (release.assets ?? []).filter((asset) => /\.apk$/i.test(asset.name));
  const apkCount = assets.length;

  document.getElementById('release-tag').textContent = release.tag_name ?? 'latest';
  document.getElementById('release-meta').textContent =
    `${release.name || release.tag_name} · published ${humanDate(
      release.published_at,
    )} · ${apkCount} APK${apkCount === 1 ? '' : 's'}`;

  const list = document.getElementById('asset-list');
  list.replaceChildren();

  if (apkCount === 0) {
    status.textContent =
      'The latest release has no APK assets yet — check the release page for details.';
  } else {
    status.textContent =
      'Pick the build for your phone. Sizes, architectures and download counts come from the GitHub API.';
    // Recommended build first, universal last.
    const ordered = [...assets].sort((a, b) => {
      const rank = (asset) =>
        hintFor(asset.name).recommended ? 0 : /universal/.test(asset.name) ? 2 : 1;
      return rank(a) - rank(b);
    });
    ordered.forEach((asset) => list.append(assetCard(asset)));
  }

  const notes = document.getElementById('release-notes');
  if (notes) {
    const body = (release.body ?? '').trim();
    notes.textContent = body.length > 1200 ? `${body.slice(0, 1200)}…` : body;
    notes.hidden = body.length === 0;
  }

  const allLink = document.getElementById('all-releases');
  if (allLink) {
    allLink.href = ALL_RELEASES;
  }

  box.hidden = false;
}

function showFallback(detail) {
  const status = document.getElementById('release-status');
  const fallback = document.getElementById('release-fallback');
  status.textContent =
    'Could not read the release feed automatically (this can happen when GitHub rate-limits anonymous API calls).';
  if (fallback) {
    fallback.hidden = false;
    const why = fallback.querySelector('[data-detail]');
    if (why && detail) why.textContent = detail;
  }
}

async function loadLatestRelease() {
  const status = document.getElementById('release-status');
  if (status) status.textContent = 'Asking the GitHub API for the latest release…';

  try {
    const response = await fetch(RELEASE_API, {
      headers: { Accept: 'application/vnd.github+json' },
    });
    if (!response.ok) {
      throw new Error(
        response.status === 404
          ? 'No release published yet.'
          : `GitHub API responded with HTTP ${response.status}.`,
      );
    }
    renderRelease(await response.json());
  } catch (error) {
    showFallback(error instanceof Error ? error.message : String(error));
  }
}

document.addEventListener('DOMContentLoaded', loadLatestRelease);
