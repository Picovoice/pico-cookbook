const URL_PATTERN = /(?:https?:\/\/|www\.)[^\s<>"'()[\]]+|(?<![@\w.-])(?:[a-z0-9-]+\.)+(?:ai|com|dev|io|net|org)\b(?:\/[^\s<>"'()[\]]*)?/gi;
const MARKDOWN_LINK_PATTERN = /\[([^\]\n]+)\]\(([^)\s]+)\)/g;

const TRAILING_PUNCTUATION = /[.,!?:;]+$/;

const trimUrl = (url: string): string => url.replace(TRAILING_PUNCTUATION, '');

const escapeHtml = (text: string): string =>
  text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');

const toHref = (url: string): string => (/^https?:\/\//i.test(url) ? url : `https://${url}`);

const toAnchor = (url: string, label: string): string =>
  `<a href="${escapeHtml(toHref(url))}" target="_blank" rel="noopener noreferrer">${escapeHtml(label)}</a>`;

const toHtml = (text: string): string => {
  const pattern = new RegExp(`${MARKDOWN_LINK_PATTERN.source}|${URL_PATTERN.source}`, 'gi');

  let html = '';
  let last = 0;
  for (const match of text.matchAll(pattern)) {
    const index = match.index ?? 0;
    html += escapeHtml(text.slice(last, index));
    if (match[1] !== undefined) {
      html += toAnchor(match[2], match[1]);
    } else {
      const url = trimUrl(match[0]);
      html += toAnchor(url, url) + escapeHtml(match[0].slice(url.length));
    }
    last = index + match[0].length;
  }
  return html + escapeHtml(text.slice(last));
};

export {
  toHtml,
};
