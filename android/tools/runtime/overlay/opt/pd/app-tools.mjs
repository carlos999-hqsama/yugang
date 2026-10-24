import { applySourceFidelity } from './source-fidelity.mjs';
import { applySkillDisclosure } from './skill-disclosure.mjs';
import { applyBotWorkspace, PROMPT_SECTION_ORDER } from './bot-workspace.mjs';
import { applyBotMessages } from './bot-messages.mjs';
import { defineTool } from './dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js';
import { requestApp } from './app-bridge.mjs';
import { applyPluginTools } from './plugin-tools.mjs';

const output = {
  schema: { type: 'object', additionalProperties: false, properties: {
    keepAwakeWhileRunning: { type: 'boolean', required: true },
  } },
  render: (_args, value) => [{ type: 'text', text: JSON.stringify(value, null, 2) }],
};

export function requireAppWrite(ctx, exec) {
  if (!exec.agent?.session) throw new Error('需要由当前 App 对话调用');
}

export const name = 'app-tools';
export const inject = ['tools', 'systemPrompt', 'skills', 'sessionController'];
export function apply(ctx) {
  applyBotWorkspace(ctx);
  applyBotMessages(ctx);
  applySkillDisclosure(ctx);
  applySourceFidelity(ctx);
  ctx.systemPrompt.section({ name: 'host:android-boundary', order: PROMPT_SECTION_ORDER.androidBoundary, text:
    'You are running inside the Yugang Android app. This deployment is full-access without operation approvals; do not change that fixed policy or add a permission gate. Android app-level isolation is the runtime boundary; a Bot workspace organizes work and ownership, not a jail. Use only tools present in the current tool list and never claim an unavailable capability. Never read or edit Android preferences, keys or credential files, or inspect/modify private DSH metadata and live registries. Do not create work files under /root or runtime directories. Host services and their management tools are the supported interfaces.' });
  ctx.systemPrompt.section({ name: 'agent:capabilities', order: PROMPT_SECTION_ORDER.capabilities, text:
    '业务技能按当前 Bot 的启用配置按需披露。' });
  // Plugin management and verification preview are host capabilities, not business plugins.
  applyPluginTools(ctx);
  ctx.systemPrompt.section({ name: 'tool:app-settings', order: PROMPT_SECTION_ORDER.appSettings, text:
    'Use app_settings_get/update only for this App\'s public settings, and only when the user requests a change. They cover keeping the device awake during tasks—not model keys, endpoints, credentials or permissions.' });
  ctx.tools.register(defineTool({
    name: 'app_settings_get',
    description: '查看本 App 的非秘密设置：任务期间保持唤醒。不返回 Key、令牌、地址或会话正文。',
    parameters: {}, output,
    execute: (_args, exec) => requestApp('get', undefined, exec.signal),
  }));
  ctx.tools.register(defineTool({
    name: 'app_settings_update',
    description: '按用户要求修改本 App 的非秘密设置。仅支持 keepAwakeWhileRunning；不修改模型Key、连接地址或权限。由当前手机会话直接执行。返回实际保存结果。',
    parameters: { patch: { type: 'object', required: true, additionalProperties: false, properties: {
      keepAwakeWhileRunning: { type: 'boolean' },
    } } }, output,
    execute: (args, exec) => { requireAppWrite(ctx, exec); return requestApp('update', args.patch, exec.signal); },
  }));
}
export default { name, inject, apply };
