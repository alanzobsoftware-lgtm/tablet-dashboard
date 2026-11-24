/**
 * Google bridge for the tablet dashboard: calendar, private settings, stock quotes and a backup route for news feeds.
 *
 * Runs inside YOUR Google account (script.google.com). When you deploy it, Google asks you
 * to allow access to your calendars and to fetch web pages. After that, the dashboard can read
 * your upcoming events (including calendars shared with you) and stock prices, with no sign-in
 * on the tablet and no access that expires.
 *
 * Setup:
 * 1. Go to script.google.com and start a New project. Paste this whole file in.
 * 2. Change SECRET below to a long random phrase. It works like a password for this link.
 * 3. Project Settings (gear) > Time zone: your own (for example America/New_York).
 * 4. Deploy > New deployment > type "Web app".
 *      Execute as: Me
 *      Who has access: Anyone
 *    Click Deploy and approve the permissions Google shows you.
 *    (Google may warn that the app isn't verified. It's your own script, so choose
 *    Advanced > Go to project.)
 * 5. Copy the Web app URL and add  ?key=YOUR_SECRET  to the end of it.
 *    That full link is your "Google bridge link". Keep it private.
 * 6. Optional: Project Settings > Script properties > add DASH_CONFIG with your personal settings (see below).
 *
 * If you later paste in a newer version of this file: Deploy > Manage deployments > edit (pencil)
 * > Version: New version > Deploy. The link stays the same.
 */
const SECRET = 'change-me-to-a-long-random-phrase';

// Calendars shown on the tablet. Exact names; a trailing * matches any name starting with that text,
// e.g. ['Family', 'Youth Soccer*']. Leave the list empty to show every calendar.
// (The bridge's JSON lists every calendar name under "available"; add &all=1 to the link to see them all.)
const SHOW_CALENDARS = [];

function doGet(e) {
  const p = (e && e.parameter) || {};
  if (SECRET.indexOf('change-me') === 0) return json({ error: 'Set SECRET in the script first.' });
  if (p.key !== SECRET) return json({ error: 'unauthorized' });
  try {
    if (p.type === 'stocks') return json(stocks(p.symbols));
    if (p.type === 'feed') return json(feed(p.url));
    if (p.type === 'config') return json(privateConfig());
    return json(calendar(p));
  } catch (err) {
    return json({ error: String(err && err.message || err) });
  }
}

/* ---------- Calendar ---------- */
function calendar(p) {
  const days = Math.max(1, Math.min(31, Number(p.days) || 7));
  const from = new Date(); from.setHours(0, 0, 0, 0);
  const to = new Date(from.getTime()); to.setDate(to.getDate() + days);
  const only = String(p.calendars || '').split('|').map(s => s.trim()).filter(String);
  const want = p.all ? [] : (only.length ? only : SHOW_CALENDARS);
  const wanted = n => !want.length || want.some(w => w.slice(-1) === '*' ? n.indexOf(w.slice(0, -1)) === 0 : n === w);

  const out = { generated: new Date().toISOString(), calendars: [], available: [], events: [] };
  CalendarApp.getAllCalendars().forEach(cal => {
    if (cal.isHidden()) return;
    const name = cal.getName();
    out.available.push(name);
    if (!wanted(name)) return;
    const color = cal.getColor();
    out.calendars.push({ name: name, color: color });
    cal.getEvents(from, to).forEach(ev => {
      try { if (ev.getMyStatus() === CalendarApp.GuestStatus.NO) return; } catch (err) {}
      out.events.push({
        cal: name,
        color: color,
        title: ev.getTitle(),
        location: ev.getLocation(),
        allDay: ev.isAllDayEvent(),
        start: (ev.isAllDayEvent() ? ev.getAllDayStartDate() : ev.getStartTime()).toISOString(),
        end: (ev.isAllDayEvent() ? ev.getAllDayEndDate() : ev.getEndTime()).toISOString()
      });
    });
  });
  return out;
}

/* ---------- Private dashboard settings ----------
 * Your location, news sections, teams, stocks, family dates and stations live here instead of on the public site.
 * Project Settings (gear) > Script properties > DASH_CONFIG holds them as JSON, for example:
 *   {"location":{"name":"Home","lat":0,"lon":0,"units":"fahrenheit"},"stocks":["SPY"],
 *    "personalDates":[{"label":"Grandpa's birthday","date":"2027-06-15","yearly":true}]}
 * The tablet merges them over the public config.json. Editing the property takes effect right away.
 */
function privateConfig() {
  const raw = PropertiesService.getScriptProperties().getProperty('DASH_CONFIG');
  return { config: raw ? JSON.parse(raw) : {} };
}

/* ---------- Stocks: Yahoo Finance, falling back to Stooq; cached 2 minutes ---------- */
function stocks(symbolsParam) {
  const syms = String(symbolsParam || '').split(',').map(s => s.trim().toUpperCase()).filter(String).slice(0, 30);
  const cache = CacheService.getScriptCache();
  const key = 'q:' + syms.join(',');
  const hit = cache.get(key);
  if (hit) return JSON.parse(hit);

  const opts = { muteHttpExceptions: true, headers: { 'User-Agent': 'Mozilla/5.0' } };
  const res = UrlFetchApp.fetchAll(syms.map(s => Object.assign({
    url: 'https://query1.finance.yahoo.com/v8/finance/chart/' + encodeURIComponent(s) + '?range=1d&interval=1d'
  }, opts)));
  const quotes = syms.map((s, i) => {
    try {
      const m = JSON.parse(res[i].getContentText()).chart.result[0].meta;
      const prev = m.chartPreviousClose || m.previousClose;
      return { sym: s, price: m.regularMarketPrice, pct: prev ? (m.regularMarketPrice - prev) / prev * 100 : 0 };
    } catch (err) {
      return stooq(s);
    }
  }).filter(Boolean);

  const out = { generated: new Date().toISOString(), quotes: quotes };
  if (quotes.length) cache.put(key, JSON.stringify(out), 120);
  return out;
}

function stooq(sym) {
  try {
    const s = sym.indexOf('.') < 0 ? sym.toLowerCase() + '.us' : sym.toLowerCase();
    const csv = UrlFetchApp.fetch('https://stooq.com/q/l/?s=' + encodeURIComponent(s) + '&f=sd2t2ohlcp&h&e=csv', { muteHttpExceptions: true }).getContentText();
    const row = csv.trim().split('\n')[1].split(',');   // Symbol,Date,Time,Open,High,Low,Close,Prev
    const close = Number(row[6]), prev = Number(row[7]);
    if (!close) return null;
    return { sym: sym, price: close, pct: prev ? (close - prev) / prev * 100 : 0 };
  } catch (err) {
    return null;
  }
}

/* ---------- Feeds: fetches an RSS/Atom feed server-side; cached 10 minutes ---------- */
function feed(url) {
  if (!/^https?:\/\//i.test(String(url || ''))) throw new Error('bad url');
  const cache = CacheService.getScriptCache();
  const key = 'f:' + Utilities.base64EncodeWebSafe(Utilities.computeDigest(Utilities.DigestAlgorithm.MD5, url));
  const hit = cache.get(key);
  if (hit) return { body: hit };
  const r = UrlFetchApp.fetch(url, { muteHttpExceptions: true, followRedirects: true, headers: { 'User-Agent': 'Mozilla/5.0' } });
  if (r.getResponseCode() >= 400) throw new Error('HTTP ' + r.getResponseCode());
  const body = r.getContentText();
  if (body.length < 90000) cache.put(key, body, 600);
  return { body: body };
}

function json(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
