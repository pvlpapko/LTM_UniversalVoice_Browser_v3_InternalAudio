let enabled = true;

const BLOCK_PATTERNS = [
  'doubleclick.net', 'googlesyndication.com', 'google-analytics.com', 'adservice.google.',
  'an.yandex.ru', 'mc.yandex.ru', 'adform.net', 'taboola.com', 'outbrain.com',
  'criteo.com', 'scorecardresearch.com', 'adriver.ru', 'adskeeper.co', 'mgid.com',
  'popads.net', 'propellerads.com', 'adnxs.com', 'amazon-adsystem.com',
  'facebook.com/tr/', 'vk.com/rtrg', 'hotjar.com', 'clarity.ms', 'adsrvr.org'
];

browser.storage.local.get({ enabled: true }).then(value => { enabled = value.enabled !== false; }).catch(() => {});

browser.storage.onChanged.addListener(changes => {
  if (changes.enabled) enabled = changes.enabled.newValue !== false;
});

browser.webRequest.onBeforeRequest.addListener(
  details => {
    if (!enabled) return {};
    const url = String(details.url || '').toLowerCase();
    if (BLOCK_PATTERNS.some(pattern => url.includes(pattern))) return { cancel: true };
    return {};
  },
  { urls: ['<all_urls>'] },
  ['blocking']
);
