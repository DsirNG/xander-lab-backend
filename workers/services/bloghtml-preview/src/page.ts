const OPENING_HTML = /<html[^>]*>/i;
const CLOSING_HEAD = /<\/head>/i;
const OPENING_HEAD = /<head[^>]*>/i;
const OPENING_BODY = /<body[\s>]/i;

const CSP_META = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; base-uri 'none'; form-action 'none'\">";
const SCROLL_CONTAINMENT_STYLE = "<style>html, body { overscroll-behavior: contain; }</style>";

const HEIGHT_REPORTER = `<script>
(function () {
  function announce() {
    var height = document.documentElement.scrollHeight || document.body.scrollHeight || 0;
    try { parent.postMessage({ type: '__sandboxHeight', height: height }, '*'); } catch (e) {}
  }
  window.addEventListener('load', announce);
  window.addEventListener('resize', announce);
  if (window.MutationObserver) {
    new MutationObserver(announce).observe(
      document.documentElement,
      { childList: true, subtree: true, attributes: true, characterData: true }
    );
  }
  setTimeout(announce, 300);
})();
</script>`;

const DEBUG_REPORTER = `<script>
(function () {
  var scheduled = false;
  function describe(element) {
    if (!element || !element.tagName) return null;
    return element.tagName.toLowerCase()
      + (element.id ? '#' + element.id : '')
      + (element.className && typeof element.className === 'string'
        ? '.' + element.className.trim().replace(/\\s+/g, '.') : '');
  }
  function report(eventName, target) {
    var scrollingElement = document.scrollingElement;
    try { parent.postMessage({
      type: '__sandboxDebug', event: eventName, hash: location.hash,
      scrollY: window.scrollY, scrollTop: scrollingElement ? scrollingElement.scrollTop : null,
      activeElement: describe(document.activeElement), target: describe(target),
      viewportHeight: window.innerHeight, documentHeight: document.documentElement.scrollHeight
    }, '*'); } catch (e) {}
  }
  document.addEventListener('click', function (event) {
    var link = event.target && event.target.closest ? event.target.closest('a[href]') : null;
    if (link) report('anchor-click:' + link.getAttribute('href'), link);
  }, true);
  document.addEventListener('focusin', function (event) { report('focusin', event.target); }, true);
  window.addEventListener('hashchange', function () { report('hashchange'); });
  window.addEventListener('popstate', function () { report('popstate'); });
  window.addEventListener('scroll', function () {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () { scheduled = false; report('scroll'); });
  }, { passive: true });
  window.addEventListener('load', function () { report('load'); });
})();
</script>`;

const FRAGMENT_STYLE = `<style>
  html, body { margin: 0; padding: 16px; background: #fff; color: #0f172a; font-family: system-ui, -apple-system, Segoe UI, sans-serif; }
</style>`;

export function buildPreviewPage(code: string): string {
  if (OPENING_HTML.test(code) || OPENING_HEAD.test(code) || OPENING_BODY.test(code)) {
    return injectIntoFullDocument(code);
  }
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  ${CSP_META}
  ${SCROLL_CONTAINMENT_STYLE}
  ${HEIGHT_REPORTER}
  ${DEBUG_REPORTER}
  ${FRAGMENT_STYLE}
</head>
<body>
${code}
</body>
</html>`;
}

function injectIntoFullDocument(raw: string): string {
  const closingHead = CLOSING_HEAD.exec(raw);
  if (closingHead?.index !== undefined) {
    return raw.slice(0, closingHead.index)
      + CSP_META + "\n" + SCROLL_CONTAINMENT_STYLE + "\n"
      + HEIGHT_REPORTER + DEBUG_REPORTER
      + raw.slice(closingHead.index);
  }

  const openingHead = OPENING_HEAD.exec(raw);
  if (openingHead?.index !== undefined) {
    const end = openingHead.index + openingHead[0].length;
    return raw.slice(0, end)
      + "\n" + CSP_META + SCROLL_CONTAINMENT_STYLE
      + HEIGHT_REPORTER + DEBUG_REPORTER + "</head>"
      + raw.slice(end);
  }

  const openingHtml = OPENING_HTML.exec(raw);
  if (openingHtml?.index !== undefined) {
    const end = openingHtml.index + openingHtml[0].length;
    return raw.slice(0, end)
      + "\n<head>" + CSP_META + SCROLL_CONTAINMENT_STYLE
      + HEIGHT_REPORTER + DEBUG_REPORTER + "</head>"
      + raw.slice(end);
  }

  return CSP_META + SCROLL_CONTAINMENT_STYLE + HEIGHT_REPORTER + DEBUG_REPORTER + raw;
}
