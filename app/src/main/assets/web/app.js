/* =============================================================================
   AndroidSpect - browser-first dashboard controller
   - Sidebar app picker (always visible)
   - Tab routing in main area
   - Keyboard: 1-9 jump tabs, / focus search, T toggle theme
   ============================================================================= */
(() => {

const $  = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

const fmt = {
    bytes(b) {
        if (b == null || b < 0) return '-';
        const u = ['B','KB','MB','GB','TB']; let i = 0, v = b;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return `${v < 10 && i ? v.toFixed(1) : Math.round(v)} ${u[i]}`;
    },
    esc(s) { return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
};

// ============== Auth ==============
// Password + session cookie. The cookie is HttpOnly so JS never sees it -
// the browser ships it automatically with every request.

async function explainError(r) {
    let body = '';
    try { body = await r.text(); } catch (_) {}
    let msg = `HTTP ${r.status}`;
    if (body) {
        try { const j = JSON.parse(body); if (j.error) msg = j.error; } catch (_) { msg += ` ${body.slice(0, 160)}`; }
    }
    const e = new Error(msg); e.status = r.status; return e;
}

async function fetchAuthed(url, init = {}) {
    const merged = Object.assign({ credentials: 'same-origin' }, init);
    let r = await fetch(url, merged);
    if (r.status === 401) {
        // Session expired or never authed. Show login overlay; on success retry once.
        if (await showLoginOverlay()) {
            r = await fetch(url, merged);
        }
    }
    return r;
}

const api = {
    async get(url, opts) {
        const r = await fetchAuthed(url, opts);
        if (!r.ok) throw await explainError(r);
        const ct = r.headers.get('content-type') || '';
        return ct.includes('json') ? r.json() : r.text();
    },
    async post(url, body, opts) {
        const r = await fetchAuthed(url, Object.assign({
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: body ? JSON.stringify(body) : undefined
        }, opts || {}));
        if (!r.ok) throw await explainError(r);
        return r.json();
    },
    async put(url, body, opts) {
        const r = await fetchAuthed(url, Object.assign({
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: body ? JSON.stringify(body) : undefined
        }, opts || {}));
        if (!r.ok) throw await explainError(r);
        return r.json();
    },
    async del(url, opts) {
        const r = await fetchAuthed(url, Object.assign({ method: 'DELETE' }, opts || {}));
        if (!r.ok) throw await explainError(r);
        return r.json();
    },
    async blob(url, opts) {
        const r = await fetchAuthed(url, opts);
        if (!r.ok) throw await explainError(r);
        return r.blob();
    }
};

// Render a modal login overlay. Returns a promise that resolves true on
// successful login.
//
// Concurrency: when several `api.*` calls fire in parallel and ALL get 401
// (the typical SPA-boot case), they each call showLoginOverlay(). The
// original code set the dedup token INSIDE the Promise executor where the
// outer `p` is still undefined - so every caller created its own overlay
// and 6 password fields ended up stacked in the DOM. Fix: build the
// promise + dedup token FIRST, append the overlay second, so a second
// caller sees the existing promise and bails.
function showLoginOverlay() {
    if (window.__androidspect_loginPromise) return window.__androidspect_loginPromise;

    let resolveOuter;
    const p = new Promise(resolve => { resolveOuter = resolve; });
    window.__androidspect_loginPromise = p;

    const veil = document.createElement('div');
    veil.className = 'login-veil';
    veil.innerHTML = `
        <form class="login-card" autocomplete="off">
            <div class="login-brand">
                <svg viewBox="0 0 64 64" width="22" height="22" fill="none" aria-hidden="true">
                    <rect x="12" y="12" width="40" height="40" rx="9"
                          stroke="currentColor" stroke-width="2.6" stroke-linejoin="round"/>
                    <circle cx="32" cy="32" r="9.5" fill="none" stroke="var(--accent)" stroke-width="2.8"/>
                    <circle cx="32" cy="32" r="3.2" fill="var(--accent)"/>
                </svg>
                <span><b>Android</b>Spect</span>
            </div>
            <h3>Sign in</h3>
            <p class="muted small">Enter the password shown in the AndroidSpect app on your phone.<br>
            The password can only be changed from inside the app.</p>
            <input type="password" id="login-pw" class="input mono" placeholder="password" autofocus required>
            <button type="submit" class="btn">Sign in</button>
            <div id="login-err" class="muted small" style="color:var(--red);min-height:14px"></div>
        </form>`;
    document.body.appendChild(veil);

    const form = veil.querySelector('form');
    const pwInput = veil.querySelector('#login-pw');
    const err = veil.querySelector('#login-err');
    form.addEventListener('submit', async e => {
        e.preventDefault();
        err.textContent = '';
        try {
            const r = await fetch('/api/auth/login', {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: pwInput.value })
            });
            if (r.ok) {
                veil.remove();
                window.__androidspect_loginPromise = null;
                resolveOuter(true);
            } else {
                err.textContent = r.status === 429
                    ? 'Too many attempts. Try again in a minute.'
                    : 'Wrong password.';
                pwInput.select();
            }
        } catch (ex) { err.textContent = ex.message; }
    });
    return p;
}

const S = {
    appList: [],
    pkg: null,            // currently selected package
    appInfo: null,        // currently selected app metadata
    tab: 'welcome',
    sqlitePath: null,
    filesRel: '',
    initialized: {},
    // Per-tab wiring guard. `S.initialized` gets cleared on every app switch
    // (so the tab refetches its data) - but the static DOM event listeners
    // must only be registered ONCE. Without this guard each app switch
    // accumulated another click listener on #files-list, so after two
    // switches a single click on a folder fired the handler twice and
    // produced paths like `databases/databases/code_cache/code_cache`
    // (the second handler read S.filesRel that the first had just
    // optimistically updated).
    wired: new Set(),
    // AbortControllers for the currently in-flight file/preview fetches so
    // that a fast follow-up click cancels the slow one in flight instead of
    // being dropped or stacking behind it (the old behaviour made the file
    // browser feel frozen).
    filesAbort: null,
    previewAbort: null,
    theme: localStorage.getItem('androidspect.theme') || 'dark'
};

/**
 * Run [fn] exactly once for [key]. Used inside each tab's init function to
 * register static DOM listeners (and any other one-shot setup) so the
 * data-refresh side of init can be called freely on every app switch
 * without leaking listeners.
 */
function once(key, fn) {
    if (S.wired.has(key)) return;
    S.wired.add(key);
    fn();
}

function toast(msg, kind = '') {
    const t = document.createElement('div');
    t.className = 'toast ' + kind;
    t.textContent = msg;
    $('#toast').appendChild(t);
    setTimeout(() => { t.style.opacity = 0; setTimeout(() => t.remove(), 300); }, 3500);
}

// ============== Theme ==============
function applyTheme(t) {
    document.documentElement.dataset.theme = t;
    S.theme = t;
    localStorage.setItem('androidspect.theme', t);
}
$('#themeToggle').onclick = () => applyTheme(S.theme === 'dark' ? 'light' : 'dark');
applyTheme(S.theme);

// ============== Tabs ==============
function showTab(name) {
    S.tab = name;
    $$('.panel').forEach(p => p.classList.toggle('hidden', p.dataset.tab !== name));
    $$('.tab').forEach(b => b.classList.toggle('active', b.dataset.tab === name));
    if (!S.initialized[name]) {
        try { initTab(name); S.initialized[name] = true; } catch (e) { console.error(e); }
    } else {
        try { refreshTab(name); } catch (e) { console.error(e); }
    }
}

// Tab clicks can come from EITHER the sidebar nav (LIVE/ACT) OR the
// horizontal #main-tabs strip (Files/Manifest/Components/Native). Delegate
// from document so both work without needing two listeners.
document.addEventListener('click', e => {
    const t = e.target.closest('.tab');
    if (t && t.dataset.tab) showTab(t.dataset.tab);
});

// welcome panel "jump to" links
document.addEventListener('click', e => {
    const j = e.target.closest('[data-tab-jump]');
    if (j) { e.preventDefault(); showTab(j.dataset.tabJump); }
});

// keyboard shortcuts
document.addEventListener('keydown', e => {
    if (e.target.matches('input, textarea, [contenteditable]')) {
        if (e.key === 'Escape') e.target.blur();
        return;
    }
    if (e.key === '/') { e.preventDefault(); $('#app-search').focus(); return; }
    if (e.key === 't' || e.key === 'T') { applyTheme(S.theme === 'dark' ? 'light' : 'dark'); return; }
    const tabs = ['files','prefs','sqlite','manifest','components','native','processes','net','logcat','shell'];
    const idx = parseInt(e.key, 10);
    if (idx >= 1 && idx <= 9 && tabs[idx - 1]) { showTab(tabs[idx - 1]); }
});

// ============== Header status pills ==============
async function refreshStatus() {
    try {
        const s = await api.get('/api/status');
        const rp = $('#root-pill');
        rp.classList.toggle('ok', !!s.rootAvailable);
        rp.classList.toggle('bad', !s.rootAvailable);
        // Don't render "root: root" - when shell IS root the prefix is
        // redundant. Only show the user name when it's something else.
        const label = !s.rootAvailable
            ? 'no su'
            : (s.shellUser && s.shellUser !== 'root' ? `root · ${s.shellUser}` : 'rooted');
        rp.innerHTML = `<span class="dot"></span>${fmt.esc(label)}`;
        $('#dev-pill').textContent = `${s.device.model} · API ${s.device.sdk}`;
    } catch (e) {
        $('#root-pill').innerHTML = '<span class="dot"></span>offline';
        $('#root-pill').classList.add('bad');
        $('#dev-pill').textContent = '-';
    }
}
refreshStatus();
setInterval(refreshStatus, 5000);

// ============== Sidebar: app list ==============
// We always fetch the installed apps AND the running process list, then mark
// rows green if a process exists. That way a pentester can tell at a glance
// which targets are alive (worth auditing live state) vs dormant (cold storage).
const S_runningPkgs = new Set();

async function loadRunningPkgs() {
    try {
        const r = await api.get('/api/live/processes');
        S_runningPkgs.clear();
        (r.processes || []).forEach(p => (p.packages || []).forEach(pk => S_runningPkgs.add(pk)));
    } catch (_) { /* leave whatever we had before */ }
}

async function loadApps() {
    const q = $('#app-search').value.trim().toLowerCase();
    const sys = $('#app-system').checked ? '1' : '0';
    const onlyRunning = $('#app-running')?.checked === true;
    try {
        // Refresh running set in parallel with the app listing.
        const [apps, _] = await Promise.all([
            api.get(`/api/apps?system=${sys}` + (q ? `&q=${encodeURIComponent(q)}` : '')),
            loadRunningPkgs()
        ]);
        const filtered = onlyRunning ? apps.filter(a => S_runningPkgs.has(a.packageName)) : apps;
        S.appList = filtered;
        const runCount = apps.filter(a => S_runningPkgs.has(a.packageName)).length;
        $('#apps-count').textContent = `${filtered.length} app${filtered.length === 1 ? '' : 's'} · ${runCount} running`;
        renderAppList(filtered);
    } catch (e) { toast(e.message, 'err'); }
}

function renderAppList(apps) {
    const root = $('#app-list');
    if (!apps.length) {
        root.innerHTML = '<div class="empty small">no apps</div>';
        return;
    }
    root.innerHTML = apps.map(a => {
        const isRunning = S_runningPkgs.has(a.packageName);
        // Compact: name + tiny inline status chips on row 1, mono pkg name
        // on row 2. Verbose "v1.x · SDK X" badges go to a hover-only title
        // tooltip to keep the row to two lines.
        const title = `v${fmt.esc(a.versionName || a.versionCode)} · SDK ${a.targetSdk}${a.debuggable ? ' · debuggable' : ''}`;
        return `
        <div class="app-row${S.pkg === a.packageName ? ' active' : ''}" data-pkg="${fmt.esc(a.packageName)}" role="listitem" title="${title}">
            <div class="name">
                ${isRunning ? '<span class="run-dot" title="Process is running"></span>' : '<span class="run-dot off" title="Not running"></span>'}
                <span class="lbl">${fmt.esc(a.label)}</span>
                ${a.debuggable ? '<span class="tg warn">debug</span>' : ''}
                ${a.allowBackup ? '<span class="tg warn">backup</span>' : ''}
                ${a.cleartext ? '<span class="tg warn">cleartext</span>' : ''}
                <span class="tg">minSdk ${fmt.esc(a.minSdk)}</span>
            </div>
            <div class="pkg">${fmt.esc(a.packageName)}</div>
        </div>`;
    }).join('');
}

$('#app-list').addEventListener('click', e => {
    const row = e.target.closest('.app-row');
    if (!row) return;
    selectPkg(row.dataset.pkg);
});

function selectPkg(pkg) {
    S.pkg = pkg;
    S.appInfo = S.appList.find(a => a.packageName === pkg) || null;
    S.filesRel = '';
    S.sqlitePath = null;
    // every per-app tab needs a fresh load
    ['files','prefs','sqlite','manifest','components','native','code','deeplinks','snapshots','web','overlay','fridascripts','aichat','remoteconfig'].forEach(t => delete S.initialized[t]);
    renderAppList(S.appList);
    renderSelectedApp();
    if (S.tab === 'welcome' || ['processes','net','logcat','shell'].includes(S.tab)) {
        showTab('files');
    } else {
        showTab(S.tab); // re-render with new pkg
    }
}

function renderSelectedApp() {
    const el = $('#selected-app');
    const actionsBlock = $('#sb-actions');
    const inspectTabs  = $('#main-tabs');
    const notesRail    = $('#notes-rail');
    if (!S.appInfo) {
        el.innerHTML = '<span class="muted small">Pick an app on the left to start.</span>';
        // Hide everything that needs an app context: action icons in the
        // sidebar + the per-app inspection tabs above the panels.
        if (actionsBlock) actionsBlock.hidden = true;
        if (inspectTabs)  inspectTabs.hidden  = true;
        if (notesRail)    notesRail.hidden    = true;
        return;
    }
    const a = S.appInfo;
    el.innerHTML = `
        <span class="label">${fmt.esc(a.label)}</span>
        <span class="pkg">${fmt.esc(a.packageName)}</span>
        <span class="ver">v${fmt.esc(a.versionName)} · SDK ${a.targetSdk} · uid ${a.uid}${a.debuggable ? ' · debuggable' : ''}</span>
    `;
    if (actionsBlock) actionsBlock.hidden = false;
    if (inspectTabs)  inspectTabs.hidden  = false;
    if (notesRail) {
        notesRail.hidden = false;
        notesSetupRail();           // idempotent: wires handlers once
        notesLoad();                // load this package's notes
        if (notesPreview) notesRenderPreview();
    }
}

$('#app-search').addEventListener('input', debounce(loadApps, 200));
$('#app-system').onchange = loadApps;
$('#app-running')?.addEventListener('change', loadApps);
$('#app-refresh').onclick = loadApps;
// Periodically refresh the running set so the green dot reflects reality.
setInterval(() => { loadRunningPkgs().then(() => renderAppList(S.appList)); }, 8000);

// ============== App actions (sidebar grid) ==============
// Actions live in the sidebar now (under the selected app) but the handler
// stays event-delegated by [data-action] so the wiring is identical.
$('#sb-actions').addEventListener('click', async e => {
    const b = e.target.closest('[data-action]');
    if (!b || !S.pkg) { if (!S.pkg) toast('Pick an app first', 'err'); return; }
    const action = b.dataset.action;
    if (action === 'clear' && !confirm(`Wipe ALL data for ${S.pkg}? This calls "pm clear" and cannot be undone.`)) return;

    // Pull APK now triggers a direct browser download (base + every split),
    // streamed as a single ZIP. No detour to /sdcard.
    if (action === 'pull-apk') {
        const url = `/api/apps/${encodeURIComponent(S.pkg)}/apk`;
        const a = document.createElement('a');
        a.href = url;
        // Best-effort filename; server will override via Content-Disposition.
        a.download = `${S.pkg}.apks.zip`;
        document.body.appendChild(a); a.click(); a.remove();
        toast(`Downloading APK + splits for ${S.pkg} …`, 'ok');
        return;
    }

    try {
        const r = await api.post(`/api/apps/${encodeURIComponent(S.pkg)}/actions/${action}`);
        toast(`${action} → ${r.ok ? 'ok' : 'failed'}` + (r.output ? ` - ${String(r.output).slice(0, 100)}` : ''), r.ok ? 'ok' : 'err');
    } catch (e) { toast(e.message, 'err'); }
});

// ============== FILES tab ==============
const iconFor = e => {
    if (e.isDir) return '📁';
    switch (e.kind) {
        case 'sqlite': return '🗄'; case 'prefs': return '⚙';
        case 'xml': return '📜';   case 'json': return '{}';
        case 'image': return '🖼';  case 'text': return '📄';
        case 'html': return '🌐';  case 'code': return '<>';
        case 'native': return 'ʚ'; case 'apk': return '📦';
        default: return '·';
    }
};
async function loadFiles(rel = '') {
    if (!S.pkg) return;
    // Cancel any in-flight nav. The old "drop second click" guard made the
    // browser feel frozen - if the user clicked a folder while another nav
    // was loading, nothing visibly happened. Now the second click cancels
    // the first and starts a fresh fetch immediately.
    if (S.filesAbort) { try { S.filesAbort.abort(); } catch (_) {} }
    const ctrl = new AbortController();
    S.filesAbort = ctrl;

    const prevRel = S.filesRel;
    S.filesRel = rel;
    // Visual hint while the fetch is in flight - keeps the existing rows
    // visible (so the user knows the click landed) but dims them.
    const list = $('#files-list');
    if (list) list.classList.add('loading');
    // Clear stale preview when changing directory.
    if (rel !== prevRel) { const pv = $('#files-preview'); if (pv) pv.innerHTML = '<div class="empty">Select a file to preview.</div>'; }
    try {
        const data = await api.get(
            `/api/apps/${encodeURIComponent(S.pkg)}/files?path=${encodeURIComponent(rel)}`,
            { signal: ctrl.signal }
        );
        // Bail if a newer nav already kicked off - we don't want stale results
        // to overwrite the current list.
        if (S.filesAbort !== ctrl) return;
        renderCrumb(data.relative);
        if (!data.entries.length) { list.innerHTML = '<div class="empty small">empty</div>'; return; }
        list.innerHTML = data.entries.map(e => `
            <div class="row" data-name="${fmt.esc(e.name)}" data-dir="${e.isDir}" data-kind="${e.kind}">
                <span class="icon">${iconFor(e)}</span>
                <span class="name">${fmt.esc(e.name)}</span>
                <span class="meta">${e.isDir ? '' : fmt.bytes(e.size)}</span>
            </div>
        `).join('');
    } catch (e) {
        if (e.name === 'AbortError') return; // superseded by newer click
        // Roll back so a typo'd path doesn't poison subsequent navigations.
        S.filesRel = prevRel;
        if (list) list.innerHTML = `<div class="empty" style="color: var(--red)">${fmt.esc(e.message)}</div>`;
    } finally {
        if (S.filesAbort === ctrl) S.filesAbort = null;
        if (list) list.classList.remove('loading');
    }
}
function renderCrumb(rel) {
    const parts = rel.split('/').filter(Boolean);
    const html = [`<a data-path="">/data/data/${fmt.esc(S.pkg)}</a>`];
    let acc = '';
    parts.forEach(p => { acc = acc ? `${acc}/${p}` : p; html.push('<span class="sep">/</span>', `<a data-path="${fmt.esc(acc)}">${fmt.esc(p)}</a>`); });
    $('#files-crumb').innerHTML = html.join('');
}
async function previewFile(name, kind) {
    const rel = S.filesRel ? `${S.filesRel}/${name}` : name;
    const enc = encodeURIComponent;
    const base = `/api/apps/${enc(S.pkg)}/files`;
    const p = $('#files-preview');
    const dlUrl = `${base}/raw?path=${enc(rel)}&download=1`;
    const header = `
        <div class="toolbar" style="margin-bottom:8px">
            <span class="muted small mono">${fmt.esc(rel)}</span>
            <span class="grow"></span>
            <a class="btn ghost small" href="${dlUrl}" download="${fmt.esc(name)}" title="Download">⤓ Download</a>
        </div>`;
    p.innerHTML = header + '<div class="empty">loading…</div>';
    // Cancel a previous preview that's still streaming so rapid clicks
    // through a directory don't queue up.
    if (S.previewAbort) { try { S.previewAbort.abort(); } catch (_) {} }
    const ctrl = new AbortController();
    S.previewAbort = ctrl;
    const stale = () => S.previewAbort !== ctrl;
    try {
        if (kind === 'image') {
            const blob = await api.blob(`${base}/raw?path=${enc(rel)}`, { signal: ctrl.signal });
            if (stale()) return;
            p.innerHTML = header + `<img class="preview-img" src="${URL.createObjectURL(blob)}" alt="${fmt.esc(name)}"/>`;
        } else if (kind === 'sqlite') {
            // .db / .sqlite - used to auto-jump to the SQLite query interface,
            // which left the user stranded with no obvious way back. Now we
            // show a lightweight summary in the preview pane (table list +
            // row counts) plus a "🗄 Open in SQLite Browser" button that
            // explicitly hands off to the full query view.
            let summary;
            try {
                const data = await api.get(
                    `/api/apps/${enc(S.pkg)}/sqlite/tables?path=${enc(rel)}`,
                    { signal: ctrl.signal }
                );
                if (stale()) return;
                const tables = data.tables || [];
                summary = tables.length
                    ? `<div class="kv-list">${tables.slice(0, 50).map(t => `
                        <div class="kv"><div class="k mono">${fmt.esc(t.name)}</div><div class="v mono">${t.rowCount >= 0 ? t.rowCount + ' rows' : '?'}</div></div>
                       `).join('')}${tables.length > 50 ? `<div class="muted small">… and ${tables.length - 50} more</div>` : ''}</div>`
                    : '<div class="empty small">no tables</div>';
            } catch (e) {
                if (e.name === 'AbortError') return;
                summary = `<div class="empty" style="color: var(--red)">${fmt.esc(e.message)}</div>`;
            }
            const newHeader = `
                <div class="toolbar" style="margin-bottom:8px">
                    <span class="muted small mono">${fmt.esc(rel)}</span>
                    <span class="grow"></span>
                    <button class="btn small" id="open-sqlite-browser" title="Open the typed SQLite browser for this DB">🗄 Open in SQLite Browser</button>
                    <a class="btn ghost small" href="${dlUrl}" download="${fmt.esc(name)}" title="Download">⤓ Download</a>
                </div>`;
            p.innerHTML = newHeader + summary;
            const sb = $('#open-sqlite-browser');
            if (sb) sb.onclick = () => {
                $('#sqlite-path').value = rel;
                showTab('sqlite');
                openSqlite(rel);
            };
        } else if (['text','json','xml','prefs','html','code'].includes(kind)) {
            // SharedPreferences XML used to auto-jump to the Prefs editor -
            // surprising for a "click to view" gesture. Now we show the raw
            // XML like any other text file and offer a tiny header link to
            // open it in the editor when the user actually wants to change
            // keys. The server marks these as `xml` (not `prefs`), so we
            // detect by path prefix.
            const text = await api.get(`${base}/text?path=${enc(rel)}`, { signal: ctrl.signal });
            if (stale()) return;
            const isPrefs = kind === 'prefs' || (rel.startsWith('shared_prefs/') && rel.endsWith('.xml'));
            const editBtn = isPrefs
                ? `<button class="btn ghost small" id="open-prefs-editor" title="Open this XML in the typed Prefs editor">✎ Edit as Prefs</button>`
                : '';
            // Re-render the header with the optional Edit link.
            const newHeader = `
                <div class="toolbar" style="margin-bottom:8px">
                    <span class="muted small mono">${fmt.esc(rel)}</span>
                    <span class="grow"></span>
                    ${editBtn}
                    <a class="btn ghost small" href="${dlUrl}" download="${fmt.esc(name)}" title="Download">⤓ Download</a>
                </div>`;
            p.innerHTML = newHeader + `<pre class="code-block">${fmt.esc(text)}</pre>`;
            if (isPrefs) {
                const btn = $('#open-prefs-editor');
                if (btn) btn.onclick = async () => {
                    showTab('prefs');
                    if (typeof loadPrefs === 'function') await loadPrefs();
                };
            }
        } else {
            const text = await api.get(`${base}/hex?path=${enc(rel)}&limit=4096`, { signal: ctrl.signal });
            if (stale()) return;
            p.innerHTML = header + `<pre class="code-block">${fmt.esc(text)}</pre>`;
        }
    } catch (e) {
        if (e.name === 'AbortError') return;
        p.innerHTML = header + `<div class="empty" style="color: var(--red)">${fmt.esc(e.message)}</div>`;
    } finally {
        if (S.previewAbort === ctrl) S.previewAbort = null;
    }
}

// Trigger a download of the current dir as a ZIP. The server streams the zip,
// so for huge dirs the browser shows progress in its native download UI.
function downloadCurrentDirZip() {
    if (!S.pkg) { toast('Pick an app first', 'err'); return; }
    const url = `/api/apps/${encodeURIComponent(S.pkg)}/files/zip?path=${encodeURIComponent(S.filesRel)}`;
    // Force the browser to download via a transient anchor.
    const a = document.createElement('a');
    a.href = url;
    a.download = `${S.pkg}${S.filesRel ? '_' + S.filesRel.replace(/\//g, '_') : ''}.zip`;
    document.body.appendChild(a); a.click(); a.remove();
    toast(`Downloading ${a.download} …`, 'ok');
}

// Content-search the current dir recursively.
async function grepCurrentDir() {
    if (!S.pkg) { toast('Pick an app first', 'err'); return; }
    const q = $('#files-grep').value.trim();
    if (!q) { toast('Enter a pattern to grep', 'err'); $('#files-grep').focus(); return; }
    const p = $('#files-preview');
    p.innerHTML = `<div class="empty">searching for "${fmt.esc(q)}" under <code>${fmt.esc(S.filesRel || '/')}</code> …</div>`;
    try {
        const url = `/api/apps/${encodeURIComponent(S.pkg)}/files/grep?path=${encodeURIComponent(S.filesRel)}&q=${encodeURIComponent(q)}&limit=200`;
        const r = await api.get(url);
        if (!r.hits || !r.hits.length) {
            p.innerHTML = `<div class="empty">no matches for "${fmt.esc(q)}"</div>`;
            return;
        }
        p.innerHTML = `
            <div class="toolbar" style="margin-bottom:8px">
                <span class="muted small">${r.total} hit${r.total === 1 ? '' : 's'} for <code>${fmt.esc(q)}</code> under <code>${fmt.esc(r.root)}</code></span>
            </div>
            ${r.hits.map(h => `
                <div class="component-row" style="cursor:pointer" data-rel="${fmt.esc(h.relative)}">
                    <div class="component-name mono">${fmt.esc(h.relative)}</div>
                    <div class="component-filters mono" style="white-space:pre-wrap">${fmt.esc(h.sample)}</div>
                </div>
            `).join('')}
        `;
        p.addEventListener('click', e => {
            const row = e.target.closest('[data-rel]'); if (!row) return;
            const fullRel = row.dataset.rel;
            // Open the file content view via existing previewFile flow.
            const parts = fullRel.split('/');
            const name = parts.pop();
            S.filesRel = parts.join('/');
            // Determine kind from extension
            const ext = (name.split('.').pop() || '').toLowerCase();
            const kind = ['png','jpg','jpeg','webp','gif'].includes(ext) ? 'image'
                       : ['db','sqlite','sqlite3','db3'].includes(ext) ? 'sqlite'
                       : ['xml','json','txt','log','csv','html','htm','js','css','yml','yaml'].includes(ext) ? 'text'
                       : 'binary';
            loadFiles(S.filesRel).then(() => previewFile(name, kind));
        }, { once: true });
    } catch (e) {
        p.innerHTML = `<div class="empty" style="color: var(--red)">${fmt.esc(e.message)}</div>`;
    }
}
function initFiles() {
    once('files', () => {
        $('#files-list').addEventListener('click', e => {
            const r = e.target.closest('.row'); if (!r) return;
            $$('.row', $('#files-list')).forEach(x => x.classList.remove('active'));
            r.classList.add('active');
            if (r.dataset.dir === 'true') loadFiles(S.filesRel ? `${S.filesRel}/${r.dataset.name}` : r.dataset.name);
            else previewFile(r.dataset.name, r.dataset.kind);
        });
        $('#files-crumb').addEventListener('click', e => {
            const a = e.target.closest('a[data-path]'); if (a) loadFiles(a.dataset.path);
        });
        $('#files-up').onclick = () => { if (S.filesRel) loadFiles(S.filesRel.split('/').slice(0, -1).join('/')); };
        $('#files-zip').onclick = downloadCurrentDirZip;
        $('#files-grep-btn').onclick = grepCurrentDir;
        $('#files-grep').addEventListener('keydown', e => { if (e.key === 'Enter') grepCurrentDir(); });
    });
    loadFiles('');
}
function refreshFiles() { if (S.pkg) loadFiles(S.filesRel); }

// ============== PREFS tab ==============
async function loadPrefs() {
    if (!S.pkg) return;
    try {
        const data = await api.get(`/api/apps/${encodeURIComponent(S.pkg)}/prefs`);
        const root = $('#prefs-list');
        if (!data.buckets.length) { root.innerHTML = '<div class="empty">No shared_prefs found.</div>'; return; }
        root.innerHTML = data.buckets.map(b => `
            <div class="pref-bucket" data-bucket="${fmt.esc(b.name)}">
                <h3>
                    <span>${fmt.esc(b.name)}.xml <span class="muted small">· ${b.entries.length} entries</span></span>
                    <button class="btn ghost small" data-action="add-pref" title="Add a new key">＋ key</button>
                </h3>
                ${b.entries.map(e => prefRowHtml(b.name, e)).join('')}
            </div>
        `).join('');
    } catch (e) { toast(e.message, 'err'); }
}

function prefRowHtml(bucket, e) {
    const typeTag = e.type === 'STRING' ? '' : `<span class="tg cyan tiny">${e.type.toLowerCase()}</span>`;
    return `
        <div class="pref-row" data-bucket="${fmt.esc(bucket)}" data-key="${fmt.esc(e.key)}" data-type="${e.type}">
            <div class="key">${fmt.esc(e.key)} ${typeTag}</div>
            <div class="val" data-role="val-cell">
                <span class="val-text">${fmt.esc(e.value)}</span>
                <span class="pref-actions">
                    <button class="link-btn" data-action="edit-pref" title="Edit value (force-stops the app)">✎</button>
                    <button class="link-btn" data-action="delete-pref" title="Delete this key (force-stops the app)">🗑</button>
                </span>
            </div>
        </div>`;
}

function enterEdit(row) {
    const valCell = row.querySelector('[data-role="val-cell"]');
    const current = row.querySelector('.val-text').textContent;
    const type = row.dataset.type;
    let inputHtml;
    if (type === 'BOOLEAN') {
        inputHtml = `<select class="input mono small" data-role="val-input">
            <option value="true"${current === 'true' ? ' selected' : ''}>true</option>
            <option value="false"${current === 'false' ? ' selected' : ''}>false</option>
        </select>`;
    } else {
        const inputType = (type === 'INT' || type === 'LONG' || type === 'FLOAT') ? 'number' : 'text';
        inputHtml = `<input class="input mono" data-role="val-input" type="${inputType}" value="${fmt.esc(current)}">`;
    }
    valCell.innerHTML = `${inputHtml}
        <span class="pref-actions">
            <button class="link-btn" data-action="save-pref" title="Save (force-stops the app)">💾</button>
            <button class="link-btn" data-action="cancel-pref" title="Cancel">✕</button>
        </span>`;
    valCell.querySelector('[data-role="val-input"]').focus();
}

async function savePref(row) {
    const bucket = row.dataset.bucket;
    const key = row.dataset.key;
    const type = row.dataset.type;
    const input = row.querySelector('[data-role="val-input"]');
    if (!input) return;
    const value = input.value;
    try {
        const r = await api.post(`/api/apps/${encodeURIComponent(S.pkg)}/prefs/set`, {
            bucket, key, value, type, forceStop: true
        });
        if (!r.ok) throw new Error(r.error || 'write failed');
        toast(`${bucket}.xml · ${key} = ${value} (app force-stopped)`, 'ok');
        await loadPrefs();
    } catch (e) { toast(e.message, 'err'); }
}

async function deletePref(row) {
    const bucket = row.dataset.bucket;
    const key = row.dataset.key;
    if (!confirm(`Delete "${key}" from ${bucket}.xml?\n\nThe app will be force-stopped first so it picks up the change on next launch.`)) return;
    try {
        const r = await api.post(`/api/apps/${encodeURIComponent(S.pkg)}/prefs/set`, {
            bucket, key, value: null, forceStop: true
        });
        if (!r.ok) throw new Error(r.error || 'delete failed');
        toast(`Deleted ${key} from ${bucket}.xml`, 'ok');
        await loadPrefs();
    } catch (e) { toast(e.message, 'err'); }
}

async function addPref(bucketEl) {
    const bucket = bucketEl.dataset.bucket;
    const key = prompt(`New key in ${bucket}.xml:`);
    if (!key) return;
    const type = prompt('Type: string / int / long / float / boolean', 'string')?.toLowerCase() || 'string';
    const value = prompt(`Value (${type}):`, type === 'boolean' ? 'true' : '');
    if (value === null) return;
    try {
        const r = await api.post(`/api/apps/${encodeURIComponent(S.pkg)}/prefs/set`, {
            bucket, key, value, type, forceStop: true
        });
        if (!r.ok) throw new Error(r.error || 'write failed');
        toast(`Added ${key} = ${value} to ${bucket}.xml`, 'ok');
        await loadPrefs();
    } catch (e) { toast(e.message, 'err'); }
}

function initPrefs() {
    once('prefs', () => {
        $('#prefs-refresh').onclick = loadPrefs;
        $('#prefs-list').addEventListener('click', e => {
            const btn = e.target.closest('[data-action]');
            if (!btn) return;
            const row = e.target.closest('.pref-row');
            switch (btn.dataset.action) {
                case 'edit-pref':   if (row) enterEdit(row); break;
                case 'save-pref':   if (row) savePref(row); break;
                case 'cancel-pref': loadPrefs(); break;
                case 'delete-pref': if (row) deletePref(row); break;
                case 'add-pref':    addPref(e.target.closest('.pref-bucket')); break;
            }
        });
        $('#prefs-list').addEventListener('keydown', e => {
            if (e.key === 'Enter' && e.target.matches('[data-role="val-input"]')) {
                const row = e.target.closest('.pref-row'); if (row) savePref(row);
            } else if (e.key === 'Escape' && e.target.matches('[data-role="val-input"]')) {
                loadPrefs();
            }
        });
    });
    loadPrefs();
}
function refreshPrefs() { if (S.pkg) loadPrefs(); }

// ============== SQLITE tab ==============
async function openSqlite(path) {
    S.sqlitePath = path || $('#sqlite-path').value.trim();
    if (!S.pkg || !S.sqlitePath) return;
    try {
        const data = await api.get(`/api/apps/${encodeURIComponent(S.pkg)}/sqlite/tables?path=${encodeURIComponent(S.sqlitePath)}`);
        $('#sqlite-tables').innerHTML = data.tables.map(t => `
            <div class="row" data-table="${fmt.esc(t.name)}">
                <span class="icon">🗄</span>
                <span class="name">${fmt.esc(t.name)}</span>
                <span class="meta">${t.rowCount >= 0 ? t.rowCount : '?'}</span>
            </div>
        `).join('') || '<div class="empty small">no tables</div>';
    } catch (e) { $('#sqlite-tables').innerHTML = `<div class="empty" style="color: var(--red)">${fmt.esc(e.message)}</div>`; }
}
async function loadTable(table) {
    try {
        const data = await api.get(`/api/apps/${encodeURIComponent(S.pkg)}/sqlite/rows?path=${encodeURIComponent(S.sqlitePath)}&table=${encodeURIComponent(table)}&limit=200&offset=0`);
        renderTable($('#sqlite-result'), data);
    } catch (e) { toast(e.message, 'err'); }
}
async function runQuery() {
    const sql = $('#sqlite-query').value.trim(); if (!sql) return;
    try {
        const data = await api.post(`/api/apps/${encodeURIComponent(S.pkg)}/sqlite/query`, { path: S.sqlitePath, sql, limit: 500 });
        renderTable($('#sqlite-result'), data);
    } catch (e) { $('#sqlite-result').innerHTML = `<div class="empty" style="color: var(--red)">${fmt.esc(e.message)}</div>`; }
}
function renderTable(root, data) {
    if (!data.columns) { root.innerHTML = '<div class="empty">no data</div>'; return; }
    const head = data.columns.map(c => `<th>${fmt.esc(c)}</th>`).join('');
    const body = data.rows.map(row => '<tr>' + row.map(v => v === null ? '<td class="null">NULL</td>' : `<td>${fmt.esc(v)}</td>`).join('') + '</tr>').join('');
    root.innerHTML = `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>
        <div class="muted small" style="padding: 6px 12px; border-top: 1px solid var(--line)">
            ${data.rows.length} of ${data.total} rows · offset ${data.offset} · limit ${data.limit}
        </div>`;
}
function initSqlite() {
    once('sqlite', () => {
        $('#sqlite-back').onclick = () => showTab('files');
        $('#sqlite-open').onclick = () => openSqlite();
        $('#sqlite-run').onclick = runQuery;
        $('#sqlite-query').addEventListener('keydown', e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) runQuery(); });
        $('#sqlite-tables').addEventListener('click', e => {
            const r = e.target.closest('.row'); if (!r) return;
            $$('.row', $('#sqlite-tables')).forEach(x => x.classList.remove('active'));
            r.classList.add('active');
            S.sqliteCurrentTable = r.dataset.table;
            loadTable(r.dataset.table);
        });
        $('#sqlite-download').onclick = () => {
            if (!S.pkg || !S.sqlitePath) { toast('Open a DB first', 'err'); return; }
            const url = `/api/apps/${encodeURIComponent(S.pkg)}/sqlite/download?path=${encodeURIComponent(S.sqlitePath)}`;
            const a = document.createElement('a'); a.href = url;
            a.download = S.sqlitePath.split('/').pop() || 'database.db';
            document.body.appendChild(a); a.click(); a.remove();
            toast(`Downloading ${a.download} …`, 'ok');
        };
        $('#sqlite-csv').onclick = async () => {
            if (!S.pkg || !S.sqlitePath) { toast('Open a DB first', 'err'); return; }
            const sql = $('#sqlite-query').value.trim();
            const body = sql ? { path: S.sqlitePath, sql, limit: 10000 }
                             : { path: S.sqlitePath, table: S.sqliteCurrentTable, limit: 10000 };
            if (!body.sql && !body.table) { toast('Run a query or select a table first', 'err'); return; }
            // Use fetch directly since we want the file as a blob to trigger download.
            try {
                const r = await fetch(`/api/apps/${encodeURIComponent(S.pkg)}/sqlite/csv`, {
                    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
                });
                if (!r.ok) throw await explainError(r);
                const blob = await r.blob();
                const a = document.createElement('a'); a.href = URL.createObjectURL(blob);
                a.download = (body.table || 'query') + '.csv';
                document.body.appendChild(a); a.click(); a.remove();
                toast(`Downloaded ${a.download}`, 'ok');
            } catch (e) { toast(e.message, 'err'); }
        };
    });
    if (S.sqlitePath) openSqlite();
}
function refreshSqlite() { if (S.pkg && S.sqlitePath) openSqlite(S.sqlitePath); }

// ============== MANIFEST tab ==============
async function loadManifest() {
    if (!S.pkg) return;
    try {
        const d = await api.get(`/api/apps/${encodeURIComponent(S.pkg)}/manifest`);
        S.manifestData = d;
        $('#manifest-summary').innerHTML = `
            <div class="kv"><div class="k">package</div><div class="v mono">${fmt.esc(d.packageName)}</div></div>
            <div class="kv"><div class="k">version</div><div class="v">${fmt.esc(d.versionName)} (${d.versionCode})</div></div>
            <div class="kv"><div class="k">min sdk</div><div class="v">${d.minSdk}</div></div>
            <div class="kv"><div class="k">target sdk</div><div class="v">${d.targetSdk}</div></div>
            <div class="kv"><div class="k">permissions</div><div class="v">${d.permissions.length}</div></div>
        `;
        $('#manifest-perm-count').textContent = d.permissions.length
            ? `${d.permissions.length} permission${d.permissions.length > 1 ? 's' : ''}`
            : '';
        $('#manifest-xml').textContent = d.xml || '(failed to decode)';
        // Re-render perm list if currently open.
        if ($('#manifest-perm-toggle').getAttribute('aria-pressed') === 'true') {
            manifestRenderPerms(d.permissions);
        }
    } catch (e) { toast(e.message, 'err'); }
}

function manifestRenderPerms(perms) {
    const el = $('#manifest-perm-list');
    if (!perms || !perms.length) {
        el.innerHTML = '<div class="empty muted small">No permissions declared.</div>';
        return;
    }
    // Classify by protection level using well-known dangerous permissions.
    const DANGEROUS = new Set([
        'android.permission.READ_CONTACTS','android.permission.WRITE_CONTACTS',
        'android.permission.READ_CALL_LOG','android.permission.WRITE_CALL_LOG',
        'android.permission.CAMERA','android.permission.RECORD_AUDIO',
        'android.permission.ACCESS_FINE_LOCATION','android.permission.ACCESS_COARSE_LOCATION',
        'android.permission.ACCESS_BACKGROUND_LOCATION',
        'android.permission.READ_EXTERNAL_STORAGE','android.permission.WRITE_EXTERNAL_STORAGE',
        'android.permission.READ_SMS','android.permission.SEND_SMS','android.permission.RECEIVE_SMS',
        'android.permission.READ_PHONE_STATE','android.permission.CALL_PHONE',
        'android.permission.PROCESS_OUTGOING_CALLS',
        'android.permission.BODY_SENSORS','android.permission.ACTIVITY_RECOGNITION',
        'android.permission.BLUETOOTH_SCAN','android.permission.BLUETOOTH_CONNECT',
        'android.permission.UWB_RANGING','android.permission.NEARBY_WIFI_DEVICES',
        'android.permission.READ_MEDIA_IMAGES','android.permission.READ_MEDIA_VIDEO',
        'android.permission.READ_MEDIA_AUDIO',
    ]);
    const sorted = [...perms].sort((a, b) => {
        const ad = DANGEROUS.has(a) ? 0 : 1;
        const bd = DANGEROUS.has(b) ? 0 : 1;
        return ad - bd || a.localeCompare(b);
    });
    el.innerHTML = sorted.map(p => {
        const isDangerous = DANGEROUS.has(p);
        const short = p.startsWith('android.permission.') ? p.slice(19) : p;
        return `<div class="manifest-perm-row${isDangerous ? ' dangerous' : ''}">
            ${isDangerous ? '<span class="manifest-perm-badge danger">DANGEROUS</span>' : '<span class="manifest-perm-badge">NORMAL</span>'}
            <span class="manifest-perm-name mono small" title="${fmt.esc(p)}">${fmt.esc(short)}</span>
        </div>`;
    }).join('');
}

function initManifest() {
    once('manifest', () => {
        $('#manifest-refresh').onclick = loadManifest;
        $('#manifest-perm-toggle').addEventListener('click', () => {
            const btn     = $('#manifest-perm-toggle');
            const list    = $('#manifest-perm-list');
            const xml     = $('#manifest-xml');
            const pressed = btn.getAttribute('aria-pressed') === 'true';
            btn.setAttribute('aria-pressed', String(!pressed));
            btn.classList.toggle('active', !pressed);
            list.classList.toggle('hidden', pressed);
            xml.classList.toggle('hidden', !pressed);
            if (!pressed && S.manifestData) manifestRenderPerms(S.manifestData.permissions);
        });
    });
    loadManifest();
}
function refreshManifest() { if (S.pkg) loadManifest(); }

// ============== COMPONENTS tab ==============
// Live runtime view of activities/services/receivers/providers via
// PackageManager. The static deep view (intent filters + ADB exploit
// commands) lives in APK Auditor - index.html surfaces a CTA strip
// pointing there, this code just renders the on-device list.
//
// For apps with hundreds of components (hpandro.android.security has 106
// activities alone) a name filter is essential. We keep the latest
// payload in S.componentsData so the filter can re-render without
// re-fetching.
async function loadComponents() {
    if (!S.pkg) return;
    try {
        const d = await api.get(`/api/apps/${encodeURIComponent(S.pkg)}/components`);
        S.componentsData = d;
        renderComponentsFiltered();
    } catch (e) { toast(e.message, 'err'); }
}
function renderComponentsFiltered() {
    const d = S.componentsData;
    if (!d) return;
    const q = ($('#components-filter')?.value || '').trim().toLowerCase();
    const exportedOnly = $('#components-exported-only')?.getAttribute('aria-pressed') === 'true';
    // Filter by both name and (optionally) exported flag.
    const passes = (c) => (!q || c.name.toLowerCase().includes(q)) && (!exportedOnly || c.exported);
    // Sort exported components to the top of each group so the user can see
    // the attack surface at a glance instead of scrolling past 100 private
    // activities to find the 3 exported ones.
    const sortExportedFirst = (arr) => arr.slice().sort((a, b) => {
        if (a.exported !== b.exported) return a.exported ? -1 : 1;
        return a.name.localeCompare(b.name);
    });
    const groups = [
        ['Activities', sortExportedFirst((d.activities || []).filter(passes))],
        ['Services',   sortExportedFirst((d.services   || []).filter(passes))],
        ['Receivers',  sortExportedFirst((d.receivers  || []).filter(passes))],
        ['Providers',  sortExportedFirst((d.providers  || []).filter(passes))],
    ];
    const totalShown = groups.reduce((n, g) => n + g[1].length, 0);
    const totalAll = (d.activities||[]).length + (d.services||[]).length + (d.receivers||[]).length + (d.providers||[]).length;
    const totalExported = [d.activities, d.services, d.receivers, d.providers]
        .reduce((n, list) => n + (list || []).filter(c => c.exported).length, 0);
    const countEl = $('#components-count');
    if (countEl) {
        const filtered = q || exportedOnly;
        countEl.textContent = filtered ? `${totalShown} / ${totalAll} match` : `${totalAll} total · ${totalExported} exported`;
    }
    $('#components-list').innerHTML = groups.map(([title, items]) => {
        // Hide entire group when the active filters empty it.
        if ((q || exportedOnly) && items.length === 0) return '';
        return `
            <div class="component-group">
                <h3>${title} <span class="count">${items.length}</span></h3>
                ${items.map((c, gi) => renderComponentRow(c, d.packageName)).join('') || '<div class="empty small">no matches</div>'}
            </div>`;
    }).join('') || `<div class="empty">No components match the current filters.</div>`;

    wireComponentActions(d.packageName);
}

/**
 * Render a single component row with an expandable adb-command builder.
 * Exported components get a dropdown of pre-forged `am`/`content` commands
 * derived from their intent-filters (actions, categories, deeplink data).
 */
function renderComponentRow(c, pkg) {
    const cmds = buildAdbCommands(c, pkg);
    const hasCmds = cmds.length > 0;
    // Unique id for toggling the command panel
    const rid = 'cmp_' + Math.random().toString(36).slice(2, 9);
    const filterSummary = (c.filters || []).flatMap(f => [
        ...f.actions.map(a => 'action: ' + a.replace('android.intent.action.', '')),
        ...f.data.filter(dd => dd.scheme).map(dd => `data: ${dd.scheme}://${dd.host || ''}${dd.path || dd.pathPrefix || ''}`)
    ]);

    // The Extras builder applies to components launched via `am` (activities,
    // services, receivers). Providers use content URIs, not extras.
    const supportsExtras = c.type === 'activity' || c.type === 'service' || c.type === 'receiver';

    return `
        <div class="component-row ${c.exported ? 'exported' : ''}">
            <div class="component-head" ${hasCmds ? `data-toggle="${rid}" role="button" tabindex="0"` : ''}>
                <span class="component-name">${fmt.esc(c.name)}</span>
                ${c.exported ? '<span class="tg danger">exported</span>' : '<span class="tg">private</span>'}
                ${c.authority ? `<span class="tg cyan">${fmt.esc(c.authority)}</span>` : ''}
                ${hasCmds ? `<span class="cmp-caret" aria-hidden="true">▸</span>` : ''}
            </div>
            ${filterSummary.length ? `<div class="component-filters">${filterSummary.map(fmt.esc).join(' · ')}</div>` : ''}
            ${hasCmds ? `
                <div class="cmp-cmds hidden" id="${rid}">
                    ${cmds.map((cmd, i) => `
                        <div class="cmp-cmd">
                            <code class="cmp-cmd-text" id="${rid}_${i}">${fmt.esc(cmd.display)}</code>
                            <div class="cmp-cmd-actions">
                                <button class="btn ghost small cmp-copy" data-copy="${rid}_${i}" title="Copy adb command">copy</button>
                                <button class="btn small cmp-run" data-run="${rid}_${i}" data-shell="${fmt.esc(cmd.shell)}" title="Run on device via su">▶ Run</button>
                            </div>
                            <div class="cmp-label muted small">${fmt.esc(cmd.label)}</div>
                            <pre class="cmp-cmd-output hidden"></pre>
                        </div>
                    `).join('')}
                    ${supportsExtras ? `
                        <div class="cmp-extras" data-rid="${rid}"
                             data-type="${c.type}" data-target="${fmt.esc(pkg + '/' + c.name)}"
                             data-class="${fmt.esc(c.name)}">
                            <button class="btn ghost small cmp-extras-load" title="Scan the component's code for the Intent extras it reads">
                                <svg class="ic ic-sm"><use href="#i-search"/></svg> Build command with extras
                            </button>
                            <div class="cmp-extras-body hidden"></div>
                        </div>` : ''}
                    <div class="cmp-custom">
                        <div class="muted small cmp-custom-label">Custom command</div>
                        <div class="cmp-custom-row">
                            <input class="input mono small cmp-custom-input" spellcheck="false"
                                   value="${fmt.esc(defaultShellFor(c, pkg))}"
                                   placeholder="adb shell …">
                            <button class="btn ghost small cmp-custom-copy" title="Copy">copy</button>
                            <button class="btn small cmp-custom-run" title="Run on device">▶ Run</button>
                        </div>
                        <pre class="cmp-cmd-output hidden cmp-custom-output"></pre>
                    </div>
                </div>` : ''}
        </div>`;
}

/**
 * Returns a sensible default adb shell command to pre-fill the custom
 * command input for a given component type.
 */
function defaultShellFor(c, pkg) {
    const target = pkg + '/' + c.name;
    switch (c.type) {
        case 'activity':
            return `adb shell am start -n ${target}`;
        case 'service':
            return `adb shell am startservice -n ${target}`;
        case 'receiver':
            return `adb shell am broadcast -n ${target}`;
        case 'provider':
            return c.authority
                ? `adb shell content query --uri content://${c.authority}/`
                : `adb shell content query --uri content://`;
        default:
            return `adb shell am start -n ${target}`;
    }
}

/**
 * Build a list of pre-forged adb commands for a component, based on its type
 * and intent-filters. Each entry has:
 *   display — full `adb shell …` form (what the pentester copies)
 *   shell   — the on-device form (no `adb shell` prefix) run via /api/live/exec
 *   label   — human description
 */
function buildAdbCommands(c, pkg) {
    const out = [];
    // PackageManager returns fully-qualified component names already
    // (e.g. com.example.MainActivity). am accepts pkg/fully.qualified.Name,
    // and the short form pkg/.Name only when the class sits directly under
    // the package. Using the FQ name is always safe.
    const target = `${pkg}/${c.name}`;
    const add = (shell, label) => out.push({ shell, display: 'adb shell ' + shell, label });

    if (c.type === 'activity') {
        add(`am start -n ${target}`, 'Launch activity directly (component name)');
        // Per intent-filter: actions, categories, deeplinks
        (c.filters || []).forEach(f => {
            f.actions.filter(a => a !== 'android.intent.action.MAIN').forEach(action => {
                const cats = f.categories.filter(x => x !== 'android.intent.category.LAUNCHER')
                    .map(x => `-c ${x}`).join(' ');
                add(`am start -n ${target} -a ${action}${cats ? ' ' + cats : ''}`,
                    `Launch with action ${action.replace('android.intent.action.', '')}`);
            });
            // Deeplinks (VIEW + data)
            f.data.filter(dd => dd.scheme).forEach(dd => {
                const host = dd.host || 'HOST';
                const path = dd.path || dd.pathPrefix || dd.pathPattern || '';
                const uri = `${dd.scheme}://${host}${path}`;
                add(`am start -a android.intent.action.VIEW -d "${uri}"`,
                    `Open deeplink ${dd.scheme}://`);
            });
        });

    } else if (c.type === 'service') {
        add(`am startservice -n ${target}`, 'Start service');
        add(`am start-foreground-service -n ${target}`, 'Start as foreground service');
        (c.filters || []).forEach(f => f.actions.forEach(action => {
            add(`am startservice -n ${target} -a ${action}`,
                `Start with action ${action.replace('android.intent.action.', '')}`);
        }));

    } else if (c.type === 'receiver') {
        (c.filters || []).forEach(f => f.actions.forEach(action => {
            add(`am broadcast -n ${target} -a ${action}`,
                `Broadcast action ${action.replace('android.intent.action.', '')}`);
        }));
        add(`am broadcast -n ${target}`, 'Broadcast to receiver directly');

    } else if (c.type === 'provider' && c.authority) {
        const auth = c.authority;
        const paths = c.providerPaths || [];

        if (paths.length) {
            // Rich commands using actual table/column data from DEX scan.
            paths.forEach(p => {
                const uri = `content://${auth}/${p.path}`;
                const cols = (p.columns || []);
                const colList = cols.length ? cols.join(', ') : null;

                add(`content query --uri ${uri}`, `Query ${p.path}`);

                if (p.path.includes('#')) {
                    // Path pattern with ID — generate a concrete ID-based query.
                    const base = p.path.replace('/#', '');
                    add(`content query --uri content://${auth}/${base}/1`, `Query ${base} by id=1`);
                }

                if (cols.length) {
                    // Insert with actual column bindings.
                    const binds = cols
                        .filter(col => col !== 'id')
                        .map(col => `--bind ${col}:s:test_value`)
                        .join(' ');
                    if (binds) {
                        const insertUri = `content://${auth}/${p.path.replace('/#', '')}`;
                        add(`content insert --uri ${insertUri} ${binds}`,
                            `Insert into ${p.path.replace('/#', '')} (${cols.filter(c => c !== 'id').join(', ')})`);
                    }

                    // Update a row.
                    if (p.path.includes('#')) {
                        const base = p.path.replace('/#', '');
                        const firstCol = cols.find(c => c !== 'id');
                        if (firstCol) {
                            add(`content update --uri content://${auth}/${base}/1 --bind ${firstCol}:s:new_value`,
                                `Update ${base} id=1 (${firstCol})`);
                        }
                    }

                    // Delete a row.
                    if (p.path.includes('#')) {
                        const base = p.path.replace('/#', '');
                        add(`content delete --uri content://${auth}/${base}/1`,
                            `Delete from ${base} where id=1`);
                    }
                }
            });

            // Projection query for the first non-ID table.
            const firstTable = paths.find(p => !p.path.includes('#'));
            if (firstTable && firstTable.columns.length) {
                const cols = firstTable.columns.join(',');
                add(`content query --uri content://${auth}/${firstTable.path} --projection ${cols}`,
                    `Query ${firstTable.path} columns: ${firstTable.columns.join(', ')}`);
            }
        } else {
            // Fallback: generic commands when no DEX info available.
            add(`content query --uri content://${auth}/`, 'Query provider root');
            add(`content query --uri content://${auth}/PATH`, 'Query a path (edit PATH)');
            add(`content read --uri content://${auth}/PATH`, 'Read data from a path (edit PATH)');
            add(`content insert --uri content://${auth}/PATH --bind col:s:value`, 'Insert a row (edit PATH/col)');
        }
    }
    return out;
}

function wireComponentActions(pkg) {
    // Expand/collapse command panels
    $$('.component-head[data-toggle]').forEach(head => {
        const fire = () => {
            const panel = document.getElementById(head.dataset.toggle);
            if (!panel) return;
            const open = panel.classList.toggle('hidden');
            const caret = head.querySelector('.cmp-caret');
            if (caret) caret.textContent = open ? '▸' : '▾';
        };
        head.onclick = fire;
        head.onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fire(); } };
    });
    // Copy buttons
    $$('.cmp-copy').forEach(b => b.onclick = () => {
        const txt = document.getElementById(b.dataset.copy)?.textContent || '';
        navigator.clipboard?.writeText(txt).then(
            () => toast('Command copied', 'ok'),
            () => toast('Copy failed', 'err')
        );
    });
    // Launch buttons — run the on-device (shell) form via /api/live/exec
    $$('.cmp-run').forEach(b => b.onclick = async () => {
        const shell = b.dataset.shell;
        if (!shell) return;
        // Find the output panel for this command row.
        const row = b.closest('.cmp-cmd');
        const out = row?.querySelector('.cmp-cmd-output');
        b.disabled = true;
        const orig = b.textContent;
        b.textContent = '…';
        if (out) { out.textContent = 'Running…'; out.classList.remove('hidden', 'cmp-out-ok', 'cmp-out-err'); }
        try {
            const r = await api.post('/api/live/exec', { command: shell });
            const result = [r.stdout, r.stderr].filter(Boolean).join('\n').trim();
            if (out) {
                out.textContent = result || `(exit ${r.code}, no output)`;
                out.classList.toggle('cmp-out-ok',  r.code === 0);
                out.classList.toggle('cmp-out-err', r.code !== 0);
                out.classList.remove('hidden');
            }
            if (r.code !== 0) toast(`exit ${r.code}`, 'err');
        } catch (e) {
            if (out) { out.textContent = e.message; out.classList.add('cmp-out-err'); out.classList.remove('hidden'); }
            else toast(e.message, 'err');
        } finally {
            b.disabled = false;
            b.textContent = orig;
        }
    });

    // Custom command rows — editable input with copy + run.
    $$('.cmp-custom').forEach(box => {
        const input  = box.querySelector('.cmp-custom-input');
        const outEl  = box.querySelector('.cmp-custom-output');
        const copyBtn = box.querySelector('.cmp-custom-copy');
        const runBtn  = box.querySelector('.cmp-custom-run');

        copyBtn.onclick = () => {
            const val = input.value.trim();
            if (!val) return;
            navigator.clipboard?.writeText(val).then(
                () => toast('Copied', 'ok'), () => toast('Copy failed', 'err'));
        };

        runBtn.onclick = async () => {
            // Strip leading "adb shell " if present — /api/live/exec runs on-device.
            const raw   = input.value.trim();
            const shell = raw.startsWith('adb shell ') ? raw.slice(10) : raw;
            if (!shell) return;
            runBtn.disabled = true; runBtn.textContent = '…';
            outEl.textContent = 'Running…';
            outEl.classList.remove('hidden', 'cmp-out-ok', 'cmp-out-err');
            try {
                const r = await api.post('/api/live/exec', { command: shell });
                const result = [r.stdout, r.stderr].filter(Boolean).join('\n').trim();
                outEl.textContent = result || `(exit ${r.code}, no output)`;
                outEl.classList.toggle('cmp-out-ok',  r.code === 0);
                outEl.classList.toggle('cmp-out-err', r.code !== 0);
                if (r.code !== 0) toast(`exit ${r.code}`, 'err');
            } catch (e) {
                outEl.textContent = e.message;
                outEl.classList.add('cmp-out-err');
            } finally {
                runBtn.disabled = false; runBtn.textContent = '▶ Run';
            }
        };

        // Run on Enter key in the input.
        input.addEventListener('keydown', e => { if (e.key === 'Enter') runBtn.click(); });
    });
    $$('.cmp-extras').forEach(box => {
        const loadBtn = box.querySelector('.cmp-extras-load');
        const body    = box.querySelector('.cmp-extras-body');
        loadBtn.onclick = async () => {
            loadBtn.disabled = true;
            const orig = loadBtn.innerHTML;
            loadBtn.innerHTML = 'Scanning code…';
            try {
                const cls = box.dataset.class;
                const r = await api.get(
                    `/api/apps/${encodeURIComponent(pkg)}/components/extras?class=${encodeURIComponent(cls)}`
                );
                renderExtrasBuilder(box, body, r.extras || []);
                body.classList.remove('hidden');
                loadBtn.style.display = 'none';
            } catch (e) {
                toast(e.message, 'err');
                loadBtn.disabled = false;
                loadBtn.innerHTML = orig;
            }
        };
    });
}

// adb `am` extra flags per type token
const EXTRA_FLAG = { s: '--es', i: '--ei', l: '--el', z: '--ez', f: '--ef', d: '--ef' };
const EXTRA_TYPE_LABEL = { s: 'string', i: 'int', l: 'long', z: 'bool', f: 'float', d: 'float' };

function renderExtrasBuilder(box, body, extras) {
    const type   = box.dataset.type;       // activity | service | receiver
    const target = box.dataset.target;     // pkg/Component
    const verb   = type === 'service' ? 'startservice'
                 : type === 'receiver' ? 'broadcast'
                 : 'start';

    if (!extras.length) {
        body.innerHTML = `<div class="muted small">No literal extras found in this component's code. It may read extras with dynamic keys, or none at all. You can still add custom extras manually.</div>
            <div class="cmp-extras-rows"></div>
            ${extrasBuilderControls()}`;
    } else {
        body.innerHTML = `
            <div class="muted small">${extras.length} extra${extras.length>1?'s':''} found in code — fill values to forge the command:</div>
            <div class="cmp-extras-rows">
                ${extras.map(e => extraRow(e.name, e.type)).join('')}
            </div>
            ${extrasBuilderControls()}`;
    }

    const rowsWrap = body.querySelector('.cmp-extras-rows');
    const cmdOut   = body.querySelector('.cmp-extras-cmd');
    const enabledOf = () => Array.from(rowsWrap.querySelectorAll('.cmp-extra-row'));

    const rebuild = () => {
        const parts = [`am ${verb} -n ${target}`];
        enabledOf().forEach(row => {
            if (!row.querySelector('.cmp-extra-on').checked) return;
            const name = row.querySelector('.cmp-extra-name').value.trim();
            const t    = row.querySelector('.cmp-extra-type').value;
            let val    = row.querySelector('.cmp-extra-val').value;
            if (!name) return;
            if (t === 'z') val = (val === 'true' || val === '1') ? 'true' : 'false';
            // Quote string values that contain spaces
            const needsQuote = t === 's' && /\s/.test(val);
            const v = needsQuote ? `"${val.replace(/"/g, '\\"')}"` : val;
            parts.push(`${EXTRA_FLAG[t]} ${name} ${v}`);
        });
        cmdOut.textContent = 'adb shell ' + parts.join(' ');
        cmdOut.dataset.shell = parts.join(' ');
    };

    // Wire inputs
    body.addEventListener('input', rebuild);
    body.addEventListener('change', rebuild);

    // Add-custom-extra button
    body.querySelector('.cmp-extra-add').onclick = () => {
        rowsWrap.insertAdjacentHTML('beforeend', extraRow('', 's'));
        rebuild();
    };
    // Remove-row (delegated)
    rowsWrap.onclick = (e) => {
        const rm = e.target.closest('.cmp-extra-rm');
        if (rm) { rm.closest('.cmp-extra-row').remove(); rebuild(); }
    };
    // Copy / Launch the built command
    body.querySelector('.cmp-extras-copy').onclick = () => {
        navigator.clipboard?.writeText(cmdOut.textContent).then(
            () => toast('Command copied', 'ok'), () => toast('Copy failed', 'err'));
    };
    body.querySelector('.cmp-extras-run').onclick = async () => {
        const shell = cmdOut.dataset.shell;
        if (!shell) return;
        const outEl = body.querySelector('.cmp-extras-output');
        const btn   = body.querySelector('.cmp-extras-run');
        btn.disabled = true; btn.textContent = '…';
        if (outEl) { outEl.textContent = 'Running…'; outEl.classList.remove('hidden', 'cmp-out-ok', 'cmp-out-err'); }
        try {
            const r = await api.post('/api/live/exec', { command: shell });
            const result = [r.stdout, r.stderr].filter(Boolean).join('\n').trim();
            if (outEl) {
                outEl.textContent = result || `(exit ${r.code}, no output)`;
                outEl.classList.toggle('cmp-out-ok',  r.code === 0);
                outEl.classList.toggle('cmp-out-err', r.code !== 0);
            }
            if (r.code !== 0) toast(`exit ${r.code}`, 'err');
        } catch (e) {
            if (outEl) { outEl.textContent = e.message; outEl.classList.add('cmp-out-err'); }
            else toast(e.message, 'err');
        } finally { btn.disabled = false; btn.textContent = '▶ Run'; }
    };

    rebuild();
}

function extraRow(name, type) {
    const opts = Object.entries(EXTRA_TYPE_LABEL)
        .filter(([k]) => k !== 'd') // collapse float/double into one
        .map(([k, label]) => `<option value="${k}" ${k===type?'selected':''}>${label}</option>`).join('');
    return `
        <div class="cmp-extra-row">
            <input type="checkbox" class="cmp-extra-on" checked title="Include this extra">
            <input class="input mono small cmp-extra-name" value="${fmt.esc(name)}" placeholder="key">
            <select class="input small cmp-extra-type">${opts}</select>
            <input class="input mono small cmp-extra-val" placeholder="value">
            <button class="btn ghost small cmp-extra-rm" title="Remove">✕</button>
        </div>`;
}

function extrasBuilderControls() {
    return `
        <button class="btn ghost small cmp-extra-add">+ Add extra</button>
        <code class="cmp-cmd-text cmp-extras-cmd" style="margin-top:8px"></code>
        <div class="cmp-cmd-actions">
            <button class="btn ghost small cmp-extras-copy">copy</button>
            <button class="btn small cmp-extras-run">▶ Run</button>
        </div>
        <pre class="cmp-cmd-output hidden cmp-extras-output"></pre>`;
}
function initComponents() {
    once('components', () => {
        $('#components-refresh').onclick = loadComponents;
        // Debounced so typing in a 100+ component list stays smooth.
        $('#components-filter')?.addEventListener('input', debounce(renderComponentsFiltered, 120));
        // "Exported only" toggle - flip aria-pressed and re-render.
        const tog = $('#components-exported-only');
        if (tog) tog.onclick = () => {
            const pressed = tog.getAttribute('aria-pressed') === 'true';
            tog.setAttribute('aria-pressed', String(!pressed));
            renderComponentsFiltered();
        };
    });
    loadComponents();
}
function refreshComponents() { if (S.pkg) loadComponents(); }

// ============== NATIVE tab ==============
async function loadNative() {
    if (!S.pkg) return;
    try {
        const d = await api.get(`/api/apps/${encodeURIComponent(S.pkg)}/native`);
        $('#native-list').innerHTML = d.libs.map(l => `
            <div class="native-card">
                <div class="name">${fmt.esc(l.name)}</div>
                <div class="path">${fmt.esc(l.path)}</div>
                <div class="tags">
                    <span class="tg accent">${fmt.bytes(l.size)}</span>
                    <span class="tg">${fmt.esc(l.arch || '?')}</span>
                    ${l.stripped ? '<span class="tg warn">stripped</span>' : '<span class="tg cyan">symbols</span>'}
                </div>
                <div class="actions">
                    <button class="btn ghost small" data-so-path="${fmt.esc(l.path)}" data-so-name="${fmt.esc(l.name)}" title="Download">
                        <svg class="ic ic-sm"><use href="#i-download"/></svg> Download
                    </button>
                </div>
            </div>
        `).join('') || '<div class="empty">No native libraries.</div>';
    } catch (e) { toast(e.message, 'err'); }
}
function downloadSo(path, name) {
    if (!S.pkg) return;
    // Use a transient <a download> so the cookie ships with the request and
    // Content-Disposition controls the filename.
    const url = `/api/apps/${encodeURIComponent(S.pkg)}/native/raw?path=${encodeURIComponent(path)}`;
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a); a.click(); a.remove();
    toast(`Downloading ${name}…`, 'ok');
}
function initNative() {
    once('native', () => {
        $('#native-refresh').onclick = loadNative;
        $('#native-list').addEventListener('click', e => {
            const b = e.target.closest('[data-so-path]');
            if (b) downloadSo(b.dataset.soPath, b.dataset.soName);
        });
    });
    loadNative();
}
function refreshNative() { if (S.pkg) loadNative(); }

// ============== PROCESSES tab ==============
async function loadProcesses() {
    const f = $('#proc-filter').value.trim().toLowerCase();
    try {
        // Always fetch all processes — filter client-side across all fields
        // so searching by name, package, cmdline, pid or uid all work.
        const d = await api.get('/api/live/processes');
        const all = d.processes || [];
        const procs = f ? all.filter(p =>
            p.name.toLowerCase().includes(f) ||
            String(p.pid).includes(f) ||
            String(p.uid).includes(f) ||
            (p.cmdline || '').toLowerCase().includes(f) ||
            (p.packages || []).some(pkg => pkg.toLowerCase().includes(f))
        ) : all;
        $('#proc-count').textContent = `${procs.length} proc${procs.length === 1 ? '' : 's'}${f && procs.length !== all.length ? ` of ${all.length}` : ''}`;
        const rows = procs.map(p => `<tr data-pid="${p.pid}" data-name="${fmt.esc(p.name)}">
            <td class="num">${p.pid}</td>
            <td>${(p.packages || []).map(fmt.esc).join('<br>') || `<span class="null mono">system uid ${p.uid}</span>`}</td>
            <td>${fmt.esc(p.name)}</td>
            <td>${fmt.esc(p.cmdline)}</td>
            <td>${p.state}</td>
            <td class="num">${p.threads}</td>
            <td class="num">${fmt.bytes(p.rssKb * 1024)}</td>
            <td><button class="btn ghost small" data-action="logcat" title="View logcat for this PID">📜 logs</button></td>
        </tr>`).join('');
        $('#proc-table').innerHTML = `<table><thead><tr><th>PID</th><th>Package</th><th>Name</th><th>Cmdline</th><th>State</th><th>Thr</th><th>RSS</th><th></th></tr></thead><tbody>${rows}</tbody></table>`;
    } catch (e) { toast(e.message, 'err'); }
}
function initProcesses() {
    once('processes', () => {
        $('#proc-refresh').onclick = loadProcesses;
        $('#proc-filter').addEventListener('input', debounce(loadProcesses, 300));

        // Click on a "📜 logs" button → jump to Logcat tab with --pid=<this row's pid>.
        // Also right-click anywhere on a row gives the same affordance.
        const handler = e => {
            const btn = e.target.closest('[data-action="logcat"]');
            const row = e.target.closest('tr[data-pid]');
            if (!btn && e.type !== 'contextmenu') return;
            if (!row) return;
            e.preventDefault();
            const pid = row.dataset.pid;
            S.pendingLogcatPid = pid;
            toast(`Following PID ${pid} (${row.dataset.name}) in Logcat`, 'ok');
            showTab('logcat');
        };
        $('#proc-table').addEventListener('click', handler);
        $('#proc-table').addEventListener('contextmenu', handler);
    });
    loadProcesses();
}
function refreshProcesses() { loadProcesses(); }

// ============== NETWORK tab ==============
async function loadNet() {
    const f = $('#net-filter').value.trim().toLowerCase();
    try {
        const d = await api.get('/api/live/connections');
        const conns = (d.connections || []).filter(c =>
            !f || c.localAddr.includes(f) || c.remoteAddr.includes(f) ||
            c.state.toLowerCase().includes(f) || String(c.uid).includes(f) ||
            (c.packages || []).some(p => p.toLowerCase().includes(f))
        );
        $('#net-count').textContent = `${conns.length} sock${conns.length === 1 ? '' : 's'}`;
        const rows = conns.map(c => `<tr>
            <td>${c.proto}</td>
            <td>${fmt.esc(c.localAddr)}</td>
            <td>${fmt.esc(c.remoteAddr)}</td>
            <td>${c.state}</td>
            <td>${(c.packages || []).map(fmt.esc).join('<br>') || `<span class="null mono">system uid ${c.uid}</span>`}</td>
        </tr>`).join('');
        $('#net-table').innerHTML = `<table><thead><tr><th>Proto</th><th>Local</th><th>Remote</th><th>State</th><th>Package</th></tr></thead><tbody>${rows}</tbody></table>`;
    } catch (e) { toast(e.message, 'err'); }
}
function initNet() {
    once('net', () => {
        $('#net-refresh').onclick = loadNet;
        $('#net-filter').addEventListener('input', debounce(loadNet, 300));
    });
    loadNet();
}
function refreshNet() { loadNet(); }

// ============== LOGCAT tab ==============
let lcSocket = null;
let lcSearchRegex = null;
let lcMonitorHits = [];
let lcNotifPermission = false;

// Ask for notification permission once.
function lcRequestNotifPermission() {
    if (!('Notification' in window)) return;
    if (Notification.permission === 'granted') { lcNotifPermission = true; return; }
    if (Notification.permission !== 'denied') {
        Notification.requestPermission().then(p => { lcNotifPermission = p === 'granted'; });
    }
}

function lcGetKeywords() {
    const raw = $('#lc-keywords')?.value || '';
    return raw.split(',').map(k => k.trim().toLowerCase()).filter(Boolean);
}

function lcCheckMonitor(line) {
    const keywords = lcGetKeywords();
    if (!keywords.length) return;

    // If pid-scoped is on and a PID is set, only alert on lines from that process.
    const pidScoped = $('#lc-monitor-pid-only')?.checked;
    const pid = parseInt($('#lc-pid')?.value, 10) || 0;
    if (pidScoped && pid > 0) {
        // Logcat line format: MM-DD HH:MM:SS.mmm  PID  TID L/tag: msg
        // PID appears as second numeric token after date/time.
        const m = line.match(/^\S+\s+\S+\s+(\d+)/);
        const linePid = m ? parseInt(m[1], 10) : -1;
        if (linePid !== pid) return;
    }

    const lineLow    = line.toLowerCase();
    const wholeWord  = $('#lc-monitor-whole-word')?.checked;
    const matched = keywords.filter(k => {
        if (wholeWord) {
            // Escape regex special chars in the keyword, then wrap in \b boundaries.
            const escaped = k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            return new RegExp(`\\b${escaped}\\b`, 'i').test(line);
        }
        return lineLow.includes(k);
    });
    if (!matched.length) return;

    // Store the hit.
    const hit = { line, keywords: matched, ts: new Date().toLocaleTimeString() };
    lcMonitorHits.unshift(hit);
    if (lcMonitorHits.length > 200) lcMonitorHits.pop();
    lcRenderMonitorHits();

    // Browser notification.
    if (lcNotifPermission) {
        new Notification(`🔔 AndroidSpect — keyword match: ${matched.join(', ')}`, {
            body: line.slice(0, 180),
            tag:  'lc-monitor',   // replace previous rather than stacking
            silent: false
        });
    }
}

function lcRenderMonitorHits() {
    const el = $('#lc-monitor-hits');
    const count = lcMonitorHits.length;
    $('#lc-monitor-count').textContent = count ? `${count} hit${count > 1 ? 's' : ''}` : '';
    if (!count) { el.classList.add('hidden'); return; }
    el.classList.remove('hidden');
    el.innerHTML = lcMonitorHits.map(h =>
        `<div class="lc-hit">` +
        `<span class="lc-hit-ts muted small">${h.ts}</span>` +
        h.keywords.map(k =>
            `<span class="tg danger">${fmt.esc(k)}</span>`
        ).join('') +
        `<span class="lc-hit-line">${fmt.esc(h.line.slice(0, 300))}</span>` +
        `</div>`
    ).join('');
}

function lcStart() {
    lcStop();
    lcRequestNotifPermission();
    const filter = $('#lc-filter').value.trim() || '*:V';
    const tail = $('#lc-tail').value || 200;
    const pid = parseInt($('#lc-pid').value, 10) || 0;
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const params = new URLSearchParams({ filter, tail });
    if (pid > 0) params.set('pid', pid);
    lcSocket = new WebSocket(`${proto}//${location.host}/ws/logcat?${params}`);
    const out = $('#lc-out');
    $('#lc-status').textContent = 'connecting…';
    lcSocket.onopen  = () => { $('#lc-status').textContent = pid > 0 ? `live · pid ${pid}` : 'live'; };
    lcSocket.onclose = () => { $('#lc-status').textContent = 'stopped'; lcSocket = null; };
    lcSocket.onerror = () => { $('#lc-status').textContent = 'error'; };
    lcSocket.onmessage = e => appendLogcatLine(e.data);
}
function appendLogcatLine(line) {
    // Monitor first — before any filtering so we catch everything matching pid+keywords.
    lcCheckMonitor(line);

    const out = $('#lc-out');
    const sev = (line.match(/\s([VDIWEF])\s/) || [])[1] || 'V';
    const onlyMatching = $('#lc-only-matching')?.checked;
    if (lcSearchRegex) {
        if (!lcSearchRegex.test(line)) { if (onlyMatching) return; }
    }
    const span = document.createElement('span');
    span.className = sev;
    if (lcSearchRegex) {
        // Highlight all matches in the line.
        const html = fmt.esc(line).replace(lcSearchRegex, m => `<mark>${m}</mark>`);
        span.innerHTML = html + '\n';
    } else {
        span.textContent = line + '\n';
    }
    out.appendChild(span);
    if ($('#lc-autoscroll').checked) out.scrollTop = out.scrollHeight;
    while (out.childNodes.length > 5000) out.removeChild(out.firstChild);
    // Update search count
    if (lcSearchRegex) {
        const total = out.querySelectorAll('mark').length;
        $('#lc-search-count').textContent = `${total} match${total === 1 ? '' : 'es'}`;
    } else {
        $('#lc-search-count').textContent = '';
    }
}
function lcStop() { if (lcSocket) { lcSocket.close(); lcSocket = null; } }
function lcSave() {
    const text = $('#lc-out').textContent;
    if (!text.trim()) { toast('Logcat buffer is empty', 'err'); return; }
    const blob = new Blob([text], { type: 'text/plain' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    a.download = `logcat-${stamp}.txt`;
    document.body.appendChild(a); a.click(); a.remove();
    toast(`Saved ${a.download}`, 'ok');
}
function lcApplySearch() {
    const raw = $('#lc-search').value.trim();
    if (!raw) {
        lcSearchRegex = null;
        $('#lc-search-count').textContent = '';
        // Strip any existing <mark>s by re-rendering current text:
        const out = $('#lc-out');
        const lines = out.textContent.split('\n').filter(l => l.length);
        out.innerHTML = '';
        lines.forEach(l => appendLogcatLine(l));
        return;
    }
    try {
        lcSearchRegex = new RegExp(raw, 'gi');
        // Re-render existing lines with the new regex.
        const out = $('#lc-out');
        const lines = out.textContent.split('\n').filter(l => l.length);
        out.innerHTML = '';
        $('#lc-search-count').textContent = '';
        lines.forEach(l => appendLogcatLine(l));
    } catch (e) {
        toast(`Invalid regex: ${e.message}`, 'err');
    }
}
function initLogcat() {
    once('logcat', () => {
        $('#lc-start').onclick = lcStart;
        $('#lc-stop').onclick = lcStop;
        $('#lc-clear').onclick = () => { $('#lc-out').innerHTML = ''; $('#lc-search-count').textContent = ''; };
        $('#lc-save').onclick = lcSave;
        $('#lc-monitor-clear-hits').onclick = () => {
            lcMonitorHits = []; lcRenderMonitorHits();
            $('#lc-monitor-count').textContent = '';
        };
        $('#lc-search').addEventListener('input', debounce(lcApplySearch, 250));
        $('#lc-only-matching').addEventListener('change', lcApplySearch);
    });
    // If something asked us to start with a PID prefilled (from Processes right-click),
    // honour it and auto-start.
    if (S.pendingLogcatPid) {
        $('#lc-pid').value = S.pendingLogcatPid;
        S.pendingLogcatPid = null;
        lcStart();
    }
}
function refreshLogcat() {
    if (S.pendingLogcatPid) {
        lcStop();
        $('#lc-pid').value = S.pendingLogcatPid;
        S.pendingLogcatPid = null;
        lcStart();
    }
}

// ============== SHELL tab ==============
async function shRun() {
    const cmd = $('#sh-cmd').value.trim(); if (!cmd) return;
    const out = $('#sh-out');
    out.textContent += `\n$ ${cmd}\n`;
    $('#sh-cmd').value = '';
    try {
        const r = await api.post('/api/live/exec', { command: cmd });
        if (r.stdout) out.textContent += r.stdout + '\n';
        if (r.stderr) out.textContent += `[stderr] ${r.stderr}\n`;
        out.textContent += `[exit ${r.code}]\n`;
    } catch (e) { out.textContent += `[error] ${e.message}\n`; }
    out.scrollTop = out.scrollHeight;
}
function initShell() {
    once('shell', () => {
        $('#sh-run').onclick = shRun;
        $('#sh-clear').onclick = () => { $('#sh-out').textContent = ''; };
        $('#sh-cmd').addEventListener('keydown', e => { if (e.key === 'Enter') shRun(); });
    });
}
function refreshShell() {}

// ============== CODE tab ==============
let codeJobId    = null;
let searchJobId  = null;  // jobId used for the most recent search — may differ from selected job
let codeOpenTabs = [];
let codeTabIdx   = -1;
let codePollTimer= null;
let codePkg      = null;  // package the current code state belongs to

function codeReset() {
    codeStopPoll();
    codeJobId    = null;
    searchJobId  = null;
    codeOpenTabs = [];
    codeTabIdx   = -1;
    codePkg      = null;
    renderCodeTabs();
    $('#code-tree').innerHTML       = '<div class="empty small muted">Select a job to browse its source.</div>';
    $('#code-viewer').innerHTML     = '';
    $('#code-viewer').style.display = '';
    $('#code-search-results').classList.add('hidden');
    $('#code-search-input').value   = '';
    $('#code-crumb').innerHTML      = '';
    $('#code-status').textContent   = '';
    $('#code-zip-btn').disabled     = true;
    $('#code-progress-wrap').hidden = true;
}

function initCode() {
    once('code', () => {
        $('#code-decompile').addEventListener('click', async () => {
            if (!S.pkg) { toast('Pick an app first', 'err'); return; }
            try {
                $('#code-decompile').disabled = true;
                $('#code-status').textContent = 'Starting…';
                const r = await api.post('/api/decompiler/jobs', { pkg: S.pkg });
                codePkg   = S.pkg;
                codeJobId = r.jobId;
                codeOpenTabs = []; codeTabIdx = -1;
                renderCodeTabs();
                $('#code-tree').innerHTML = '<div class="empty small">Decompiling…</div>';
                $('#code-zip-btn').disabled = true;
                codeStartPoll();
                await codeRefreshJobList();
            } catch (e) {
                toast(e.message, 'err');
                $('#code-status').textContent = '';
            } finally {
                $('#code-decompile').disabled = false;
            }
        });

        $('#code-jobs-refresh').addEventListener('click', codeRefreshJobList);
        $('#code-search-btn').addEventListener('click', codeRunSearch);
        $('#code-search-input').addEventListener('keydown', e => { if (e.key === 'Enter') codeRunSearch(); });
        $('#code-zip-btn').addEventListener('click', () => {
            if (codeJobId) window.location.href = `/api/decompiler/jobs/${codeJobId}/zip`;
        });

        // JADX import — upload a ZIP of decompiled Java sources from the workstation.
        $('#code-jadx-btn').addEventListener('click', () => $('#code-jadx-input').click());
        $('#code-jadx-input').addEventListener('change', async e => {
            const file = e.target.files?.[0]; if (!file) return;
            e.target.value = '';
            const label = (S.appInfo?.name || S.pkg || 'app') + ' (JADX)';
            $('#code-status').textContent = `Uploading ${file.name}…`;
            $('#code-jadx-btn').disabled = true;
            try {
                const form = new FormData();
                form.append('file', file, file.name);
                const r = await fetchAuthed(
                    `/api/decompiler/jadx?label=${encodeURIComponent(label)}&pkg=${encodeURIComponent(S.pkg || '')}`,
                    { method: 'POST', body: form }
                );
                if (!r.ok) throw await explainError(r);
                const job = await r.json();
                codeJobId = job.id;
                codeOpenTabs = []; codeTabIdx = -1;
                renderCodeTabs();
                await codeRefreshJobList();
                if (job.status === 'DONE') {
                    $('#code-status').textContent = '';
                    $('#code-zip-btn').disabled = false;
                    await codeLoadTree(job.id);
                } else {
                    $('#code-status').textContent = '✗ ' + (job.message || 'import failed');
                }
            } catch (e) {
                toast(e.message, 'err');
                $('#code-status').textContent = '';
            } finally {
                $('#code-jadx-btn').disabled = false;
            }
        });
    });

    // Only reset state when switching to a different package.
    // If the user just switched tabs and came back to the same package,
    // keep the tree, open files, and job selection intact.
    if (codePkg !== S.pkg) {
        codeReset();
        codePkg = S.pkg;
        codeRefreshJobList();
    }
}

function refreshCode() {
    if (codePkg !== S.pkg) { codeReset(); codePkg = S.pkg; }
    codeRefreshJobList();
}

async function codeRefreshJobList() {
    try {
        const pkgParam = S.pkg ? `?pkg=${encodeURIComponent(S.pkg)}` : '';
        const jobs = await api.get(`/api/decompiler/jobs${pkgParam}`);
        const list = $('#code-job-list');
        if (!jobs.length) {
            list.innerHTML = '<div class="empty small">No jobs yet.</div>';
            return;
        }
        list.innerHTML = jobs.map(j => `
            <div class="code-job-item ${j.id === codeJobId ? 'active' : ''}"
                 data-id="${fmt.esc(j.id)}" title="${fmt.esc(j.apkPath)}">
                <span class="job-name">${fmt.esc(j.label)}</span>
                <span class="code-job-src ${j.source === 'jadx' ? 'jadx' : 'smali'}">${j.source === 'jadx' ? 'java' : 'smali'}</span>
                <span class="code-job-badge ${j.status.toLowerCase()}">${j.status}</span>
            </div>`).join('');
        list.querySelectorAll('.code-job-item').forEach(row => {
            row.addEventListener('click', () => codeSelectJob(row.dataset.id));
        });
        if (codeJobId) {
            const active = jobs.find(j => j.id === codeJobId);
            if (active && (active.status === 'RUNNING' || active.status === 'PENDING')) {
                if (!codePollTimer) codeStartPoll();
            }
            if (active && active.status === 'DONE' && $('#code-tree .empty')) {
                await codeLoadTree(codeJobId);
                $('#code-zip-btn').disabled = false;
            }
        }
    } catch (_) {}
}

async function codeSelectJob(id) {
    if (id === codeJobId) return;
    codeJobId = id;
    codeStopPoll();
    codeOpenTabs = []; codeTabIdx = -1;
    renderCodeTabs();
    $('#code-zip-btn').disabled = true;
    await codeRefreshJobList();
    try {
        const job = await api.get(`/api/decompiler/jobs/${id}`);
        codeUpdateProgress(job.progress, job.message);
        if (job.status === 'DONE') {
            $('#code-progress-wrap').hidden = true;
            $('#code-zip-btn').disabled = false;
            await codeLoadTree(id);
        } else if (job.status === 'RUNNING' || job.status === 'PENDING') {
            $('#code-progress-wrap').hidden = false;
            codeStartPoll();
        } else {
            $('#code-status').textContent = job.status;
        }
    } catch (e) { toast(e.message, 'err'); }
}

function codeStartPoll() {
    codeStopPoll();
    $('#code-progress-wrap').hidden = false;
    codePollTimer = setInterval(codePoll, 1600);
    codePoll();
}
function codeStopPoll() {
    if (codePollTimer) { clearInterval(codePollTimer); codePollTimer = null; }
}
async function codePoll() {
    if (!codeJobId) return;
    try {
        const job = await api.get(`/api/decompiler/jobs/${codeJobId}`);
        codeUpdateProgress(job.progress, job.message);
        if (job.status === 'DONE') {
            codeStopPoll();
            $('#code-progress-wrap').hidden = true;
            $('#code-zip-btn').disabled = false;
            $('#code-status').textContent = '';
            await codeLoadTree(codeJobId);
            await codeRefreshJobList();
        } else if (job.status === 'ERROR' || job.status === 'CANCELLED') {
            codeStopPoll();
            $('#code-tree').innerHTML = `<div class="empty small" style="color:var(--red)">${fmt.esc(job.message)}</div>`;
            $('#code-status').textContent = job.status;
            await codeRefreshJobList();
        }
    } catch (_) {}
}
function codeUpdateProgress(pct, msg) {
    $('#code-progress-fill').style.width = pct + '%';
    $('#code-progress-msg').textContent  = msg || '';
}

async function codeLoadTree(jobId) {
    $('#code-tree').innerHTML = '<div class="empty small">Loading…</div>';
    try {
        const tree = await api.get(`/api/decompiler/jobs/${jobId}/tree`);
        $('#code-tree').innerHTML = '';
        codeRenderNode(tree, $('#code-tree'), 0, jobId);
    } catch (e) {
        $('#code-tree').innerHTML = `<div class="empty small" style="color:var(--red)">${fmt.esc(e.message)}</div>`;
    }
}

function codeRenderNode(node, container, depth, jobId) {
    const wrap = document.createElement('div');
    const row  = document.createElement('div');
    row.className = 'code-tree-row';
    row.style.paddingLeft = (8 + depth * 13) + 'px';
    const icon = document.createElement('span');
    icon.className = 'ctr-icon';
    const name = document.createElement('span');
    name.className = 'ctr-name';
    name.title = node.path || node.name;
    name.textContent = node.name;
    row.append(icon, name);
    wrap.appendChild(row);
    container.appendChild(wrap);

    if (node.type === 'dir') {
        icon.textContent = '▶';
        icon.style.transition = 'transform .15s';
        const children = document.createElement('div');
        children.className = 'code-tree-children';
        wrap.appendChild(children);
        const autoExpand = depth === 0 || ['sources','resources','res','classes'].includes(node.name);
        if (autoExpand) { children.classList.add('open'); icon.style.transform = 'rotate(90deg)'; }
        (node.children || []).forEach(c => codeRenderNode(c, children, depth + 1, jobId));
        row.addEventListener('click', () => {
            const open = children.classList.toggle('open');
            icon.style.transform = open ? 'rotate(90deg)' : '';
        });
    } else {
        icon.textContent = codeFileIcon(node.language);
        row.addEventListener('click', () => {
            $$('.code-tree-row.selected').forEach(r => r.classList.remove('selected'));
            row.classList.add('selected');
            codeOpenFile(jobId, node.path, node.name, node.language);
        });
    }
}

async function codeOpenFile(jobId, path, name, language) {
    if (!jobId) { toast('No job selected', 'err'); return; }
    if (!path)  { toast('No file path', 'err'); return; }
    const existing = codeOpenTabs.findIndex(t => t.path === path && t.jobId === jobId);
    if (existing >= 0) { codeActivateTab(existing); return; }
    // Always restore viewer visibility first — codeRunSearch sets display:none on it.
    $('#code-viewer').style.display = '';
    $('#code-search-results').classList.add('hidden');
    $('#code-viewer').innerHTML = '<div class="empty"><span class="muted">Loading…</span></div>';
    try {
        const data = await api.get(`/api/decompiler/jobs/${jobId}/file?path=${encodeURIComponent(path)}`);
        if (!data.content && data.error) {
            $('#code-viewer').innerHTML = `<div class="empty" style="color:var(--red)">${fmt.esc(data.error)}</div>`;
            return;
        }
        codeOpenTabs.push({ path, label: name, lang: data.language || language, content: data.content || '', jobId });
        codeActivateTab(codeOpenTabs.length - 1);
    } catch (e) {
        // api.get throws on non-2xx — show the full error including server message
        $('#code-viewer').innerHTML = `<div class="empty" style="color:var(--red)">${fmt.esc(e.message)}<br><small>jobId: ${fmt.esc(jobId)}, path: ${fmt.esc(path)}</small></div>`;
    }
}

function codeActivateTab(idx) {
    codeTabIdx = idx;
    renderCodeTabs();
    const tab = codeOpenTabs[idx];
    if (!tab) return;
    const parts = tab.path.split('/');
    $('#code-crumb').innerHTML = parts.map((p, i) =>
        i < parts.length - 1
            ? `<span>${fmt.esc(p)}</span><span class="sep">/</span>`
            : `<span>${fmt.esc(p)}</span>`
    ).join('');
    const lines = tab.content.split('\n');
    const gutter = lines.map((_, i) => `<div>${i + 1}</div>`).join('');
    let highlighted;
    try {
        if (window.hljs && hljs.getLanguage(tab.lang)) {
            highlighted = hljs.highlight(tab.content, { language: tab.lang, ignoreIllegals: true }).value;
        } else {
            highlighted = codeEsc(tab.content);
        }
    } catch (_) { highlighted = codeEsc(tab.content); }
    $('#code-search-results').classList.add('hidden');
    $('#code-viewer').innerHTML = `
        <div class="code-gutter-wrap">
            <div class="code-gutter">${gutter}</div>
            <div class="code-body">
                <pre class="code-block"><code class="language-${tab.lang}">${highlighted}</code></pre>
            </div>
        </div>`;
}

function renderCodeTabs() {
    $('#code-tabs').innerHTML = codeOpenTabs.map((t, i) => `
        <div class="code-tab ${i === codeTabIdx ? 'active' : ''}" data-idx="${i}" role="tab">
            <span>${fmt.esc(t.label)}</span>
            <span class="code-tab-close" data-idx="${i}">×</span>
        </div>`).join('');
    $$('.code-tab', $('#code-tabs')).forEach(el => {
        el.addEventListener('click', e => {
            if (e.target.classList.contains('code-tab-close')) return;
            codeActivateTab(+el.dataset.idx);
        });
    });
    $$('.code-tab-close', $('#code-tabs')).forEach(el => {
        el.addEventListener('click', () => {
            codeOpenTabs.splice(+el.dataset.idx, 1);
            const next = Math.min(+el.dataset.idx, codeOpenTabs.length - 1);
            if (codeOpenTabs.length) codeActivateTab(next);
            else {
                codeTabIdx = -1;
                renderCodeTabs();
                $('#code-crumb').textContent = '';
                $('#code-viewer').innerHTML = '<div class="empty">Select a file from the tree to view its source.</div>';
            }
        });
    });
}

async function codeRunSearch() {
    const q = $('#code-search-input').value.trim();
    if (!q) { $('#code-search-input').focus(); return; }
    if (!codeJobId) { toast('Start a decompile job first.', 'err'); return; }
    searchJobId = codeJobId;  // snapshot the job at search time
    const sr = $('#code-search-results');
    sr.classList.remove('hidden');
    sr.innerHTML = '<div class="empty small muted">Searching…</div>';
    $('#code-viewer').style.display = 'none';
    try {
        const data = await api.get(`/api/decompiler/jobs/${searchJobId}/search?q=${encodeURIComponent(q)}`);
        if (!data.hits.length) {
            sr.innerHTML = `<div class="empty small muted">No results for <code>${fmt.esc(q)}</code></div>`;
            return;
        }
        const byFile = {};
        data.hits.forEach(h => (byFile[h.path] = byFile[h.path] || []).push(h));
        sr.innerHTML = `<div class="muted small" style="margin-bottom:8px">${data.total} result${data.total !== 1 ? 's' : ''} for <strong>${fmt.esc(q)}</strong></div>` +
            Object.entries(byFile).map(([fp, hits]) => `
                <div class="code-sr-file" data-path="${fmt.esc(fp)}" data-jobid="${fmt.esc(searchJobId)}">${fmt.esc(fp)}</div>
                ${hits.map(h => `<div class="code-sr-line"><span class="code-sr-lineno">${h.line}</span>${codeEsc(h.text.trim())}</div>`).join('')}
            `).join('');
        $$('.code-sr-file', sr).forEach(el => {
            el.addEventListener('click', () => {
                sr.classList.add('hidden');
                $('#code-viewer').style.display = '';
                const fp  = el.dataset.path;
                const jid = el.dataset.jobid || codeJobId;
                codeOpenFile(jid, fp, fp.split('/').pop(), codeLangFromName(fp.split('/').pop()));
            });
        });
    } catch (e) {
        sr.innerHTML = `<div class="empty small" style="color:var(--red)">${fmt.esc(e.message)}</div>`;
    }
}

function codeEsc(s) {
    return String(s ?? '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}
const CODE_LANG_MAP = { smali:'smali', java:'java', kt:'kotlin', xml:'xml', json:'json', txt:'plaintext', md:'markdown' };
function codeLangFromName(name) { return CODE_LANG_MAP[name.split('.').pop()?.toLowerCase()] || 'plaintext'; }
const CODE_FILE_ICONS = { smali:'🔧', java:'☕', kotlin:'🎯', xml:'🗂', json:'📋', markdown:'📝' };
function codeFileIcon(lang) { return CODE_FILE_ICONS[lang] || '📄'; }

// Lazy-load highlight.js on first Code-tab open.
(function ensureHljs() {
    if (window.__hljsLoading || window.hljs) return;
    window.__hljsLoading = true;
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = 'https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css';
    document.head.appendChild(link);
    const s = document.createElement('script');
    s.src = 'https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js';
    s.onload = () => {
        // Register smali — not built into hljs but common in Android pentest.
        hljs.registerLanguage('smali', () => ({
            name: 'Smali',
            keywords: {
                keyword:
                    'invoke-virtual invoke-static invoke-direct invoke-interface invoke-super ' +
                    'invoke-virtual/range invoke-static/range invoke-direct/range ' +
                    'iget iget-object iget-wide iget-boolean iput iput-object iput-wide iput-boolean ' +
                    'sget sget-object sget-wide sget-boolean sput sput-object sput-wide sput-boolean ' +
                    'move move-object move-result move-result-object move-result-wide move-exception ' +
                    'return return-void return-object return-wide ' +
                    'const const/4 const/16 const/high16 const-wide const-string const-class ' +
                    'new-instance new-array filled-new-array check-cast instance-of array-length ' +
                    'if-eq if-ne if-lt if-ge if-gt if-le if-eqz if-nez if-ltz if-gez if-gtz if-lez ' +
                    'goto goto/16 goto/32 switch packed-switch sparse-switch ' +
                    'add-int sub-int mul-int div-int rem-int and-int or-int xor-int ' +
                    'add-int/2addr sub-int/2addr mul-int/2addr div-int/2addr ' +
                    'monitor-enter monitor-exit throw aget aput',
                literal: 'true false null',
            },
            contains: [
                hljs.COMMENT('#', '$'),
                { className: 'string',   begin: '"', end: '"', illegal: '\\n' },
                { className: 'type',     begin: /L[\w/$]+;/ },
                { className: 'keyword',  begin: /^\s*\.(class|super|implements|field|method|end method|annotation|end annotation|registers|prologue|line|catch|catchall|source|param|local)\b/, relevance: 10 },
                { className: 'number',   begin: /\b(0x[\da-fA-F]+|[\d]+[fFdDlL]?)\b/ },
                { className: 'symbol',   begin: /:[a-zA-Z_][\w$]*/ },
                { className: 'variable', begin: /\b[vp]\d+\b/ },
            ]
        }));
    };
    document.head.appendChild(s);
})();

// ============== NOTES tab ==============
let notesDirty   = false;
let notesPreview = false;
let notesSaveTimer = null;

let notesRailWired = false;
function notesSetupRail() {
    if (notesRailWired) return;
    notesRailWired = true;

    const ed = $('#notes-editor');
    ed.addEventListener('input', () => {
        notesDirty = true;
        $('#notes-status').textContent = 'unsaved…';
        if (notesSaveTimer) clearTimeout(notesSaveTimer);
        notesSaveTimer = setTimeout(() => notesSave(true), 1200);
    });
    ed.addEventListener('keydown', e => {
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
            e.preventDefault();
            notesSave(false);
        }
    });

    $('#notes-save').addEventListener('click', () => notesSave(false));
    $('#notes-clear').addEventListener('click', notesClear);
    $('#notes-preview-toggle').addEventListener('click', notesTogglePreview);
    $('#notes-download').addEventListener('click', notesDownload);

    // Collapse / expand the rail
    $('#notes-rail-handle').addEventListener('click', notesToggleRail);

    // Drag-to-resize the rail width
    notesSetupResizer();

    // Alt+N toggles the rail from anywhere
    document.addEventListener('keydown', e => {
        if (e.altKey && e.key.toLowerCase() === 'n') {
            e.preventDefault();
            notesToggleRail();
        }
    });
}

// Remembered expanded width (px). Survives collapse/expand and app switches
// for the session. Clamped to [MIN, MAX] / viewport on apply.
let notesRailWidth = 340;
const NOTES_RAIL_MIN = 240;
const NOTES_RAIL_MAX = 900;

function notesApplyWidth(px) {
    const max = Math.min(NOTES_RAIL_MAX, Math.round(window.innerWidth * 0.7));
    notesRailWidth = Math.max(NOTES_RAIL_MIN, Math.min(px, max));
    const rail = $('#notes-rail');
    if (rail && !rail.classList.contains('collapsed')) {
        rail.style.width = notesRailWidth + 'px';
    }
}

function notesSetupResizer() {
    const rail = $('#notes-rail');
    const grip = $('#notes-rail-resizer');
    if (!rail || !grip) return;

    let startX = 0, startW = 0, dragging = false;

    const onMove = (clientX) => {
        if (!dragging) return;
        // Rail is on the right edge, so dragging left (smaller clientX) widens it.
        const delta = startX - clientX;
        notesApplyWidth(startW + delta);
    };
    const onMouseMove = e => onMove(e.clientX);
    const onTouchMove = e => { if (e.touches[0]) onMove(e.touches[0].clientX); };

    const stop = () => {
        if (!dragging) return;
        dragging = false;
        rail.classList.remove('resizing');
        document.removeEventListener('mousemove', onMouseMove);
        document.removeEventListener('mouseup', stop);
        document.removeEventListener('touchmove', onTouchMove);
        document.removeEventListener('touchend', stop);
    };

    const start = (clientX) => {
        if (rail.classList.contains('collapsed')) return;
        dragging = true;
        startX = clientX;
        startW = rail.getBoundingClientRect().width;
        rail.classList.add('resizing');
        document.addEventListener('mousemove', onMouseMove);
        document.addEventListener('mouseup', stop);
        document.addEventListener('touchmove', onTouchMove, { passive: true });
        document.addEventListener('touchend', stop);
    };

    grip.addEventListener('mousedown', e => { e.preventDefault(); start(e.clientX); });
    grip.addEventListener('touchstart', e => { if (e.touches[0]) start(e.touches[0].clientX); }, { passive: true });

    // Double-click the grip to reset to the default width.
    grip.addEventListener('dblclick', () => notesApplyWidth(340));

    // Keep within bounds if the window is resized smaller.
    window.addEventListener('resize', () => notesApplyWidth(notesRailWidth));
}

function notesToggleRail() {
    const rail = $('#notes-rail');
    if (!rail || rail.hidden) return;
    const collapsed = rail.classList.toggle('collapsed');
    if (collapsed) {
        // Let the CSS collapsed width (38px) take over.
        rail.style.width = '';
    } else {
        // Restore the user's chosen width and focus for quick note-taking.
        rail.style.width = notesRailWidth + 'px';
        setTimeout(() => $('#notes-editor')?.focus(), 180);
    }
}

async function notesLoad() {
    if (!S.pkg) return;
    const ed = $('#notes-editor');
    ed.value = '';
    $('#notes-status').textContent = 'loading…';
    try {
        const data = await api.get(`/api/apps/${encodeURIComponent(S.pkg)}/notes`);
        ed.value = data.markdown || '';
        notesDirty = false;
        $('#notes-status').textContent = data.updatedAt
            ? `saved ${fmt.ago ? fmt.ago(data.updatedAt) : new Date(data.updatedAt).toLocaleString()}`
            : 'no notes yet';
        if (notesPreview) notesRenderPreview();
    } catch (e) {
        $('#notes-status').textContent = '';
        toast(e.message, 'err');
    }
}

async function notesSave(isAuto) {
    if (!S.pkg) return;
    if (notesSaveTimer) { clearTimeout(notesSaveTimer); notesSaveTimer = null; }
    const markdown = $('#notes-editor').value;
    $('#notes-status').textContent = 'saving…';
    try {
        const data = await api.put(`/api/apps/${encodeURIComponent(S.pkg)}/notes`, { markdown });
        notesDirty = false;
        $('#notes-status').textContent = data.updatedAt ? 'saved' : 'empty';
        if (!isAuto) toast('Notes saved', 'ok');
    } catch (e) {
        $('#notes-status').textContent = 'save failed';
        toast(e.message, 'err');
    }
}

async function notesClear() {
    if (!S.pkg) return;
    if (!confirm(`Delete all notes for ${S.pkg}?`)) return;
    try {
        await api.del(`/api/apps/${encodeURIComponent(S.pkg)}/notes`);
        $('#notes-editor').value = '';
        notesDirty = false;
        $('#notes-status').textContent = 'cleared';
        if (notesPreview) notesRenderPreview();
    } catch (e) { toast(e.message, 'err'); }
}

async function notesDownload() {
    if (!S.pkg) return;
    // Save any pending edits first so the downloaded file is current.
    if (notesDirty) {
        try { await notesSave(true); } catch (_) { /* fall through; download whatever's saved */ }
    }
    if (!$('#notes-editor').value.trim()) { toast('Nothing to download', 'err'); return; }
    // Stream via a transient <a download>; the auth cookie ships with the
    // request and Content-Disposition controls the filename.
    const url = `/api/apps/${encodeURIComponent(S.pkg)}/notes/download`;
    const a = document.createElement('a');
    a.href = url;
    a.download = `${S.pkg}-notes.md`;
    document.body.appendChild(a); a.click(); a.remove();
    toast('Downloading notes…', 'ok');
}

function notesTogglePreview() {
    notesPreview = !notesPreview;
    $('#notes-preview-toggle').textContent = notesPreview ? 'Edit' : 'Preview';
    $('#notes-editor').classList.toggle('hidden', notesPreview);
    $('#notes-preview').classList.toggle('hidden', !notesPreview);
    if (notesPreview) notesRenderPreview();
}

function notesRenderPreview() {
    $('#notes-preview').innerHTML = mdToHtml($('#notes-editor').value);
}

/**
 * Tiny dependency-free Markdown → HTML renderer. Covers the subset useful
 * for pentest notes: headings, bold/italic/code, fenced code blocks, links,
 * blockquotes, hr, ordered/unordered lists. All text is HTML-escaped first
 * so notes can never inject markup into the preview.
 */
function mdToHtml(src) {
    const esc = s => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    const lines = src.replace(/\r\n/g, '\n').split('\n');
    let html = '', i = 0;
    let inUl = false, inOl = false;
    const closeLists = () => {
        if (inUl) { html += '</ul>'; inUl = false; }
        if (inOl) { html += '</ol>'; inOl = false; }
    };
    const inline = t => esc(t)
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>')
        .replace(/\[([^\]]+)\]\(([^)]+)\)/g,
            '<a href="$2" target="_blank" rel="noopener">$1</a>');

    while (i < lines.length) {
        const line = lines[i];

        // Fenced code block
        if (/^```/.test(line)) {
            closeLists();
            const buf = [];
            i++;
            while (i < lines.length && !/^```/.test(lines[i])) { buf.push(lines[i]); i++; }
            i++; // skip closing fence
            html += `<pre><code>${esc(buf.join('\n'))}</code></pre>`;
            continue;
        }
        // Heading
        const h = line.match(/^(#{1,6})\s+(.*)$/);
        if (h) { closeLists(); const lvl = h[1].length; html += `<h${lvl}>${inline(h[2])}</h${lvl}>`; i++; continue; }
        // Horizontal rule
        if (/^(\*\*\*|---|___)\s*$/.test(line)) { closeLists(); html += '<hr>'; i++; continue; }
        // Blockquote
        if (/^>\s?/.test(line)) { closeLists(); html += `<blockquote>${inline(line.replace(/^>\s?/, ''))}</blockquote>`; i++; continue; }
        // Unordered list
        if (/^[-*+]\s+/.test(line)) {
            if (inOl) { html += '</ol>'; inOl = false; }
            if (!inUl) { html += '<ul>'; inUl = true; }
            html += `<li>${inline(line.replace(/^[-*+]\s+/, ''))}</li>`; i++; continue;
        }
        // Ordered list
        if (/^\d+\.\s+/.test(line)) {
            if (inUl) { html += '</ul>'; inUl = false; }
            if (!inOl) { html += '<ol>'; inOl = true; }
            html += `<li>${inline(line.replace(/^\d+\.\s+/, ''))}</li>`; i++; continue;
        }
        // Blank line
        if (/^\s*$/.test(line)) { closeLists(); i++; continue; }
        // Paragraph
        closeLists();
        html += `<p>${inline(line)}</p>`;
        i++;
    }
    closeLists();
    return html;
}

// ============== DEEPLINKS tab ==============
let dlData = null;

function initDeeplinks() {
    once('deeplinks', () => {
        $('#dl-refresh').addEventListener('click', () => { S.initialized.deeplinks = false; loadDeeplinks(true); });
        // Enter in the free-form launcher fires it (delegated; input is re-rendered)
        $('#dl-body').addEventListener('keydown', async (e) => {
            if (e.key !== 'Enter') return;
            if (e.target.id === 'dl-launch-uri') {
                const btn = $('.dl-section .dl-launch[data-uri=""]') || e.target.parentElement.querySelector('.dl-launch');
                await dlLaunch(e.target.value.trim(), btn || e.target);
            } else if (e.target.id === 'dl-manual-domain') {
                const dom = e.target.value.trim();
                if (dom) await dlVerify(dom, $('#dl-manual-check'), true);
            }
        });
        // Delegated handlers for per-domain "Verify" buttons + manual check
        $('#dl-body').addEventListener('click', async (e) => {
            const vb = e.target.closest('.dl-verify');
            if (vb) { await dlVerify(vb.dataset.domain, vb); return; }
            const mb = e.target.closest('#dl-manual-check');
            if (mb) {
                const dom = $('#dl-manual-domain').value.trim();
                if (dom) await dlVerify(dom, mb, true);
                return;
            }
            const pc = e.target.closest('.dl-cmd-copy');
            if (pc) {
                navigator.clipboard?.writeText(pc.dataset.cmd).then(
                    () => toast('Command copied', 'ok'),
                    () => toast('Copy failed', 'err'));
                return;
            }
            const lb = e.target.closest('.dl-launch');
            if (lb) {
                // For the free-form launcher button, read the input live.
                const uri = lb.dataset.uri || ($('#dl-launch-uri')?.value || '').trim();
                await dlLaunch(uri, lb);
                return;
            }
            const sp = e.target.closest('.dl-scan-params');
            if (sp) { await dlScanParams(sp.dataset.class, sp); return; }
        });
    });
    loadDeeplinks(false);
}

function refreshDeeplinks() { loadDeeplinks(false); }

async function loadDeeplinks(force) {
    if (!S.pkg) return;
    if (!force && dlData && dlData.packageName === S.pkg) { renderDeeplinks(); return; }
    $('#dl-status').textContent = 'analyzing…';
    $('#dl-body').innerHTML = '<div class="empty small">Reading App Links from manifest…</div>';
    try {
        dlData = await api.get(`/api/apps/${encodeURIComponent(S.pkg)}/deeplinks`);
        $('#dl-status').textContent = '';
        renderDeeplinks();
    } catch (e) {
        $('#dl-status').textContent = '';
        $('#dl-body').innerHTML = `<div class="empty small" style="color:var(--red)">${fmt.esc(e.message)}</div>`;
    }
}

function renderDeeplinks() {
    const d = dlData;
    const fp = d.signingSha256;
    const domains = d.domains || [];

    const fpBlock = `
        <div class="dl-section">
            <div class="dl-section-title">App signing certificate (SHA-256)</div>
            ${fp
                ? `<code class="dl-fp">${fmt.esc(fp)}</code>
                   <div class="muted small">This is the fingerprint that must appear in each domain's assetlinks.json for verification to pass.</div>`
                : `<div class="muted small">Could not read signing certificate.</div>`}
        </div>`;

    // Free-form deeplink launcher — fire an am start VIEW intent for any URI
    // to observe how the app handles it (no component needed).
    const launcherBlock = `
        <div class="dl-section">
            <div class="dl-section-title">Deeplink launcher</div>
            <div class="dl-manual">
                <input class="input mono small" id="dl-launch-uri" placeholder="scheme://host/path  or  https://domain/path" style="flex:1;max-width:420px">
                <button class="btn small dl-launch" data-uri="">▶ Open</button>
            </div>
            <div class="muted small" style="margin-top:4px">Fires <code>am start -a android.intent.action.VIEW -d "&lt;uri&gt;"</code> on the device. If multiple apps handle the URI, Android shows the chooser.</div>
            <div class="dl-launch-result" id="dl-launch-result"></div>
        </div>`;

    const domainsBlock = domains.length ? domains.map(dom => {
        const sampleUri = `${dom.schemes.includes('https') ? 'https' : dom.schemes[0] || 'https'}://${dom.host}/`;
        return `
        <div class="dl-domain">
            <div class="dl-domain-head">
                <span class="dl-host">${fmt.esc(dom.host)}</span>
                ${dom.autoVerify
                    ? '<span class="tg cyan">autoVerify</span>'
                    : '<span class="tg warn">not autoVerified</span>'}
                ${dom.schemes.map(s => `<span class="tg">${s}</span>`).join('')}
                <button class="btn ghost small dl-launch" data-uri="${fmt.esc(sampleUri)}" title="Open ${fmt.esc(sampleUri)}">▶ Open</button>
                <button class="btn small dl-verify" data-domain="${fmt.esc(dom.host)}">Verify assetlinks</button>
            </div>
            <div class="muted small">via ${fmt.esc(dom.component)}</div>
            ${!dom.autoVerify ? `<div class="dl-warn small">⚠ This domain is declared as a web link but the intent-filter is <strong>not</strong> marked <code>android:autoVerify="true"</code> — Android will show a disambiguation dialog instead of opening the app directly, and App Links verification won't run automatically.</div>` : ''}
            <div class="dl-params">
                <button class="btn ghost small dl-scan-params" data-class="${fmt.esc(dom.component)}"><svg class="ic ic-sm"><use href="#i-search"/></svg>Scan params</button>
                <div class="dl-params-result"></div>
            </div>
            <div class="dl-verify-result" data-for="${fmt.esc(dom.host)}"></div>
        </div>`;
    }).join('') : `<div class="empty small">No http/https App Link domains declared in the manifest. The app may still use custom-scheme deeplinks (see the Components tab).</div>`;

    const manualBlock = `
        <div class="dl-section">
            <div class="dl-section-title">Check any domain manually</div>
            <div class="dl-manual">
                <input class="input mono small" id="dl-manual-domain" placeholder="example.com" style="flex:1;max-width:320px">
                <button class="btn small" id="dl-manual-check">Verify assetlinks</button>
            </div>
            <div class="dl-verify-result" data-for="__manual__"></div>
        </div>`;

    // Custom-scheme deeplinks (non-http/https). These can't use App Links
    // verification, so any app registering the same scheme can intercept them.
    const custom = d.customSchemes || [];
    const customBlock = `
        <div class="dl-section">
            <div class="dl-section-title">Custom-scheme deeplinks (${custom.length})</div>
            ${custom.length ? `
                <div class="muted small" style="margin-bottom:10px">
                    Build a hijacking PoC app with
                    <a href="https://github.com/mathis2001/DeepLinkHijackingPoC" target="_blank" rel="noopener">DeepLinkHijackingPoC</a>.
                    Each scheme below has a ready-to-run command.
                </div>
                ${custom.map(s => {
                    const uri = s.example.endsWith('/') ? s.example : s.example + '/';
                    const cmd = `python3 DeepLinkHijacker.py -l "${uri}"`;
                    const fullPath = s.path ? (s.path.startsWith('/') ? s.path : '/' + s.path) : '';
                    return `
                    <div class="dl-domain">
                        <div class="dl-domain-head">
                            <span class="dl-host">${fmt.esc(s.scheme)}://${fmt.esc(s.host || '')}${fmt.esc(fullPath)}</span>
                            ${s.exported ? '<span class="tg danger">exported</span>' : '<span class="tg">private</span>'}
                            ${s.browsable ? '<span class="tg cyan">browsable</span>' : '<span class="tg warn">not browsable</span>'}
                            <button class="btn ghost small dl-launch" data-uri="${fmt.esc(uri)}" title="Open ${fmt.esc(uri)}" style="margin-left:auto">▶ Open</button>
                        </div>
                        <div class="muted small">via ${fmt.esc(s.component)}</div>
                        <div class="dl-warn small">⚠ Custom schemes have no ownership verification — any installed app that registers <code>${fmt.esc(s.scheme)}://</code> can hijack these links.</div>
                        <div class="dl-poc-cmd">
                            <code class="dl-cmd-text" id="${'pocc_' + Math.random().toString(36).slice(2,8)}">${fmt.esc(cmd)}</code>
                            <button class="btn ghost small dl-cmd-copy" data-cmd="${fmt.esc(cmd)}">copy</button>
                        </div>
                        <div class="dl-params">
                            <button class="btn ghost small dl-scan-params" data-class="${fmt.esc(s.component)}"><svg class="ic ic-sm"><use href="#i-search"/></svg>Scan params</button>
                            <div class="dl-params-result"></div>
                        </div>
                    </div>`;
                }).join('')}
            ` : `<div class="empty small">No custom-scheme deeplinks declared.</div>`}
        </div>`;

    $('#dl-body').innerHTML = `
        ${fpBlock}
        ${manualBlock}
        ${launcherBlock}
        <div class="dl-section">
            <div class="dl-section-title">Declared App Link domains (${domains.length})</div>
            ${domainsBlock}
        </div>
        ${customBlock}`;
}

async function dlVerify(domain, btn, isManual) {
    const resultSel = `.dl-verify-result[data-for="${isManual ? '__manual__' : cssEsc(domain)}"]`;
    const box = document.querySelector(resultSel) || (isManual ? document.querySelector('.dl-verify-result[data-for="__manual__"]') : null);
    const orig = btn.textContent;
    btn.disabled = true; btn.textContent = '…';
    if (box) box.innerHTML = '<div class="muted small">Fetching /.well-known/assetlinks.json…</div>';
    try {
        const qp = new URLSearchParams({ domain, pkg: S.pkg });
        if (dlData?.signingSha256) qp.set('fp', dlData.signingSha256);
        const r = await api.get(`/api/deeplinks/assetlinks?${qp.toString()}`);
        if (box) box.innerHTML = renderAssetlinks(r);
    } catch (e) {
        if (box) box.innerHTML = `<div class="dl-warn small" style="color:var(--red)">${fmt.esc(e.message)}</div>`;
    } finally {
        btn.disabled = false; btn.textContent = orig;
    }
}

function renderAssetlinks(r) {
    if (!r.reachable) {
        return `<div class="dl-result err"><strong>✗ Unreachable</strong><div class="muted small">${fmt.esc(r.error || '')}</div><div class="muted small">${fmt.esc(r.url)}</div></div>`;
    }
    if (!r.valid) {
        return `<div class="dl-result err"><strong>✗ Invalid</strong> <span class="muted small">(HTTP ${r.httpStatus ?? '?'})</span><div class="muted small">${fmt.esc(r.error || '')}</div></div>`;
    }

    // The file itself is valid → the card is always green. App authorization is
    // a SEPARATE, secondary status: a valid file that simply doesn't list this
    // app is still a valid file, so it must not be shown as an error.
    const stmts = (r.statements || []).map(s => `
        <div class="dl-stmt">
            <div class="small"><span class="muted">package:</span> <code>${fmt.esc(s.packageName || '—')}</code> <span class="muted">(${fmt.esc(s.namespace || '?')})</span></div>
            <div class="small"><span class="muted">relations:</span> ${(s.relations||[]).map(x=>`<code>${fmt.esc(x.replace('delegate_permission/',''))}</code>`).join(' ') || '—'}</div>
            <div class="small"><span class="muted">fingerprints:</span> ${(s.sha256Fingerprints||[]).length}</div>
        </div>`).join('');

    // Secondary line: does this file authorize the current app?
    const auth = r.authorizesApp;   // true | false | null (not checked)
    let authLine = '';
    if (auth === true) {
        authLine = `<div class="dl-auth small dl-auth-ok">✓ Authorizes this app${r.authDetail ? ' — ' + fmt.esc(r.authDetail) : ''}</div>`;
    } else if (auth === false) {
        authLine = `<div class="dl-auth small dl-auth-bad" style="white-space:pre-wrap">✗ Does not authorize this app${r.authDetail ? '\n' + fmt.esc(r.authDetail) : ''}</div>`;
    } else if (r.authDetail) {
        authLine = `<div class="dl-auth small muted">${fmt.esc(r.authDetail)}</div>`;
    }

    return `
        <div class="dl-result ok">
            <strong>✓ assetlinks.json valid</strong> <span class="muted small">(HTTP 200, ${(r.statements||[]).length} statement(s))</span>
            ${authLine}
            ${r.note ? `<div class="muted small">${fmt.esc(r.note)}</div>` : ''}
            <details class="dl-stmts"><summary class="small">statements</summary>${stmts}</details>
        </div>`;
}

async function dlLaunch(uri, btn) {
    if (!uri) { toast('Enter a deeplink URI', 'err'); $('#dl-launch-uri')?.focus(); return; }
    // Escape double quotes for the shell -d argument.
    const safe = uri.replace(/"/g, '\\"');
    const cmd = `am start -a android.intent.action.VIEW -d "${safe}"`;
    const orig = btn.textContent;
    btn.disabled = true; btn.textContent = '…';
    const resultBox = $('#dl-launch-result');
    try {
        const r = await api.post('/api/live/exec', { command: cmd });
        const out = [r.stdout, r.stderr].filter(Boolean).join('\n').trim();
        const ok = r.code === 0;
        if (resultBox && (btn.dataset.uri === '' || !btn.dataset.uri)) {
            // Only the free-form launcher has a result box; inline buttons toast.
            resultBox.innerHTML = `<div class="dl-result ${ok ? 'ok' : 'err'}">
                <strong>${ok ? '✓ Sent' : '✗ exit ' + r.code}</strong>
                <code class="dl-cmd-text" style="display:block;margin-top:6px">adb shell ${fmt.esc(cmd)}</code>
                ${out ? `<pre class="dl-launch-out">${fmt.esc(out)}</pre>` : ''}
            </div>`;
        } else {
            toast(ok ? `Opened: ${uri}` : `exit ${r.code}: ${out.slice(0,140) || 'no output'}`, ok ? 'ok' : 'err');
        }
    } catch (e) {
        toast(e.message, 'err');
    } finally {
        btn.disabled = false; btn.textContent = orig;
    }
}

async function dlScanParams(cls, btn) {
    const box = btn.parentElement.querySelector('.dl-params-result');
    const orig = btn.innerHTML;
    btn.disabled = true; btn.innerHTML = 'Scanning code…';
    if (box) box.innerHTML = '';
    try {
        const r = await api.get(`/api/apps/${encodeURIComponent(S.pkg)}/deeplinks/params?class=${encodeURIComponent(cls)}`);
        const params = r.params || [];
        if (!params.length) {
            box.innerHTML = '<div class="muted small">No literal Uri parameters found in this component (it may parse the Uri dynamically).</div>';
        } else {
            const q = params.filter(p => p.kind === 'query' || p.kind === 'query-all');
            const pth = params.filter(p => p.kind === 'path');
            box.innerHTML = `
                ${q.length ? `<div class="dl-params-group"><div class="dl-params-h">query parameters</div>${q.map(p =>
                    p.kind === 'query-all'
                        ? `<span class="dl-param-pill all">${fmt.esc(p.name)}</span>`
                        : `<span class="dl-param-pill">${fmt.esc(p.name)}</span>`).join('')}</div>` : ''}
                ${pth.length ? `<div class="dl-params-group"><div class="dl-params-h">path access</div>${pth.map(p =>
                    `<span class="dl-param-pill path">${fmt.esc(p.name)}</span>`).join('')}</div>` : ''}
            `;
        }
        btn.style.display = 'none';
    } catch (e) {
        box.innerHTML = `<div class="dl-warn small" style="color:var(--red)">${fmt.esc(e.message)}</div>`;
        btn.disabled = false; btn.innerHTML = orig;
    }
}

function cssEsc(s) { return String(s).replace(/["\\]/g, '\\$&'); }


// ============== DEVICE FILE EXPLORER tab ==============
let dfPath = '/';

function initDevfiles() {
    once('devfiles', () => {
        $('#df-go').addEventListener('click', () => dfLoad($('#df-path').value.trim() || '/'));
        $('#df-path').addEventListener('keydown', e => { if (e.key === 'Enter') dfLoad($('#df-path').value.trim() || '/'); });
        $('#df-up').addEventListener('click', () => { if (dfParent != null) dfLoad(dfParent); });
        $('#df-zip').addEventListener('click', () => {
            window.location.href = `/api/device/files/zip?path=${encodeURIComponent(dfPath)}`;
        });
        $('.df-quick')?.addEventListener('click', e => {
            const b = e.target.closest('.df-jump'); if (b) dfLoad(b.dataset.path);
        });
        $('#df-list').addEventListener('click', e => {
            const row = e.target.closest('.df-row'); if (!row) return;
            if (row.dataset.dir === '1') dfLoad(row.dataset.path);
            else dfOpenFile(row.dataset.path, row.dataset.name);
        });

        // Upload: button → hidden file input → upload
        $('#df-upload-btn').addEventListener('click', () => $('#df-upload-input').click());
        $('#df-upload-input').addEventListener('change', e => {
            if (e.target.files?.length) dfUpload(e.target.files);
            e.target.value = '';  // allow re-selecting the same file
        });

        // Drag & drop onto the file list
        const dropZone = $('#df-list');
        ['dragenter', 'dragover'].forEach(ev => dropZone.addEventListener(ev, e => {
            e.preventDefault(); e.stopPropagation();
            dropZone.classList.add('df-dragover');
        }));
        ['dragleave', 'drop'].forEach(ev => dropZone.addEventListener(ev, e => {
            e.preventDefault(); e.stopPropagation();
            if (ev === 'dragleave' && dropZone.contains(e.relatedTarget)) return;
            dropZone.classList.remove('df-dragover');
        }));
        dropZone.addEventListener('drop', e => {
            const files = e.dataTransfer?.files;
            if (files?.length) dfUpload(files);
        });
    });
    dfLoad(dfPath);
}
function refreshDevfiles() { /* keep current dir on tab re-entry */ }

let dfParent = null;
async function dfLoad(path) {
    $('#df-list').innerHTML = '<div class="empty small">Loading…</div>';
    try {
        const d = await api.get(`/api/device/files?path=${encodeURIComponent(path)}`);
        dfPath = d.path; dfParent = d.parent;
        $('#df-path').value = d.path;
        $('#df-up').disabled = d.parent == null;
        if (!d.entries.length) {
            $('#df-list').innerHTML = '<div class="empty small">empty directory</div>';
            return;
        }
        $('#df-list').innerHTML = d.entries.map(it => `
            <div class="df-row" data-path="${fmt.esc(it.path)}" data-name="${fmt.esc(it.name)}" data-dir="${it.isDir ? '1' : '0'}">
                <span class="df-icon">${it.isDir ? '📁' : dfFileIcon(it.name)}</span>
                <span class="df-name">${fmt.esc(it.name)}</span>
                <span class="df-meta">${it.isDir ? '' : fmtSize(it.size)}</span>
            </div>`).join('');
    } catch (e) {
        $('#df-list').innerHTML = `<div class="empty small" style="color:var(--red)">${fmt.esc(e.message)}</div>`;
    }
}

async function dfOpenFile(path, name) {
    const viewer = $('#df-viewer');
    const dl = `/api/device/files/raw?path=${encodeURIComponent(path)}&download=1`;
    const ext = name.split('.').pop().toLowerCase();
    const isImg = ['png','jpg','jpeg','gif','webp'].includes(ext);
    if (isImg) {
        viewer.innerHTML = `
            <div class="df-viewer-head"><code>${fmt.esc(path)}</code>
                <a class="btn ghost small" href="${dl}">download</a></div>
            <div class="df-img-wrap"><img src="/api/device/files/raw?path=${encodeURIComponent(path)}" alt="${fmt.esc(name)}"></div>`;
        return;
    }
    viewer.innerHTML = '<div class="empty small">Loading…</div>';
    try {
        const d = await api.get(`/api/device/files/text?path=${encodeURIComponent(path)}`);
        viewer.innerHTML = `
            <div class="df-viewer-head"><code>${fmt.esc(path)}</code>
                <a class="btn ghost small" href="${dl}">download</a></div>
            ${d.truncated ? '<div class="muted small">(truncated preview)</div>' : ''}
            <pre class="df-filebody">${fmt.esc(d.content)}</pre>`;
    } catch (e) {
        viewer.innerHTML = `
            <div class="df-viewer-head"><code>${fmt.esc(path)}</code>
                <a class="btn ghost small" href="${dl}">download</a></div>
            <div class="empty small" style="color:var(--red)">Can't preview as text — ${fmt.esc(e.message)}. Use download.</div>`;
    }
}

async function dfUpload(fileList) {
    const files = Array.from(fileList);
    const form = new FormData();
    files.forEach(f => form.append('file', f, f.name));
    const names = files.map(f => f.name).join(', ');
    toast(`Uploading ${files.length} file${files.length>1?'s':''}…`, 'ok');
    try {
        // Don't set Content-Type — the browser sets the multipart boundary.
        const r = await fetchAuthed(
            `/api/device/files/upload?path=${encodeURIComponent(dfPath)}`,
            { method: 'POST', body: form }
        );
        if (!r.ok) { throw await explainError(r); }
        const res = await r.json();
        if (res.ok) {
            toast(`Uploaded: ${res.written.join(', ')}`, 'ok');
        } else if (res.written.length) {
            toast(`Partial: wrote ${res.written.join(', ')}; failed ${res.errors.join('; ')}`, 'err');
        } else {
            toast(`Upload failed: ${res.errors.join('; ') || 'unknown error'}`, 'err');
        }
        dfLoad(dfPath);   // refresh listing to show the new files
    } catch (e) {
        toast(e.message, 'err');
    }
}

function dfFileIcon(name) {
    const ext = name.split('.').pop().toLowerCase();
    if (['png','jpg','jpeg','gif','webp','bmp'].includes(ext)) return '🖼';
    if (['db','sqlite','sqlite3'].includes(ext)) return '🗃';
    if (['apk','jar','zip','tar','gz'].includes(ext)) return '📦';
    if (['xml','json','txt','log','conf','prop','ini','md'].includes(ext)) return '📄';
    if (['so','bin'].includes(ext)) return '⚙';
    return '📄';
}
function fmtSize(n) {
    if (n == null) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024*1024) return (n/1024).toFixed(1) + ' KB';
    if (n < 1024*1024*1024) return (n/1024/1024).toFixed(1) + ' MB';
    return (n/1024/1024/1024).toFixed(1) + ' GB';
}

// ============== SNAPSHOTS tab ==============
let snapList = [];

function initSnapshots() {
    once('snapshots', () => {
        $('#snap-capture').addEventListener('click', snapCapture);
        $('#snap-diff').addEventListener('click', snapRunDiff);
        $('#snap-list').addEventListener('click', e => {
            const del = e.target.closest('.snap-del');
            if (del) { snapDelete(del.dataset.id); return; }
            const exp = e.target.closest('.snap-export');
            if (exp) { snapExport(exp.dataset.id); return; }
        });
        // Snapshot APK: upload from computer
        $('#snap-apk-btn').addEventListener('click', () => $('#snap-apk-input').click());
        $('#snap-apk-input').addEventListener('change', e => {
            if (e.target.files?.length) snapCaptureApk(e.target.files[0]);
            e.target.value = '';
        });
        // Import snapshot JSON
        $('#snap-import-btn').addEventListener('click', () => $('#snap-import-input').click());
        $('#snap-import-input').addEventListener('change', e => {
            if (e.target.files?.length) snapImport(e.target.files[0]);
            e.target.value = '';
        });
    });
    snapLoadList();
}
function refreshSnapshots() { snapLoadList(); }

async function snapLoadList() {
    if (!S.pkg) return;
    $('#snap-status').textContent = '';
    try {
        snapList = await api.get(`/api/snapshots/${encodeURIComponent(S.pkg)}`);
        renderSnapList();
        fillSnapSelects();
    } catch (e) {
        $('#snap-list').innerHTML = `<div class="empty small" style="color:var(--red)">${fmt.esc(e.message)}</div>`;
    }
}

function renderSnapList() {
    if (!snapList.length) {
        $('#snap-list').innerHTML = '<div class="empty small">No snapshots yet. Click “Snapshot now”.</div>';
        return;
    }
    $('#snap-list').innerHTML = snapList.map(s => `
        <div class="snap-item">
            <div class="snap-item-main">
                <span class="snap-ver">v${fmt.esc(s.versionName || '?')} (${s.versionCode})</span>
                <span class="snap-when">${new Date(s.createdAt).toLocaleString()}</span>
            </div>
            <div class="muted small">${s.source.startsWith('apk') ? '📦 APK' : '📱 installed'} · ${s.componentCount} comp · ${s.classCount} classes</div>
            <div class="snap-item-actions">
                <button class="btn ghost small snap-export" data-id="${fmt.esc(s.id)}" title="Export snapshot to a .json file">export</button>
                <button class="btn ghost small snap-del" data-id="${fmt.esc(s.id)}" title="Delete snapshot">✕</button>
            </div>
        </div>`).join('');
}

function fillSnapSelects() {
    const opts = snapList.map(s =>
        `<option value="${fmt.esc(s.id)}">v${fmt.esc(s.versionName||'?')} (${s.versionCode}) · ${new Date(s.createdAt).toLocaleString()}</option>`
    ).join('');
    const a = $('#snap-a'), b = $('#snap-b');
    a.innerHTML = opts; b.innerHTML = opts;
    // Default: a = older (last), b = newer (first)
    if (snapList.length >= 2) {
        a.value = snapList[snapList.length - 1].id;
        b.value = snapList[0].id;
    }
}

async function snapCapture() {
    if (!S.pkg) { toast('Pick an app first', 'err'); return; }
    const btn = $('#snap-capture'); btn.disabled = true;
    $('#snap-status').textContent = 'capturing…';
    try {
        const includeDataDir = $('#snap-include-data').checked;
        await api.post(`/api/snapshots/${encodeURIComponent(S.pkg)}`, { includeDataDir });
        $('#snap-status').textContent = 'snapshot saved';
        toast('Snapshot captured', 'ok');
        await snapLoadList();
    } catch (e) { toast(e.message, 'err'); $('#snap-status').textContent = ''; }
    finally { btn.disabled = false; }
}

async function snapCaptureApk(file) {
    const pkg = S.pkg;
    if (!pkg) { toast('Pick an app first (it labels where the snapshot is stored)', 'err'); return; }
    $('#snap-status').textContent = `uploading ${file.name}…`;
    try {
        const form = new FormData();
        form.append('file', file, file.name);
        const r = await fetchAuthed(
            `/api/snapshots/apk?pkg=${encodeURIComponent(pkg)}`,
            { method: 'POST', body: form }
        );
        if (!r.ok) throw await explainError(r);
        await r.json();
        toast('APK snapshot captured', 'ok');
        $('#snap-status').textContent = 'snapshot saved';
        await snapLoadList();
    } catch (e) { toast(e.message, 'err'); $('#snap-status').textContent = ''; }
}

function snapExport(id) {
    // Stream the JSON down via a transient <a download>; the auth cookie rides along.
    const url = `/api/snapshots/${encodeURIComponent(S.pkg)}/${encodeURIComponent(id)}/export`;
    const a = document.createElement('a');
    a.href = url; a.download = `snapshot-${S.pkg}-${id}.json`;
    document.body.appendChild(a); a.click(); a.remove();
    toast('Exporting snapshot…', 'ok');
}

async function snapImport(file) {
    $('#snap-status').textContent = `importing ${file.name}…`;
    try {
        const form = new FormData();
        form.append('file', file, file.name);
        const r = await fetchAuthed('/api/snapshots/import', { method: 'POST', body: form });
        if (!r.ok) throw await explainError(r);
        await r.json();
        toast('Snapshot imported', 'ok');
        $('#snap-status').textContent = 'imported';
        await snapLoadList();
    } catch (e) { toast(e.message, 'err'); $('#snap-status').textContent = ''; }
}

async function snapDelete(id) {
    if (!confirm('Delete this snapshot?')) return;
    try {
        await api.del(`/api/snapshots/${encodeURIComponent(S.pkg)}/${encodeURIComponent(id)}`);
        await snapLoadList();
    } catch (e) { toast(e.message, 'err'); }
}

async function snapRunDiff() {
    const a = $('#snap-a').value, b = $('#snap-b').value;
    if (!a || !b) { toast('Need two snapshots', 'err'); return; }
    if (a === b) { toast('Pick two different snapshots', 'err'); return; }
    $('#snap-diff-view').innerHTML = '<div class="empty small">Diffing…</div>';
    try {
        const d = await api.get(`/api/snapshots/${encodeURIComponent(S.pkg)}/diff?a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}`);
        renderSnapDiff(d);
    } catch (e) {
        $('#snap-diff-view').innerHTML = `<div class="empty small" style="color:var(--red)">${fmt.esc(e.message)}</div>`;
    }
}

function renderSnapDiff(d) {
    const sec = (title, body, count) => `
        <div class="snap-diff-sec">
            <div class="snap-diff-title">${title} ${count != null ? `<span class="count">${count}</span>` : ''}</div>
            ${body}
        </div>`;

    // Expandable list: shows the first `cap` items, the remainder behind a
    // "show N more" <details> toggle — so nothing is permanently hidden.
    const CAP = 100;
    const list = (arr, cls) => {
        if (!arr.length) return '<div class="muted small">none</div>';
        const head = arr.slice(0, CAP);
        const rest = arr.slice(CAP);
        const li = x => `<li>${fmt.esc(x)}</li>`;
        let html = `<ul class="snap-diff-list ${cls||''}">${head.map(li).join('')}</ul>`;
        if (rest.length) {
            html += `<details class="snap-more">
                <summary>show ${rest.length} more</summary>
                <ul class="snap-diff-list ${cls||''}">${rest.map(li).join('')}</ul>
            </details>`;
        }
        return html;
    };

    const header = `
        <div class="snap-diff-head">
            <strong>${fmt.esc(d.from.versionName)} (${d.from.versionCode})</strong> →
            <strong>${fmt.esc(d.to.versionName)} (${d.to.versionCode})</strong>
            ${d.versionChanged ? '<span class="tg cyan">version changed</span>' : '<span class="tg">same version</span>'}
            ${d.signingChanged ? '<span class="tg danger">signing cert CHANGED</span>' : ''}
        </div>
        <div class="muted small" style="margin-bottom:12px">
            ${d.minSdkChange ? `minSdk ${fmt.esc(d.minSdkChange)} · ` : ''}
            ${d.targetSdkChange ? `targetSdk ${fmt.esc(d.targetSdkChange)} · ` : ''}
            manifest ${d.manifestChanged ? '<span style="color:var(--accent)">changed</span>' : 'unchanged'}
        </div>`;

    // Manifest security attributes (minSdk/targetSdk direction, backup, cleartext)
    const mfChanges = d.manifestSecurityChanges || [];
    const sevIcon = { good: '✓', info: 'ℹ', warn: '⚠' };
    const manifestSec = sec('Manifest security',
        mfChanges.length
            ? `<ul class="snap-mf-list">${mfChanges.map(m => `
                <li class="snap-mf-row sev-${fmt.esc(m.severity)}">
                    <span class="snap-mf-icon">${sevIcon[m.severity] || '•'}</span>
                    <span class="snap-mf-attr">${fmt.esc(m.attribute)}</span>
                    <span class="snap-mf-val">${fmt.esc(m.from)} → ${fmt.esc(m.to)}</span>
                    <span class="snap-mf-note">${fmt.esc(m.note)}</span>
                </li>`).join('')}</ul>`
            : '<div class="muted small">no security-relevant manifest changes</div>',
        mfChanges.length || null);

    const perms = sec('Permissions',
        `<div class="snap-cols">
            <div><div class="snap-col-h added">+ added (${d.permsAdded.length})</div>${list(d.permsAdded,'added')}</div>
            <div><div class="snap-col-h removed">− removed (${d.permsRemoved.length})</div>${list(d.permsRemoved,'removed')}</div>
        </div>`, d.permsAdded.length + d.permsRemoved.length);

    const compFmt = c => `${c.type}: ${c.name}${c.exported ? ' [exported]' : ''}`;
    const comps = sec('Components',
        `<div class="snap-cols">
            <div><div class="snap-col-h added">+ added (${d.componentsAdded.length})</div>${list(d.componentsAdded.map(compFmt),'added')}</div>
            <div><div class="snap-col-h removed">− removed (${d.componentsRemoved.length})</div>${list(d.componentsRemoved.map(compFmt),'removed')}</div>
        </div>
        ${d.componentsChanged.length ? `<div class="snap-col-h changed">~ changed (${d.componentsChanged.length})</div>${list(d.componentsChanged.map(c=>`${c.type}: ${c.name} — ${c.changes.join('; ')}`),'changed')}` : ''}`,
        d.componentsAdded.length + d.componentsRemoved.length + d.componentsChanged.length);

    const deeplinks = sec('Deeplinks',
        `<div class="snap-cols">
            <div><div class="snap-col-h added">+ added (${(d.deeplinksAdded||[]).length})</div>${list(d.deeplinksAdded||[],'added')}</div>
            <div><div class="snap-col-h removed">− removed (${(d.deeplinksRemoved||[]).length})</div>${list(d.deeplinksRemoved||[],'removed')}</div>
        </div>`,
        (d.deeplinksAdded||[]).length + (d.deeplinksRemoved||[]).length);

    const native = sec('Native libraries',
        `<div class="snap-cols">
            <div><div class="snap-col-h added">+ added (${(d.nativeAdded||[]).length})</div>${list(d.nativeAdded||[],'added')}</div>
            <div><div class="snap-col-h removed">− removed (${(d.nativeRemoved||[]).length})</div>${list(d.nativeRemoved||[],'removed')}</div>
        </div>
        ${(d.nativeChanged||[]).length ? `<div class="snap-col-h changed">~ modified (${d.nativeChanged.length})</div>${list(d.nativeChanged,'changed')}` : ''}`,
        (d.nativeAdded||[]).length + (d.nativeRemoved||[]).length + (d.nativeChanged||[]).length);

    const code = sec('Code (classes)',
        `<div class="snap-cols">
            <div><div class="snap-col-h added">+ added (${d.classesAdded.length})</div>${list(d.classesAdded,'added')}</div>
            <div><div class="snap-col-h removed">− removed (${d.classesRemoved.length})</div>${list(d.classesRemoved,'removed')}</div>
        </div>
        ${d.classesChanged.length ? `<div class="snap-col-h changed">~ method-count changed (${d.classesChanged.length})</div>${list(d.classesChanged.map(c=>`${c.name}: ${c.before} → ${c.after} methods`),'changed')}` : ''}`,
        d.classesAdded.length + d.classesRemoved.length + d.classesChanged.length);

    const data = sec('Data dir',
        `<div class="snap-cols">
            <div><div class="snap-col-h added">+ added (${d.dataAdded.length})</div>${list(d.dataAdded,'added')}</div>
            <div><div class="snap-col-h removed">− removed (${d.dataRemoved.length})</div>${list(d.dataRemoved,'removed')}</div>
        </div>
        ${d.dataChanged.length ? `<div class="snap-col-h changed">~ modified (${d.dataChanged.length})</div>${list(d.dataChanged,'changed')}` : ''}`,
        d.dataAdded.length + d.dataRemoved.length + d.dataChanged.length);

    $('#snap-diff-view').innerHTML = header + manifestSec + perms + comps + deeplinks + native + code + data;
}

// ============== WEB tab ==============
let webData = null;

function initWeb() {
    once('web', () => {
        $('#web-scan').addEventListener('click', () => webScan());
        $('#web-filter').addEventListener('input', renderWeb);
        $('#web-export').addEventListener('click', webExport);
        $('#web-hide-boilerplate').addEventListener('click', e => {
            const on = e.currentTarget.getAttribute('aria-pressed') === 'true';
            e.currentTarget.setAttribute('aria-pressed', on ? 'false' : 'true');
            renderWeb();
        });
    });
    // Don't auto-scan (full-DEX walk is heavy); wait for the button.
    if (webData && webData._pkg === S.pkg) renderWeb();
    else $('#web-body').innerHTML = '<div class="empty">Click “Scan” to extract URLs, endpoints and parameters from this app.</div>';
}
function refreshWeb() {
    if (webData && webData._pkg === S.pkg) renderWeb();
    else $('#web-body').innerHTML = '<div class="empty">Click “Scan” to extract URLs, endpoints and parameters.</div>';
}

async function webScan() {
    if (!S.pkg) { toast('Pick an app first', 'err'); return; }
    const btn = $('#web-scan'); btn.disabled = true;
    $('#web-status').textContent = 'scanning code…';
    $('#web-body').innerHTML = '<div class="empty small">Walking the DEX — this can take a moment on large apps…</div>';
    try {
        const d = await api.get(`/api/apps/${encodeURIComponent(S.pkg)}/web`);
        d._pkg = S.pkg;
        webData = d;
        $('#web-status').textContent = `${d.urls.length} URLs · ${d.endpoints.length} endpoints · ${d.params.length} params`;
        renderWeb();
    } catch (e) {
        $('#web-status').textContent = '';
        $('#web-body').innerHTML = `<div class="empty small" style="color:var(--red)">${fmt.esc(e.message)}</div>`;
    } finally { btn.disabled = false; }
}

function renderWeb() {
    if (!webData) return;
    const q = ($('#web-filter')?.value || '').trim().toLowerCase();
    const match = s => !q || s.toLowerCase().includes(q);
    const hideBoilerplate = $('#web-hide-boilerplate')?.getAttribute('aria-pressed') === 'true';

    let urls = webData.urls.filter(u => match(u.url));
    if (hideBoilerplate) urls = urls.filter(u => !webIsBoilerplate(u.url));
    const endpoints = webData.endpoints.filter(e => match(e.path) || e.params.some(match));
    const params = webData.params.filter(match);

    const srcBadges = sources => (sources || []).map(s =>
        `<span class="web-src web-src-${s.replace(/[^a-z0-9]/gi,'')}">${fmt.esc(s)}</span>`).join('');

    const urlRows = urls.length ? urls.map(u => `
        <div class="web-row">
            <a class="web-url" href="${fmt.esc(u.url)}" target="_blank" rel="noopener">${fmt.esc(u.url)}</a>
            ${srcBadges(u.sources)}
            <button class="btn ghost small web-copy" data-copy="${fmt.esc(u.url)}">copy</button>
        </div>`).join('') : '<div class="empty small">none</div>';

    const epRows = endpoints.length ? endpoints.map(e => `
        <div class="web-row">
            <code class="web-ep">${fmt.esc(e.path)}</code>
            ${e.params.length ? `<span class="web-ep-params">${e.params.map(p=>`<span class="web-pill">${fmt.esc(p)}</span>`).join('')}</span>` : ''}
            ${srcBadges(e.sources)}
            <button class="btn ghost small web-copy" data-copy="${fmt.esc(e.path)}" style="margin-left:auto">copy</button>
        </div>`).join('') : '<div class="empty small">none</div>';

    const paramPills = params.length
        ? `<div class="web-params">${params.map(p=>`<span class="web-pill">${fmt.esc(p)}</span>`).join('')}</div>`
        : '<div class="empty small">none</div>';

    // WebView security findings
    const wvFindings = (webData.webviews || []);
    const sevColor = { HIGH: 'red', MEDIUM: 'orange', LOW: 'blue', INFO: 'muted' };
    const wvRows = wvFindings.length ? wvFindings.map(f => {
        const argBadge = f.argument != null
            ? `<span class="web-pill ${f.argument === 'true' ? 'pill-danger' : 'pill-safe'}">${fmt.esc(f.argument)}</span>`
            : '';
        const sev = f.severity || 'INFO';
        const desc = WV_DESCRIPTIONS[f.method] || '';
        return `<div class="wv-row wv-${sev.toLowerCase()}">
            <span class="wv-sev sev-${sev.toLowerCase()}">${sev}</span>
            <div class="wv-detail">
                <code class="wv-method">${fmt.esc(f.method)}(${f.argument != null ? fmt.esc(f.argument) : '…'})</code>
                ${argBadge}
                <span class="wv-class muted small">${fmt.esc(f.callerClass)}</span>
                ${desc ? `<div class="wv-desc muted small">${fmt.esc(desc)}</div>` : ''}
            </div>
        </div>`;
    }).join('') : '<div class="empty small">No sensitive WebView API calls detected.</div>';

    $('#web-body').innerHTML = `
        <div class="web-sec">
            <div class="web-sec-h">WebView Security <span class="count">${wvFindings.length}</span></div>
            ${wvRows}
        </div>
        <div class="web-sec">
            <div class="web-sec-h">URLs <span class="count">${urls.length}</span></div>
            ${urlRows}
        </div>
        <div class="web-sec">
            <div class="web-sec-h">Potential API endpoints <span class="count">${endpoints.length}</span></div>
            ${epRows}
        </div>
        <div class="web-sec">
            <div class="web-sec-h">Potential parameters <span class="count">${params.length}</span></div>
            ${paramPills}
        </div>`;

    $$('.web-copy', $('#web-body')).forEach(b => b.onclick = () => {
        navigator.clipboard?.writeText(b.dataset.copy).then(
            () => toast('Copied', 'ok'), () => toast('Copy failed', 'err'));
    });
}

const WV_DESCRIPTIONS = {
    setJavaScriptEnabled:               'Enables JavaScript execution in the WebView. Required for XSS attacks if content is not properly sanitised.',
    setAllowUniversalAccessFromFileURLs:'Allows file:// URLs to access content from any origin. Critical risk — enables full local file read from JS.',
    setAllowFileAccessFromFileURLs:     'Allows file:// URLs to access other file:// URLs. Enables local file theft via crafted HTML.',
    setAllowFileAccess:                 'Allows WebView to access the device file system via file:// URIs.',
    setWebContentsDebuggingEnabled:     'Enables Chrome DevTools remote debugging of this WebView. Should never be true in production.',
    setAllowContentAccess:              'Allows access to content:// URIs (ContentProvider). May expose internal app data.',
    addJavascriptInterface:             'Exposes a Java object to JavaScript. If JS is enabled and input is not sanitised, attackers can call arbitrary Java methods.'
};

// Mirrors WebExtractor.isBoilerplateHost — hosts that are XML namespaces,
// schema/spec references, or doc placeholders, never a real endpoint.
const WEB_BOILERPLATE_HOSTS = new Set([
    'www.w3.org','w3.org','schemas.android.com','ns.adobe.com',
    'xmlpull.org','www.xmlpull.org','xml.org','www.xml.org',
    'java.sun.com','sun.com','aomedia.org','www.aomedia.org',
    'iptc.org','www.iptc.org','purl.org','www.example.com',
    'example.com','example.org','example.net','schema.org','www.schema.org',
    'apache.org','www.apache.org','xml.apache.org','relaxng.org','docbook.org'
]);
function webIsBoilerplate(url) {
    let host;
    try {
        host = new URL(url).hostname.toLowerCase();
    } catch {
        host = (url.split('://')[1] || '').split('/')[0].split('?')[0].split('@').pop().split(':')[0].toLowerCase();
    }
    if (!host) return false;
    if (WEB_BOILERPLATE_HOSTS.has(host)) return true;
    if (host.startsWith('schemas.') || host.startsWith('ns.') || host.startsWith('xmlns.')) return true;
    if (host.endsWith('.w3.org')) return true;
    if (host === 'example.com' || host.endsWith('.example.com') || host.endsWith('.example')) return true;
    return false;
}

function webExport() {
    if (!webData || !webData.urls.length) { toast('Nothing to export — scan first', 'err'); return; }
    const hideBoilerplate = $('#web-hide-boilerplate')?.getAttribute('aria-pressed') === 'true';
    let list = webData.urls;
    if (hideBoilerplate) list = list.filter(u => !webIsBoilerplate(u.url));
    const blob = new Blob([list.map(u => u.url).join('\n')], { type: 'text/plain' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${S.pkg}-urls.txt`;
    document.body.appendChild(a); a.click(); a.remove();
    URL.revokeObjectURL(a.href);
}

// ============== OVERLAY / TAPJACKING tab ==============
async function initOverlay() {
    once('overlay', () => {
        $('#ov-opacity').addEventListener('input', e => {
            $('#ov-opacity-val').textContent = e.target.value + '%';
        });
        $('#ov-launch').addEventListener('click', ovLaunch);
        $('#ov-show').addEventListener('click', () => ovSendOverlay(false));
        $('#ov-update').addEventListener('click', () => ovSendOverlay(true));
        $('#ov-dismiss').addEventListener('click', ovDismiss);
        $('#ov-perm-open').addEventListener('click', async () => {
            await api.post('/api/live/exec', {
                command: `am start -a android.settings.action.MANAGE_OVERLAY_PERMISSION -d package:${S.pkg}`
            });
        });
    });
    ovCheckPerm();
    await ovPopulateActivities();
}
function refreshOverlay() { ovCheckPerm(); ovPopulateActivities(); }

async function ovCheckPerm() {
    try {
        const s = await api.get('/api/overlay/status');
        const warn = $('#ov-perm-warn');
        if (!s.canDraw) warn.classList.remove('hidden');
        else warn.classList.add('hidden');
    } catch (_) {}
}

async function ovPopulateActivities() {
    const sel = $('#ov-activity');
    if (!S.pkg) { sel.innerHTML = '<option value="">— pick an app first —</option>'; return; }
    sel.innerHTML = '<option value="">Loading…</option>';
    try {
        // Fetch directly — don't rely on S.componentsData being populated by
        // the Components tab. Both can coexist since the server is stateless.
        const comps = S.componentsData
            || await api.get(`/api/apps/${encodeURIComponent(S.pkg)}/components`);
        const exported = (comps.activities || [])
            .filter(c => c.exported)
            .map(c => c.name);
        sel.innerHTML = exported.length
            ? exported.map(a => `<option value="${fmt.esc(a)}">${fmt.esc(a)}</option>`).join('')
            : '<option value="">— no exported activities found —</option>';
    } catch (e) {
        sel.innerHTML = '<option value="">— failed to load —</option>';
        toast(e.message, 'err');
    }
}

function ovParams() {
    return {
        text:      $('#ov-text').value,
        x:         parseInt($('#ov-x').value) || 100,
        y:         parseInt($('#ov-y').value) || 400,
        widthDp:   parseInt($('#ov-w').value) || 220,
        heightDp:  parseInt($('#ov-h').value) || 80,
        bgColor:   $('#ov-bg').value,
        textColor: $('#ov-fg').value,
        textSize:  parseFloat($('#ov-textsize').value) || 16,
        opacity:   parseInt($('#ov-opacity').value) / 100
    };
}

async function ovLaunch() {
    const activity = $('#ov-activity').value;
    if (!activity) { toast('Pick an activity first', 'err'); return; }
    const btn = $('#ov-launch'); btn.disabled = true;
    try {
        const r = await api.post('/api/overlay/launch', { pkg: S.pkg, activity });
        const out = [r.stdout, r.stderr].filter(Boolean).join(' ').trim();
        $('#ov-launch-out').textContent = r.ok
            ? `✓ launched ${r.target || ''}` + (out ? ' — ' + out : '')
            : `✗ exit ${r.code}: ${out || 'no output'} (target: ${r.target || activity})`;
        if (!r.ok) toast(`am start failed (exit ${r.code})`, 'err');
    } catch (e) { toast(e.message, 'err'); }
    finally { btn.disabled = false; }
}

async function ovSendOverlay(isUpdate) {
    try {
        await api.post('/api/overlay/show', ovParams());
        $('#ov-status').textContent = isUpdate ? '✓ overlay updated' : '✓ overlay shown';
        if (!isUpdate) toast('Overlay shown on device', 'ok');
        await ovCheckPerm();
    } catch (e) {
        const msg = e.message || String(e);
        $('#ov-status').textContent = '✗ ' + msg;
        toast(msg, 'err');
    }
}

async function ovDismiss() {
    try {
        await api.del('/api/overlay');
        $('#ov-status').textContent = 'overlay dismissed';
        toast('Overlay dismissed', 'ok');
    } catch (e) { toast(e.message, 'err'); }
}

// ============== ENVIRONMENT SETUP tab ==============
function initEnvsetup() {
    once('envsetup', () => {
        // Certificate — compute hash and show adb commands
        const certInput = $('#env-cert-input');
        const certInstall = $('#env-cert-install');
        let certFile = null;

        certInput.addEventListener('change', e => {
            certFile = e.target.files?.[0] || null;
            $('#env-cert-name').textContent = certFile ? certFile.name : '';
            certInstall.disabled = !certFile;
        });

        certInstall.addEventListener('click', async () => {
            if (!certFile) return;
            certInstall.disabled = true;
            const res = $('#env-cert-result');
            res.textContent = 'Computing hash…'; res.className = 'env-result'; res.style.whiteSpace = 'pre-wrap';
            try {
                const form = new FormData();
                form.append('file', certFile, certFile.name);
                const r = await fetchAuthed('/api/env/cert', { method: 'POST', body: form });
                const d = await r.json();
                const msg = d.message || d.error || JSON.stringify(d);
                res.textContent = msg;
                res.className = 'env-result ' + (d.success ? 'ok' : 'err');
                if (d.success) {
                    certFile = null; certInput.value = '';
                    $('#env-cert-name').textContent = '';
                }
            } catch (e) {
                res.textContent = e.message || String(e);
                res.className = 'env-result err';
            } finally { if (certFile) certInstall.disabled = false; }
        });

        // Frida manager
        $('#frida-start').addEventListener('click', async () => {
            await fridaAction('start'); fridaLoadStatus();
        });
        $('#frida-stop').addEventListener('click', async () => {
            await fridaAction('stop'); fridaLoadStatus();
        });
        $('#frida-refresh').addEventListener('click', fridaLoadStatus);
        $('#frida-install').addEventListener('click', async () => {
            const ver = $('#frida-version-select').value;
            if (!ver) { toast('Pick a version', 'err'); return; }
            const btn = $('#frida-install'); btn.disabled = true;
            const st = $('#frida-install-status');
            st.textContent = `Downloading frida-server ${ver}…`;
            st.className = 'env-result';
            try {
                const r = await api.post(`/api/env/frida/install?version=${encodeURIComponent(ver)}`);
                st.textContent = r.message;
                st.className = 'env-result ' + (r.success ? 'ok' : 'err');
                if (r.success) fridaLoadStatus();
            } catch (e) { st.textContent = e.message; st.className = 'env-result err'; }
            finally { btn.disabled = false; }
        });

        $('#env-proxy-set').addEventListener('click', async () => {
            const host = $('#env-proxy-host').value.trim();
            const port = parseInt($('#env-proxy-port').value);
            if (!host) { toast('Enter a host', 'err'); return; }
            const st = $('#env-proxy-status');
            try {
                const r = await api.put('/api/env/proxy', { host, port });
                st.textContent = r.ok ? `✓ Proxy set to ${r.value}` : `✗ ${r.stderr || 'failed'}`;
                st.className = 'env-result ' + (r.ok ? 'ok' : 'err');
                envLoadProxy();
            } catch (e) { st.textContent = e.message; st.className = 'env-result err'; }
        });

        $('#env-proxy-clear').addEventListener('click', async () => {
            const st = $('#env-proxy-status');
            try {
                await api.del('/api/env/proxy');
                st.textContent = '✓ Proxy cleared (set to :0)';
                st.className = 'env-result ok';
                envLoadProxy();
            } catch (e) { st.textContent = e.message; st.className = 'env-result err'; }
        });
    });

    envLoadProxy();
    fridaLoadStatus();
    fridaLoadReleases();
}
function refreshEnvsetup() { envLoadProxy(); fridaLoadStatus(); }

async function fridaLoadStatus() {
    const box = $('#frida-status-box');
    if (!box) return;
    try {
        const s = await api.get('/api/env/frida/status');
        const badge = s.running ? '<span class="tg cyan">running PID ' + s.pid + '</span>' : '<span class="tg">stopped</span>';
        const ver = s.installed ? (s.installedVersion ? 'v' + fmt.esc(s.installedVersion) : 'installed') : 'not installed';
        box.innerHTML = 'ABI: <strong>' + fmt.esc(s.abi) + '</strong> &nbsp; ' + badge + ' &nbsp; ' + ver;
        box.className = 'env-result ' + (s.running ? 'ok' : '');
    } catch (e) { box.textContent = e.message; box.className = 'env-result err'; }
}

async function fridaLoadReleases() {
    const sel = $('#frida-version-select');
    if (!sel) return;
    try {
        const r = await api.get('/api/env/frida/releases');
        if (r.error) { sel.innerHTML = '<option value="">Error: ' + fmt.esc(r.error) + '</option>'; return; }
        sel.innerHTML = r.versions.map(v => '<option value="' + fmt.esc(v) + '">' + fmt.esc(v) + '</option>').join('');
    } catch (e) { sel.innerHTML = '<option value="">Failed: ' + fmt.esc(e.message) + '</option>'; }
}

async function fridaAction(action) {
    const btn = action === 'start' ? $('#frida-start') : $('#frida-stop');
    const st = $('#frida-install-status');
    if (btn) btn.disabled = true;
    try {
        const r = await api.post('/api/env/frida/' + action);
        if (st) { st.textContent = r.message; st.className = 'env-result ' + (r.success ? 'ok' : 'err'); st.style.whiteSpace = 'pre-wrap'; }
    } catch (e) { if (st) { st.textContent = e.message; st.className = 'env-result err'; } }
    finally { if (btn) btn.disabled = false; }
}

async function envLoadProxy() {
    try {
        const p = await api.get('/api/env/proxy');
        const el = $('#env-proxy-current');
        const isCleared = !p.current || p.current === ':0';
        if (!isCleared) {
            el.textContent = p.current;
            el.style.color = 'var(--accent)';
            if (p.host) $('#env-proxy-host').value = p.host;
            if (p.port) $('#env-proxy-port').value = p.port;
        } else {
            el.textContent = 'none (:0)';
            el.style.color = '';
        }
    } catch (_) {}
}

// ============== SCREEN CAPTURE tab ==============
let scCurrentName = null;
let scCurrentType = 'image';
let scRecTimer = null;
let scRecSeconds = 0;

function initScreenshot() {
    once('screenshot', () => {
        $('#sc-take').addEventListener('click', scTake);
        $('#sc-rec-start').addEventListener('click', scRecStart);
        $('#sc-rec-stop').addEventListener('click', scRecStop);
        $('#sc-refresh').addEventListener('click', scLoad);
        $('#sc-lightbox-close').addEventListener('click', scCloseLightbox);
        $('#sc-lightbox-bg').addEventListener('click', scCloseLightbox);
        $('#sc-lightbox-dl').addEventListener('click', () => {
            if (!scCurrentName) return;
            const a = document.createElement('a');
            a.href = '/api/capture/' + scCurrentName;
            a.download = scCurrentName;
            document.body.appendChild(a); a.click(); document.body.removeChild(a);
        });
        $('#sc-lightbox-del').addEventListener('click', async () => {
            if (!scCurrentName) return;
            await api.del('/api/capture/' + scCurrentName);
            scCloseLightbox(); scLoad();
        });
        document.addEventListener('keydown', e => { if (e.key === 'Escape') scCloseLightbox(); });
    });
    scLoad();
    scCheckRecStatus();
}
function refreshScreenshot() { scLoad(); scCheckRecStatus(); }

async function scLoad() {
    const gallery = $('#sc-gallery');
    gallery.innerHTML = '<div class="muted small">Loading...</div>';
    try {
        const captures = await api.get('/api/capture');
        const count = captures.length;
        $('#sc-count').textContent = count ? count + ' file' + (count > 1 ? 's' : '') : '';
        if (!count) { gallery.innerHTML = '<div class="empty">No captures yet.</div>'; return; }
        gallery.innerHTML = captures.map(c => {
            const label = c.name
                .replace(/^(screenshot|screenrecord)_/, '')
                .replace(/\.(png|mp4)$/, '');
            if (c.type === 'video') {
                return '<div class="sc-thumb sc-thumb-video" data-name="' + fmt.esc(c.name) + '" data-type="video" title="' + fmt.esc(c.name) + '">' +
                    '<div class="sc-thumb-play">&#9654;</div>' +
                    '<div class="sc-thumb-name muted small">REC ' + fmt.esc(label) + '</div>' +
                    '<div class="sc-thumb-size muted small">' + scFmtSize(c.sizeBytes) + '</div></div>';
            }
            return '<div class="sc-thumb" data-name="' + fmt.esc(c.name) + '" data-type="image" title="' + fmt.esc(c.name) + '">' +
                '<img src="' + fmt.esc(c.url) + '" alt="' + fmt.esc(c.name) + '" loading="lazy">' +
                '<div class="sc-thumb-name muted small">' + fmt.esc(label) + '</div></div>';
        }).join('');
        $$('.sc-thumb', gallery).forEach(t =>
            t.addEventListener('click', () => scOpenLightbox(t.dataset.name, t.dataset.type)));
    } catch (e) { gallery.innerHTML = '<div class="empty">' + fmt.esc(e.message) + '</div>'; }
}

async function scTake() {
    const btn = $('#sc-take'); btn.disabled = true; btn.textContent = 'Capturing...';
    try {
        const r = await fetchAuthed('/api/capture/screenshot', { method: 'POST' });
        const d = await r.json();
        if (!r.ok) { toast(d.error || 'Failed', 'err'); return; }
        toast('Screenshot saved', 'ok'); scLoad();
    } catch (e) { toast(e.message, 'err'); }
    finally { btn.disabled = false; btn.textContent = 'Screenshot'; }
}

async function scRecStart() {
    const btn = $('#sc-rec-start'); btn.disabled = true;
    try {
        const r = await fetchAuthed('/api/capture/record/start', { method: 'POST' });
        const d = await r.json();
        if (!r.ok || d.error) { toast(d.error || 'Failed to start', 'err'); return; }
        toast('Recording started', 'ok');
        $('#sc-rec-start').classList.add('hidden');
        $('#sc-rec-stop').classList.remove('hidden');
        $('#sc-rec-timer').classList.remove('hidden');
        scRecSeconds = 0;
        scRecTimer = setInterval(() => {
            scRecSeconds++;
            $('#sc-rec-timer').textContent = scFmtTime(scRecSeconds);
        }, 1000);
    } catch (e) { toast(e.message, 'err'); }
    finally { btn.disabled = false; }
}

async function scRecStop() {
    const btn = $('#sc-rec-stop'); btn.disabled = true; btn.textContent = 'Stopping...';
    try {
        const r = await fetchAuthed('/api/capture/record/stop', { method: 'POST' });
        const d = await r.json();
        if (!r.ok || d.error) { toast(d.error || 'Failed to stop', 'err'); return; }
        toast('Recording saved (' + scFmtSize(d.sizeBytes) + ')', 'ok');
        scLoad();
    } catch (e) { toast(e.message, 'err'); }
    finally {
        clearInterval(scRecTimer); scRecTimer = null;
        btn.disabled = false; btn.textContent = 'Stop';
        $('#sc-rec-stop').classList.add('hidden');
        $('#sc-rec-timer').classList.add('hidden');
        $('#sc-rec-start').classList.remove('hidden');
    }
}

async function scCheckRecStatus() {
    try {
        const s = await api.get('/api/capture/record/status');
        if (s.recording) {
            $('#sc-rec-start').classList.add('hidden');
            $('#sc-rec-stop').classList.remove('hidden');
            $('#sc-rec-timer').classList.remove('hidden');
            scRecSeconds = s.elapsedSeconds;
            if (!scRecTimer) scRecTimer = setInterval(() => {
                scRecSeconds++;
                $('#sc-rec-timer').textContent = scFmtTime(scRecSeconds);
            }, 1000);
        }
    } catch (_) {}
}

function scOpenLightbox(name, type) {
    scCurrentName = name; scCurrentType = type;
    const img = $('#sc-lightbox-img');
    if (type === 'video') {
        img.style.display = 'none';
        let vid = $('#sc-lightbox-video');
        if (!vid) {
            vid = document.createElement('video');
            vid.id = 'sc-lightbox-video'; vid.controls = true;
            vid.style.cssText = 'max-width:85vw;max-height:75vh;border-radius:6px;box-shadow:0 8px 40px rgba(0,0,0,.6)';
            img.parentNode.insertBefore(vid, img);
        }
        vid.src = '/api/capture/' + name + '?t=' + Date.now();
        vid.style.display = 'block';
    } else {
        const vid = $('#sc-lightbox-video');
        if (vid) { vid.pause(); vid.src = ''; vid.style.display = 'none'; }
        img.style.display = 'block';
        img.src = '/api/capture/' + name + '?t=' + Date.now();
    }
    $('#sc-lightbox').classList.remove('hidden');
}

function scCloseLightbox() {
    $('#sc-lightbox').classList.add('hidden'); scCurrentName = null;
    $('#sc-lightbox-img').src = '';
    const vid = $('#sc-lightbox-video');
    if (vid) { vid.pause(); vid.src = ''; }
}

function scFmtSize(b) {
    if (!b) return '0 B';
    if (b < 1024) return b + ' B';
    if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
    return (b / 1048576).toFixed(1) + ' MB';
}
function scFmtTime(s) {
    const m = Math.floor(s / 60), sec = s % 60;
    return m > 0 ? m + 'm ' + sec + 's' : sec + 's';
}

// ============== CLIPBOARD tab ==============
let cbWatchInterval = null;
let cbEntries = [];

function initClipboard() {
    once('clipboard', () => {
        $('#cb-dump').addEventListener('click', cbDump);
        $('#cb-watch-btn').addEventListener('click', () => {
            const btn = $('#cb-watch-btn');
            const watching = btn.getAttribute('aria-pressed') === 'true';
            if (watching) {
                cbStopWatch(); btn.setAttribute('aria-pressed', 'false');
                btn.textContent = '👁 Watch'; $('#cb-watch-status').textContent = '';
            } else {
                cbStartWatch(); btn.setAttribute('aria-pressed', 'true');
                btn.textContent = '⏹ Stop watching';
            }
        });
    });
    cbDump();
}
function refreshClipboard() { cbDump(); }

async function cbDump() {
    try {
        const r = await api.get('/api/clipboard');
        cbAddEntry(r);
    } catch (e) { toast(e.message, 'err'); }
}

function cbAddEntry(r) {
    // Avoid duplicate consecutive entries with same content.
    if (cbEntries.length && cbEntries[0].content === r.content && r.content !== '') return;
    cbEntries.unshift(r);
    if (cbEntries.length > 100) cbEntries = cbEntries.slice(0, 100);
    cbRender();
}

function cbRender() {
    const el = $('#cb-entries');
    if (!cbEntries.length) { el.innerHTML = '<div class="empty">No clipboard data yet.</div>'; return; }
    el.innerHTML = cbEntries.map((e, i) => {
        const ts = new Date(e.timestamp).toLocaleTimeString();
        const hasContent = e.content && e.content.trim();
        const mimes = (e.mimeTypes || []).map(m => `<span class="tg muted">${fmt.esc(m)}</span>`).join('');
        const itemBadge = e.itemCount > 0 ? `<span class="tg">${e.itemCount} item${e.itemCount > 1 ? 's' : ''}</span>` : '';
        return `<div class="cb-entry ${hasContent ? '' : 'cb-empty'}">
            <div class="cb-entry-header">
                <span class="muted small">${ts}</span>
                ${itemBadge}${mimes}
                ${hasContent ? `<button class="btn ghost small cb-copy" data-i="${i}" style="margin-left:auto">copy</button>` : ''}
                <button class="btn ghost small danger cb-del-entry" data-i="${i}" ${hasContent ? '' : 'style="margin-left:auto"'}>✕</button>
            </div>
            ${hasContent
                ? `<pre class="cb-content">${fmt.esc(e.content.slice(0, 2000))}</pre>`
                : `<div class="muted small" style="padding:8px 12px">${fmt.esc(e.error || '(empty)')}</div>`}
        </div>`;
    }).join('');
    $$('.cb-copy', el).forEach(b => b.onclick = () => {
        const entry = cbEntries[+b.dataset.i];
        navigator.clipboard?.writeText(entry.content)
            .then(() => toast('Copied', 'ok'), () => toast('Copy failed', 'err'));
    });
    $$('.cb-del-entry', el).forEach(b => b.onclick = () => {
        cbEntries.splice(+b.dataset.i, 1);
        cbRender();
    });
}

function cbStartWatch() {
    cbWatchInterval = setInterval(async () => {
        try {
            const r = await api.get('/api/clipboard');
            const changed = !cbEntries.length || cbEntries[0].content !== r.content;
            if (changed && r.content) {
                cbAddEntry(r);
                toast('Clipboard changed', 'ok');
            }
            $('#cb-watch-status').textContent = 'last check: ' + new Date().toLocaleTimeString();
        } catch (_) {}
    }, 3000);
}
function cbStopWatch() {
    if (cbWatchInterval) { clearInterval(cbWatchInterval); cbWatchInterval = null; }
}

// ============== FRIDA SCRIPTS tab ==============
let fsSelected = [];   // FridaScript objects
let fsCatalog  = null;

function initFridaScripts() {
    once('fridascripts', () => {
        $('#fs-search').addEventListener('input', () => fsRenderCatalog(fsCatalog));
        $('#fs-reload').addEventListener('click', () => { fsCatalog = null; fsLoadCatalog(); });
        $('#fs-mode').addEventListener('change', () => {
            const showPid = $('#fs-mode').value === '-p';
            $('#fs-pid').style.display = showPid ? '' : 'none';
            fsUpdateCommand();
        });
        $('#fs-pid').addEventListener('input', fsUpdateCommand);
        $('#fs-copy-cmd').addEventListener('click', () => {
            const cmd = $('#fs-cmd').textContent;
            navigator.clipboard?.writeText(cmd).then(() => toast('Copied', 'ok'));
        });
        $('#fs-clear-sel').addEventListener('click', () => {
            fsSelected = []; fsRenderSelected(); fsUpdateCommand(); fsRenderCatalog(fsCatalog);
        });
        $('#fs-custom-save').addEventListener('click', fsSaveCustom);
    });
    fsLoadCatalog();
    fsUpdateCommand();
}
function refreshFridaScripts() { fsUpdateCommand(); }

async function fsLoadCatalog() {
    if (fsCatalog) { fsRenderCatalog(fsCatalog); return; }
    const cat = $('#fs-catalog');
    cat.innerHTML = '<div class="muted small">Loading...</div>';
    try {
        fsCatalog = await api.get('/api/frida-scripts/catalog');
        // Merge custom scripts as a category.
        try {
            const customs = await api.get('/api/frida-scripts/custom');
            if (customs.length) {
                fsCatalog = { ...fsCatalog, categories: [
                    ...fsCatalog.categories,
                    { id: 'custom', name: 'Custom Scripts', icon: '\u270F', scripts: customs }
                ]};
            }
        } catch (_) {}
        fsRenderCatalog(fsCatalog);
    } catch (e) {
        cat.innerHTML = '<div class="empty" style="color:var(--red)">' + fmt.esc(e.message) + '</div>';
    }
}

function fsRenderCatalog(catalog) {
    if (!catalog) return;
    const q   = $('#fs-search').value.trim().toLowerCase();
    const cat = $('#fs-catalog');
    cat.innerHTML = catalog.categories.map(c => {
        const scripts = c.scripts.filter(s =>
            !q || s.name.toLowerCase().includes(q) ||
            s.description.toLowerCase().includes(q) ||
            s.tags.some(t => t.includes(q)));
        if (!scripts.length) return '';
        return '<div class="fs-cat">' +
            '<div class="fs-cat-h">' + fmt.esc(c.icon) + ' ' + fmt.esc(c.name) + '</div>' +
            scripts.map(s => {
                const sel     = fsSelected.some(x => x.id === s.id);
                const isCustom = s.tags.includes('custom');
                return '<div class="fs-script-row' + (sel ? ' selected' : '') + '" data-id="' + fmt.esc(s.id) + '">' +
                    '<div class="fs-script-info">' +
                        '<div class="fs-script-name">' + fmt.esc(s.name) + '</div>' +
                        '<div class="fs-script-desc muted small">' + fmt.esc(s.description) + '</div>' +
                        '<div class="fs-tags">' + s.tags.map(t => '<span class="tg">' + fmt.esc(t) + '</span>').join('') + '</div>' +
                    '</div>' +
                    '<div style="display:flex;gap:4px;align-items:flex-start;flex-shrink:0">' +
                        (isCustom ? '<button class="btn ghost small fs-dl-btn" data-id="' + fmt.esc(s.id) + '" title="Download script"><svg class="ic ic-sm"><use href="#i-download"/></svg></button>' : '') +
                        (isCustom ? '<button class="btn ghost small danger fs-del-btn" data-id="' + fmt.esc(s.id) + '" title="Delete custom script">🗑</button>' : '') +
                        '<button class="btn small fs-add-btn' + (sel ? ' fs-added' : '') + '" data-id="' + fmt.esc(s.id) + '">' +
                            (sel ? '&#10003; Added' : '+ Add') +
                        '</button>' +
                    '</div>' +
                '</div>';
            }).join('') +
        '</div>';
    }).join('');

    $$('.fs-add-btn', cat).forEach(btn => {
        btn.addEventListener('click', () => {
            const id = btn.dataset.id;
            const script = catalog.categories.flatMap(c => c.scripts).find(s => s.id === id);
            if (!script) return;
            if (fsSelected.some(s => s.id === id)) {
                fsSelected = fsSelected.filter(s => s.id !== id);
            } else {
                fsSelected.push(script);
            }
            fsRenderCatalog(catalog);
            fsRenderSelected();
            fsUpdateCommand();
        });
    });

    $$('.fs-dl-btn', cat).forEach(btn => {
        btn.addEventListener('click', async () => {
            const id = btn.dataset.id;
            try {
                const d = await api.get('/api/frida-scripts/custom/' + encodeURIComponent(id));
                const blob = new Blob([d.content], { type: 'text/javascript' });
                const a = document.createElement('a');
                a.href = URL.createObjectURL(blob);
                a.download = id + '.js';
                a.click();
                URL.revokeObjectURL(a.href);
            } catch (e) { toast(e.message, 'err'); }
        });
    });

    $$('.fs-del-btn', cat).forEach(btn => {
        btn.addEventListener('click', async () => {
            const id = btn.dataset.id;
            if (!confirm('Delete this custom script?')) return;
            try {
                await api.del('/api/frida-scripts/custom/' + encodeURIComponent(id));
                // Remove from selection if it was selected.
                fsSelected = fsSelected.filter(s => s.id !== id);
                fsCatalog = null;
                await fsLoadCatalog();
                fsRenderSelected();
                fsUpdateCommand();
                toast('Custom script deleted', 'ok');
            } catch (e) { toast(e.message, 'err'); }
        });
    });
}

function fsRenderSelected() {
    const el = $('#fs-selected');
    $('#fs-sel-count').textContent = fsSelected.length;
    if (!fsSelected.length) {
        el.innerHTML = '<div class="empty muted small">Select scripts from the left to build a command.</div>';
        return;
    }
    el.innerHTML = fsSelected.map((s, i) =>
        '<div class="fs-sel-row">' +
            '<span class="fs-sel-name">' + fmt.esc(s.name) + '</span>' +
            (s.codeshare ? '<span class="tg muted" style="font-size:9px">' + fmt.esc(s.codeshare) + '</span>' : '<span class="tg">custom</span>') +
            '<button class="btn ghost small fs-sel-rm" data-i="' + i + '">&#10005;</button>' +
        '</div>'
    ).join('');
    $$('.fs-sel-rm', el).forEach(b => b.addEventListener('click', () => {
        fsSelected.splice(+b.dataset.i, 1);
        fsRenderSelected(); fsRenderCatalog(fsCatalog); fsUpdateCommand();
    }));
}

function fsUpdateCommand() {
    const pkg  = S.pkg || '<package>';
    const mode = $('#fs-mode') ? $('#fs-mode').value : '-f';
    const pid  = $('#fs-pid')  ? $('#fs-pid').value.trim() : '';
    const target = mode === '-p' ? (pid || '<pid>') : pkg;

    if (!fsSelected.length) {
        $('#fs-cmd').textContent = 'frida -U ' + mode + ' ' + target + '  # add scripts first';
        return;
    }

    // Separate codeshare scripts from custom ones.
    const codeshareScripts = fsSelected.filter(s => s.codeshare);
    const customScripts    = fsSelected.filter(s => !s.codeshare);

    const parts = ['frida', '-U', mode, target];

    codeshareScripts.forEach(s => parts.push('--codeshare', s.codeshare));
    customScripts.forEach(s => parts.push('-l', s.id + '.js'));

    // Build readable multi-line command.
    const base   = 'frida -U ' + mode + ' ' + target;
    const csArgs = codeshareScripts.map(s => '  --codeshare ' + s.codeshare).join(' \\\n');
    const lArgs  = customScripts.map(s   => '  -l ' + s.id + '.js').join(' \\\n');
    const allArgs = [csArgs, lArgs].filter(Boolean).join(' \\\n');

    const cmd = base + ' \\\n' + allArgs +
        (customScripts.length
            ? '\n\n# Custom scripts must be present on your PC:\n' +
              customScripts.map(s => '# ' + s.id + '.js').join('\n')
            : '');

    $('#fs-cmd').textContent = cmd;
}

async function fsSaveCustom() {
    const name    = $('#fs-custom-name').value.trim();
    const cat     = $('#fs-custom-cat').value;
    const content = $('#fs-custom-content').value.trim();
    const st      = $('#fs-custom-status');
    if (!name || !content) { toast('Name and content required', 'err'); return; }
    try {
        await api.post('/api/frida-scripts/custom', { name, category: cat, content });
        st.textContent = '&#10003; Saved';
        $('#fs-custom-name').value = '';
        $('#fs-custom-content').value = '';
        fsCatalog = null;
        await fsLoadCatalog();
        toast('Custom script saved', 'ok');
    } catch (e) { st.textContent = '&#10007; ' + e.message; toast(e.message, 'err'); }
}


// ============== AI ASSISTANT tab ==============
let aiMessages       = [];   // current package's messages (in-memory)
let aiAttachments    = [];   // { name, path, content }
let aiProviders      = [];
let aiSelectedProv   = null;

// ── Session persistence ────────────────────────────────────────────────────────

async function aiSessionLoad(pkg) {
    try {
        const raw  = await fetchAuthed(`/api/ai/session?pkg=${encodeURIComponent(pkg)}`);
        const data = await raw.json();
        // Filter out typing indicators / errors that shouldn't be persisted.
        return (data.messages || []).filter(m => !m._typing && !m._error);
    } catch (_) { return []; }
}

async function aiSessionSave(pkg, messages) {
    if (!pkg) return;
    const clean = messages.filter(m => !m._typing);
    try {
        await fetchAuthed(`/api/ai/session?pkg=${encodeURIComponent(pkg)}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ messages: clean })
        });
    } catch (_) {}
}

async function aiSessionClear(pkg) {
    try {
        await fetchAuthed(`/api/ai/session?pkg=${encodeURIComponent(pkg)}`, { method: 'DELETE' });
    } catch (_) {}
}

function initAichat() {
    once('aichat', () => {
        // Send button + Ctrl+Enter
        $('#ai-send').addEventListener('click', aiSend);
        $('#ai-stop').addEventListener('click', () => {
            if (aiAbortController) aiAbortController.abort();
        });
        $('#ai-input').addEventListener('keydown', e => {
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); aiSend(); }
        });
        // Provider management
        $('#ai-ctx-build').addEventListener('click', aiCtxBuild);
        $('#ai-provider-manage').addEventListener('click', () => {
            aiRenderProviderList();
            $('#ai-provider-modal').classList.remove('hidden');
        });
        $('#ai-modal-close').addEventListener('click', () => $('#ai-provider-modal').classList.add('hidden'));
        $('#ai-provider-modal').addEventListener('click', e => {
            if (e.target === $('#ai-provider-modal')) $('#ai-provider-modal').classList.add('hidden');
        });
        $('#ai-provider-sel').addEventListener('change', () => {
            aiSelectedProv = $('#ai-provider-sel').value || null;
        });
        $('#ai-prov-type').addEventListener('change', () => {
            $('#ai-prov-url').style.display = $('#ai-prov-type').value === 'custom' ? '' : 'none';
        });
        $('#ai-prov-save').addEventListener('click',  aiSaveProvider);
        $('#ai-prov-clear').addEventListener('click', aiClearProviderForm);
        $('#ai-files-refresh').addEventListener('click', aiLoadFiles);
        $('#ai-files-search').addEventListener('input', aiFilterFiles);
        $('#ai-clear-session').addEventListener('click', async () => {
            if (!confirm('Clear conversation history for this package?')) return;
            aiMessages = []; aiAttachments = [];
            await aiSessionClear(S.pkg);
            aiRenderAttachments();
            aiAddSystemWelcome();
            aiScrollBottom();
            aiResetTokenCounter();
        });
    });
    aiLoadProviders();
    aiLoadFiles();
    aiCtxCheckStatus();
    // Load persisted session for this package, or start fresh.
    aiMessages = []; aiAttachments = [];
    aiRenderAttachments();
    aiResetTokenCounter();
    aiSessionLoad(S.pkg || '').then(msgs => {
        if (msgs.length) {
            aiMessages = msgs;
            aiRenderMessages();
            aiScrollBottom();
        } else {
            aiAddSystemWelcome();
        }
    });
}
function refreshAichat() { aiLoadFiles(); }

// ── Welcome message ────────────────────────────────────────────────────────────
function aiAddSystemWelcome() {
    const pkg = S.pkg ? `Currently targeting **${S.pkg}**.` : 'Select an app from the sidebar first.';
    aiMessages = [{ role: 'assistant', content:
        `Hello! I'm your Android security assistant.\n\n${pkg}\n\n` +
        `I can help you:\n` +
        `- Analyse decompiled Smali/Java code for vulnerabilities\n` +
        `- Compare app snapshots to detect changes\n` +
        `- Review network captures and exported components\n` +
        `- Draft pentest report findings\n\n` +
        `Attach files from the left panel or just ask a question.`
    }];
    aiRenderMessages();
}

// ── Token / cost counter ───────────────────────────────────────────────────────

// Pricing in USD per million tokens [input, output].
// Sources: official pricing pages as of mid-2025.
const AI_PRICING = {
    // Anthropic
    'claude-opus-4-6':   [15.00, 75.00],
    'claude-opus-4-7':   [15.00, 75.00],
    'claude-opus-4-8':   [15.00, 75.00],
    'claude-sonnet-4-6': [3.00,  15.00],
    'claude-haiku-4-5':  [0.80,   4.00],
    // OpenAI
    'gpt-4o':            [2.50,  10.00],
    'gpt-4o-mini':       [0.15,   0.60],
    'gpt-4-turbo':       [10.00, 30.00],
    'o1':                [15.00, 60.00],
    'o1-mini':           [3.00,  12.00],
    'o3-mini':           [1.10,   4.40],
    // Gemini
    'gemini-1.5-flash':  [0.075,  0.30],
    'gemini-1.5-pro':    [1.25,   5.00],
    'gemini-2.0-flash':  [0.10,   0.40],
    'gemini-2.5-pro':    [1.25,  10.00],
};

let aiSessionTokens = { input: 0, output: 0 };  // cumulative for this session

function aiUpdateTokenCounter(usage) {
    if (!usage) return;
    aiSessionTokens.input  += usage.inputTokens  || 0;
    aiSessionTokens.output += usage.outputTokens || 0;

    const provider = aiProviders.find(p => p.id === aiSelectedProv);
    const model    = provider?.model?.toLowerCase() || '';
    const pricing  = AI_PRICING[model] || null;
    const inputM   = aiSessionTokens.input  / 1_000_000;
    const outputM  = aiSessionTokens.output / 1_000_000;
    const costUSD  = pricing ? (inputM * pricing[0] + outputM * pricing[1]) : null;

    const total = aiSessionTokens.input + aiSessionTokens.output;
    let label   = `${aiFmtTokens(total)} tokens`;
    if (pricing) label += ` · ~$${costUSD.toFixed(4)}`;

    const counter = $('#ai-token-counter');
    if (counter) {
        counter.textContent = label;
        counter.title = `Input: ${aiFmtTokens(aiSessionTokens.input)} · Output: ${aiFmtTokens(aiSessionTokens.output)}`;
    }

    // Budget footer.
    const budget    = provider?.budgetUsd;
    const footer    = $('#ai-budget-footer');
    const bar       = $('#ai-budget-bar');
    const spentEl   = $('#ai-budget-spent');
    const leftEl    = $('#ai-budget-left');
    if (footer && budget && budget > 0 && costUSD !== null) {
        const pct  = Math.min(costUSD / budget * 100, 100);
        const left = Math.max(budget - costUSD, 0);
        footer.style.display = '';
        bar.style.width = pct + '%';
        bar.className = 'ai-budget-bar' + (pct >= 90 ? ' danger' : pct >= 70 ? ' warn' : '');
        spentEl.textContent = `$${costUSD.toFixed(4)} spent`;
        leftEl.textContent  = `$${left.toFixed(4)} left`;
        if (pct >= 90 && pct < 100) toast(`⚠ Budget ${pct.toFixed(0)}% used`, 'err');
        if (pct >= 100)             toast('🛑 Budget exceeded!', 'err');
    } else if (footer) {
        footer.style.display = 'none';
    }
}

function aiFmtTokens(n) {
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M';
    if (n >= 1_000)     return (n / 1_000).toFixed(1) + 'k';
    return String(n);
}

function aiResetTokenCounter() {
    aiSessionTokens = { input: 0, output: 0 };
    const el = $('#ai-token-counter');
    if (el) el.textContent = 'Tokens: —';
    const footer = $('#ai-budget-footer');
    if (footer) footer.style.display = 'none';
}

async function aiCtxCheckStatus() {
    if (!S.pkg) return;
    const st = $('#ai-ctx-status');
    if (!st) return;
    try {
        const r = await api.get(`/api/ai/context/status?pkg=${encodeURIComponent(S.pkg)}`);
        if (r.exists === 'true') {
            const age  = Math.round((Date.now() - +r.updatedAt) / 60000);
            const size = (+r.sizeBytes / 1024).toFixed(0);
            st.textContent = `✓ Built ${age < 60 ? age + 'm ago' : Math.round(age/60) + 'h ago'} · ${size} KB`;
            st.style.color = '';
        } else {
            st.textContent = 'Not built — click ⚡ to analyse the app';
            st.style.color = 'var(--muted)';
        }
    } catch (_) {}
}

async function aiCtxBuild() {
    if (!S.pkg) { toast('Select an app first', 'err'); return; }
    const btn = $('#ai-ctx-build');
    const st  = $('#ai-ctx-status');
    btn.disabled = true; btn.textContent = '⏳ Building…';
    if (st) st.textContent = 'Scanning app…';
    try {
        const r = await api.post(`/api/ai/context/build?pkg=${encodeURIComponent(S.pkg)}`);
        const size = (r.sizeBytes / 1024).toFixed(0);
        if (st) {
            st.textContent = `✓ Built now · ${size} KB${r.errors?.length ? ` (${r.errors.length} warnings)` : ''}`;
            st.style.color = '';
        }
        toast(`Context built — ${size} KB`, 'ok');
        // Reload file list so the AI sidebar shows the new file.
        aiLoadFiles();
    } catch (e) {
        if (st) { st.textContent = '✗ ' + e.message; st.style.color = 'var(--red)'; }
        toast(e.message, 'err');
    } finally {
        btn.disabled = false; btn.textContent = '⚡ Build context';
    }
}
async function aiLoadProviders() {
    try {
        aiProviders = await api.get('/api/ai/providers');
        const sel = $('#ai-provider-sel');
        sel.innerHTML = '<option value="">— select a provider —</option>' +
            aiProviders.map(p => `<option value="${fmt.esc(p.id)}">${fmt.esc(p.name)}</option>`).join('');
        if (aiSelectedProv) sel.value = aiSelectedProv;
    } catch (_) {}
}

function aiRenderProviderList() {
    const list = $('#ai-provider-list');
    if (!aiProviders.length) { list.innerHTML = '<div class="muted small">No providers configured yet.</div>'; return; }
    list.innerHTML = aiProviders.map(p => `
        <div class="ai-prov-row">
            <span class="ai-prov-name">${fmt.esc(p.name)}</span>
            <span class="tg muted">${fmt.esc(p.type)}</span>
            <span class="tg muted">${fmt.esc(p.model || 'default')}</span>
            <button class="btn ghost small ai-prov-edit" data-id="${fmt.esc(p.id)}">Edit</button>
            <button class="btn ghost small danger ai-prov-del"  data-id="${fmt.esc(p.id)}">Delete</button>
        </div>`).join('');
    $$('.ai-prov-edit', list).forEach(b => b.onclick = () => aiEditProvider(b.dataset.id));
    $$('.ai-prov-del',  list).forEach(b => b.onclick = () => aiDeleteProvider(b.dataset.id));
}

function aiEditProvider(id) {
    const p = aiProviders.find(x => x.id === id);
    if (!p) return;
    $('#ai-prov-id').value    = p.id;
    $('#ai-prov-name').value  = p.name;
    $('#ai-prov-type').value  = p.type;
    $('#ai-prov-key').value   = '';
    $('#ai-prov-key').placeholder = 'Leave blank to keep existing key';
    $('#ai-prov-model').value  = p.model || '';
    $('#ai-prov-budget').value = p.budgetUsd != null ? p.budgetUsd : '';
    $('#ai-prov-url').value    = p.baseUrl || '';
    $('#ai-prov-url').style.display = p.type === 'custom' ? '' : 'none';
}

async function aiDeleteProvider(id) {
    if (!confirm('Delete this provider?')) return;
    try {
        await api.del(`/api/ai/providers/${encodeURIComponent(id)}`);
        await aiLoadProviders();
        aiRenderProviderList();
    } catch (e) { toast(e.message, 'err'); }
}

async function aiSaveProvider() {
    const name  = $('#ai-prov-name').value.trim();
    const type  = $('#ai-prov-type').value;
    const key   = $('#ai-prov-key').value.trim();
    const model = $('#ai-prov-model').value.trim();
    const url   = $('#ai-prov-url').value.trim();
    const existingId = $('#ai-prov-id').value.trim();
    const isEdit     = existingId !== '';

    // Client-side validation.
    if (!name)  { toast('Display name is required', 'err'); $('#ai-prov-name').focus();  return; }
    if (!model) { toast('Model is required (e.g. claude-opus-4-6)', 'err'); $('#ai-prov-model').focus(); return; }
    if (type !== 'custom' && !key && !isEdit) { toast('API key is required', 'err'); $('#ai-prov-key').focus(); return; }
    if (type === 'custom' && !url)  { toast('Base URL is required for custom providers', 'err'); $('#ai-prov-url').focus(); return; }

    const budget = parseFloat($('#ai-prov-budget')?.value) || null;
    const id = existingId || name.toLowerCase().replace(/[^a-z0-9]+/g, '-').slice(0, 32);
    try {
        const resp = await fetchAuthed(`/api/ai/providers/${encodeURIComponent(id)}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id, name, type, apiKey: key, model, baseUrl: url || null, budgetUsd: budget })
        });
        const d = await resp.json();
        if (!resp.ok) { toast(d.error || 'Save failed', 'err'); return; }
        await aiLoadProviders();
        aiRenderProviderList();
        aiClearProviderForm();
        toast('Provider saved', 'ok');
    } catch (e) { toast(e.message, 'err'); }
}

function aiClearProviderForm() {
    ['ai-prov-id','ai-prov-name','ai-prov-key','ai-prov-model','ai-prov-url','ai-prov-budget'].forEach(id => $('#' + id).value = '');
    $('#ai-prov-key').placeholder = 'API Key (leave blank for local)';
    $('#ai-prov-id').value = '';
    $('#ai-prov-type').value = 'anthropic';
    $('#ai-prov-url').style.display = 'none';
}

// ── File browser ───────────────────────────────────────────────────────────────
let aiAllFiles = [];

async function aiLoadFiles() {
    const pkg = S.pkg || '';
    try {
        aiAllFiles = await api.get(`/api/ai/files?pkg=${encodeURIComponent(pkg)}`);
        aiRenderFiles(aiAllFiles);
    } catch (e) {
        $('#ai-files-list').innerHTML = `<div class="muted small" style="padding:8px;color:var(--red)">${fmt.esc(e.message)}</div>`;
    }
}

function aiFilterFiles() {
    const q = $('#ai-files-search').value.trim().toLowerCase();
    aiRenderFiles(q ? aiAllFiles.filter(f => f.name.toLowerCase().includes(q) || f.category.toLowerCase().includes(q)) : aiAllFiles);
}

function aiRenderFiles(files) {
    const el = $('#ai-files-list');
    if (!files.length) { el.innerHTML = '<div class="muted small" style="padding:8px">No files found.</div>'; return; }
    const byCategory = {};
    files.forEach(f => (byCategory[f.category] = byCategory[f.category] || []).push(f));
    el.innerHTML = Object.entries(byCategory).map(([cat, fs]) => `
        <div class="ai-file-cat">${fmt.esc(cat)}</div>
        ${fs.map(f => `
            <div class="ai-file-row" title="${fmt.esc(f.path)}">
                <span class="ai-file-name">${fmt.esc(f.name)}</span>
                <span class="ai-file-size muted">${aiFmtSize(f.size)}</span>
                <button class="btn ghost small ai-attach-btn" data-path="${fmt.esc(f.path)}" data-name="${fmt.esc(f.name)}">Attach</button>
            </div>`).join('')}
    `).join('');
    $$('.ai-attach-btn', el).forEach(b => b.onclick = () => aiAttachFile(b.dataset.path, b.dataset.name));
}

async function aiAttachFile(path, name) {
    if (aiAttachments.some(a => a.path === path)) { toast('Already attached', 'err'); return; }
    try {
        const d = await api.get(`/api/ai/files/content?path=${encodeURIComponent(path)}`);
        aiAttachments.push({ name, path, content: d.content, size: d.size });
        aiRenderAttachments();
        toast(`Attached: ${name}`, 'ok');
    } catch (e) { toast(e.message, 'err'); }
}

function aiRenderAttachments() {
    const el = $('#ai-attachments');
    if (!aiAttachments.length) { el.classList.add('hidden'); return; }
    el.classList.remove('hidden');
    el.innerHTML = aiAttachments.map((a, i) => `
        <div class="ai-att-chip">
            <span>📎 ${fmt.esc(a.name)}</span>
            <span class="muted">${aiFmtSize(a.size)}</span>
            <button class="ai-att-rm" data-i="${i}">✕</button>
        </div>`).join('');
    $$('.ai-att-rm', el).forEach(b => b.onclick = () => {
        aiAttachments.splice(+b.dataset.i, 1); aiRenderAttachments();
    });
}

// ── Chat ───────────────────────────────────────────────────────────────────────
let aiAbortController = null;

function aiSetGenerating(on) {
    const sendBtn = $('#ai-send');
    const stopBtn = $('#ai-stop');
    if (!sendBtn || !stopBtn) return;
    sendBtn.disabled = on;
    sendBtn.textContent = on ? '…' : 'Send';
    stopBtn.classList.toggle('hidden', !on);
}

async function aiSend() {
    const text = $('#ai-input').value.trim();
    if (!text && !aiAttachments.length) return;
    if (!aiSelectedProv) {
        toast('Select a provider first', 'err');
        $('#ai-provider-modal').classList.remove('hidden');
        return;
    }

    // Build user message — inject attachment contents inline.
    let userContent = text;
    if (aiAttachments.length) {
        const attachText = aiAttachments.map(a =>
            `\n\n--- File: ${a.name} ---\n\`\`\`\n${a.content}\n\`\`\`\n--- End: ${a.name} ---`
        ).join('');
        userContent = (text ? text + '\n' : '') + attachText;
    }

    aiMessages.push({ role: 'user', content: userContent });
    aiAttachments = [];
    aiRenderAttachments();
    const inputEl = $('#ai-input');
    inputEl.value = '';
    inputEl.style.height = '';
    aiRenderMessages();
    aiScrollBottom();

    aiMessages.push({ role: 'assistant', content: '…', _typing: true });
    aiRenderMessages();
    aiScrollBottom();

    aiAbortController = new AbortController();
    aiSetGenerating(true);

    try {
        const resp = await api.post('/api/ai/chat', {
            providerId:    aiSelectedProv,
            messages:      aiMessages.filter(m => !m._typing).map(m => ({ role: m.role, content: m.content })),
            packageName:   S.pkg || '',
            systemContext: ($('#ai-context-prompt')?.value || '').trim(),
            maxRounds:     parseInt($('#ai-max-rounds')?.value || '8') || 8
        }, { signal: aiAbortController.signal });

        aiMessages = aiMessages.filter(m => !m._typing);
        if (resp.error) {
            aiMessages.push({ role: 'assistant', content: `⚠ Error: ${resp.error}`, _error: true });
        } else {
            aiMessages.push({ role: 'assistant', content: resp.content, toolCalls: resp.toolCalls || [] });
            aiUpdateTokenCounter(resp.usage);
        }
    } catch (e) {
        aiMessages = aiMessages.filter(m => !m._typing);
        if (e.name === 'AbortError') {
            // User cancelled — remove the last user message too so they can re-edit.
            aiMessages.pop();
        } else {
            aiMessages.push({ role: 'assistant', content: `⚠ ${e.message}`, _error: true });
        }
    } finally {
        aiAbortController = null;
        aiSetGenerating(false);
        aiRenderMessages();
        aiScrollBottom();
        // Persist conversation.
        aiSessionSave(S.pkg || '', aiMessages);
    }
}

function aiRenderMessages() {
    const el = $('#ai-messages');
    el.innerHTML = aiMessages.map((m, idx) => {
        const isUser   = m.role === 'user';
        const isTyping = m._typing;
        const isError  = m._error;

        let toolHtml = '';
        if (m.toolCalls && m.toolCalls.length) {
            toolHtml = '<div class="ai-tool-trace">' +
                m.toolCalls.map(tc =>
                    `<div class="ai-tool-call">` +
                    `<span class="ai-tool-name">🔧 ${fmt.esc(tc.name)}(<code>${fmt.esc(tc.arguments.path || '')}</code>)</span>` +
                    `<details><summary class="muted small">Result</summary>` +
                    `<pre class="ai-tool-result">${fmt.esc((tc.result || '').slice(0, 2000))}</pre>` +
                    `</details></div>`
                ).join('') +
            '</div>';
        }

        let html;
        if (isUser) {
            // User messages: plain text only — no markdown processing.
            html = fmt.esc(m.content).replace(/\n/g, '<br>');
        } else {
            html = fmt.esc(m.content)
                .replace(/```([\s\S]*?)```/g, '<pre class="ai-code">$1</pre>')
                .replace(/`([^`]+)`/g, '<code class="ai-inline-code">$1</code>')
                .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
                .replace(/^#{1,3} (.+)$/gm, '<strong>$1</strong>')
                .replace(/\n/g, '<br>');
        }

        // Copy button only on assistant messages (not user, not typing indicator).
        const copyBtn = (!isUser && !isTyping)
            ? `<button class="ai-copy-btn" data-idx="${idx}" title="Copy message">⎘</button>`
            : '';

        return `<div class="ai-msg ai-msg-${isUser ? 'user' : 'assistant'}${isError ? ' ai-msg-error' : ''}${isTyping ? ' ai-msg-typing' : ''}">
            ${toolHtml}
            <div class="ai-msg-bubble-wrap">
                <div class="ai-msg-bubble">${html}</div>
                ${copyBtn}
            </div>
        </div>`;
    }).join('');

    // Wire copy buttons after render.
    $$('.ai-copy-btn', el).forEach(btn => {
        btn.addEventListener('click', () => {
            const msg = aiMessages[+btn.dataset.idx];
            if (!msg) return;
            navigator.clipboard?.writeText(msg.content).then(
                () => { btn.textContent = '✓'; setTimeout(() => { btn.textContent = '⎘'; }, 1500); },
                () => toast('Copy failed', 'err')
            );
        });
    });
}

function aiScrollBottom() {
    const el = $('#ai-messages');
    setTimeout(() => { el.scrollTop = el.scrollHeight; }, 30);
}

function aiFmtSize(b) {
    if (!b) return '';
    if (b < 1024) return b + 'B';
    if (b < 1048576) return (b/1024).toFixed(0) + 'KB';
    return (b/1048576).toFixed(1) + 'MB';
}

// ============== REMOTE CONFIG tab ==============

function initRemoteconfig() {
    once('remoteconfig', () => {
        $('#rc-fetch').addEventListener('click', rcFetch);
        $('#rc-refresh').addEventListener('click', rcFetch);
    });
    rcFetch();
}
function refreshRemoteconfig() { rcFetch(); }

async function rcFetch() {
    if (!S.pkg) { $('#rc-content').innerHTML = '<div class="empty muted">Select an app first.</div>'; return; }
    const btn = $('#rc-fetch'); btn.disabled = true;
    $('#rc-status').textContent = 'Detecting…';
    $('#rc-content').innerHTML = '<div class="muted small">Scanning APK and fetching…</div>';
    try {
        const r = await api.get(`/api/remoteconfig?pkg=${encodeURIComponent(S.pkg)}`);
        rcRender(r);
    } catch (e) {
        $('#rc-content').innerHTML = `<div class="empty" style="color:var(--red)">${fmt.esc(e.message)}</div>`;
        $('#rc-status').textContent = 'error';
    } finally { btn.disabled = false; }
}

function rcRender(r) {
    const el = $('#rc-content');

    if (r.error && !r.firebaseDetected) {
        $('#rc-status').textContent = 'not found';
        el.innerHTML = `<div class="rc-section">
            <div class="rc-badge rc-badge-none">✗ Firebase not detected</div>
            <div class="muted small" style="margin-top:8px">${fmt.esc(r.error)}</div>
        </div>`;
        return;
    }

    let html = '';

    // Firebase config card.
    if (r.firebaseDetected && r.firebaseConfig) {
        const fc = r.firebaseConfig;
        html += `<div class="rc-section">
            <div class="rc-section-h">Firebase Project</div>
            <div class="rc-badge rc-badge-ok">✓ Firebase detected <span class="muted">(${fmt.esc(fc.source)})</span></div>
            <table class="rc-table">
                <tr><td>Project ID</td><td><code>${fmt.esc(fc.projectId)}</code></td></tr>
                ${fc.projectNumber ? `<tr><td>Project Number</td><td><code>${fmt.esc(fc.projectNumber)}</code></td></tr>` : ''}
                ${fc.appId   ? `<tr><td>App ID</td><td><code>${fmt.esc(fc.appId)}</code></td></tr>` : ''}
                ${fc.apiKey  ? `<tr><td>API Key</td><td><code class="sensitive">${fmt.esc(fc.apiKey)}</code></td></tr>` : '<tr><td>API Key</td><td><span class="muted">not found</span></td></tr>'}
            </table>
        </div>`;
    }

    // Remote Config detection.
    if (r.firebaseDetected && !r.remoteConfigDetected) {
        html += `<div class="rc-section">
            <div class="rc-badge rc-badge-warn">⚠ Remote Config class not found in DEX</div>
            ${r.note ? `<div class="muted small" style="margin-top:6px">${fmt.esc(r.note)}</div>` : ''}
        </div>`;
        $('#rc-status').textContent = 'Firebase yes · Remote Config no';
        el.innerHTML = html; return;
    }

    // Fetch result.
    if (r.remoteConfigDetected) {
        const statusBadge = r.fetchStatus === 'NO_CHANGE'
            ? `<div class="rc-badge rc-badge-warn">↻ NO_CHANGE — server returned no parameters (default/cached state)</div>`
            : r.fetchStatus === 'UPDATE'
            ? `<div class="rc-badge rc-badge-ok">✓ UPDATE — Remote Config values fetched</div>`
            : r.fetchStatus === 'AUTH_ERROR' || r.fetchStatus?.startsWith('HTTP_')
            ? `<div class="rc-badge rc-badge-err">✗ ${fmt.esc(r.fetchStatus)}</div>`
            : `<div class="rc-badge rc-badge-warn">${fmt.esc(r.fetchStatus || 'unknown')}</div>`;

        html += `<div class="rc-section">
            <div class="rc-section-h">Remote Config <span class="rc-badge rc-badge-ok" style="margin-left:8px">✓ detected</span></div>
            ${statusBadge}
            ${r.fetchError ? `<div class="muted small" style="margin-top:6px;color:var(--red)">${fmt.esc(r.fetchError)}</div>` : ''}
        </div>`;

        if (r.parameters && Object.keys(r.parameters).length) {
            const count = Object.keys(r.parameters).length;
            html += `<div class="rc-section">
                <div class="rc-section-h">Parameters <span class="muted">(${count})</span></div>
                <table class="rc-table rc-params">
                    <thead><tr><th>Key</th><th>Value</th></tr></thead>
                    <tbody>${Object.entries(r.parameters).map(([k, v]) =>
                        `<tr>
                            <td><code>${fmt.esc(k)}</code></td>
                            <td><code class="${rcIsSensitive(k) ? 'sensitive' : ''}">${fmt.esc(v.value)}</code></td>
                        </tr>`
                    ).join('')}</tbody>
                </table>
            </div>`;
            $('#rc-status').textContent = `${count} parameter${count > 1 ? 's' : ''} fetched`;
        } else if (r.fetchStatus === 'NO_CHANGE') {
            html += `<div class="rc-section muted small">The server returned NO_CHANGE — this typically means the app has no remote config values set, or the request was rate-limited. Try again in a few seconds.</div>`;
            $('#rc-status').textContent = 'no parameters returned';
        } else {
            $('#rc-status').textContent = r.fetchStatus || '';
        }

        if (r.rawResponse) {
            html += `<div class="rc-section">
                <div class="rc-section-h" style="cursor:pointer" onclick="this.nextElementSibling.classList.toggle('hidden')">
                    Raw response ▾
                </div>
                <pre class="rc-raw hidden">${fmt.esc(r.rawResponse)}</pre>
            </div>`;
        }
    }

    el.innerHTML = html || '<div class="empty muted">No results.</div>';
}

function rcIsSensitive(key) {
    const low = key.toLowerCase();
    return ['key','secret','token','password','pass','api','auth','credential','cert','private']
        .some(s => low.includes(s));
}

// ============== BARCODE / QR GENERATOR tab ==============

const BC_TYPES = [
    // QR / 2D
    { id: 'qr',       label: 'QR Code',       group: '2D',      engine: 'qr',
      fields: [{ id: 'text', label: 'Content', type: 'textarea', placeholder: 'https://example.com' }] },
    { id: 'qr-url',   label: 'URL',            group: '2D',      engine: 'qr',
      fields: [{ id: 'text', label: 'URL', type: 'text', placeholder: 'https://example.com' }],
      prefix: v => v.text },
    { id: 'qr-wifi',  label: 'WiFi',           group: '2D',      engine: 'qr',
      fields: [
        { id: 'ssid', label: 'SSID', type: 'text', placeholder: 'MyNetwork' },
        { id: 'pass', label: 'Password', type: 'text', placeholder: 'password' },
        { id: 'enc',  label: 'Encryption', type: 'select', options: ['WPA','WEP','nopass'] }
      ],
      prefix: v => `WIFI:T:${v.enc};S:${v.ssid};P:${v.pass};;` },
    { id: 'qr-email', label: 'Email',          group: '2D',      engine: 'qr',
      fields: [
        { id: 'to',      label: 'To',      type: 'text', placeholder: 'user@example.com' },
        { id: 'subject', label: 'Subject', type: 'text', placeholder: '' },
        { id: 'body',    label: 'Body',    type: 'textarea', placeholder: '' }
      ],
      prefix: v => `mailto:${v.to}?subject=${encodeURIComponent(v.subject)}&body=${encodeURIComponent(v.body)}` },
    { id: 'qr-sms',   label: 'SMS',            group: '2D',      engine: 'qr',
      fields: [
        { id: 'phone', label: 'Phone', type: 'text', placeholder: '+33612345678' },
        { id: 'msg',   label: 'Message', type: 'textarea', placeholder: '' }
      ],
      prefix: v => `sms:${v.phone}?body=${encodeURIComponent(v.msg)}` },
    { id: 'qr-vcard', label: 'vCard',          group: '2D',      engine: 'qr',
      fields: [
        { id: 'name',  label: 'Name',  type: 'text', placeholder: 'John Doe' },
        { id: 'tel',   label: 'Phone', type: 'text', placeholder: '+33612345678' },
        { id: 'email', label: 'Email', type: 'text', placeholder: 'john@example.com' },
        { id: 'org',   label: 'Org',   type: 'text', placeholder: '' }
      ],
      prefix: v => `BEGIN:VCARD\nVERSION:3.0\nN:${v.name}\nTEL:${v.tel}\nEMAIL:${v.email}\nORG:${v.org}\nEND:VCARD` },
    { id: 'qr-geo',   label: 'Geolocation',    group: '2D',      engine: 'qr',
      fields: [
        { id: 'lat', label: 'Latitude',  type: 'text', placeholder: '48.8566' },
        { id: 'lon', label: 'Longitude', type: 'text', placeholder: '2.3522' }
      ],
      prefix: v => `geo:${v.lat},${v.lon}` },
    // 1D
    { id: 'code128', label: 'Code 128',        group: '1D',      engine: 'jsbarcode', format: 'CODE128',
      fields: [{ id: 'text', label: 'Value', type: 'text', placeholder: '123456789' }] },
    { id: 'code39',  label: 'Code 39',         group: '1D',      engine: 'jsbarcode', format: 'CODE39',
      fields: [{ id: 'text', label: 'Value', type: 'text', placeholder: 'HELLO' }] },
    { id: 'ean13',   label: 'EAN-13',          group: '1D',      engine: 'jsbarcode', format: 'EAN13',
      fields: [{ id: 'text', label: '12-digit number', type: 'text', placeholder: '590123412345' }] },
    { id: 'ean8',    label: 'EAN-8',           group: '1D',      engine: 'jsbarcode', format: 'EAN8',
      fields: [{ id: 'text', label: '7-digit number', type: 'text', placeholder: '9638507' }] },
    { id: 'upca',    label: 'UPC-A',           group: '1D',      engine: 'jsbarcode', format: 'UPC',
      fields: [{ id: 'text', label: '11-digit number', type: 'text', placeholder: '01234567890' }] },
    { id: 'itf14',   label: 'ITF-14',          group: '1D',      engine: 'jsbarcode', format: 'ITF14',
      fields: [{ id: 'text', label: '13-digit number', type: 'text', placeholder: '1234567890123' }] },
    { id: 'msi',     label: 'MSI Plessey',     group: '1D',      engine: 'jsbarcode', format: 'MSI',
      fields: [{ id: 'text', label: 'Value', type: 'text', placeholder: '123456' }] },
    { id: 'pharmacode', label: 'Pharmacode',   group: '1D',      engine: 'jsbarcode', format: 'pharmacode',
      fields: [{ id: 'text', label: 'Number (3–131070)', type: 'text', placeholder: '1234' }] },
];

let bcCurrentType = BC_TYPES[0];
let bcCanvas = null;

function initBarcode() {
    once('barcode', () => {
        bcLoadLibs();
        bcRenderTypeGrid();
        bcSelectType(BC_TYPES[0]);
        $('#bc-generate').addEventListener('click', bcGenerate);
        $('#bc-download').addEventListener('click', bcDownload);
        $('#bc-copy-img').addEventListener('click', bcCopyImg);
    });
}
function refreshBarcode() {}

function bcLoadLibs() {
    if (window.JsBarcode) return;
    const s1 = document.createElement('script');
    s1.src = 'https://cdnjs.cloudflare.com/ajax/libs/jsbarcode/3.11.6/JsBarcode.all.min.js';
    document.head.appendChild(s1);
    if (window.qrcode) return;
    const s2 = document.createElement('script');
    s2.src = 'https://cdnjs.cloudflare.com/ajax/libs/qrcode-generator/1.4.4/qrcode.min.js';
    document.head.appendChild(s2);
}

function bcRenderTypeGrid() {
    const grid = $('#bc-type-grid');
    const groups = {};
    BC_TYPES.forEach(t => (groups[t.group] = groups[t.group] || []).push(t));
    grid.innerHTML = Object.entries(groups).map(([g, types]) =>
        `<div class="bc-group-label muted small">${g}</div>` +
        types.map(t =>
            `<button class="btn ghost small bc-type-btn${t.id === bcCurrentType.id ? ' active' : ''}" data-id="${t.id}">${t.label}</button>`
        ).join('')
    ).join('');
    $$('.bc-type-btn', grid).forEach(b => b.addEventListener('click', () => {
        bcSelectType(BC_TYPES.find(t => t.id === b.dataset.id));
    }));
}

function bcSelectType(type) {
    bcCurrentType = type;
    $$('.bc-type-btn').forEach(b => b.classList.toggle('active', b.dataset.id === type.id));
    const fields = $('#bc-fields');
    fields.innerHTML = type.fields.map(f => {
        if (f.type === 'textarea')
            return `<label class="bc-label muted small">${f.label}</label>
                    <textarea class="input small mono bc-field" id="bcf-${f.id}" rows="3" placeholder="${fmt.esc(f.placeholder || '')}" spellcheck="false"></textarea>`;
        if (f.type === 'select')
            return `<label class="bc-label muted small">${f.label}</label>
                    <select class="input small bc-field" id="bcf-${f.id}">
                        ${f.options.map(o => `<option>${o}</option>`).join('')}
                    </select>`;
        return `<label class="bc-label muted small">${f.label}</label>
                <input class="input small mono bc-field" id="bcf-${f.id}" type="text"
                       placeholder="${fmt.esc(f.placeholder || '')}" spellcheck="false">`;
    }).join('');
    // Pre-fill URL field with current package's deeplink authority if available.
    if (type.id === 'qr-url' && S.pkg) {
        const el = $('#bcf-text');
        if (el && !el.value) el.value = `https://`;
    }
    $('#bc-output').innerHTML = '<div class="empty muted small">Fill the fields and click Generate.</div>';
    $('#bc-preview-actions').classList.add('hidden');
    const meta = $('#bc-preview-meta');
    if (meta) { meta.textContent = ''; meta.classList.add('hidden'); }
    bcCanvas = null;
}

async function bcGenerate() {
    const type = bcCurrentType;
    const btn  = $('#bc-generate'); btn.disabled = true; btn.textContent = 'Generating…';
    const out  = $('#bc-output');
    const actions = $('#bc-preview-actions');
    bcCanvas = null;
    actions.classList.add('hidden');
    out.innerHTML = '';

    try {
        // Collect and validate field values.
        const vals = {};
        let missing = null;
        for (const f of type.fields) {
            const el = $(`#bcf-${f.id}`);
            vals[f.id] = el ? el.value.trim() : '';
            if (!vals[f.id] && f.required !== false && !['pass','subject','body','org','msg'].includes(f.id)) {
                if (!missing) missing = f.label;
            }
        }
        if (missing) {
            bcShowError(`"${missing}" is required.`);
            return;
        }

        const content = type.prefix ? type.prefix(vals) : (vals.text || '');
        if (!content) { bcShowError('Content is empty.'); return; }

        if (type.engine === 'qr') {
            await bcEnsureLib('qrcode');
            const canvas = document.createElement('canvas');
            out.appendChild(canvas);
            // qrcode-generator: draw modules directly onto our canvas, no extra DOM.
            const qr = window.qrcode(0, 'H');
            qr.addData(content);
            qr.make();
            const count  = qr.getModuleCount();
            const size   = 280;
            const cell   = size / count;
            canvas.width  = size;
            canvas.height = size;
            const ctx = canvas.getContext('2d');
            ctx.fillStyle = '#ffffff';
            ctx.fillRect(0, 0, size, size);
            ctx.fillStyle = '#000000';
            for (let r = 0; r < count; r++) {
                for (let c = 0; c < count; c++) {
                    if (qr.isDark(r, c)) {
                        ctx.fillRect(Math.floor(c * cell), Math.floor(r * cell),
                                     Math.ceil(cell), Math.ceil(cell));
                    }
                }
            }
            bcCanvas = canvas;

        } else {
            await bcEnsureLib('JsBarcode');
            const canvas = document.createElement('canvas');
            out.appendChild(canvas);
            try {
                window.JsBarcode(canvas, content, {
                    format: type.format, width: 2, height: 80,
                    displayValue: true, fontSize: 13, margin: 10,
                    background: '#ffffff', lineColor: '#000000'
                });
            } catch (jsbErr) {
                out.innerHTML = '';
                bcShowError(jsbErr.message || 'Invalid value for this barcode format.');
                return;
            }
            bcCanvas = canvas;
        }

        actions.classList.remove('hidden');
        // Show the encoded content below the preview.
        const meta = $('#bc-preview-meta');
        if (meta) {
            meta.textContent = content.length > 120 ? content.slice(0, 120) + '…' : content;
            meta.classList.remove('hidden');
        }

    } catch (e) {
        bcShowError(e.message || 'Unknown error.');
    } finally {
        btn.disabled = false; btn.textContent = 'Generate';
    }
}

function bcShowError(msg) {
    const out = $('#bc-output');
    out.innerHTML = `<div class="bc-error"><svg style="width:32px;height:32px;opacity:.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg><span>${fmt.esc(msg)}</span></div>`;
    toast(msg, 'err');
}

function bcEnsureLib(name) {
    if (window[name]) return Promise.resolve();
    return new Promise((resolve, reject) => {
        const check = setInterval(() => {
            if (window[name]) { clearInterval(check); resolve(); }
        }, 80);
        setTimeout(() => { clearInterval(check); reject(new Error(`${name} failed to load`)); }, 8000);
    });
}

function bcDownload() {
    if (!bcCanvas) return;
    const a = document.createElement('a');
    a.href = bcCanvas.toDataURL('image/png');
    a.download = `${bcCurrentType.id}_${Date.now()}.png`;
    a.click();
}

async function bcCopyImg() {
    if (!bcCanvas) return;
    try {
        bcCanvas.toBlob(async blob => {
            await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
            toast('Image copied', 'ok');
        }, 'image/png');
    } catch (e) { toast('Copy failed: ' + e.message, 'err'); }
}

// ============== Dispatch ==============
const INITS = { files: initFiles, prefs: initPrefs, sqlite: initSqlite, manifest: initManifest, components: initComponents, native: initNative, processes: initProcesses, net: initNet, logcat: initLogcat, shell: initShell, code: initCode, deeplinks: initDeeplinks, devfiles: initDevfiles, snapshots: initSnapshots, web: initWeb, overlay: initOverlay, fridascripts: initFridaScripts, aichat: initAichat, remoteconfig: initRemoteconfig, envsetup: initEnvsetup, screenshot: initScreenshot, clipboard: initClipboard, barcode: initBarcode };
const REFRESH = { files: refreshFiles, prefs: refreshPrefs, sqlite: refreshSqlite, manifest: refreshManifest, components: refreshComponents, native: refreshNative, processes: refreshProcesses, net: refreshNet, logcat: refreshLogcat, shell: refreshShell, code: refreshCode, deeplinks: refreshDeeplinks, devfiles: refreshDevfiles, snapshots: refreshSnapshots, web: refreshWeb, overlay: refreshOverlay, fridascripts: refreshFridaScripts, aichat: refreshAichat, remoteconfig: refreshRemoteconfig, envsetup: refreshEnvsetup, screenshot: refreshScreenshot, clipboard: refreshClipboard, barcode: refreshBarcode };
function initTab(name) { (INITS[name] || (() => {}))(); }
function refreshTab(name) { (REFRESH[name] || (() => {}))(); }

function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }

// ============== Boot ==============
loadApps();
showTab('welcome');

})();
