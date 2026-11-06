let page=0,total=0;
const $=id=>document.getElementById(id);
const date=value=>value?String(value).replace('T',' ').slice(0,19):'—';
const labels={MONTH:'1 个月',YEAR:'1 年',NONE:'取消会员',SUCCESS:'成功',FAILED:'失败'};
const cell=(row,value)=>{const td=document.createElement('td');td.textContent=value==null?'—':String(value);row.append(td);};
async function load(){
  const query=new URLSearchParams({page,size:'20'});
  ['userId','outcome','from','to'].forEach(id=>{const value=$(id).value;if(value)query.set(id,id==='from'||id==='to'?new Date(value).toISOString():value);});
  try{const response=await adminFetch('/api/v1/admin/android-users/history?'+query);if(!response.ok)throw Error('加载失败：'+response.status);const data=await response.json();total=data.total;const body=$('rows');body.replaceChildren();data.items.forEach(item=>{const row=document.createElement('tr');[date(item.created_at),item.actor,item.user_id,'设置会员',labels[item.plan_code]||item.plan_code,labels[item.outcome]||item.outcome,item.reason].forEach(v=>cell(row,v));const td=document.createElement('td');const button=document.createElement('button');button.textContent='展开';button.onclick=()=>{ $('detail').hidden=false;$('meta').textContent='请求标识：'+item.request_id+'　来源 IP：'+(item.source_ip||'—');$('before').textContent=item.before_value?JSON.stringify(JSON.parse(item.before_value),null,2):'—';$('after').textContent=item.after_value?JSON.stringify(JSON.parse(item.after_value),null,2):'—';$('error').textContent=item.error_summary||'';};td.append(button);row.append(td);body.append(row);});$('paging').textContent='第 '+(page+1)+' 页 · 共 '+total+' 条';$('previous').disabled=page===0;$('next').disabled=(page+1)*20>=total;}catch(error){$('paging').textContent=error.message;}
}
$('searchButton').onclick=()=>{page=0;load();};$('previous').onclick=()=>{if(page>0){page--;load();}};$('next').onclick=()=>{if((page+1)*20<total){page++;load();}};load();
