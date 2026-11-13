const $ = id => document.getElementById(id);
const modeName = mode => mode === 'REVIEW' ? '提审模式' : '实际模式';
const time = value => value ? beijingDateTime(value) : '—';
let currentMode = null;
let page = 0;
let total = 0;

async function get(path) {
  const response = await adminFetch(path, { cache: 'no-store' });
  if (!response.ok) throw Error('加载失败：' + response.status);
  return response.json();
}

function addCell(row, value) {
  const cell = document.createElement('td');
  cell.textContent = value == null || value === '' ? '—' : String(value);
  row.append(cell);
}

async function loadMode() {
  try {
    const data = await get('/api/v1/admin/miniprogram-mode');
    currentMode = data.mode;
    $('currentMode').textContent = modeName(currentMode);
    $('mode').value = currentMode;
    $('mode').disabled = false;
    $('saveMode').disabled = true;
    $('updatedAt').textContent = time(data.updated_at);
    $('updatedBy').textContent = data.updated_by || '—';
  } catch (error) {
    $('modeMessage').textContent = error.message;
  }
}

async function loadHistory() {
  try {
    const rows = await get('/api/v1/admin/miniprogram-mode/history');
    const body = $('historyRows');
    body.replaceChildren();
    rows.forEach(item => {
      const row = document.createElement('tr');
      [time(item.created_at), modeName(item.mode_before), modeName(item.mode_after), item.actor, item.source_ip].forEach(value => addCell(row, value));
      body.append(row);
    });
  } catch (error) {
    $('historyRows').textContent = error.message;
  }
}

async function loadArrivals() {
  try {
    const data = await get(`/api/v1/admin/review-arrivals?page=${page}&size=20`);
    total = data.total;
    const body = $('arrivalRows');
    body.replaceChildren();
    data.items.forEach(item => {
      const row = document.createElement('tr');
      [item.id, item.member_id, item.longitude, item.latitude, item.accuracy_m, time(item.created_at)].forEach(value => addCell(row, value));
      body.append(row);
    });
    $('arrivalPaging').textContent = `第 ${page + 1} 页 · 共 ${total} 条`;
    $('previous').disabled = page === 0;
    $('next').disabled = (page + 1) * 20 >= total;
  } catch (error) {
    $('arrivalPaging').textContent = error.message;
  }
}

$('mode').addEventListener('change', () => {
  $('saveMode').disabled = $('mode').value === currentMode;
  $('modeMessage').textContent = '';
});
$('saveMode').addEventListener('click', async () => {
  $('saveMode').disabled = true;
  try {
    const response = await adminFetch('/api/v1/admin/miniprogram-mode', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode: $('mode').value })
    });
    const data = await response.json();
    if (!response.ok) throw Error(data.error || '保存失败');
    $('modeMessage').textContent = '配置已保存';
    await Promise.all([loadMode(), loadHistory()]);
  } catch (error) {
    $('modeMessage').textContent = error.message;
    $('saveMode').disabled = $('mode').value === currentMode;
  }
});
$('previous').addEventListener('click', () => { if (page > 0) { page--; loadArrivals(); } });
$('next').addEventListener('click', () => { if ((page + 1) * 20 < total) { page++; loadArrivals(); } });
Promise.all([loadMode(), loadHistory(), loadArrivals()]);
