import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const SOURCES = [['Harness', 'harness.js']];

test('公共插件框架与 Bot 对话使用相同的滚动控制器', () => {
 const host = readFileSync(new URL('../../android/app/src/main/assets/www/js/chat-scroll.js', import.meta.url),'utf8');
 const framework = readFileSync(new URL('../../android/app/src/main/assets/plugin-framework/v1/js/chat-scroll.js', import.meta.url),'utf8');
 assert.equal(framework,host);
});

function controllerSource(file) {
 return readFileSync(new URL('../../android/app/src/main/assets/www/js/chat-scroll.js', import.meta.url),'utf8').replace('export function','function');
}
function controllerFactory(file) { return new Function(`${controllerSource(file)}\nreturn createChatScroll;`)(); }

class FakeNode {
  constructor(box, item) {
    this.box = box;
    this.item = item;
    this.dataset = { scrollKey: item.key };
  }
  getBoundingClientRect() {
    const top = this.box.baseTop - this.box.scrollTop + this.item.top;
    return { top, bottom: top + this.item.height, height: this.item.height, width: 320 };
  }
}

class FakeScrollBox {
  constructor(height = 300) {
    this.clientHeight = height;
    this.scrollTop = 0;
    this.baseTop = 80;
    this.items = [];
    this.listeners = new Map();
    this.children = [];
  }
  get scrollHeight() {
    const content = this.items.reduce((max, item) => Math.max(max, item.top + item.height), 0);
    return Math.max(this.clientHeight, content);
  }
  setLayout(items) {
    let top = 0;
    this.items = items.map(({ key, height }) => {
      const item = { key, height, top };
      top += height;
      return item;
    });
  }
  append(key, height) {
    const top = this.items.reduce((max, item) => Math.max(max, item.top + item.height), 0);
    this.items.push({ key, height, top });
  }
  prepend(items) {
    const shift = items.reduce((sum, item) => sum + item.height, 0);
    for (const item of this.items) item.top += shift;
    let top = 0;
    const before = items.map(({ key, height }) => {
      const item = { key, height, top };
      top += height;
      return item;
    });
    this.items = [...before, ...this.items];
  }
  setViewport(height) { this.clientHeight = height; }
  get maxTop() { return Math.max(0, this.scrollHeight - this.clientHeight); }
  clamp() { this.scrollTop = Math.max(0, Math.min(this.maxTop, this.scrollTop)); }
  userScroll(top) {
    this.scrollTop = Math.max(0, Math.min(this.maxTop, top));
    this.dispatch('scroll');
  }
  getBoundingClientRect() {
    return { top: this.baseTop, bottom: this.baseTop + this.clientHeight, height: this.clientHeight, width: 320 };
  }
  querySelectorAll(selector) {
    return selector === '[data-scroll-key]' ? this.items.map((item) => new FakeNode(this, item)) : [];
  }
  addEventListener(type, fn) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(fn);
  }
  removeEventListener(type, fn) {
    this.listeners.get(type)?.delete(fn);
  }
  dispatch(type, event = {}) {
    for (const fn of this.listeners.get(type) || []) fn({ type, ...event });
  }
  firstVisible() {
    const viewportTop = this.scrollTop;
    const viewportBottom = viewportTop + this.clientHeight;
    const hit = this.items.find((item) => item.top < viewportBottom && item.top + item.height > viewportTop);
    return hit ? { key: hit.key, offset: hit.top - viewportTop } : null;
  }
}

let rafQueue = [];
let observers = [];
globalThis.ResizeObserver = class FakeResizeObserver {
  constructor(callback) {
    this.callback = callback;
    this.targets = new Set();
    observers.push(this);
  }
  observe(target) { this.targets.add(target); }
  disconnect() { this.targets.clear(); }
};
globalThis.requestAnimationFrame = (callback) => {
  rafQueue.push(callback);
  return rafQueue.length;
};

function flushFrames() {
  while (rafQueue.length) {
    const queued = rafQueue;
    rafQueue = [];
    for (const callback of queued) callback(0);
  }
}

function triggerResize() {
  for (const observer of observers) {
    if (observer.targets.size) observer.callback([]);
  }
}

function setup(file) {
  rafQueue = [];
  observers = [];
  const create = controllerFactory(file);
  const box = new FakeScrollBox();
  const controller = create({ threshold: 56, isVisible: () => true });
  controller.attach(box);
  flushFrames();
  return { box, controller };
}

function enterLoaded(controller, box, sid, layout) {
  controller.enter(sid);
  box.setLayout(layout);
  controller.begin(sid);
  controller.end({ sid, ready: true });
}

for (const [label, file] of SOURCES) {
  test(`${label}: 初次异步加载后定位最新（同一 SID 的占位渲染不丢定位）`, () => {
    const { box, controller } = setup(file);
    controller.enter('sid-a');
    flushFrames();
    box.setLayout([{ key: 'a', height: 500 }]);
    controller.begin('sid-a');
    controller.end({ sid: 'sid-a', ready: false });
    assert.equal(box.scrollTop, 0, '加载占位不应被视为已完成定位');

    box.setLayout([{ key: 'a', height: 500 }, { key: 'b', height: 500 }]);
    controller.begin('sid-a');
    controller.end({ sid: 'sid-a', ready: true });
    assert.equal(box.scrollTop, box.maxTop);
    assert.equal(controller.state.pendingEntry, false);
  });

  test(`${label}: 隐藏/零尺寸时渲染不误判，恢复尺寸后可测量时才到底`, () => {
    const { box, controller } = setup(file);
    box.setViewport(0);
    controller.enter('sid-a');
    box.setLayout([{ key: 'a', height: 900 }]);
    controller.begin('sid-a');
    controller.end({ sid: 'sid-a', ready: true });
    assert.equal(box.scrollTop, 0, '零尺寸时不能判定到底');
    assert.equal(controller.state.pendingEntry, true);

    box.setViewport(240);
    triggerResize();
    flushFrames();
    assert.equal(box.scrollTop, box.maxTop, '显示后应完成挂起的首次定位');
  });

  test(`${label}: 在底部持续跟随流式新增和延迟图片增高`, () => {
    const { box, controller } = setup(file);
    enterLoaded(controller, box, 'sid-a', [{ key: 'a', height: 300 }, { key: 'b', height: 300 }]);
    assert.equal(box.scrollTop, box.maxTop);

    box.append('c', 220);
    controller.begin('sid-a');
    controller.end({ sid: 'sid-a', ready: true });
    assert.equal(box.scrollTop, box.maxTop);

    box.items[2].height += 160; // 模拟延迟图片加载使当前底部内容增高
    box.dispatch('load');
    flushFrames();
    assert.equal(box.scrollTop, box.maxTop);
  });

  test(`${label}: 用户上翻后新消息不抢滚动`, () => {
    const { box, controller } = setup(file);
    enterLoaded(controller, box, 'sid-a', [
      { key: 'a', height: 240 }, { key: 'b', height: 240 },
      { key: 'c', height: 240 }, { key: 'd', height: 240 },
    ]);
    box.userScroll(260);
    const before = box.firstVisible();
    assert.equal(controller.state.follow, false);

    box.append('e', 240);
    controller.begin('sid-a');
    controller.end({ sid: 'sid-a', ready: true });
    assert.deepEqual(box.firstVisible(), before);
    assert.notEqual(box.scrollTop, box.maxTop);
  });

  test(`${label}: 历史前插保持首个可见消息锚点`, () => {
    const { box, controller } = setup(file);
    enterLoaded(controller, box, 'sid-a', [
      { key: 'a', height: 200 }, { key: 'b', height: 200 },
      { key: 'c', height: 200 }, { key: 'd', height: 200 },
    ]);
    box.userScroll(250);
    const before = box.firstVisible();
    box.prepend([{ key: 'old-1', height: 120 }, { key: 'old-2', height: 100 }]);
    controller.begin('sid-a');
    controller.end({ sid: 'sid-a', ready: true, prepend: true });
    assert.deepEqual(box.firstVisible(), before);
  });

  test(`${label}: 容器尺寸/键盘变化在历史中保持锚点，在底部保持最新`, () => {
    const { box, controller } = setup(file);
    enterLoaded(controller, box, 'sid-a', [
      { key: 'a', height: 260 }, { key: 'b', height: 260 },
      { key: 'c', height: 260 }, { key: 'd', height: 260 },
    ]);
    box.userScroll(280);
    const before = box.firstVisible();
    box.setViewport(190);
    triggerResize();
    flushFrames();
    assert.deepEqual(box.firstVisible(), before);

    box.userScroll(box.maxTop);
    assert.equal(controller.state.follow, true);
    box.setViewport(120);
    triggerResize();
    flushFrames();
    assert.equal(box.scrollTop, box.maxTop);
  });

  test(`${label}: 隐藏期间更新不写滚动，恢复显示后保持原阅读锚点`, () => {
    const { box, controller } = setup(file);
    enterLoaded(controller, box, 'sid-a', [
      { key: 'a', height: 260 }, { key: 'b', height: 260 },
      { key: 'c', height: 260 }, { key: 'd', height: 260 },
    ]);
    box.userScroll(280);
    const before = box.firstVisible();

    box.setViewport(0);
    triggerResize();
    flushFrames();
    box.append('hidden-new', 180);
    controller.begin('sid-a');
    controller.end({ sid: 'sid-a', ready: true });
    assert.equal(box.scrollTop, 280, '隐藏时不能把零尺寸当到底');

    box.setViewport(190);
    triggerResize();
    flushFrames();
    assert.deepEqual(box.firstVisible(), before);
  });

  test(`${label}: 折叠改变上方高度时保持阅读锚点，底部仍跟随`, () => {
    const { box, controller } = setup(file);
    enterLoaded(controller, box, 'sid-a', [
      { key: 'a', height: 240 }, { key: 'b', height: 240 },
      { key: 'c', height: 240 }, { key: 'd', height: 240 },
    ]);
    box.userScroll(300);
    const before = box.firstVisible();
    box.items[0].height += 120;
    for (let i = 1; i < box.items.length; i++) box.items[i].top += 120;
    box.dispatch('toggle');
    flushFrames();
    assert.deepEqual(box.firstVisible(), before);

    box.userScroll(box.maxTop);
    box.items[3].height += 80;
    box.dispatch('toggle');
    flushFrames();
    assert.equal(box.scrollTop, box.maxTop);
  });

  test(`${label}: 切换 SID 不串位置，同 SID 显式重新进入仍定位最新`, () => {
    const { box, controller } = setup(file);
    enterLoaded(controller, box, 'sid-a', [
      { key: 'a', height: 300 }, { key: 'b', height: 300 }, { key: 'c', height: 300 },
    ]);
    box.userScroll(200);
    assert.equal(controller.state.follow, false);

    box.setLayout([{ key: 'x', height: 200 }, { key: 'y', height: 400 }]);
    controller.begin('sid-b');
    controller.end({ sid: 'sid-b', ready: true });
    assert.equal(controller.state.sid, 'sid-b');
    assert.equal(box.scrollTop, box.maxTop, '自动 SID 变化也不能复用上一会话锚点');

    box.userScroll(0);
    controller.enter('sid-b');
    controller.begin('sid-b');
    controller.end({ sid: 'sid-b', ready: true });
    assert.equal(box.scrollTop, box.maxTop, '同一 SID 的显式进入不能被旧滚动位置拦截');
  });
}

test('Bot 切换后恢复阅读锚点，不跳到另一 Bot 或当前底部',()=>{
 const {box,controller}=setup('harness.js');
 enterLoaded(controller,box,'a',Array.from({length:20},(_,i)=>({key:'a'+i,height:60})));
 box.userScroll(360);controller.remember();const saved={...controller.state};
 controller.enter('b');box.setLayout(Array.from({length:5},(_,i)=>({key:'b'+i,height:80})));controller.end({sid:'b'});
 controller.resume('a',saved);box.setLayout(Array.from({length:20},(_,i)=>({key:'a'+i,height:60})));controller.end({sid:'a'});
 assert.equal(box.scrollTop,360);assert.equal(controller.state.follow,false);
});


test('上滑尚未派发 scroll 时，异步渲染也不抢回底部',()=>{
 const {box,controller}=setup('harness.js');
 enterLoaded(controller,box,'a',[{key:'a',height:500},{key:'b',height:500}]);
 box.scrollTop=500;
 controller.begin('a');
 const before=box.firstVisible();
 box.append('c',200);
 controller.end({sid:'a',ready:true});
 assert.deepEqual(box.firstVisible(),before);
 assert.equal(controller.state.follow,false);
});
test('距离底部仅20px的主动上滑也退出跟随',()=>{
 const {box,controller}=setup('harness.js');
 enterLoaded(controller,box,'a',[{key:'a',height:500},{key:'b',height:500}]);
 box.userScroll(box.maxTop-20);
 const top=box.scrollTop;
 controller.begin('a');box.append('c',200);controller.end({sid:'a',ready:true});
 assert.equal(box.scrollTop,top);assert.equal(controller.state.follow,false);
});

test('向下滚动的原生 scroll 事件尚未派发时，布局刷新保持当前阅读位置', () => {
 const {box,controller}=setup('harness.js');
 enterLoaded(controller,box,'a',Array.from({length:20},(_,i)=>({key:'a'+i,height:60})));
 box.userScroll(0);
 // The browser updates scrollTop before delivering its queued scroll event.
 box.scrollTop=420;
 const reading=box.firstVisible();
 triggerResize();flushFrames();
 assert.equal(box.scrollTop,420);
 assert.deepEqual(box.firstVisible(),reading);
 box.dispatch('scroll');
 assert.equal(controller.state.follow,false);
});


test('顶部下拉越界（负 scrollTop）不写入锚点，前插完成仍锚定原消息',()=>{
 const {box,controller}=setup('harness.js');
 enterLoaded(controller,box,'a',Array.from({length:20},(_,i)=>({key:'m'+i,height:100})));
 box.userScroll(0); // 到顶，锚定 m0
 box.dispatch('touchstart',{touches:[{clientY:100}]});
 box.dispatch('touchmove',{touches:[{clientY:180}]});
 assert.equal(controller.state.follow,false,'下拉应暂停跟随');
 // iOS 式橡皮筋越界：scrollTop 变负并派发原生事件
 box.scrollTop=-60; box.dispatch('scroll');
 assert.equal(controller.state.anchor.top,0,'越界负值不能写进锚点位置');
 // 加载中的并发状态渲染
 controller.begin('a'); controller.end({sid:'a',ready:true});
 // 旧页前插完成
 box.prepend([{key:'p0',height:120},{key:'p1',height:120}]);
 controller.begin('a'); controller.end({sid:'a',ready:true,prepend:true});
 assert.deepEqual(box.firstVisible(),{key:'m0',offset:0},'前插后仍锚定原首条消息');
 assert.equal(box.scrollTop,240);
 box.dispatch('touchend');
});

test('短内容下拉回弹的 0/0 滚动事件不重新打开跟随，后续新消息不把用户拽走',()=>{
 const {box,controller}=setup('harness.js');
 enterLoaded(controller,box,'a',[{key:'m0',height:150},{key:'m1',height:150}]); // 内容恰满一屏：maxTop=0
 assert.equal(controller.state.follow,true);
 box.dispatch('touchstart',{touches:[{clientY:100}]});
 box.dispatch('touchmove',{touches:[{clientY:180}]});
 assert.equal(controller.state.follow,false,'下拉已暂停跟随');
 // 越界与回弹都产生 0/0 附近的原生事件；它们不含用户滚动意图
 box.scrollTop=-40; box.dispatch('scroll');
 box.scrollTop=0; box.dispatch('scroll');
 assert.equal(controller.state.follow,false,'回弹事件不能重新打开跟随');
 // 下拉触发的旧页前插完成：锚定原首条消息
 box.prepend([{key:'p0',height:100},{key:'p1',height:100},{key:'p2',height:100},{key:'p3',height:100},{key:'p4',height:100}]);
 controller.begin('a'); controller.end({sid:'a',ready:true,prepend:true});
 assert.deepEqual(box.firstVisible(),{key:'m0',offset:0});
 // 前插后又来新消息：用户在阅读旧内容，不能被拽到底部
 box.append('n0',100);
 controller.begin('a'); controller.end({sid:'a',ready:true});
 assert.equal(box.firstVisible().key,'m0','阅读旧内容时新消息不能拽走');
 assert.equal(controller.state.follow,false);
});

test('DOM 替换导致内容暂时塌缩时，钳位到 0 的原生事件不清空锚点，内容恢复后还原阅读位置',()=>{
 const {box,controller}=setup('harness.js');
 enterLoaded(controller,box,'a',Array.from({length:30},(_,i)=>({key:'m'+i,height:100})));
 box.userScroll(1200);
 assert.equal(controller.state.follow,false);
 // DOM 被替换成临时短内容（如加载占位），浏览器布局时把 scrollTop 钳到 0 并异步派发事件
 box.setLayout([{key:'x',height:200}]);
 box.scrollTop=0; box.dispatch('scroll');
 assert.equal(controller.state.follow,false,'塌缩钳位事件不代表用户意图，不能打开跟随');
 assert.ok(controller.state.anchor,'塌缩期间锚点应保留');
 // 完整内容随下一轮渲染恢复：应按原锚点还原，而不是停在顶部或底部
 controller.begin('a');
 box.setLayout(Array.from({length:30},(_,i)=>({key:'m'+i,height:100})));
 controller.end({sid:'a',ready:true});
 assert.equal(box.scrollTop,1200,'内容恢复后应还原到原阅读位置');
 assert.equal(box.firstVisible().key,'m12');
 assert.equal(controller.state.follow,false);
});

test('内容收缩后仍可滚动时，布局钳位到底部不能冒充用户主动跟随', () => {
 const {box,controller}=setup('harness.js');
 const full=Array.from({length:30},(_,i)=>({key:'m'+i,height:100}));
 enterLoaded(controller,box,'a',full);
 box.userScroll(1200);
 box.setLayout(full.slice(0,10));
 box.scrollTop=box.maxTop;
 box.dispatch('scroll');
 controller.refresh();
 controller.begin('a');
 box.setLayout(full);
 controller.end({sid:'a',ready:true});
 assert.equal(controller.state.follow,false);
 assert.equal(box.scrollTop,1200);
 assert.equal(box.firstVisible().key,'m12');
});
