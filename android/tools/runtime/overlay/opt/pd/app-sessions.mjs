import {requestBotContext,ownsSession} from './bot-context.mjs';
// Agent-facing session management for this App's own local DSH sessions.
//
// The three tools only ever touch the App's private session storage: they
// list sessions, move selected sessions into the recoverable recycle bin and
// restore them again. A destructive step always asks the real user through
// the existing `userQuestions` service; the runtime's approval policy is
// `never` under full access, so a fabricated approval request would be
// rejected instead of answered.
import { defineTool } from './dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js';
import { requireAppWrite } from './app-tools.mjs';
import { SessionRecycle, optionalService } from './session-recycle.mjs';
import { PROMPT_SECTION_ORDER } from './bot-workspace.mjs';

const MAX_BATCH = 20;
const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;
const MAX_TITLE_LENGTH = 120;
const CONFIRM_LABEL = '确认删除';
const CANCEL_LABEL = '取消';

/** Credential-shaped fragments that must never leave the App in a title. */
const SECRET_PATTERNS = [
  [/sk-[A-Za-z0-9_-]{6,}/g, () => 'sk-***'],
  [/mm-[A-Za-z0-9_-]{6,}/g, () => 'mm-***'],
  [/Bearer\s+[A-Za-z0-9._~+/=-]{6,}/gi, () => 'Bearer ***'],
  [/gh[pousr]_[A-Za-z0-9]{10,}/g, (match) => `${match.slice(0, match.indexOf('_') + 1)}***`],
  [/xox[baprs]-[A-Za-z0-9-]{8,}/g, (match) => `${match.slice(0, match.indexOf('-') + 1)}***`],
  [/AKIA[0-9A-Z]{12,}/g, () => 'AKIA***'],
  [/(api[_-]?key|apikey|token|secret|password)\s*[:=]\s*["']?[A-Za-z0-9._~+/=-]{6,}/gi, (match) => `${match.slice(0, match.search(/[:=]/) + 1)}***`]
];

/**
 * Mask credential-shaped text and cap its length. Session titles are shown to
 * the model and to the user, so they never carry a raw key.
 */
export function maskSecrets(text) {
  if (typeof text !== 'string' || text.length === 0) return '';
  let masked = text;
  for (const [pattern, replace] of SECRET_PATTERNS) masked = masked.replace(pattern, replace);
  return masked.length > MAX_TITLE_LENGTH ? `${masked.slice(0, MAX_TITLE_LENGTH)}…` : masked;
}

/** The masked title of every session the controller knows about. */
async function collectTitles(ctx, signal, bot) {
  const titles = new Map();
  const controller = ctx.sessionController;
  if (controller === undefined || controller === null || typeof controller.list !== 'function') return titles;
  const listed = await controller.list({}, signal);
  const items = (Array.isArray(listed?.items) ? listed.items : []).filter(r=>ownsSession(bot,r.sessionId,r.cwd));
  for (const item of items) {
    const id = item?.sessionId;
    if (typeof id !== 'string') continue;
    const raw = item?.projections?.values?.title;
    titles.set(id, maskSecrets(typeof raw === 'string' ? raw : ''));
  }
  return titles;
}

/** The short project name of the workspace that owns one session. */
function projectNameOf(registry, id) {
  for (const entity of registry.list()) {
    if (entity.sessionIds.includes(id)) {
      const path = entity.path;
      const short = typeof path === 'string' ? path.split(/[\\/]/).filter(Boolean).pop() : '';
      return short ?? '';
    }
  }
  return '';
}

/** Validate one integer tool argument against its own range. */
function readInteger(value, fallback, min, max, label) {
  if (value === undefined || value === null) return fallback;
  if (!Number.isInteger(value)) throw new Error(`${label}必须是整数`);
  if (value < min || value > max) throw new Error(`${label}必须在 ${min} 到 ${max} 之间`);
  return value;
}

/** The output schema shared by the three session tools. */
const output = {
  schema: { type: 'object', additionalProperties: false, properties: {
    message: { type: 'string', required: true },
    mode: { type: 'string' },
    total: { type: 'integer' },
    offset: { type: 'integer' },
    limit: { type: 'integer' },
    hasMore: { type: 'boolean' },
    confirmed: { type: 'boolean' },
    items: { type: 'array', items: { type: 'object', additionalProperties: false, properties: {
      id: { type: 'string', required: true },
      title: { type: 'string', required: true },
      recoveryId: { type: 'string' },
      state: { type: 'string' },
      project: { type: 'string' },
      archived: { type: 'boolean' },
      pinned: { type: 'boolean' },
      current: { type: 'boolean' },
      loaded: { type: 'boolean' },
      canDelete: { type: 'boolean' },
      reason: { type: 'string' }
    } } },
    deleted: { type: 'array', items: { type: 'object', additionalProperties: false, properties: {
      id: { type: 'string', required: true },
      recoveryId: { type: 'string', required: true },
      title: { type: 'string' }
    } } },
    restored: { type: 'array', items: { type: 'object', additionalProperties: false, properties: {
      recoveryId: { type: 'string', required: true },
      id: { type: 'string', required: true }
    } } },
    skipped: { type: 'array', items: { type: 'object', additionalProperties: false, properties: {
      id: { type: 'string', required: true },
      code: { type: 'string', required: true },
      reason: { type: 'string', required: true }
    } } },
    failed: { type: 'array', items: { type: 'object', additionalProperties: false, properties: {
      id: { type: 'string' },
      recoveryId: { type: 'string' },
      code: { type: 'string', required: true },
      reason: { type: 'string', required: true }
    } } }
  } },
  render: (_args, value) => [{ type: 'text', text: JSON.stringify(value, null, 2) }]
};

export const name = 'app-sessions';
export const inject = ['tools', 'systemPrompt', 'sessionController', 'sessionPersistence', 'sessionQuery', 'sessions', 'workspaceRegistry'];

export function apply(ctx) {
  const recycle = new SessionRecycle(ctx);
  const registry = ctx.workspaceRegistry;

  ctx.systemPrompt.section({ name: 'tool:app-sessions', order: PROMPT_SECTION_ORDER.appSessions, text:
    'Manage this App\'s local DSH sessions only when the user asks: list first, delete only the exact named session ids, and restore with the reported recovery ids. Deletion is confirmation-gated and recoverable; live, running, background and lineage-bound sessions are refused—report that refusal instead of retrying. Never delete sessions by removing .dsh directories or using a shell, and never print session bodies or projections; these tools return only ids, masked titles, project short names and status flags.' });

  ctx.tools.register(defineTool({
    name: 'app_sessions_list',
    description: '列出本 App 的本地会话（仅 id、已遮盖标题、项目短名、归档/置顶/当前/已加载状态与可否删除）。recycled=true 时列出可恢复的回收站记录（recoveryId、id、标题）。不返回对话正文、投影或任何凭据。',
    parameters: {
      query: { type: 'string', description: '可选的子串过滤，按会话ID、标题或项目短名匹配。' },
      recycled: { type: 'boolean', description: '为 true 时列出回收站中的可恢复记录。' },
      offset: { type: 'integer', description: '分页起始位置，0 到 10000 之间的整数。' },
      limit: { type: 'integer', description: '每页条数，1 到 200 之间的整数，默认 50。' }
    }, output,
    execute: async (args, exec) => {
      const signal = exec?.signal;
      const bot = await requestBotContext(exec?.agent?.session?.id, exec?.agent?.session?.header?.cwd, signal);
      const offset = readInteger(args.offset, 0, 0, 10000, 'offset');
      const limit = readInteger(args.limit, DEFAULT_LIMIT, 1, MAX_LIMIT, 'limit');
      const query = typeof args.query === 'string' ? args.query.trim().toLowerCase() : '';
      if (args.recycled === true) {
        const records = (await recycle.listRecords({ signal })).filter(r=>ownsSession(bot,r.id,r.cwd));
        const restorable = records.filter((record) => record.state === 'recycled');
        const filtered = query === ''
          ? records
          : records.filter((record) => `${record.id} ${record.title}`.toLowerCase().includes(query));
        const page = filtered.slice(offset, offset + limit);
        return {
          message: `回收站里有 ${restorable.length} 条可恢复记录，另有 ${records.length - restorable.length} 条状态异常。`,
          mode: 'recycled',
          total: filtered.length,
          offset,
          limit,
          hasMore: offset + page.length < filtered.length,
          items: page.map((record) => ({
            id: record.id,
            title: maskSecrets(record.title),
            recoveryId: record.recoveryId,
            state: record.state
          }))
        };
      }
      const listed = await ctx.sessionController.list({}, signal);
      const items = (Array.isArray(listed?.items) ? listed.items : []).filter(r=>ownsSession(bot,r.sessionId,r.cwd));
      const titles = await collectTitles(ctx, signal, bot);
      const currentSessionId = exec?.agent?.session?.id;
      const ancestors = await recycle.ancestorsOf(currentSessionId);
      const children = new Set((await recycle.allHeaders(signal)).map(h => h.parentSession).filter(Boolean));
      const archived = registry.archivedSessionIds ?? [];
      const pinned = registry.pinnedSessionIds ?? [];
      const sessions = ctx.sessions;
      const entries = items.map((item) => {
        const id = item?.sessionId;
        return {
          id: typeof id === 'string' ? id : '',
          title: titles.get(id) ?? maskSecrets(typeof item?.projections?.values?.title === 'string' ? item.projections.values.title : ''),
          project: typeof id === 'string' ? projectNameOf(registry, id) : '',
          archived: typeof id === 'string' ? archived.includes(id) : false,
          pinned: typeof id === 'string' ? pinned.includes(id) : false,
          current: typeof id === 'string' && id === currentSessionId,
          loaded: item?.agentAvailable === true || (sessions?.get?.(id) !== undefined && sessions?.get?.(id) !== null),
          canDelete: false,
          reason: ''
        };
      }).filter((entry) => entry.id !== '');
      const filtered = query === ''
        ? entries
        : entries.filter((entry) => `${entry.id} ${entry.title} ${entry.project}`.toLowerCase().includes(query));
      const page = filtered.slice(offset, offset + limit);
      for (const entry of page) {
        const checked = await recycle.inspect(entry.id, { currentSessionId, ancestors, children, signal });
        entry.canDelete = checked.ok;
        entry.reason = checked.ok ? '' : checked.reason;
      }
      const deletable = page.filter((entry) => entry.canDelete).length;
      return {
        message: `共 ${filtered.length} 个会话，当前页 ${deletable} 个可以移入回收站。`,
        mode: 'sessions',
        total: filtered.length,
        offset,
        limit,
        hasMore: offset + page.length < filtered.length,
        items: page
      };
    }
  }));

  ctx.tools.register(defineTool({
    name: 'app_sessions_delete',
    description: '把用户明确指定的会话移入本 App 的可恢复回收站（会话从活动/归档/历史列表中移除，可再用 app_sessions_restore 恢复）。先做预检，再用内置问答向用户确认；没有收到“确认删除”就不改动任何文件。当前会话、活动/运行中、有后台活动或属于父子血缘的会话会被拒绝。',
    parameters: {
      ids: { type: 'array', required: true, description: '1 到 20 个明确的会话ID，不接受路径或通配符。', items: { type: 'string' } }
    }, output,
    execute: async (args, exec) => {
      requireAppWrite(ctx, exec);
      const ids = args.ids;
      if (!Array.isArray(ids) || ids.length < 1 || ids.length > MAX_BATCH) {
        throw new Error(`ids 必须是 1 到 ${MAX_BATCH} 个明确的会话ID`);
      }
      const signal = exec.signal;
      const currentSessionId = exec.agent?.session?.id;
      const bot = await requestBotContext(exec.agent.session.id,exec.agent.session.header.cwd,signal);
      const titles = await collectTitles(ctx, signal, bot);
      const ownHeaders = (await recycle.allHeaders(signal)).filter(h=>ownsSession(bot,h.id,h.cwd));
      if(ids.some(id=>!ownHeaders.some(h=>h.id===id)))throw Error('只能管理当前 Bot 的会话');
      const plan = await recycle.plan(ids, { currentSessionId, signal });
      const skipped = plan.rejected.map((entry) => ({ id: entry.id, code: entry.code, reason: entry.reason }));
      if (plan.accepted.length === 0) {
        return {
          message: '没有可以移入回收站的会话，未修改任何文件。',
          confirmed: false,
          deleted: [],
          skipped,
          failed: []
        };
      }
      const questions = optionalService(ctx, 'userQuestions');
      if (questions === null || typeof questions.askTimed !== 'function') {
        return {
          message: '缺少用户问答服务，无法获得删除确认，未修改任何文件。',
          confirmed: false,
          deleted: [],
          skipped,
          failed: []
        };
      }
      const lines = plan.accepted.map((item) => `- ${item.id}  ${titles.get(item.id) || '(无标题)'}`).join('\n');
      let answer;
      try {
        answer = await questions.askTimed({
          questions: [{
            id: 'confirm',
            question: `确认把下面 ${plan.accepted.length} 个会话移入可恢复的回收站吗？\n${lines}\n移入后它们会从活动、归档和历史列表中消失，但内容仍在回收站里，可以用 app_sessions_restore 按恢复ID恢复。`,
            options: [{ label: CONFIRM_LABEL }, { label: CANCEL_LABEL }]
          }],
          agent: exec.agent,
          signal: exec.signal
        }, exec.callId, 120000);
      } catch (error) {
        return {
          message: `没有拿到用户确认（${error?.message ?? '未知原因'}），未修改任何文件。`,
          confirmed: false,
          deleted: [],
          skipped,
          failed: []
        };
      }
      if (answer === undefined || answer === null || answer.pending === true) {
        return {
          message: '用户没有在期限内确认，未修改任何文件。',
          confirmed: false,
          deleted: [],
          skipped,
          failed: []
        };
      }
      const answers = Array.isArray(answer.answers) ? answer.answers : [];
      const confirmation = answers.find((entry) => entry?.id === 'confirm');
      const selected = Array.isArray(confirmation?.selected) ? confirmation.selected : [];
      const approved = confirmation !== undefined
        && selected.length === 1
        && selected[0] === CONFIRM_LABEL
        && (confirmation.custom === undefined || confirmation.custom === null || confirmation.custom === '');
      if (!approved) {
        return {
          message: '用户没有选择“确认删除”，未修改任何文件。',
          confirmed: false,
          deleted: [],
          skipped,
          failed: []
        };
      }
      const deleted = [];
      const failed = [];
      for (const item of plan.accepted) {
        if (signal?.aborted === true) {
          failed.push({ id: item.id, code: 'cancelled', reason: '操作已取消，后续会话未处理' });
          break;
        }
        const result = await recycle.remove(item.id, {
          signal, currentSessionId,
          revision: item.revision,
          title: titles.get(item.id) ?? ''
        });
        if (result.ok) {
          deleted.push({ id: item.id, recoveryId: result.recoveryId, title: titles.get(item.id) ?? '' });
        } else {
          failed.push({ id: item.id, code: result.code, reason: result.reason });
        }
      }
      const summary = deleted.length === 0
        ? '没有会话被移入回收站。'
        : `已把 ${deleted.length} 个会话移入回收站，可用 app_sessions_restore 按恢复ID恢复。`;
      return {
        message: failed.length === 0 ? summary : `${summary}另有 ${failed.length} 个会话未处理。`,
        confirmed: true,
        deleted,
        skipped,
        failed
      };
    }
  }));

  ctx.tools.register(defineTool({
    name: 'app_sessions_restore',
    description: '按恢复ID把回收站中的会话恢复回原位置。恢复ID来自 app_sessions_list(recycled=true)。原路径已被占用、会话ID当前处于活动状态或原工作区已不存在时会被拒绝，不会覆盖任何文件。',
    parameters: {
      recovery_ids: { type: 'array', required: true, description: '1 到 20 个恢复记录ID。', items: { type: 'string' } }
    }, output,
    execute: async (args, exec) => {
      requireAppWrite(ctx, exec);
      const ids = args.recovery_ids;
      if (!Array.isArray(ids) || ids.length < 1 || ids.length > MAX_BATCH) {
        throw new Error(`recovery_ids 必须是 1 到 ${MAX_BATCH} 个恢复记录ID`);
      }
      const signal = exec.signal;
      const bot = await requestBotContext(exec.agent.session.id,exec.agent.session.header.cwd,signal);
      const ownRecords = (await recycle.listRecords({signal})).filter(r=>ownsSession(bot,r.id,r.cwd));
      if(ids.some(id=>!ownRecords.some(r=>r.recoveryId===id)))throw Error('只能恢复当前 Bot 的会话');
      const restored = [];
      const failed = [];
      for (const recoveryId of ids) {
        if (signal?.aborted === true) {
          failed.push({ recoveryId: String(recoveryId), code: 'cancelled', reason: '操作已取消，后续记录未处理' });
          break;
        }
        const result = await recycle.restore(String(recoveryId), { signal });
        if (result.ok) restored.push({ recoveryId: result.recoveryId, id: result.id });
        else failed.push({ recoveryId: String(recoveryId), code: result.code, reason: result.reason });
      }
      const summary = restored.length === 0
        ? '没有会话被恢复。'
        : `已恢复 ${restored.length} 个会话。`;
      return {
        message: failed.length === 0 ? summary : `${summary}另有 ${failed.length} 条记录未恢复。`,
        restored,
        skipped: [],
        failed
      };
    }
  }));
}

export default { name, inject, apply };
