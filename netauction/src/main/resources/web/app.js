let sid = null, me = null, es = null;
const $ = id => document.getElementById(id);
const strip = r => r.replace(/^\d{3}\s+(SUCCESS|ERROR)\s*/, '');
const idr = n => 'Rp ' + Number(n).toLocaleString('id-ID');

async function api(path, body, method = 'POST') {
  const hint = '503 ERROR Gateway tidak ditemukan. Jalankan web.WebGateway, lalu buka http://localhost:8080 (jangan buka berkas index.html langsung).';
  try {
    const r = await fetch(path, { method, body });
    if (!(r.headers.get('content-type') || '').includes('application/json')) return { ok: false, reply: hint };
    return await r.json();
  } catch (e) {
    return { ok: false, reply: hint };
  }
}
const cmd = line => api('/api/cmd?sid=' + sid, line);

/* ---------- login ---------- */
$('loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  const name = $('user').value.trim();
  $('loginErr').textContent = '';
  if (!/^[A-Za-z0-9_]{3,15}$/.test(name)) { $('loginErr').textContent = 'Nama harus 3-15 karakter: huruf, angka, atau garis bawah.'; return; }
  const r = await api('/api/connect', name);
  if (!r.ok) { $('loginErr').textContent = strip(r.reply); return; }
  sid = r.sid; me = name;
  $('login').classList.add('hidden'); $('app').classList.remove('hidden');
  $('me').textContent = me;
  openEvents(); route(); refreshLots(); refreshUsers(); refreshFiles(); loadRate();
  setInterval(tickCds, 1000); setInterval(() => refreshLots(), 20000);
  addLog('Selamat datang, ' + me + '.', 'sys');
});

/* ---------- SSE ---------- */
function openEvents() {
  es = new EventSource('/api/events?sid=' + sid);
  es.onmessage = m => {
    const ev = JSON.parse(m.data);
    if (ev.t === 'ticker') { $('ticker').textContent = ev.d; }
    else if (ev.t === 'pong') { $('udpDot').classList.add('on'); $('udpTxt').textContent = 'UDP aktif'; }
    else if (ev.t === 'udp') { $('udpTxt').textContent = ev.d; }
    else if (ev.t === 'closed') { lost(); }
    else if (ev.t === 'async') onAsync(ev.d);
  };
  es.onerror = () => { if (sid) { $('tcpDot').classList.remove('on'); $('udpDot').classList.remove('on'); } };
}
function lost() {
  sid = null; if (es) es.close();
  $('tcpDot').classList.remove('on'); $('udpDot').classList.remove('on');
  addLog('Sesi berakhir. Muat ulang halaman untuk masuk kembali.', 'out');
}
function onAsync(t) {
  if (t.startsWith('OUTBID')) { addLog(t.slice(7), 'out'); toast(t.slice(7), 'out'); refreshLots(true); loadHistory(); }
  else if (t.startsWith('PRIVATE')) addLog('Pribadi dari ' + t.slice(8), 'pm');
  else if (t.startsWith('BID_UPDATE')) { addLog(t.slice(11), 'sys'); refreshLots(true); loadHistory(); }
  else if (t.startsWith('NEW_LOT')) { addLog(t.slice(8), 'sys'); refreshLots(); }
  else if (t.startsWith('LOT_REMOVED')) { addLog(t.slice(12), 'sys'); refreshLots(); }
  else if (t.startsWith('FILE_REMOVED')) { addLog(t.slice(13), 'sys'); refreshFiles(); }
  else if (t.startsWith('AUCTION_START')) { addLog(t.slice(14), 'sys'); refreshLots(true); }
  else if (t.startsWith('AUCTION_END')) { addLog(t.slice(12), 'sys'); refreshLots(true); loadHistory(); }
  else if (/^(WINNER|SOLD|UNSOLD)/.test(t)) {
    const msg = t.replace(/^(WINNER|SOLD|UNSOLD)\s*/, '');
    addLog(msg, 'win'); toast(msg, t.startsWith('UNSOLD') ? '' : 'win'); refreshLots(true);
  }
  else if (t.startsWith('BROADCAST')) addLog(t.slice(10));
  else { addLog(t.replace(/^SYSTEM:\s*/, ''), 'sys'); refreshUsers(); }
}
function toast(text, kind) {
  let box = $('toasts');
  if (!box) { box = document.createElement('div'); box.id = 'toasts'; box.setAttribute('aria-live', 'assertive'); document.body.append(box); }
  const t = document.createElement('div'); t.className = 'toast ' + (kind || ''); t.textContent = text; box.append(t);
  setTimeout(() => t.remove(), 9000);
}
function addLog(text, cls) {
  const li = document.createElement('li'); li.textContent = text; if (cls) li.className = cls;
  const log = $('log'); log.appendChild(li); log.scrollTop = log.scrollHeight;
  while (log.children.length > 200) log.removeChild(log.firstChild);
}

/* ---------- lelang ---------- */
const CAT = { smartphone: 'Smartphone', pclaptop: 'PC & Laptop', aksesoris: 'Aksesoris', lainnya: 'Lainnya' };
const STATUS = { running: 'Berjalan', pending: 'Menunggu', ended: 'Selesai' };
let lots = [], cat = 'all', stf = 'all', fetchedAt = 0, prev = {}, curId = null;

const parseLot = row => {
  const f = row.split(';'), bid = +f[2];
  return { id: f[0], name: f[1], bid, who: f[3], usd: f[4], file: f[5], seller: f[6], category: f[7] || 'lainnya',
           status: f[8] || 'running', startsIn: +f[9] || 0, endsIn: +f[10] || 0, inc: +f[11] || 10000,
           start: +f[12] || bid, n: +f[13] || 0, minBid: +f[14] || bid };
};
/* Sisa waktu dihitung dari detik kiriman server + waktu lokal yang berlalu (tidak bergantung jam komputer) */
const remaining = sec => Math.max(0, Math.ceil(sec - (performance.now() - fetchedAt) / 1000));
const clock = s => {
  const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60, p = n => String(n).padStart(2, '0');
  return h ? h + ':' + p(m) + ':' + p(x) : p(m) + ':' + p(x);
};
const curLot = () => lots.find(l => l.id === curId);
const digits = v => String(v).replace(/\D/g, '');

async function refreshLots(flash) {
  if (!sid) return;
  const r = await cmd('LIST');
  if (!r.ok) { $('lots').textContent = strip(r.reply); return; }
  lots = strip(r.reply).split('#').filter(Boolean).map(parseLot);
  fetchedAt = performance.now();
  renderLots(flash); renderFiles(); if (curId) updateLotView();
}

function markActive(groupId, b) {
  document.querySelectorAll('#' + groupId + ' .filter').forEach(x => { const on = x === b; x.classList.toggle('active', on); x.setAttribute('aria-pressed', on); });
}
$('filters').addEventListener('click', e => { const b = e.target.closest('.filter'); if (!b) return; cat = b.dataset.cat; markActive('filters', b); renderLots(); });
$('statusFilters').addEventListener('click', e => { const b = e.target.closest('.filter'); if (!b) return; stf = b.dataset.st; markActive('statusFilters', b); renderLots(); });

function tickCds() {
  document.querySelectorAll('[data-kind]').forEach(el => {
    const sec = remaining(+el.dataset.sec);
    el.textContent = (el.dataset.prefix || '') + clock(sec);
    if (sec <= 0 && !el.dataset.fired) { el.dataset.fired = '1'; setTimeout(() => refreshLots(true), 800); }
  });
}
function cdEl(l, prefixes) {
  const el = document.createElement('span'); el.className = 'cd';
  if (l.status === 'ended') { el.textContent = l.who === '-' ? 'Selesai, tidak terjual' : 'Pemenang: ' + (l.who === me ? 'Anda' : l.who); return el; }
  el.dataset.kind = l.status; el.dataset.sec = l.status === 'pending' ? l.startsIn : l.endsIn;
  el.dataset.prefix = l.status === 'pending' ? prefixes[0] : prefixes[1];
  el.textContent = el.dataset.prefix + clock(remaining(+el.dataset.sec));
  return el;
}
function badge(cls, text) { const b = document.createElement('span'); b.className = 'badge ' + cls; b.textContent = text; return b; }

function renderLots(flash) {
  document.querySelectorAll('#filters .filter').forEach(b => { const c = b.dataset.cat;
    b.querySelector('span').textContent = lots.filter(l => (stf === 'all' || l.status === stf) && (c === 'all' || l.category === c)).length; });
  document.querySelectorAll('#statusFilters .filter').forEach(b => { const s = b.dataset.st;
    b.querySelector('span').textContent = lots.filter(l => (cat === 'all' || l.category === cat) && (s === 'all' || l.status === s)).length; });
  const rank = { running: 0, pending: 1, ended: 2 };
  const shown = lots.filter(l => (cat === 'all' || l.category === cat) && (stf === 'all' || l.status === stf))
    .sort((a, b) => rank[a.status] - rank[b.status] ||
      (a.status === 'ended' ? b.id - a.id : a.status === 'pending' ? a.startsIn - b.startsIn : a.endsIn - b.endsIn));
  const wrap = $('lots'); wrap.replaceChildren();
  if (!shown.length) {
    const e = document.createElement('div'); e.className = 'empty-state';
    e.textContent = 'Belum ada barang' + (cat === 'all' ? '' : ' di kategori ' + CAT[cat]) + (stf === 'all' ? '.' : ' dengan status ' + STATUS[stf].toLowerCase() + '.');
    wrap.append(e); return;
  }
  shown.forEach(l => {
    const lot = document.createElement('div'); lot.className = 'lot' + (l.status === 'ended' ? ' ended' : '');
    const left = document.createElement('div');
    const h = document.createElement('h3'); h.textContent = l.name;
    const meta = document.createElement('div'); meta.className = 'meta';
    meta.append(badge('cat', CAT[l.category] || 'Lainnya'), badge('st-' + l.status, STATUS[l.status]),
      'Barang #' + l.id + ' - dijual oleh ' + (l.seller === me ? 'Anda' : l.seller));
    left.append(h, meta);
    const right = document.createElement('div');
    const price = document.createElement('div'); price.className = 'price'; price.textContent = idr(l.bid);
    if (flash && prev[l.id] && prev[l.id] !== l.bid) { price.classList.add('flash'); setTimeout(() => price.classList.remove('flash'), 1200); }
    prev[l.id] = l.bid;
    const w = document.createElement('div'); w.className = 'who' + (l.who === me ? ' me' : '');
    w.textContent = l.who === '-' ? 'Belum ada penawar' : (l.who === me ? 'Anda penawar tertinggi' : 'Tertinggi: ' + l.who);
    right.append(price, w);
    const foot = document.createElement('div'); foot.className = 'bid';
    const link = document.createElement('a'); link.className = 'btn' + (l.status === 'ended' ? ' ghost' : ''); link.href = '#/barang/' + l.id;
    link.textContent = l.status === 'ended' ? 'Lihat hasil' : (l.seller === me ? 'Kelola barang' : 'Buka lelang') + ' \u2192';
    foot.append(cdEl(l, ['Mulai dalam ', 'Berakhir dalam ']), link);
    lot.append(left, right, foot); wrap.append(lot);
  });
}

/* ---------- halaman (routing berbasis #hash) ---------- */
function route() {
  if (!sid) return;
  const h = location.hash, m = h.match(/^#\/barang\/(\d+)$/);
  $('viewList').classList.toggle('hidden', !!m || h === '#/jual');
  $('viewLot').classList.toggle('hidden', !m);
  $('viewSell').classList.toggle('hidden', h !== '#/jual');
  curId = null;
  if (m) openLot(m[1]); else { $('viewLot').replaceChildren(); if (h === '#/jual') updateSummary(); }
  window.scrollTo({ top: 0 });
}
window.addEventListener('hashchange', route);

/* ---------- halaman detail penawaran ---------- */
const LOT_TEMPLATE = `
  <a class="back-link" href="#/">&larr; Kembali ke daftar barang</a>
  <div class="section-heading">
    <div><p class="eyebrow" id="dCat"></p><h1 id="dName"></h1><p class="section-intro" id="dMeta"></p></div>
    <span class="badge" id="dBadge"></span>
  </div>
  <div class="detail-grid">
    <div class="bid-hero">
      <p class="hero-label">Penawaran tertinggi saat ini</p>
      <div class="hero-price" id="dPrice"></div>
      <p class="hero-who" id="dWho"></p>
      <div class="hero-foot"><span>Harga awal</span><b id="dStart"></b></div>
    </div>
    <div class="bid-box">
      <p class="timer-label" id="dTimerLbl"></p>
      <div class="timer" id="dTimer"></div>
      <div id="dBidArea">
        <label class="field-label" for="dAmt">Penawaran Anda</label>
        <div class="amount-row"><span class="prefix">Rp</span><input id="dAmt" inputmode="numeric" autocomplete="off"></div>
        <div class="stepper">
          <button type="button" class="danger" data-step="-10" aria-label="Kurangi 10 kelipatan">-10x</button>
          <button type="button" class="danger" data-step="-1" aria-label="Kurangi satu kelipatan">&minus;</button>
          <button type="button" data-step="1" aria-label="Tambah satu kelipatan">+</button>
          <button type="button" data-step="10" aria-label="Tambah 10 kelipatan">+10x</button>
        </div>
        <p class="step-note" id="dStep"></p>
        <button type="button" id="dSend" class="button-wide">Kirim penawaran <span>&rarr;</span></button>
      </div>
      <div class="msg" id="dMsg"></div>
    </div>
  </div>
  <div class="detail-extra">
    <div><h2>Riwayat tawaran</h2><ol class="history" id="dHist"></ol></div>
    <div class="detail-actions" id="dActions"></div>
  </div>`;

function openLot(id) {
  curId = id; $('viewLot').innerHTML = LOT_TEMPLATE;
  $('dAmt').addEventListener('input', () => { $('dAmt').value = digits($('dAmt').value); $('dAmt').dataset.touched = '1'; });
  $('dAmt').addEventListener('blur', () => { const v = digits($('dAmt').value); $('dAmt').value = v ? Number(v).toLocaleString('id-ID') : ''; });
  $('dAmt').addEventListener('keydown', e => { if (e.key === 'Enter') sendBid(); });
  $('viewLot').querySelectorAll('[data-step]').forEach(b => b.addEventListener('click', () => adjust(+b.dataset.step)));
  $('dSend').addEventListener('click', sendBid);
  updateLotView(); loadHistory();
}
function adjust(n) {
  const l = curLot(); if (!l) return;
  const cur = Number(digits($('dAmt').value)) || l.minBid;
  $('dAmt').value = Math.max(l.minBid, cur + n * l.inc).toLocaleString('id-ID'); $('dAmt').dataset.touched = '1';
}
function updateLotView() {
  const l = curLot(), v = $('viewLot'); if (!v || !$('dName')) return;
  if (!l) { v.querySelector('.detail-grid').replaceChildren(Object.assign(document.createElement('div'), { className: 'empty-state', textContent: lots.length ? 'Barang tidak ditemukan atau sudah dihapus.' : 'Memuat...' })); return; }
  $('dCat').textContent = (CAT[l.category] || 'Lainnya').toUpperCase();
  $('dName').textContent = l.name;
  $('dMeta').textContent = 'Barang #' + l.id + ' - dijual oleh ' + (l.seller === me ? 'Anda' : l.seller) + (l.usd && l.usd !== '-' ? ' - setara US$ ' + l.usd : '');
  const b = $('dBadge'); b.className = 'badge st-' + l.status; b.textContent = STATUS[l.status];
  $('dPrice').textContent = idr(l.bid); $('dStart').textContent = idr(l.start);
  $('dWho').textContent = l.status === 'ended' ? (l.who === '-' ? 'Tidak terjual (tanpa penawaran)' : 'Pemenang: ' + (l.who === me ? 'Anda' : l.who))
    : l.who === '-' ? 'Belum ada penawar' : (l.who === me ? 'Anda penawar tertinggi' : 'Tertinggi: ' + l.who);
  // timer
  const t = $('dTimer'); t.className = 'timer'; delete t.dataset.fired;
  if (l.status === 'ended') { $('dTimerLbl').textContent = 'Lelang telah berakhir'; t.removeAttribute('data-kind'); t.textContent = '00:00'; }
  else { $('dTimerLbl').textContent = l.status === 'pending' ? 'Lelang dimulai dalam' : 'Lelang berakhir dalam';
    t.dataset.kind = l.status; t.dataset.sec = l.status === 'pending' ? l.startsIn : l.endsIn; t.textContent = clock(remaining(+t.dataset.sec)); }
  // area penawaran
  const mine = l.seller === me, area = $('dBidArea'), amt = $('dAmt');
  area.classList.toggle('hidden', l.status === 'ended' || mine);
  if (mine && l.status !== 'ended') $('dMsg').textContent = 'Barang Anda. Penjual tidak dapat menawar barangnya sendiri.';
  const closed = l.status !== 'running';
  area.querySelectorAll('button, input').forEach(x => { x.disabled = closed; });
  $('dStep').textContent = closed ? 'Penawaran dibuka saat lelang dimulai.' : 'Kelipatan ' + idr(l.inc) + ' - minimal ' + idr(l.minBid);
  const cur = Number(digits(amt.value));
  if (!amt.dataset.touched || cur < l.minBid) { amt.value = l.minBid.toLocaleString('id-ID'); delete amt.dataset.touched; }
  // aksi
  const act = $('dActions'); act.replaceChildren();
  if (l.file && l.file !== '-' && l.file !== 'null') {
    const a = document.createElement('a'); a.className = 'btn ghost'; a.textContent = 'Unduh katalog'; a.href = '/api/download?sid=' + sid + '&name=' + encodeURIComponent(l.file); act.append(a);
  }
  if (mine && l.who === '-') {
    const del = document.createElement('button'); del.type = 'button'; del.className = 'danger'; del.textContent = 'Hapus barang';
    del.addEventListener('click', async () => {
      if (!confirm('Hapus barang "' + l.name + '" dari lelang?')) return;
      del.disabled = true;
      const res = await cmd('DELLOT ' + l.id);
      if (res.ok) { addLog('Anda menghapus barang #' + l.id + ' (' + l.name + ').', 'sys'); location.hash = '#/'; refreshLots(); refreshFiles(); }
      else { $('dMsg').className = 'msg bad'; $('dMsg').textContent = strip(res.reply); del.disabled = false; }
    });
    act.append(del);
  }
}
async function sendBid() {
  const l = curLot(); if (!l) return;
  const v = digits($('dAmt').value), msg = $('dMsg');
  if (!v) { msg.className = 'msg bad'; msg.textContent = 'Isi nominal tawaran dalam angka.'; return; }
  $('dSend').disabled = true;
  const res = await cmd('BID ' + l.id + ' ' + v);
  msg.className = 'msg ' + (res.ok ? 'good' : 'bad'); msg.textContent = strip(res.reply);
  if (res.ok) delete $('dAmt').dataset.touched;
  await refreshLots(true); loadHistory();
}
async function loadHistory() {
  if (!curId || !sid) return;
  const r = await cmd('HISTORY ' + curId), ol = $('dHist'); if (!ol) return;
  ol.replaceChildren();
  const rows = r.ok ? strip(r.reply).split('#').filter(Boolean) : [];
  if (!rows.length) { const li = document.createElement('li'); li.className = 'empty'; li.textContent = 'Belum ada penawaran.'; ol.append(li); return; }
  rows.forEach(x => {
    const [who, amt, ago] = x.split(';'), li = document.createElement('li');
    const a = document.createElement('b'); a.textContent = idr(amt);
    const w = document.createElement('span'); w.textContent = (who === me ? 'Anda' : who) + ' - ' + (+ago < 60 ? ago + ' dtk lalu' : Math.floor(ago / 60) + ' mnt lalu');
    li.append(a, w); ol.append(li);
  });
}

/* ---------- chat & peserta ---------- */
$('chatMode').addEventListener('change', () => {
  const priv = $('chatMode').value === 'private';
  $('chatTo').classList.toggle('hidden', !priv);
  $('chatTarget').classList.toggle('private', priv);
  if (priv) { updateRecipients(); $('chatTo').focus(); }
});

$('chatForm').addEventListener('submit', async e => {
  e.preventDefault();
  const t = $('chatIn').value.trim(); if (!t || !sid) return;
  const priv = $('chatMode').value === 'private', to = $('chatTo').value;
  if (priv && !to) { addLog('Pilih peserta penerima pesan pribadi terlebih dahulu.', 'out'); return; }
  const r = await cmd(priv ? 'PRIVATE ' + to + ' ' + t : 'BROADCAST ' + t);
  if (r.ok) { addLog('Anda' + (priv ? ' ke ' + to : ' (semua)') + ': ' + t, priv ? 'pm' : ''); $('chatIn').value = ''; }
  else { addLog(strip(r.reply), 'out'); if (priv) refreshUsers(); }
});

/* ---------- peserta aktif ---------- */
let users = [];
/** Membaca balasan USERS, mis. "Pengguna aktif (2): alice, bobby", menjadi daftar nama. */
function parseUsers(text) {
  const m = text.match(/\):\s*(.*)$/);
  return m ? m[1].split(',').map(x => x.trim()).filter(Boolean) : [];
}
function updateRecipients() {
  const sel = $('chatTo'), keep = sel.value; sel.replaceChildren();
  const others = users.filter(u => u !== me);
  if (!others.length) {
    const o = document.createElement('option'); o.value = ''; o.textContent = 'Belum ada peserta lain'; sel.append(o); return;
  }
  const ph = document.createElement('option'); ph.value = ''; ph.textContent = 'Pilih penerima'; sel.append(ph);
  others.forEach(u => { const o = document.createElement('option'); o.value = o.textContent = u; sel.append(o); });
  sel.value = others.includes(keep) ? keep : '';
}
async function refreshUsers() {
  if (!sid) return;
  const r = await cmd('USERS');
  const box = $('users'); box.replaceChildren();
  if (!r.ok) { box.textContent = strip(r.reply); return; }
  users = parseUsers(strip(r.reply));
  const count = document.createElement('p'); count.className = 'user-count';
  count.textContent = users.length + ' peserta sedang online';
  const list = document.createElement('div'); list.className = 'user-list';
  users.forEach(u => {
    const c = document.createElement('span'); c.className = 'user-chip' + (u === me ? ' me' : '');
    c.textContent = u === me ? u + ' (Anda)' : u; list.append(c);
  });
  box.append(count, list);
  updateRecipients();
}

/* ---------- berkas ---------- */
let fileRows = [];
async function refreshFiles() {
  const r = await cmd('CATALOG');
  if (!r.ok) { fileRows = []; const ul = $('files'); ul.replaceChildren(); const li = document.createElement('li'); li.textContent = strip(r.reply); ul.append(li); return; }
  fileRows = strip(r.reply).split('#').filter(Boolean).map(x => { const [name, size, owner] = x.split(';'); return { name, size, owner: owner || '-' }; });
  renderFiles();
}
function renderFiles() {
  const ul = $('files'); ul.replaceChildren();
  const sel = $('sFile'); const keep = sel.value; sel.length = 1;
  const used = {}; lots.forEach(l => { if (l.file && l.file !== '-' && l.status !== 'ended') used[l.file] = l.id; });
  fileRows.forEach(({ name, size, owner }) => {
    if (/\.(png|jpe?g|pdf)$/i.test(name)) { const o = document.createElement('option'); o.value = o.textContent = name; sel.append(o); }
    const li = document.createElement('li'); const a = document.createElement('a');
    a.textContent = name; a.href = '/api/download?sid=' + sid + '&name=' + encodeURIComponent(name);
    const meta = document.createElement('span'); meta.className = 'file-meta';
    const s = document.createElement('small'); s.textContent = (size / 1024).toFixed(1) + ' KB - ' + (owner === me ? 'Anda' : owner === '-' ? 'tanpa pemilik' : owner);
    meta.append(s);
    if (owner === me) {
      const del = document.createElement('button'); del.type = 'button'; del.className = 'danger'; del.textContent = 'Hapus';
      if (used[name]) { del.disabled = true; del.title = 'Dipakai barang #' + used[name] + '. Hapus barangnya dulu.'; }
      del.addEventListener('click', async () => {
        if (!confirm('Hapus berkas "' + name + '" dari katalog?')) return;
        del.disabled = true;
        const res = await cmd('DELFILE ' + name);
        $('fileMsg').className = 'msg ' + (res.ok ? 'good' : 'bad'); $('fileMsg').textContent = strip(res.reply);
        refreshFiles();
      });
      meta.append(del);
    }
    li.append(a, meta); ul.append(li);
  });
  sel.value = keep;
}
$('upFile').addEventListener('change', async e => {
  const f = e.target.files[0]; if (!f) return;
  const bad = !/\.(png|jpe?g|pdf)$/i.test(f.name) ? 'Hanya berkas PNG, JPG, atau PDF yang boleh diunggah.'
            : f.size > 5 * 1024 * 1024 ? 'Ukuran berkas melebihi batas 5 MB.' : '';
  if (bad) { $('fileMsg').className = 'msg bad'; $('fileMsg').textContent = bad; e.target.value = ''; return; }
  $('fileMsg').className = 'msg'; $('fileMsg').textContent = 'Mengunggah ' + f.name + '...';
  const r = await api('/api/upload?sid=' + sid + '&name=' + encodeURIComponent(f.name), f);
  $('fileMsg').className = 'msg ' + (r.ok ? 'good' : 'bad'); $('fileMsg').textContent = strip(r.reply);
  e.target.value = ''; if (r.ok) refreshFiles();
});

/* ---------- ajukan barang (halaman terpisah) ---------- */
const hhmm = d => d.toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit' });
function updateSummary() {
  const s = +$('sStart').value, d = +$('sDur').value, t0 = new Date(Date.now() + s * 60000), t1 = new Date(t0.getTime() + d * 60000);
  $('sSummary').textContent = (s === 0 ? 'Mulai sekarang' : 'Mulai pukul ' + hhmm(t0) + ' (' + s + ' menit lagi)') + ', berakhir pukul ' + hhmm(t1) +
    ' (durasi ' + d + ' menit). Jam hanya perkiraan; waktu pasti mengikuti jam server. Tawaran di 30 detik terakhir memperpanjang waktu 30 detik.';
}
$('sStart').addEventListener('change', updateSummary); $('sDur').addEventListener('change', updateSummary);
$('sellForm').addEventListener('submit', async e => {
  e.preventDefault();
  const name = $('sName').value.trim().replace(/\s+/g, ' '), price = digits($('sPrice').value), file = $('sFile').value, c = $('sCat').value;
  const out = $('sMsg');
  if (!c) { out.className = 'msg bad'; out.textContent = 'Pilih kategori barang terlebih dahulu.'; return; }
  const r = await cmd('SELL ' + name + ' ' + price + (file ? ' ' + file : '') + ' @' + c + ' +S' + $('sStart').value + ' +D' + $('sDur').value);
  out.className = 'msg ' + (r.ok ? 'good' : 'bad'); out.textContent = strip(r.reply);
  if (r.ok) {
    const id = (strip(r.reply).match(/#(\d+)/) || [])[1];
    $('sName').value = ''; $('sPrice').value = ''; $('sCat').value = ''; out.textContent = '';
    addLog(strip(r.reply), 'sys'); await refreshLots();
    location.hash = id ? '#/barang/' + id : '#/';
  }
});

/* ---------- kurs ---------- */
const CURRENCIES = [
  ['IDR','Rupiah Indonesia'],['USD','Dolar Amerika Serikat'],['EUR','Euro'],['SGD','Dolar Singapura'],['MYR','Ringgit Malaysia'],
  ['JPY','Yen Jepang'],['GBP','Pound Inggris'],['AUD','Dolar Australia'],['CNY','Yuan Tiongkok'],['HKD','Dolar Hong Kong'],
  ['KRW','Won Korea Selatan'],['THB','Baht Thailand'],['SAR','Riyal Arab Saudi'],['AED','Dirham Uni Emirat Arab'],['INR','Rupee India'],
  ['CHF','Franc Swiss'],['CAD','Dolar Kanada'],['NZD','Dolar Selandia Baru'],['PHP','Peso Filipina'],['VND','Dong Vietnam'],
  ['BND','Dolar Brunei'],['TWD','Dolar Taiwan Baru'],['TRY','Lira Turki'],['RUB','Rubel Rusia'],['BRL','Real Brasil'],
  ['ZAR','Rand Afrika Selatan'],['EGP','Pound Mesir'],['QAR','Riyal Qatar'],['KWD','Dinar Kuwait'],['PKR','Rupee Pakistan'],
  ['BDT','Taka Bangladesh'],['SEK','Krona Swedia'],['NOK','Krone Norwegia'],['DKK','Krone Denmark']
];
['cFrom','cTo'].forEach((id, k) => {
  const sel = $(id);
  CURRENCIES.forEach(([c, n]) => { const o = document.createElement('option'); o.value = c; o.textContent = c + ' - ' + n; sel.append(o); });
  sel.value = k === 0 ? 'USD' : 'IDR';
});
async function loadRate() {
  const r = await cmd('CONVERT USD IDR 1');
  const v = parseFloat((strip(r.reply).split('=')[1] || '').trim());
  $('rate').textContent = r.ok && isFinite(v) ? idr(Math.round(v)) : 'tidak tersedia';
}
$('cGo').addEventListener('click', async () => {
  const amt = $('cAmt').value.trim().replace(',', '.');
  if (!/^\d{1,12}(\.\d{1,2})?$/.test(amt)) { $('cOut').className = 'msg bad'; $('cOut').textContent = 'Jumlah harus berupa angka (maksimal 2 desimal, pakai titik).'; return; }
  const r = await cmd('CONVERT ' + $('cFrom').value + ' ' + $('cTo').value + ' ' + amt);
  $('cOut').className = 'msg ' + (r.ok ? 'good' : 'bad'); $('cOut').textContent = strip(r.reply);
});

$('exit').addEventListener('click', async () => {
  if (sid) await api('/api/exit?sid=' + sid, '');
  location.hash = '';
  location.reload();
});
