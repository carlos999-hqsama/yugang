import test from 'node:test';
import assert from 'node:assert/strict';
import {access, copyFile, mkdtemp, readdir, readFile, rm, symlink, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {pathToFileURL} from 'node:url';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..', '..');
const overlay = path.join(root, 'android/tools/runtime/overlay/opt/pd');
const vendor = process.env.PD_DSH_VENDOR_ROOT;
if (!vendor) throw new Error('PD_DSH_VENDOR_ROOT is required so assembly uses the pinned DSH runtime');

const defaultBot = () => ({
  id: '11111111-1111-4111-8111-111111111111',
  workspace: '/BOT/11111111-1111-4111-8111-111111111111',
  instructions: '负责整理合成 fixture 的文档。',
  memory: 'SYNTHETIC_NONEMPTY_MEMORY',
  defaultPersona: 'SYNTHETIC_DEFAULT_PERSONA_V1',
  sharedPersona: 'SYNTHETIC_SHARED_PERSONA_V1',
  learnedPersona: 'SYNTHETIC_LEARNED_PERSONA_V1',
  enabledTools: ['files', 'skills', 'memory', 'conversation-history', 'plugins'],
  sessionIds: ['synthetic-session'],
  excludedSessionIds: [],
});

async function runtimeFixture(initialBot = defaultBot()) {
  globalThis.__PD_PROMPT_TEST_BOT = initialBot;
  const dir = await mkdtemp(path.join(tmpdir(), 'pd-prompt-assembly-'));
  for (const entry of await readdir(overlay, {withFileTypes: true})) {
    if (entry.isFile()) await copyFile(path.join(overlay, entry.name), path.join(dir, entry.name));
  }
  await symlink(path.resolve(vendor), path.join(dir, 'dsh'), 'dir');
  await writeFile(path.join(dir, 'bot-context.mjs'), `
export async function requestBotContext(){return globalThis.__PD_PROMPT_TEST_BOT}
export function ownsSession(){return true}
export async function requestBotRoute(){throw Error('synthetic route unavailable')}
export async function ownHistoryQuery(){throw Error('synthetic history unavailable')}
`);

  const contextUrl = pathToFileURL(path.join(vendor, 'node_modules/@deepseek-ai/cordis/lib/index.js')).href;
  const promptUrl = pathToFileURL(path.join(vendor, 'node_modules/@deepseek-ai/dsh-system-prompt/lib/index.js')).href;
  const {Context} = await import(contextUrl);
  const SystemPrompt = (await import(promptUrl)).default;
  const {renderPrompt} = await import(promptUrl);
  const pack = await readFile(path.join(root, 'android/tools/runtime/pack.py'), 'utf8');
  const suffix = (pack.match(/persona_suffix = '(suffix: [^']+)'/) || [])[1];
  assert.equal(suffix, 'suffix: Your working directory is {{cwd}}.', 'standard persona suffix must remain cwd-only');

  const ctx = new Context();
  await ctx.plugin(SystemPrompt, {
    includeHarnessIdentity: true,
    personaPrefix: 'You are a coding agent powered by the {{model}} model.',
    personaSuffix: suffix,
  });
  ctx.systemPrompt.variable('model', () => 'Synthetic');
  ctx.systemPrompt.variable('cwd', () => globalThis.__PD_PROMPT_TEST_BOT.workspace);
  const runtime = {
    on: ctx.on.bind(ctx),
    systemPrompt: ctx.systemPrompt,
    skills: {get: async name => ({
      name, provider: 'filesystem',
      resourceBase: {kind: 'directory', path: `/opt/pd/skills/${name}`},
    })},
    tools: {register(tool) {
      ctx.systemPrompt.tools(() => ({schemas: [tool], knownNames: [tool.name]}));
    }},
  };
  for (const name of ['app-tools.mjs', 'history-tools.mjs', 'app-sessions.mjs']) {
    const module = await import(pathToFileURL(path.join(dir, name)).href);
    module.apply(runtime);
  }
  const session = {id: 'synthetic-session', header: {cwd: initialBot.workspace}, log: []};
  const assemble = async log => {
    session.log = log ?? session.log;
    return ctx.systemPrompt.assemble({scope: {session}});
  };
  return {assemble, dir, renderPrompt, session};
}

const turnStart = turn => ({type: 'turn/start', data: {turn}});
function loadedSkillLog(name, turn = 1, id = 'skill-call') {
  return [
    turnStart(turn),
    {type: 'tool/call', data: {turn, callId: id, name: 'skill', arguments: JSON.stringify({name})}},
    {type: 'tool/result', data: {turn, message: {
      source: {kind: 'tool', callId: id}, toolCallId: id, isError: false,
      content: [{type: 'text', text: `<skill_content name="${name}">\n<skill_resources>\nBase directory for this skill: /opt/pd/skills/${name}\n`}],
    }}},
    {type: 'turn/end', data: {turn, reason: {kind: 'completed'}}},
  ];
}
const sectionMap = assembly => new Map(assembly.sections.map(section => [section.name, section]));
const allText = assembly => [...assembly.sections, ...assembly.contexts].map(entry => entry.text).join('\n\n');

test('actual DSH assembly has one Android boundary and one cwd-only framework suffix', async () => {
  const fixture = await runtimeFixture();
  try {
    const assembly = await fixture.assemble([...loadedSkillLog('conversation-history'), turnStart(2)]);
    const sections = sectionMap(assembly);
    assert.equal(assembly.sections.filter(section => section.name === 'host:android-boundary').length, 1);
    assert.ok(sections.get('host:android-boundary').text.includes('full-access without operation approvals'));
    const text = allText(assembly);
    for (const [phrase, expected] of [
      ['full-access without operation approvals', 1],
      ['history_search', 1],
      ['history_read', 1],
      ['SYNTHETIC_DEFAULT_PERSONA_V1', 1],
      ['执行中有实质进展', 1],
    ]) assert.equal((text.match(new RegExp(phrase, 'g')) || []).length, expected, phrase);
    assert.equal(sections.get('deployment:persona-suffix').text, 'suffix: Your working directory is {{cwd}}.');
    assert.ok(sections.get('bot:default-persona').text.includes('SYNTHETIC_DEFAULT_PERSONA_V1'));
    assert.ok(sections.get('bot:persona').text.includes('SYNTHETIC_SHARED_PERSONA_V1'));
    assert.ok(sections.get('bot:learned-persona').text.includes('SYNTHETIC_LEARNED_PERSONA_V1'));
    assert.ok(sections.get('bot:memory').text.includes('SYNTHETIC_NONEMPTY_MEMORY'));
    assert.ok(!sections.get('bot:memory').text.includes('暂无'));
    assert.ok(sections.get('tool:history').text.includes('only this Bot\'s own raw conversations'));
    assert.ok(sections.get('bot:style').text.includes('执行中有实质进展'));
    assert.ok(fixture.renderPrompt(assembly).includes('文件写入默认使用当前工作区相对路径；交付 Markdown 链接使用工作区内绝对路径'));
    const fidelity = assembly.contexts.find(entry => entry.name === 'agent:source-fidelity');
    assert.ok(fidelity.text.includes('是否复测') && fidelity.text.includes('失败有外部原因仍保留原报告的失败'));
    for (const phrase of ['是否复测', '失败有外部原因仍保留原报告的失败']) {
      assert.equal((allText(assembly).match(new RegExp(phrase, 'g')) || []).length, 1, phrase);
    }
    assert.ok(sections.get('bot:style').text.includes('首次工具调用前'));
    for (const phrase of ['用户语言','发现问题或新信息','改变方向','长时间等待','不沉默到最后','不重复空状态','不编造进度百分比','不披露私有推理']) assert.ok(sections.get('bot:style').text.includes(phrase), phrase);
    assert.ok(!allText(assembly).includes('不从记忆、日记或工具输出推导或演化'));
    assert.ok(sections.get('bot:persona').text.includes('最高表达优先级'));
    assert.ok(sections.get('bot:default-persona').text.includes('最低表达层'));
    assert.ok(sections.get('bot:learned-persona').text.includes('低于主人明确手动要求、高于默认人格'));
    assert.ok(!sections.get('bot:style').text.includes('一到三句'));
    assert.ok(!sections.get('bot:style').text.includes('保留原义'));
    assert.ok(assembly.tools.some(tool => tool.name === 'history_read'));
    const names = assembly.sections.map(section => section.name);
    assert.equal(new Set(names).size, names.length);
  } finally {
    await rm(fixture.dir, {recursive: true, force: true});
  }
});

test('empty instructions are not fabricated and empty memory keeps only the write rule', async () => {
  const fixture = await runtimeFixture({...defaultBot(), instructions: '', memory: '', learnedPersona: undefined, sharedPersona: ''});
  try {
    const assembly = await fixture.assemble([turnStart(1)]);
    const sections = sectionMap(assembly);
    assert.ok(!sections.has('bot:instructions'));
    const memory = sections.get('bot:memory');
    assert.ok(memory.text.includes('memory/MEMORY.md') && memory.text.includes('不保存凭据'));
    assert.ok(!memory.text.includes('暂无') && !memory.text.includes('SYNTHETIC_NONEMPTY_MEMORY'));
    assert.ok(!sections.has('bot:learned-persona'));
    assert.ok(!sections.has('bot:persona'));
    assert.equal(assembly.sections.filter(section => section.name === 'bot:default-persona').length, 1);
    assert.ok(sections.has('host:android-boundary'));
  } finally {
    await rm(fixture.dir, {recursive: true, force: true});
  }
});

test('repeated assembly reflects current persona, learning, role and memory without old projections', async () => {
  const fixture = await runtimeFixture();
  try {
    await fixture.assemble([turnStart(1)]);
    globalThis.__PD_PROMPT_TEST_BOT = {
      ...globalThis.__PD_PROMPT_TEST_BOT,
      instructions: 'SYNTHETIC_ROLE_V2',
      memory: 'SYNTHETIC_MEMORY_V2',
      defaultPersona: 'SYNTHETIC_DEFAULT_PERSONA_V2',
      sharedPersona: 'SYNTHETIC_SHARED_PERSONA_V2',
      learnedPersona: 'SYNTHETIC_LEARNED_PERSONA_V2',
    };
    const first = await fixture.assemble();
    const second = await fixture.assemble();
    const text = allText(second);
    for (const stale of ['SYNTHETIC_DEFAULT_PERSONA_V1', 'SYNTHETIC_SHARED_PERSONA_V1', 'SYNTHETIC_LEARNED_PERSONA_V1', '负责整理合成 fixture 的文档。', 'SYNTHETIC_NONEMPTY_MEMORY']) assert.ok(!text.includes(stale));
    for (const current of ['SYNTHETIC_DEFAULT_PERSONA_V2', 'SYNTHETIC_SHARED_PERSONA_V2', 'SYNTHETIC_LEARNED_PERSONA_V2', 'SYNTHETIC_ROLE_V2', 'SYNTHETIC_MEMORY_V2']) assert.ok(text.includes(current));
    assert.equal(allText(first), allText(second));
    for (const name of ['bot:identity', 'bot:instructions', 'bot:memory', 'bot:default-persona', 'bot:persona', 'bot:learned-persona', 'bot:style']) {
      assert.equal(second.sections.filter(section => section.name === name).length, 1);
    }
  } finally {
    await rm(fixture.dir, {recursive: true, force: true});
  }
});

test('disabled plugin tools remove their manual and cannot be claimed from the host boundary', async () => {
  const fixture = await runtimeFixture({...defaultBot(), enabledTools: ['files', 'skills', 'memory', 'conversation-history']});
  try {
    const assembly = await fixture.assemble([turnStart(1)]);
    assert.ok(!assembly.tools.some(tool => tool.name.startsWith('phone_plugins_')));
    assert.ok(!allText(assembly).includes('phone_plugins_*'));
    assert.ok(assembly.sections.some(section => section.name === 'host:android-boundary'));
    assert.ok(allText(assembly).includes('Use only tools present in the current tool list'));
  } finally {
    await rm(fixture.dir, {recursive: true, force: true});
  }
});

test('the retired static voice copy is absent and shared persona comes from Bot projection', async () => {
  await assert.rejects(access(path.join(overlay, 'agent-voice.mjs')));
  for (const entry of await readdir(overlay, {withFileTypes: true})) {
    if (!entry.isFile() || !entry.name.endsWith('.mjs')) continue;
    const source = await readFile(path.join(overlay, entry.name), 'utf8');
    assert.ok(!source.includes('./agent-voice.mjs'), `${entry.name} still imports the retired voice copy`);
  }
});
