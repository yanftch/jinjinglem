let page=0,total=0,selected=null;
const $=id=>document.getElementById(id);
const date=value=>value?String(value).replace('T',' ').slice(0,19):'—';
const cell=(row,value)=>{const td=document.createElement('td');td.textContent=value==null?'—':String(value);row.append(td);};
async function get(path){const response=await adminFetch(path);if(!response.ok)throw Error('加载失败：'+response.status);return response.json();}
async function load(){
  try{
    const data=await get('/api/v1/admin/android-users?page='+page+'&size=20&query='+encodeURIComponent($('search').value.trim()));
    total=data.total;const body=$('rows');body.replaceChildren();
    data.items.forEach(item=>{const row=document.createElement('tr');[item.id,item.device_id,item.active?'有效':'未开通/已到期',date(item.member_expires_at),date(item.created_at),date(item.last_seen_at)].forEach(v=>cell(row,v));const td=document.createElement('td');const button=document.createElement('button');button.textContent='查看 / 设置';button.onclick=()=>detail(item.id);td.append(button);row.append(td);body.append(row);});
    $('paging').textContent='第 '+(page+1)+' 页 · 共 '+total+' 人';$('previous').disabled=page===0;$('next').disabled=(page+1)*20>=total;
  }catch(error){$('paging').textContent=error.message;}
}
async function detail(id){
  try{const data=await get('/api/v1/admin/android-users/'+id);selected=id;$('detail').hidden=false;const box=$('summary');box.replaceChildren();[['用户编号',data.user.id],['设备标识',data.user.deviceId],['会员状态',data.user.active?'有效':'未开通/已到期'],['有效期至',date(data.user.memberExpiresAt)],['首次登记',date(data.user.createdAt)],['最近启动',date(data.user.lastSeenAt)]].forEach(([label,value])=>{const div=document.createElement('div');div.textContent=label+'：'+value;box.append(div);});}catch(error){$('message').textContent=error.message;}
}
$('searchButton').onclick=()=>{page=0;load();};$('previous').onclick=()=>{if(page>0){page--;load();}};$('next').onclick=()=>{if((page+1)*20<total){page++;load();}};
$('save').onclick=async()=>{
  if(!selected)return;const reason=$('reason').value.trim();if(!reason){$('message').textContent='请填写操作原因';return;}
  $('save').disabled=true;
  try{const response=await adminFetch('/api/v1/admin/android-users/'+selected+'/membership',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({plan:$('plan').value,reason})});const data=await response.json();if(!response.ok)throw Error(data.error||'保存失败');$('message').textContent='保存成功 · 请求 '+data.requestId;$('reason').value='';await detail(selected);await load();}
  catch(error){$('message').textContent=error.message;}
  finally{$('save').disabled=false;}
};
load();
async function loadQr(){const response=await adminFetch('/api/v1/contact/wechat-qr?cache='+Date.now());const preview=$('qrPreview');preview.style.display=response.ok?'block':'none';if(response.ok)preview.src='/api/v1/contact/wechat-qr?cache='+Date.now();$('qrMessage').textContent=response.ok?'已配置二维码':response.status===404?'尚未配置二维码':'无法查询二维码状态';}
$('uploadQr').onclick=async()=>{const file=$('qrFile').files[0];if(!file){$('qrMessage').textContent='请选择二维码图片';return;}if(!['image/png','image/jpeg'].includes(file.type)||file.size>2*1024*1024){$('qrMessage').textContent='仅支持不超过 2MB 的 PNG 或 JPEG 图片';return;}const button=$('uploadQr');button.disabled=true;try{const body=new FormData();body.append('file',file);const response=await adminFetch('/api/v1/admin/contact/wechat-qr',{method:'POST',body});const data=await response.json();if(!response.ok)throw Error(data.error||'上传失败');$('qrMessage').textContent='上传成功';await loadQr();}catch(error){$('qrMessage').textContent=error.message;}finally{button.disabled=false;}};
loadQr();
