'use strict';

// Admin dashboard. The browser keeps the admin's Basic login for this origin and sends it with every request.

// absolute: a page opened as https://user:pass@host/admin/ would otherwise resolve credentials into fetch URLs
const API = location.origin + '/v1/admin';
const state = {households: [], loaded: {}};
const DEFAULT_HOUSEHOLD = '00000000-0000-7000-8000-000000000001';
// deleted accounts keep no name (V24)
const DELETED_ACCOUNT = '(usunięte konto)';

// --- helpers ---

async function api(path, options = {}) {
    const init = {...options, headers: {...(options.headers || {})}};
    if (init.body !== undefined && typeof init.body !== 'string') {
        init.body = JSON.stringify(init.body);
        init.headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(API + path, init);
    if (!response.ok) {
        let message = response.status + ' ' + response.statusText;
        try {
            const error = await response.json();
            message = (error.error || message) + (error.details ? ': ' + error.details.join('; ') : '');
        } catch (e) { /* no JSON body */ }
        throw new Error(message);
    }
    return response.status === 204 ? null : response.json();
}

function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs)) {
        if (value === undefined || value === null || value === false) continue;
        if (key === 'class') node.className = value;
        else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
        else if (value === true) node.setAttribute(key, '');
        else node.setAttribute(key, value);
    }
    for (const child of children.flat(Infinity)) {
        if (child === null || child === undefined || child === false) continue;
        node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
}

let toastTimer;
function toast(message, error = false) {
    const box = document.getElementById('toast');
    box.textContent = message;
    box.className = 'toast' + (error ? ' error' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => box.classList.add('hidden'), error ? 8000 : 3500);
}

async function run(action, success) {
    try {
        const result = await action();
        if (success) toast(success);
        return result;
    } catch (e) {
        toast(e.message, true);
        throw e;
    }
}

const dateFormat = new Intl.DateTimeFormat('pl-PL', {dateStyle: 'short', timeStyle: 'short'});
const date = value => value ? dateFormat.format(new Date(value)) : '–';
const usd = (value, digits = 4) => value === null || value === undefined ? '–' : '$' + Number(value).toFixed(digits);
const bytes = value => value >= 1 << 30 ? (value / (1 << 30)).toFixed(2) + ' GB'
    : value >= 1 << 20 ? (value / (1 << 20)).toFixed(1) + ' MB' : Math.round(value / 1024) + ' kB';
// the end: UUIDv7 ids made close together share their first (time) characters
const short = id => id ? '…' + id.slice(-8) : '–';

function table(id, headers, rows, empty = 'Brak danych.') {
    const node = document.getElementById(id);
    node.replaceChildren();
    if (rows.length === 0) {
        node.append(el('tbody', {}, el('tr', {}, el('td', {class: 'empty'}, empty))));
        return;
    }
    node.append(el('thead', {}, el('tr', {}, headers.map(h => {
        const [label, cls] = Array.isArray(h) ? h : [h];
        return el('th', {class: cls}, label);
    }))));
    node.append(el('tbody', {}, rows));
}

function householdName(id) {
    const household = state.households.find(h => h.householdId === id);
    return household ? household.name : short(id);
}

// --- households (also the filters of the other tabs) ---

async function loadHouseholds() {
    state.households = await api('/households');
    const live = state.households.filter(h => !h.deletedAt);
    for (const select of document.querySelectorAll('.household-filter')) {
        const current = select.value;
        select.replaceChildren(el('option', {value: ''}, 'wszystkie'),
            ...state.households.map(h => el('option', {value: h.householdId}, h.name + (h.deletedAt ? ' (usunięty)' : ''))));
        select.value = current;
    }
    for (const select of document.querySelectorAll('.household-select')) {
        select.replaceChildren(...live.map(h => el('option', {value: h.householdId}, h.name)));
    }
}

function limitInput(value, step) {
    return el('input', {type: 'number', min: '0', step, class: 'small', value: value ?? '', placeholder: 'bez limitu'});
}

function renderHouseholds() {
    const rows = state.households.map(h => {
        const name = el('input', {class: 'name', value: h.name, maxlength: '100'});
        const limit = limitInput(h.recipeLimit, '1');
        const budget = limitInput(h.llmBudgetUsd, '0.01');
        const spent = Number(h.llmSpentUsd);
        const share = h.llmBudgetUsd ? Math.min(1, spent / Number(h.llmBudgetUsd)) : 0;
        const bar = el('div');
        bar.style.width = Math.round(share * 100) + '%';
        const save = el('button', {onclick: async () => {
            const body = {name: name.value.trim(),
                recipeLimit: limit.value === '' ? null : Number(limit.value),
                llmBudgetUsd: budget.value === '' ? null : Number(budget.value)};
            await run(() => api('/households/' + h.householdId, {method: 'PATCH', body}), 'Zapisano dom „' + body.name + '”.');
            await refresh('households');
        }}, 'Zapisz');
        const invite = el('button', {class: 'secondary', onclick: async () => {
            const invitation = await run(() => api('/households/' + h.householdId + '/invitations', {method: 'POST'}));
            const box = document.getElementById('invitation-result');
            box.textContent = 'Kod zaproszenia do „' + h.name + '”: ' + invitation.code + ' (ważny do '
                + date(invitation.expiresAt) + ', jednorazowy)';
            box.classList.remove('hidden');
        }}, 'Zaproszenie');
        const remove = h.householdId === DEFAULT_HOUSEHOLD ? null : el('button', {class: 'danger', onclick: async () => {
            if (!confirm('Usunąć dom „' + h.name + '” razem z jego ' + h.users + ' użytkownikami? Ich urządzenia przestaną działać, '
                + 'a wszystkie dane domu (przepisy, zrzuty, zdjęcia, konta) zostaną trwale usunięte. Tego nie da się cofnąć.')) return;
            await run(() => api('/households/' + h.householdId, {method: 'DELETE'}), 'Usunięto dom „' + h.name + '”.');
            await refresh('households');
        }}, 'Usuń');
        return el('tr', {class: h.deletedAt ? 'deleted' : null},
            el('td', {}, name, el('div', {class: 'muted'}, short(h.householdId))),
            el('td', {class: 'num'}, h.recipes, ' / ', limit),
            el('td', {class: 'num'}, usd(spent, 4), ' / ', budget,
                h.llmBudgetUsd ? el('div', {class: 'progress' + (share >= 1 ? ' over' : '')}, bar) : null),
            el('td', {class: 'num'}, h.users),
            el('td', {class: 'num'}, h.devices),
            el('td', {class: 'num'}, h.captures),
            el('td', {class: 'nowrap'}, date(h.createdAt), h.deletedAt ? el('div', {}, 'usunięty ' + date(h.deletedAt)) : null),
            el('td', {class: 'actions'}, h.deletedAt ? null : [save, invite, remove]));
    });
    table('households-table', ['Nazwa', ['Przepisy / limit', 'num'], ['Claude w tym miesiącu / budżet USD', 'num'],
        ['Użytk.', 'num'], ['Urządz.', 'num'], ['Zrzuty', 'num'], 'Utworzony', ''], rows);
}

// --- overview ---

async function loadOverview() {
    const o = await api('/overview');
    const card = (label, value, sub) => el('div', {class: 'card'},
        el('div', {class: 'label'}, label), el('div', {class: 'value'}, value), sub ? el('div', {class: 'sub'}, sub) : null);
    document.getElementById('overview-cards').replaceChildren(
        card('Domy', o.households),
        card('Użytkownicy', o.users),
        card('Urządzenia', o.devices),
        card('Przepisy', o.recipes, o.deletedRecipes + ' usuniętych'),
        card('Zrzuty', o.captures),
        card('Pliki', bytes(o.blobBytes), o.blobs + ' plików'),
        card('Claude w tym miesiącu', usd(o.llmCostThisMonthUsd, 2)),
        card('Claude łącznie', usd(o.llmCostTotalUsd, 2)));
    const check = o.lastApiKeyCheck;
    document.getElementById('api-key-status').textContent = check
        ? 'Ostatni test ' + date(check.checkedAt) + ': ' + (check.success ? 'działa' : 'błąd (' + check.errorType + ')')
        : 'Klucz nie był jeszcze sprawdzany.';
    document.getElementById('overview-statuses').replaceChildren(
        ...Object.entries(o.capturesByStatus).map(([status, count]) =>
            el('span', {class: 'badge' + (status === 'needs_attention' ? ' warn' : '')}, status + ': ' + count)),
        el('div', {class: 'muted'}, 'Czas serwera: ' + date(o.serverTime)));
}

// --- users and devices ---

async function loadUsers() {
    const household = document.querySelector('.household-filter[data-for=users]').value;
    const deleted = document.getElementById('users-deleted').checked;
    const users = await api('/users?deleted=' + deleted + (household ? '&householdId=' + household : ''));
    table('users-table', ['Użytkownik', 'Dom', 'Rola', 'E-mail', 'Hasło', ['Urządzenia', 'num'], 'Ostatnio aktywny', 'Utworzony', ''],
        users.map(u => {
            // an owner must have an e-mail address: the server answers 409 member_email_required otherwise
            const role = el('select', {disabled: !!u.deletedAt, onchange: async () => {
                await run(() => api('/users/' + u.userId, {method: 'PATCH', body: {role: role.value}}),
                    u.userName + ': rola ' + role.value + '.');
                await refresh('users');
            }}, ['owner', 'member'].map(r => el('option', {value: r, selected: r === u.role}, r === 'owner' ? 'właściciel' : 'członek')));
            const email = el('input', {class: 'name', type: 'email', value: u.email || '', maxlength: '254',
                placeholder: 'brak', disabled: !!u.deletedAt});
            const saveEmail = el('button', {class: 'secondary', disabled: !!u.deletedAt, onclick: async () => {
                const value = email.value.trim();
                if (value === (u.email || '')) return;
                await run(() => api('/users/' + u.userId, {method: 'PATCH', body: {email: value}}),
                    u.userName + ': ' + (value ? 'e-mail ' + value : 'bez e-maila') + '.');
                await refresh('users');
            }}, 'Zapisz');
            const remove = el('button', {class: 'danger', onclick: async () => {
                if (!confirm('Usunąć użytkownika „' + u.userName + '”? Jego nazwa, e-mail, hasło i urządzenia zostaną trwale usunięte; '
                    + 'przepisy zostają w domu bez autora. Tego nie da się cofnąć.')) return;
                await run(() => api('/users/' + u.userId, {method: 'DELETE'}), 'Usunięto ' + u.userName + '.');
                await refresh('users');
            }}, 'Usuń');
            const name = el('input', {class: 'name', value: u.userName, maxlength: '100', disabled: !!u.deletedAt});
            const rename = el('button', {class: 'secondary', onclick: async () => {
                const userName = name.value.trim();
                if (userName === u.userName) return;
                await run(() => api('/users/' + u.userId, {method: 'PATCH', body: {userName}}),
                    'Nowa nazwa: ' + userName + ' (loguje się nią od teraz).');
                await refresh('users');
            }}, 'Zapisz nazwę');
            const password = el('button', {class: 'secondary', onclick: () => askPassword(u)}, 'Hasło…');
            return el('tr', {class: u.deletedAt ? 'deleted' : null},
                el('td', {}, u.deletedAt ? (u.userName || DELETED_ACCOUNT) : name), el('td', {}, u.householdName), el('td', {}, role),
                el('td', {class: 'nowrap'}, u.deletedAt ? (u.email || '–') : [email, ' ', saveEmail]),
                el('td', {}, u.hasPassword ? 'tak' : 'nie'), el('td', {class: 'num'}, u.devices),
                el('td', {class: 'nowrap'}, date(u.lastUsedAt)), el('td', {class: 'nowrap'}, date(u.createdAt)),
                el('td', {class: 'actions'}, u.deletedAt ? 'usunięty ' + date(u.deletedAt) : [rename, password, remove]));
        }));
}

let passwordUser = null;

function askPassword(user) {
    passwordUser = user;
    document.getElementById('password-user').textContent = user.userName;
    document.getElementById('password-form').reset();
    document.getElementById('password-dialog').showModal();
}

async function loadDevices() {
    const household = document.querySelector('.household-filter[data-for=devices]').value;
    const revoked = document.getElementById('devices-revoked').checked;
    const devices = (await api('/devices'))
        .filter(d => (!household || d.householdId === household) && (revoked || !d.revokedAt));
    table('devices-table', ['Urządzenie', 'Użytkownik', 'Dom', 'Dodane', 'Ostatnio użyte', ''], devices.map(d => {
        const revoke = el('button', {class: 'danger', onclick: async () => {
            if (!confirm('Odwołać urządzenie „' + d.deviceName + '” (' + d.userName + ')? Użytkownik może się na nim zalogować ponownie.')) return;
            await run(() => api('/devices/' + d.deviceId, {method: 'DELETE'}), 'Odwołano urządzenie.');
            await refresh('devices');
        }}, 'Odwołaj');
        const name = el('input', {class: 'name', value: d.deviceName, maxlength: '100'});
        const rename = el('button', {class: 'secondary', onclick: async () => {
            const deviceName = name.value.trim();
            if (deviceName === d.deviceName) return;
            await run(() => api('/devices/' + d.deviceId, {method: 'PATCH', body: {deviceName}}), 'Zmieniono nazwę urządzenia.');
            await refresh('devices');
        }}, 'Zapisz nazwę');
        return el('tr', {class: d.revokedAt ? 'deleted' : null},
            el('td', {}, d.revokedAt ? (d.deviceName || '–') : [name, ' ', rename]), el('td', {}, d.userName || DELETED_ACCOUNT),
            el('td', {}, householdName(d.householdId)),
            el('td', {class: 'nowrap'}, date(d.createdAt)), el('td', {class: 'nowrap'}, date(d.lastUsedAt)),
            el('td', {class: 'actions'}, d.revokedAt ? 'odwołane ' + date(d.revokedAt) : revoke));
    }));
}

// --- recipes ---

async function loadRecipes() {
    const household = document.querySelector('.household-filter[data-for=recipes]').value;
    const q = document.getElementById('recipes-q').value.trim();
    const deleted = document.getElementById('recipes-deleted').checked;
    const params = new URLSearchParams({deleted});
    if (household) params.set('householdId', household);
    if (q) params.set('q', q);
    const recipes = await api('/recipes?' + params);
    table('recipes-table', ['', 'Tytuł', 'Dom', 'Źródło', ['Wersje', 'num'], 'Dodany', 'Zmieniony'], recipes.map(r =>
        el('tr', {class: 'clickable' + (r.deleted ? ' deleted' : ''), onclick: () => showRecipe(r.recipeId)},
            el('td', {}, r.thumbnailBlob ? el('img', {class: 'thumb', src: API + '/blobs/' + r.thumbnailBlob, alt: '', loading: 'lazy'})
                : el('div', {class: 'thumb'})),
            el('td', {class: 'wrap'}, r.title || (r.deleted ? '(usunięty)' : '(bez tytułu)'), r.needsReview ? el('span', {class: 'badge warn'}, ' do przejrzenia') : null),
            el('td', {}, r.householdName), el('td', {}, r.sourceSummary || '–'),
            el('td', {class: 'num'}, r.revisions), el('td', {class: 'nowrap'}, date(r.createdAt)),
            el('td', {class: 'nowrap'}, date(r.updatedAt)))), 'Brak przepisów.');
}

function groups(list, ordered) {
    return (list || []).map(group => el('div', {},
        group.name ? el('strong', {}, group.name) : null,
        el(ordered ? 'ol' : 'ul', {}, group.items.map(item => el('li', {}, item)))));
}

async function showRecipe(recipeId) {
    const detail = await run(() => api('/recipes/' + recipeId));
    const r = detail.recipe;
    const c = detail.content || {};
    document.getElementById('recipe-title').textContent = r.title || '(bez tytułu)';
    // through el(): nested arrays flattened, missing parts (null) left out
    document.getElementById('recipe-body').replaceChildren(el('div', {},
        el('p', {class: 'muted'}, r.householdName + ' · ' + (r.sourceSummary || '–') + ' · dodany ' + date(r.createdAt)
            + ' · id ' + r.recipeId + (r.deleted ? ' · USUNIĘTY' : '')),
        r.thumbnailBlob ? el('img', {class: 'photo', src: API + '/blobs/' + r.thumbnailBlob, alt: ''}) : null,
        detail.urls.length ? el('p', {}, detail.urls.map(u => el('div', {}, el('a', {href: u, target: '_blank', rel: 'noopener noreferrer'}, u)))) : null,
        detail.tags.length ? el('p', {}, detail.tags.map(t => el('span', {class: 'badge'}, t)), ' ') : null,
        c.servings && c.servings.text ? el('p', {}, 'Porcje: ' + c.servings.text) : null,
        el('h3', {}, 'Składniki'), groups(c.ingredientGroups, false),
        el('h3', {}, 'Przygotowanie'), groups(c.stepGroups, true),
        c.notes && c.notes.length ? [el('h3', {}, 'Notatki'), el('ul', {}, c.notes.map(n => el('li', {}, n)))] : null,
        el('h3', {}, 'Historia (' + detail.revisions.length + ')'),
        el('table', {}, el('tbody', {}, detail.revisions.map(v => el('tr', {},
            el('td', {class: 'nowrap'}, date(v.createdAt)), el('td', {}, v.origin === 'USER' ? 'użytkownik' : 'automat'),
            el('td', {}, v.userName || '–'), el('td', {}, v.head ? el('span', {class: 'badge'}, 'aktualna') : ''),
            el('td', {class: 'muted'}, v.captureId ? 'zrzut ' + short(v.captureId) : ''))))),
        el('h3', {}, 'Treść (JSON)'), el('pre', {}, JSON.stringify(c, null, 2))));
    document.getElementById('recipe-dialog').showModal();
}

// --- captures ---

const selectedCaptures = new Set();

async function loadCaptures() {
    const household = document.querySelector('.household-filter[data-for=captures]').value;
    const status = document.getElementById('captures-status').value;
    const params = new URLSearchParams();
    if (household) params.set('householdId', household);
    if (status) params.set('status', status);
    const captures = await api('/captures?' + params);
    selectedCaptures.clear();
    updateReread();
    table('captures-table', ['', 'Otrzymany', 'Dom', 'Kto', 'Typ', 'Tytuł / źródło', 'Stan', ['Koszt', 'num'], 'Przepisy'],
        captures.map(c => el('tr', {},
            el('td', {}, el('input', {type: 'checkbox', onchange: e => {
                if (e.target.checked) selectedCaptures.add(c.captureId); else selectedCaptures.delete(c.captureId);
                updateReread();
            }})),
            el('td', {class: 'nowrap'}, date(c.receivedAt)),
            el('td', {}, c.householdName),
            el('td', {}, c.userName || DELETED_ACCOUNT, el('div', {class: 'muted'}, c.deviceName)),
            el('td', {}, c.sourceType.toLowerCase(), c.attachments ? el('div', {class: 'muted'}, c.attachments + ' zał.') : null),
            el('td', {class: 'wrap'}, c.title || '(bez tytułu)', c.sourceUrl ? el('div', {class: 'muted'}, c.sourceUrl) : null),
            el('td', {class: 'wrap'}, el('span', {class: 'badge' + (c.status === 'needs_attention' ? ' bad' : c.status === 'done' ? '' : ' warn')}, c.status),
                c.reason ? el('div', {}, c.reason) : null, c.message ? el('div', {class: 'muted'}, c.message) : null),
            el('td', {class: 'num'}, usd(c.llmCostUsd)),
            el('td', {}, c.recipeIds.map(id => el('div', {}, el('a', {href: '#', onclick: e => { e.preventDefault(); showRecipe(id); }}, short(id))))))),
        'Brak zrzutów.');
}

function updateReread() {
    const button = document.getElementById('captures-reread');
    button.disabled = selectedCaptures.size === 0;
    button.textContent = 'Ponownie odczytaj AI (' + selectedCaptures.size + ')';
}

async function reread() {
    if (!confirm('Uruchomić ponowny odczyt AI dla ' + selectedCaptures.size + ' zrzutów? To kosztuje (Haiku, czasem Sonnet).')) return;
    const result = await run(() => api('/llm-extractions', {method: 'POST', body: {captureIds: [...selectedCaptures]}}));
    toast('W kolejce: ' + result.queued.length + (result.skipped.length
        ? '; pominięte: ' + result.skipped.map(s => short(s.captureId) + ' (' + s.error + ')').join(', ') : ''));
    await refresh('captures');
}

// --- costs ---

async function loadCosts() {
    const months = document.getElementById('costs-months').value;
    const rows = await api('/llm-costs?months=' + months);
    const byMonth = {};
    for (const row of rows) byMonth[row.month] = (byMonth[row.month] || 0) + Number(row.costUsd);
    document.getElementById('costs-totals').replaceChildren(...Object.entries(byMonth).map(([month, cost]) =>
        el('div', {class: 'card'}, el('div', {class: 'label'}, month), el('div', {class: 'value'}, usd(cost, 2)))));
    const kinds = {extraction: 'odczyt', tagging: 'tagowanie', translation: 'tłumaczenie', 'api-key-check': 'test klucza'};
    table('costs-table', ['Miesiąc', 'Dom', 'Rodzaj', 'Model', ['Wywołania', 'num'], ['Koszt', 'num']], rows.map(r => el('tr', {},
        el('td', {}, r.month), el('td', {}, r.householdName || '–'), el('td', {}, kinds[r.kind] || r.kind),
        el('td', {}, r.model || '–'), el('td', {class: 'num'}, r.calls), el('td', {class: 'num'}, usd(r.costUsd)))),
        'Brak kosztów w tym okresie.');
}

// --- backups ---

async function loadBackups() {
    const backups = await api('/backups');
    table('backups-table', ['Nazwa', 'Utworzona', ['Rozmiar', 'num'], ['Przepisy', 'num'], ['Zrzuty', 'num'], ['Pliki', 'num'], ''],
        backups.map(b => {
            const m = b.manifest || {};
            const remove = el('button', {class: 'danger', onclick: async () => {
                if (!confirm('Usunąć kopię „' + b.name + '”? Tego nie da się cofnąć.')) return;
                await run(() => api('/backups/' + encodeURIComponent(b.name), {method: 'DELETE'}), 'Usunięto kopię.');
                await refresh('backups');
            }}, 'Usuń');
            return el('tr', {},
                el('td', {class: 'wrap'}, b.name), el('td', {class: 'nowrap'}, date(m.createdAt)),
                el('td', {class: 'num'}, bytes(b.sizeBytes)), el('td', {class: 'num'}, m.recipes ?? '–'),
                el('td', {class: 'num'}, m.captures ?? '–'), el('td', {class: 'num'}, m.blobs ?? '–'),
                el('td', {class: 'actions'}, el('a', {href: API + '/backups/' + encodeURIComponent(b.name)},
                    el('button', {class: 'secondary', type: 'button'}, 'Pobierz')), remove));
        }), 'Brak kopii.');
}

// --- tabs ---

const loaders = {
    overview: loadOverview,
    households: async () => { await loadHouseholds(); renderHouseholds(); },
    users: loadUsers,
    devices: loadDevices,
    recipes: loadRecipes,
    captures: loadCaptures,
    costs: loadCosts,
    backups: loadBackups,
};

async function refresh(tab) {
    try {
        await loaders[tab]();
    } catch (e) {
        toast(e.message, true);
    }
}

function show(tab) {
    for (const button of document.querySelectorAll('#tabs button')) button.classList.toggle('active', button.dataset.tab === tab);
    for (const section of document.querySelectorAll('.tab')) section.classList.toggle('hidden', section.id !== tab);
    history.replaceState(null, '', '#' + tab);
    refresh(tab);
}

document.getElementById('tabs').addEventListener('click', e => {
    if (e.target.dataset.tab) show(e.target.dataset.tab);
});
window.addEventListener('hashchange', () => {
    const tab = location.hash.slice(1);
    if (loaders[tab] && document.getElementById(tab).classList.contains('hidden')) show(tab);
});
for (const select of document.querySelectorAll('.household-filter')) {
    select.addEventListener('change', () => refresh(select.dataset.for));
}
document.getElementById('users-deleted').addEventListener('change', () => refresh('users'));
document.getElementById('devices-revoked').addEventListener('change', () => refresh('devices'));
document.getElementById('recipes-deleted').addEventListener('change', () => refresh('recipes'));
let searchTimer;
document.getElementById('recipes-q').addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => refresh('recipes'), 300);
});
document.getElementById('captures-status').addEventListener('change', () => refresh('captures'));
document.getElementById('captures-reread').addEventListener('click', reread);
document.getElementById('costs-months').addEventListener('change', () => refresh('costs'));
document.querySelector('#recipe-dialog [data-close]').addEventListener('click', () => document.getElementById('recipe-dialog').close());

document.getElementById('api-key-check').addEventListener('click', async e => {
    e.target.disabled = true;
    try {
        const result = await run(() => api('/api-key-check', {method: 'POST'}));
        toast(result.success ? 'Klucz API działa.' : 'Klucz API nie działa: ' + result.errorType, !result.success);
        await refresh('overview');
    } finally {
        e.target.disabled = false;
    }
});

document.getElementById('enroll-form').addEventListener('submit', async e => {
    e.preventDefault();
    const form = new FormData(e.target);
    const result = await run(() => api('/enrollments', {method: 'POST', body: Object.fromEntries(form)}));
    const box = document.getElementById('enroll-result');
    box.textContent = 'Token urządzenia (pokazany tylko teraz): ' + result.token;
    box.classList.remove('hidden');
    e.target.reset();
    await refresh('users');
});

document.getElementById('password-form').addEventListener('submit', async e => {
    e.preventDefault();
    const form = new FormData(e.target);
    if (form.get('password') !== form.get('repeat')) {
        toast('Hasła się różnią.', true);
        return;
    }
    await run(() => api('/users/' + passwordUser.userId, {method: 'PATCH', body: {password: form.get('password')}}),
        'Ustawiono nowe hasło dla ' + passwordUser.userName + '.');
    document.getElementById('password-dialog').close();
    await refresh('users');
});
document.querySelector('#password-dialog [data-close]').addEventListener('click',
    () => document.getElementById('password-dialog').close());

document.getElementById('household-form').addEventListener('submit', async e => {
    e.preventDefault();
    const form = new FormData(e.target);
    const number = key => form.get(key) === '' ? null : Number(form.get(key));
    const body = {name: form.get('name').trim(), recipeLimit: number('recipeLimit'), llmBudgetUsd: number('llmBudgetUsd')};
    await run(() => api('/households', {method: 'POST', body}), 'Utworzono dom „' + body.name + '”. Teraz utwórz zaproszenie.');
    e.target.reset();
    await refresh('households');
});

document.getElementById('backup-form').addEventListener('submit', async e => {
    e.preventDefault();
    const label = new FormData(e.target).get('label').trim();
    const button = e.target.querySelector('button');
    button.disabled = true;
    button.textContent = 'Tworzę kopię…';
    try {
        await run(() => api('/backups', {method: 'POST', body: label ? {label} : {}}), 'Kopia utworzona.');
        e.target.reset();
        await refresh('backups');
    } finally {
        button.disabled = false;
        button.textContent = 'Utwórz kopię teraz';
    }
});

(async () => {
    await refresh('households');
    const initial = location.hash.slice(1);
    show(loaders[initial] ? initial : 'overview');
})();
