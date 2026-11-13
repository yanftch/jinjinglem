let page = 0;
let total = 0;
let selected = null;
const $ = id => document.getElementById(id);
const date = value => beijingDateTime(value);
const label = value => value == null || value === '' ? '—' : String(value);

function cell(row, value) {
  const td = document.createElement('td');
  td.textContent = label(value);
  row.append(td);
  return td;
}

async function get(path) {
  const response = await adminFetch(path);
  if (!response.ok) throw Error('加载失败：' + response.status);
  return response.json();
}

async function load() {
  try {
    const query = encodeURIComponent($('search').value.trim());
    const data = await get(`/api/v1/admin/android-users?page=${page}&size=20&query=${query}`);
    total = data.total;
    $('allUsers').textContent = data.summary.allUsers;
    $('newToday').textContent = data.summary.newToday;
    $('activeVip').textContent = data.summary.activeVip;
    const body = $('rows');
    body.replaceChildren();
    data.items.forEach(item => {
      const row = document.createElement('tr');
      cell(row, item.id);
      const deviceCell = cell(row, item.device_id.slice(0, 16) + '…');
      deviceCell.title = item.device_id;
      cell(row, [item.manufacturer, item.brand].filter(Boolean).join(' / '));
      cell(row, item.model);
      cell(row, [item.android_version, item.sdk_int ? `SDK ${item.sdk_int}` : ''].filter(Boolean).join(' · '));
      cell(row, item.active ? '有效' : '未开通/已到期');
      cell(row, date(item.member_expires_at));
      cell(row, date(item.created_at));
      cell(row, date(item.last_seen_at));
      const action = document.createElement('td');
      const button = document.createElement('button');
      button.textContent = '查看 / 设置';
      button.onclick = () => detail(item.id);
      action.append(button);
      row.append(action);
      body.append(row);
    });
    $('paging').textContent = `第 ${page + 1} 页 · 共 ${total} 人`;
    $('previous').disabled = page === 0;
    $('next').disabled = (page + 1) * 20 >= total;
  } catch (error) {
    $('paging').textContent = error.message;
  }
}

function localDateTime(value) { return beijingDateTimeInput(value); }

async function detail(id) {
  try {
    const data = await get('/api/v1/admin/android-users/' + id);
    selected = id;
    $('detail').hidden = false;
    const box = $('summary');
    box.replaceChildren();
    [
      ['用户编号', data.user.id], ['设备标识', data.user.deviceId],
      ['厂商', data.user.manufacturer], ['品牌', data.user.brand],
      ['手机型号', data.user.model], ['设备代号', data.user.deviceName],
      ['Android 版本', data.user.androidVersion], ['SDK 版本', data.user.sdkInt || ''],
      ['App 版本', data.user.appVersion], ['VIP 状态', data.user.active ? '有效' : '未开通/已到期'],
      ['有效期至', date(data.user.memberExpiresAt)], ['首次登记', date(data.user.createdAt)],
      ['最近使用', date(data.user.lastSeenAt)]
    ].forEach(([name, value]) => {
      const div = document.createElement('div');
      div.textContent = `${name}：${label(value)}`;
      box.append(div);
    });
    $('customExpiry').value = localDateTime(data.user.memberExpiresAt);
  } catch (error) {
    $('message').textContent = error.message;
  }
}

$('searchButton').onclick = () => { page = 0; load(); };
$('previous').onclick = () => { if (page > 0) { page--; load(); } };
$('next').onclick = () => { if ((page + 1) * 20 < total) { page++; load(); } };
$('plan').onchange = () => { $('customExpiryWrap').hidden = $('plan').value !== 'CUSTOM'; };
$('save').onclick = async () => {
  if (!selected) return;
  const reason = $('reason').value.trim();
  if (!reason) { $('message').textContent = '请填写操作原因'; return; }
  const plan = $('plan').value;
  const body = { plan, reason };
  if (plan === 'CUSTOM') {
    const chosen = $('customExpiry').value;
    if (!chosen || Number.isNaN(new Date(chosen).getTime())) {
      $('message').textContent = '请选择有效的到期时间';
      return;
    }
    body.expiresAt = beijingDateTimeInstant(chosen);
  }
  $('save').disabled = true;
  try {
    const response = await adminFetch(`/api/v1/admin/android-users/${selected}/membership`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });
    const data = await response.json();
    if (!response.ok) throw Error(data.error || '保存失败');
    $('message').textContent = '保存成功 · 请求 ' + data.requestId;
    $('reason').value = '';
    await detail(selected);
    await load();
  } catch (error) {
    $('message').textContent = error.message;
  } finally {
    $('save').disabled = false;
  }
};

async function loadQr() {
  const response = await adminFetch('/api/v1/contact/wechat-qr?cache=' + Date.now());
  const preview = $('qrPreview');
  preview.style.display = response.ok ? 'block' : 'none';
  if (response.ok) preview.src = '/api/v1/contact/wechat-qr?cache=' + Date.now();
  $('qrMessage').textContent = response.ok ? '已配置二维码' : response.status === 404 ? '尚未配置二维码' : '无法查询二维码状态';
}
$('uploadQr').onclick = async () => {
  const file = $('qrFile').files[0];
  if (!file) { $('qrMessage').textContent = '请选择二维码图片'; return; }
  if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 2 * 1024 * 1024) {
    $('qrMessage').textContent = '仅支持不超过 2MB 的 PNG 或 JPEG 图片';
    return;
  }
  const button = $('uploadQr');
  button.disabled = true;
  try {
    const body = new FormData();
    body.append('file', file);
    const response = await adminFetch('/api/v1/admin/contact/wechat-qr', { method: 'POST', body });
    const data = await response.json();
    if (!response.ok) throw Error(data.error || '上传失败');
    await loadQr();
  } catch (error) {
    $('qrMessage').textContent = error.message;
  } finally {
    button.disabled = false;
  }
};

load();
loadQr();
