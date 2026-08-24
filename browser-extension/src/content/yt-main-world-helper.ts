/**
 * YouTube main-world helper script to access window-level metadata
 * from isolated content script contexts.
 *
 * Runs in the MAIN world where window.ytInitialPlayerResponse and
 * window.ytInitialData live. Instead of projecting those multi-megabyte
 * trees across the world boundary via CustomEvent.detail, this performs
 * the carousel/metadata-row extraction locally and answers with a small
 * projected payload.
 */

(() => {
  // Loose shape of YouTube's renderer JSON trees.
  type YtValue = YtNode | YtValue[] | string | number | boolean | null | undefined;
  type YtNode = { [key: string]: YtValue };
  interface YtMetadata {
    title?: string;
    artist?: string;
    album?: string;
    label?: string;
  }

  // Depth cap keeps pathological/unbounded trees from blowing the stack.
  const MAX_WALK_DEPTH = 40;

  function isYtNode(value: YtValue): value is YtNode {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
  }

  function sanitize(value: string | null | undefined): string {
    return String(value || '').replace(/\s+/g, ' ').trim();
  }

  function findCarouselLockupRenderers(obj: YtValue, depth: number, results: YtNode[] = []): YtNode[] {
    if (!obj || typeof obj !== 'object' || depth > MAX_WALK_DEPTH) return results;

    if (Array.isArray(obj)) {
      for (const item of obj) {
        findCarouselLockupRenderers(item, depth + 1, results);
      }
      return results;
    }

    if (isYtNode(obj.carouselLockupRenderer)) {
      results.push(obj.carouselLockupRenderer);
      return results;
    }

    for (const key of Object.keys(obj)) {
      if (key === 'streamingData' || key === 'playerAds' || key === 'attestation') continue;
      findCarouselLockupRenderers(obj[key], depth + 1, results);
    }
    return results;
  }

  function findMetadataRowRenderers(obj: YtValue, depth: number, results: YtNode[] = []): YtNode[] {
    if (!obj || typeof obj !== 'object' || depth > MAX_WALK_DEPTH) return results;

    if (Array.isArray(obj)) {
      for (const item of obj) {
        findMetadataRowRenderers(item, depth + 1, results);
      }
      return results;
    }

    if (isYtNode(obj.metadataRowRenderer)) {
      results.push(obj.metadataRowRenderer);
      return results;
    }

    for (const key of Object.keys(obj)) {
      if (key === 'streamingData' || key === 'playerAds' || key === 'attestation') continue;
      findMetadataRowRenderers(obj[key], depth + 1, results);
    }
    return results;
  }

  function getTextFromRenderer(field: YtValue): string {
    if (!field) return '';
    if (typeof field === 'string') return field;
    if (isYtNode(field)) {
      if (field.simpleText) return String(field.simpleText);
      if (Array.isArray(field.runs)) {
        return field.runs.map((r) => (isYtNode(r) && typeof r.text === 'string' ? r.text : '')).join('');
      }
    }
    return '';
  }

  function parseCarouselLockup(renderer: YtNode): YtMetadata | null {
    const infoRows = renderer.infoRows;
    if (!infoRows || !Array.isArray(infoRows)) return null;

    let title: string | undefined;
    let artist: string | undefined;
    let album: string | undefined;
    let label: string | undefined;

    for (const row of infoRows) {
      if (!isYtNode(row)) continue;
      const infoRowRenderer = row.infoRowRenderer;
      if (!isYtNode(infoRowRenderer)) continue;

      const label_text = getTextFromRenderer(infoRowRenderer.title).trim().toLowerCase();
      const value = getTextFromRenderer(
        isYtNode(infoRowRenderer.defaultMetadata) ? infoRowRenderer.defaultMetadata : infoRowRenderer.expandedMetadata
      ).trim();

      if (!label_text || !value) continue;

      if (label_text.includes('song') || label_text.includes('track')) {
        title = sanitize(value);
      } else if (label_text.includes('artist') || label_text.includes('singer') || label_text.includes('performed by')) {
        artist = sanitize(value);
      } else if (label_text.includes('album')) {
        album = sanitize(value);
      } else if (label_text.includes('label') || label_text.includes('record label') || label_text.includes('licensed to')) {
        label = sanitize(value);
      }
    }

    if (title || artist || album || label) {
      return { title, artist, album, label };
    }
    return null;
  }

  window.addEventListener('tempo-request-yt-metadata', () => {
    try {
      // Well-known page globals only reachable from the main world.
      const mainWindow = window as Window & { ytInitialPlayerResponse?: YtValue; ytInitialData?: YtValue };
      const playerResponse = mainWindow.ytInitialPlayerResponse || null;
      const initialData = mainWindow.ytInitialData || null;
      const videoDetails = isYtNode(playerResponse) && isYtNode(playerResponse.videoDetails)
        ? playerResponse.videoDetails
        : undefined;

      const response = {
        videoId: typeof videoDetails?.videoId === 'string' ? videoDetails.videoId : undefined,
        videoTitle: typeof videoDetails?.title === 'string' ? videoDetails.title : undefined,
        author: typeof videoDetails?.author === 'string' ? videoDetails.author : undefined,
        shortDescription: typeof videoDetails?.shortDescription === 'string' ? videoDetails.shortDescription : '',
        carouselsPlayerResponse: findCarouselLockupRenderers(playerResponse, 0)
          .map(parseCarouselLockup)
          .filter((meta): meta is YtMetadata => meta !== null),
        carouselsInitialData: findCarouselLockupRenderers(initialData, 0)
          .map(parseCarouselLockup)
          .filter((meta): meta is YtMetadata => meta !== null),
        metadataRows: findMetadataRowRenderers(playerResponse, 0).concat(
          initialData ? findMetadataRowRenderers(initialData, 0) : []
        ),
      };
      window.dispatchEvent(new CustomEvent('tempo-response-yt-metadata', {
        detail: response
      }));
    } catch (e) {
      console.warn('[Tempo] Error dispatching YouTube main-world metadata:', e);
    }
  });
})();
