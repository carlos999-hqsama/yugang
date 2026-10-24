import { esc } from './util.js';

const INLINE_IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
const INLINE_IMAGE_RE = /^data:(image\/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/]+={0,2})$/;
const DURABLE_IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);
const DURABLE_ATTACHMENT_ID_RE = /^sha256:[0-9a-f]{64}$/;

export function inlineImageSrcFrom(mediaType, data) {
  if (!INLINE_IMAGE_TYPES.has(mediaType) || typeof data !== 'string' || !data || data.length % 4) return '';
  const src = `data:${mediaType};base64,${data}`;
  return INLINE_IMAGE_RE.test(src) ? src : '';
}

function inlineImage(block) {
  if (!block || (block.type && block.type !== 'image')) return null;
  const data = typeof block.data === 'string' ? block.data : '';
  const src = inlineImageSrcFrom(block.mediaType, data);
  return src ? { src, label: block.name || '图片' } : null;
}

export function inlineImageSrc(block) { return inlineImage(block)?.src || ''; }
export function isInlineImageSrc(src) {
  return typeof src === 'string' && INLINE_IMAGE_RE.test(src);
}

function durableImage(block) {
  const attachment = block?.attachment;
  if (!attachment || !DURABLE_IMAGE_TYPES.has(attachment.mediaType) || !DURABLE_ATTACHMENT_ID_RE.test(attachment.attachmentId || '')) return null;
  return { attachmentId: attachment.attachmentId, mediaType: attachment.mediaType, label: attachment.name || '图片' };
}

function inlineHtml(image) {
  return `<button type="button" class="chat-image" data-image="1" data-image-title="${esc(image.label)}" aria-label="查看图片：${esc(image.label)}"><img src="${esc(image.src)}" alt="${esc(image.label)}" loading="lazy"><span class="chat-image-failure" hidden>图片读取失败，点击重试查看</span></button>`;
}

function durableHtml(image, state = { status: 'loading' }) {
  const common = `data-attachment-id="${esc(image.attachmentId)}" data-image-title="${esc(image.label)}"`;
  if (state.status === 'loaded') {
    return `<button type="button" class="chat-image" ${common} data-status="loaded" aria-label="查看图片：${esc(image.label)}"><img src="${esc(state.src)}" alt="${esc(image.label)}" loading="lazy"><span class="chat-image-failure" hidden>图片读取失败，点击重试查看</span></button>`;
  }
  if (state.status === 'error') {
    const detail = state.error ? `<small>${esc(state.error)}</small>` : '';
    return `<button type="button" class="chat-image" ${common} data-status="error" aria-label="重试读取图片：${esc(image.label)}"><span class="chat-image-failure">图片读取失败，点击重试${detail}</span></button>`;
  }
  return `<button type="button" class="chat-image" ${common} data-status="loading" aria-busy="true" disabled aria-label="正在读取图片：${esc(image.label)}"><span class="chat-image-state"><span class="spin" style="width:12px;height:12px"></span>图片读取中…</span></button>`;
}

/** Render structured user image blocks; never promote a filename or URL to a source. */
export function userImagesHtml(content, options = {}) {
  const images = [], files = [];
  let invalid = 0;
  for (const block of Array.isArray(content) ? content : []) {
    if (block?.type === 'image') {
      const image = inlineImage(block);
      if (image) images.push(inlineHtml(image));
      else {
        const durable = durableImage(block);
        if (durable) images.push(durableHtml(durable, options.attachment?.(durable))); else invalid++;
      }
    } else if (block?.type === 'file') {
      files.push(String(block.attachment?.name || block.name || block.path || '附件'));
    }
  }
  let html = images.length ? `<div class="user-images">${images.join('')}</div>` : '';
  if (invalid) html += `${html ? '' : '\n'}[图片 ×${invalid} 无法预览]`;
  if (files.length) html += `${html ? '\n' : ''}[附件 ${esc(files.join('、'))}]`;
  return html;
}
