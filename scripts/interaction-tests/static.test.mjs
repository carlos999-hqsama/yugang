/**
 * 静态契约：允许缩放、正文可选字、短屏导航可达。
 * 这些是源码级硬约束，用静态检查锁住，避免以后被改回去。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const WWW = new URL('../../android/app/src/main/assets/www/', import.meta.url);
const read = (p) => fs.readFileSync(new URL(p, WWW), 'utf8');

test('页面允许用户缩放（不锁死视口）', () => {
  const html = read('index.html');
  assert.ok(!/user-scalable\s*=\s*no/i.test(html), '不能禁止用户缩放');
  assert.ok(!/maximum-scale\s*=\s*1\b/i.test(html), '不能锁死最大缩放比例');
  assert.match(html, /name="viewport"/);
});

test('对话正文可以选择文字', () => {
  const css = read('css/app.css');
  const rule = css.match(/[^\n{}]*\.sel-text[^{]*\{[^}]*\}/);
  assert.ok(rule, '应当有可选字规则');
  assert.match(rule[0], /\.msgs/, '对话正文必须可选字');
});

test('侧边导航在短屏可以滚动，设置入口不会被裁掉', () => {
  const design = read('css/design.css');
  const app = read('css/app.css');
  const rail = design.match(/\.rail\{([^}]*)\}/);
  assert.ok(rail, '应当有 .rail 规则');
  assert.match(rail[1], /overflow-y\s*:\s*auto/, '导航必须可滚动');
  assert.match(app, /@media\s*\(max-height:\s*560px\)/, '需要短屏适配');
});
