const assert=require('node:assert/strict');
const test=require('node:test');

function pageWithWx(purchaseAvailable,member={active:false,expiresAt:null}){
  const calls=[];
  global.Page=definition=>{global.membershipPageDefinition=definition;};
  global.wx={
    getStorageSync(){return 'member-token';},setStorageSync(){},
    login(options){options.success({code:'fresh-code'});},
    request(options){calls.push(options);if(options.url.endsWith('/members/me'))return options.success({statusCode:200,data:{member,purchaseAvailable,orders:[]}});if(options.url.endsWith('/members/orders'))return options.success({statusCode:200,data:{orderNo:'JL123',payParams:{signData:'{}',paySig:'sig',signature:'signature',mode:'short_series_goods'}}});if(options.url.endsWith('/members/app-code'))return options.success({statusCode:200,data:{code:'ABCDE-FGHIJ',expiresInSeconds:600}});throw Error(options.url);},
    requestVirtualPayment(options){calls.push(options);options.success();options.complete();},
    showToast(){}
  };
  delete require.cache[require.resolve('../miniprogram/source/pages/membership/membership')];
  require('../miniprogram/source/pages/membership/membership');
  const page={...global.membershipPageDefinition,data:{...global.membershipPageDefinition.data},setData(values){Object.assign(this.data,values);}};
  page.onShow();
  return {page,calls};
}

test('membership page displays unavailable purchase and keeps benefits readable',async()=>{
  const {page,calls}=pageWithWx(false);
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(page.data.purchaseAvailable,false);
  page.buyMonth();
  assert.equal(calls.filter(call=>call.url&&call.url.endsWith('/members/orders')).length,0);
});

test('membership page shows the welcome gift while purchases are unavailable',async()=>{
  const {page}=pageWithWx(false,{active:true,expiresAt:'2026-12-06T00:00:00Z',welcomeExpiresAt:'2026-12-06T00:00:00Z'});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(page.data.active,true);
  assert.equal(page.data.hasWelcome,true);
  assert.equal(page.data.welcomeExpiresAt,'2026-12-06 08:00');
  assert.equal(page.data.purchaseAvailable,false);
});

test('membership purchase passes signed parameters unchanged and waits for server confirmation',async()=>{
  const {page,calls}=pageWithWx(true);
  await new Promise(resolve=>setImmediate(resolve));
  page.buyMonth();
  await new Promise(resolve=>setImmediate(resolve));
  const order=calls.find(call=>call.url&&call.url.endsWith('/members/orders'));
  assert.equal(order.data.plan,'MONTH');
  assert.equal(order.data.code,'fresh-code');
  const payment=calls.find(call=>call.signData);
  assert.equal(payment.signData,'{}');
  assert.equal(payment.mode,'short_series_goods');
  assert.equal(page.data.active,false);
});
