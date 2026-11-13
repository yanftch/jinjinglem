const $ = id => document.getElementById(id);
const durationName = value => value === '30_DAYS' ? '30天' : '99年';
const time = value => value ? beijingDateTime(value) : '—';
let currentDuration = null;

async function get(path) {
  const response = await adminFetch(path, { cache: 'no-store' });
  if (!response.ok) throw Error('加载失败：' + response.status);
  return response.json();
}

async function loadConfig() {
  try {
    const data = await get('/api/v1/admin/system-config');
    currentDuration = data.giftDuration;
    $('giftDuration').value = currentDuration;
    $('giftDuration').disabled = false;
    $('saveConfig').disabled = true;
    $('updatedAt').textContent = time(data.updatedAt);
    $('updatedBy').textContent = data.updatedBy || '—';
  } catch (error) { $('message').textContent = error.message; }
}

async function loadHistory() {
  try {
    const rows = await get('/api/v1/admin/system-config/history');
    const body = $('historyRows');
    body.replaceChildren();
    rows.forEach(item => {
      const row = document.createElement('tr');
      [time(item.created_at), durationName(item.duration_before), durationName(item.duration_after), item.actor, item.source_ip].forEach(value => {
        const cell = document.createElement('td');
        cell.textContent = value == null || value === '' ? '—' : String(value);
        row.append(cell);
      });
      body.append(row);
    });
  } catch (error) { $('historyRows').textContent = error.message; }
}

$('giftDuration').addEventListener('change', () => {
  $('saveConfig').disabled = $('giftDuration').value === currentDuration;
  $('message').textContent = '';
});
$('saveConfig').addEventListener('click', async () => {
  $('saveConfig').disabled = true;
  try {
    const response = await adminFetch('/api/v1/admin/system-config', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ giftDuration: $('giftDuration').value })
    });
    const data = await response.json();
    if (!response.ok) throw Error(data.error || '保存失败');
    $('message').textContent = '配置已保存';
    await Promise.all([loadConfig(), loadHistory()]);
  } catch (error) {
    $('message').textContent = error.message;
    $('saveConfig').disabled = $('giftDuration').value === currentDuration;
  }
});
Promise.all([loadConfig(), loadHistory()]);
