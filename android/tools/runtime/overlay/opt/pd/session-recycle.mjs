// Recoverable recycle bin for this App's own local DSH sessions.
//
// Node builtins only: this module never imports the DSH runtime, it reaches
// the fixed session/workspace services through the injected context.
//
// The bin lives beside the session storage root (dirname(persistence.root) /
// app-recycle) inside the App's private persistent home, so a recycled session
// leaves every active/archived/history enumeration without the sessions root
// itself being touched. Nothing here erases a session permanently: a deleted
// session directory is renamed into a per-session recovery directory that
// keeps a restore manifest, and `restore` puts it back.
import { randomUUID } from 'node:crypto';
import { chmod, lstat, mkdir, readdir, readFile, realpath, rename, rmdir, unlink, writeFile } from 'node:fs/promises';
import { dirname, join, resolve, sep } from 'node:path';

const RECYCLE_DIR_NAME = 'app-recycle';
const RECORD_FILE_NAME = 'manifest.json';
const SESSION_DIR_NAME = 'session';
const TEMP_FILE_NAME = 'manifest.json.tmp';
const MAX_DELETE_IDS = 20;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/** Whether a value is a plain JSON record. */
function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Whether `candidate` is `root` itself or lives under it. */
function isContained(root, candidate) {
  const base = resolve(root);
  const target = resolve(candidate);
  return target === base || target.startsWith(base + sep);
}

/**
 * Whether one string is an explicit session id: no path, no wildcard, no
 * traversal. Session ids are opaque tokens; anything that could name a file
 * or a set of files is refused before it reaches the filesystem.
 */
export function isExplicitSessionId(id) {
  if (typeof id !== 'string') return false;
  if (id.length === 0 || id.length > 200) return false;
  if (id.trim() !== id) return false;
  if (id.includes('\0')) return false;
  if (id.includes('/') || id.includes('\\')) return false;
  if (id === '.' || id === '..') return false;
  if (/[*?[\]{}!]/.test(id)) return false;
  return true;
}

/** Read an optional service without making its absence fatal. */
export function optionalService(ctx, name) {
  try {
    if (typeof ctx.get === 'function') {
      const found = ctx.get(name);
      if (found !== undefined && found !== null) return found;
    }
  } catch {
    /* an optional service that cannot be read is simply absent */
  }
  const direct = ctx?.[name];
  return direct === undefined || direct === null ? null : direct;
}

/** The closest existing ancestor of a path, used for symlink checks. */
async function nearestExistingAncestor(target) {
  let current = resolve(target);
  for (let guard = 0; guard < 64; guard += 1) {
    try {
      await lstat(current);
      return current;
    } catch (error) {
      if (error?.code !== 'ENOENT') throw error;
    }
    const parent = dirname(current);
    if (parent === current) return current;
    current = parent;
  }
  return resolve(target);
}

/** Whether a path exists (any kind). */
async function pathExists(target) {
  try {
    await lstat(target);
    return true;
  } catch (error) {
    if (error?.code === 'ENOENT') return false;
    throw error;
  }
}

/** Whether a path is a real directory (not a symlink). */
async function isRealDirectory(target) {
  try {
    const info = await lstat(target);
    return info.isDirectory() && !info.isSymbolicLink();
  } catch (error) {
    if (error?.code === 'ENOENT') return false;
    throw error;
  }
}

/** Put one id back at its recorded position, or keep it out of the list. */
function restoreIndex(list, id, index) {
  if (index === null || index === undefined) return list.filter((entry) => entry !== id);
  if (list.includes(id)) return list;
  const at = Math.max(0, Math.min(index, list.length));
  return [...list.slice(0, at), id, ...list.slice(at)];
}

/**
 * The App's recoverable session recycle bin.
 *
 * The class owns the on-disk layout, the restore manifest and every registry
 * mutation. It never edits `workspace.json` or any other internal registry
 * file directly: workspace accounting and the archived/pinned sets are changed
 * through the registry service object and its entities only.
 */
export class SessionRecycle {
  constructor(ctx) {
    this.ctx = ctx;
    this.persistence = ctx.sessionPersistence;
    this.registry = ctx.workspaceRegistry;
    this.sessions = optionalService(ctx, 'sessions');
    this.agents = optionalService(ctx, 'agents');
  }

  /** The fixed recycle-bin directory, a sibling of the session storage root. */
  recycleRoot() {
    return join(dirname(resolve(this.persistence.root)), RECYCLE_DIR_NAME);
  }

  /**
   * Create (if needed) and verify the recycle-bin directory. The bin is
   * private (0700), is never a symlink, and never lives inside the session
   * storage root it is meant to sit beside.
   */
  async ensureRoot() {
    const root = this.recycleRoot();
    const parent = dirname(root);
    const parentInfo = await lstat(parent).catch((error) => {
      if (error?.code === 'ENOENT') throw new Error('会话存储根目录的上级目录不存在');
      throw error;
    });
    if (!parentInfo.isDirectory()) throw new Error('会话存储根目录的上级路径不是目录');
    await mkdir(root, { recursive: true, mode: 0o700 });
    const info = await lstat(root);
    if (info.isSymbolicLink() || !info.isDirectory()) throw new Error('回收站路径不是普通目录');
    await chmod(root, 0o700);
    const realRoot = await realpath(root);
    const realParent = await realpath(parent);
    if (dirname(realRoot) !== realParent) throw new Error('回收站路径经符号链接解析后越界');
    const sessionsRoot = resolve(this.persistence.root);
    if (isContained(sessionsRoot, realRoot)) throw new Error('回收站不能位于会话枚举范围内');
    return root;
  }

  /**
   * Resolve the directory that holds one session's artifacts. The path comes
   * from the persistence backend's own locator for the stored header; it is
   * then required to stay inside the session storage root, both textually and
   * after symlink resolution.
   */
  async resolveSessionDir(header) {
    const located = this.persistence.locate(header);
    if (!located || typeof located.path !== 'string') throw new Error('会话持久化后端无法定位会话文件');
    const dir = dirname(resolve(located.path));
    const root = resolve(this.persistence.root);
    if (!isContained(root, dir)) throw new Error('会话目录不在会话存储根目录内');
    const realRoot = await realpath(root).catch(() => root);
    const probe = await nearestExistingAncestor(dir);
    const realProbe = await realpath(probe);
    if (!isContained(realRoot, realProbe)) throw new Error('会话目录经符号链接解析后越界');
    return dir;
  }

  /**
   * The directory that holds one session's artifacts, required to exist.
   */
  async locateSessionDir(header) {
    const dir = await this.resolveSessionDir(header);
    let info;
    try {
      info = await lstat(dir);
    } catch (error) {
      if (error?.code === 'ENOENT') throw new Error('会话目录不存在');
      throw error;
    }
    if (info.isSymbolicLink()) throw new Error('会话目录是符号链接，拒绝处理');
    if (!info.isDirectory()) throw new Error('会话目录不是目录');
    return dir;
  }

  /** The stored header of one session, or `undefined` when it is not persisted. */
  async statSession(id, signal) {
    return await this.persistence.stat(id, { signal });
  }

  /** Every persisted header, plus the headers of live sessions. */
  async allHeaders(signal) {
    const headers = [];
    const listed = await this.persistence.list({ signal });
    for (const snapshot of listed ?? []) if (snapshot?.header) headers.push(snapshot.header);
    const live = this.sessions?.list?.();
    for (const session of live ?? []) if (session?.header) headers.push(session.header);
    const seen = new Set();
    return headers.filter((header) => {
      if (seen.has(header.id)) return false;
      seen.add(header.id);
      return true;
    });
  }

  /** The ancestor chain of one session (its parents, transitively). */
  async ancestorsOf(sessionId) {
    const ancestors = new Set();
    if (typeof sessionId !== 'string' || sessionId.length === 0) return ancestors;
    let header = this.sessions?.get?.(sessionId)?.header;
    if (header === undefined) header = (await this.statSession(sessionId))?.header;
    let guard = 0;
    while (header?.parentSession !== undefined && guard < 64) {
      guard += 1;
      const parentId = header.parentSession;
      if (ancestors.has(parentId)) break;
      ancestors.add(parentId);
      const live = this.sessions?.get?.(parentId)?.header;
      if (live !== undefined) {
        header = live;
        continue;
      }
      const snapshot = await this.statSession(parentId);
      if (snapshot === undefined) break;
      header = snapshot.header;
    }
    return ancestors;
  }

  /** Whether one session is live (loaded in this process or owned by an agent). */
  isLive(id) {
    if (this.sessions?.get?.(id) !== undefined && this.sessions?.get?.(id) !== null) return true;
    const agent = this.agents?.get?.(id);
    return agent !== undefined && agent !== null;
  }

  /** The background activity the runtime reports for one session. */
  async activityOf(id) {
    const waterfall = this.ctx?.waterfall;
    if (typeof waterfall !== 'function') return [];
    const reported = await waterfall.call(this.ctx, 'workspace/session-activity', { sessionId: id }, () => Promise.resolve([]));
    return Array.isArray(reported) ? reported : [];
  }

  /**
   * Check one session id against every deletion precondition. The result is
   * either `{ ok: true, item }` or a refusal with a stable code and a Chinese
   * reason; refusals are per item so the caller can process the rest.
   */
  async inspect(id, options = {}) {
    const { currentSessionId, ancestors, children, signal } = options;
    if (!isExplicitSessionId(id)) {
      return { ok: false, code: 'invalid-id', reason: '只接受明确的会话ID，不接受路径或通配符' };
    }
    if (typeof currentSessionId === 'string' && id === currentSessionId) {
      return { ok: false, code: 'current', reason: '这是当前正在使用的会话，不能移入回收站' };
    }
    if (ancestors?.has(id)) {
      return { ok: false, code: 'ancestor', reason: '这是当前会话的祖先会话，不能移入回收站' };
    }
    if (this.isLive(id)) {
      return { ok: false, code: 'live', reason: '会话处于活动状态（已加载或运行中），不能移入回收站' };
    }
    const snapshot = await this.statSession(id, signal);
    if (snapshot === undefined) {
      return { ok: false, code: 'unknown', reason: '会话不存在或尚未持久化' };
    }
    const header = snapshot.header;
    if (header?.cwd === undefined) {
      return { ok: false, code: 'no-cwd', reason: '会话没有工作目录，无法安全移入回收站' };
    }
    if (header?.parentSession !== undefined || header?.origin === 'subagent') {
      return { ok: false, code: 'lineage', reason: '会话属于父子或子代理血缘，不能单独移入回收站' };
    }
    if (children?.has(id)) {
      return { ok: false, code: 'lineage', reason: '会话存在关联的子会话，不能单独移入回收站' };
    }
    if (!this.describeMembership(id).workspace) return { ok: false, code: 'workspace-missing', reason: '会话没有可恢复的原工作区，未处理' };
    const activity = await this.activityOf(id);
    if (activity.length > 0) {
      return { ok: false, code: 'active', reason: '会话仍有后台活动，不能移入回收站' };
    }
    try {
      await this.locateSessionDir(header);
    } catch (error) {
      return { ok: false, code: 'locate', reason: `无法定位会话目录：${error.message}` };
    }
    return {
      ok: true,
      item: { id, header, revision: snapshot.revision, cwd: header.cwd }
    };
  }

  /**
   * Split a batch of requested ids into the ones that may be processed and
   * the ones that are refused, each refusal carrying its own reason.
   */
  async plan(ids, options = {}) {
    const { signal, currentSessionId } = options;
    const ancestors = options.ancestors ?? await this.ancestorsOf(currentSessionId);
    const children = new Map();
    for (const header of await this.allHeaders(signal)) {
      if (typeof header.parentSession !== 'string') continue;
      const bucket = children.get(header.parentSession) ?? [];
      bucket.push(header.id);
      children.set(header.parentSession, bucket);
    }
    const accepted = [];
    const rejected = [];
    const seen = new Set();
    for (const raw of Array.isArray(ids) ? ids : []) {
      const id = typeof raw === 'string' ? raw : String(raw);
      if (seen.has(id)) {
        rejected.push({ id, code: 'duplicate', reason: '同一批次里重复的会话ID' });
        continue;
      }
      seen.add(id);
      const checked = await this.inspect(id, { currentSessionId, ancestors, children, signal });
      if (checked.ok) accepted.push(checked.item);
      else rejected.push({ id, code: checked.code, reason: checked.reason });
    }
    return { accepted, rejected };
  }

  /** The workspace membership, successor and archive/pin facts of one session. */
  describeMembership(id) {
    const state = this.registry.requireState();
    const archivedAt = state.archivedSessionIds.indexOf(id);
    const pinnedAt = state.pinnedSessionIds.indexOf(id);
    let workspace = null;
    let successorSessionId = null;
    for (const entity of this.registry.list()) {
      const ids = entity.sessionIds;
      const at = ids.indexOf(id);
      if (at < 0) continue;
      workspace = { id: entity.id, path: entity.path, title: entity.title };
      successorSessionId = at + 1 < ids.length ? ids[at + 1] : null;
      break;
    }
    return {
      workspace,
      successorSessionId,
      archivedIndex: archivedAt >= 0 ? archivedAt : null,
      pinnedIndex: pinnedAt >= 0 ? pinnedAt : null
    };
  }

  /** Drop one session id from the registry's in-memory header index. */
  forgetIndex(id) {
    for (const name of ['headers', 'sessionPaths', 'invalidSessionPaths']) {
      const map = this.registry[name];
      if (map instanceof Map) map.delete(id);
      else if (typeof map?.delete === 'function') map.delete(id);
    }
  }

  /**
   * Remove one session from the workspace accounting and from the
   * registry-global archived/pinned sets. The two sets are updated through
   * `requireState`/`setState` inside the registry's own write queue; the
   * queued `unarchiveSession`/`unpinSession` helpers are deliberately not
   * used because they would queue behind this very operation.
   */
  async applyRemoval(id, membership) {
    await this.registry.enqueueOperation(async () => {
      const state = this.registry.requireState();
      const archivedSessionIds = state.archivedSessionIds.filter((entry) => entry !== id);
      const pinnedSessionIds = state.pinnedSessionIds.filter((entry) => entry !== id);
      if (archivedSessionIds.length !== state.archivedSessionIds.length
          || pinnedSessionIds.length !== state.pinnedSessionIds.length) {
        await this.registry.setState({ ...state, archivedSessionIds, pinnedSessionIds });
      }
      const entity = membership.workspace?.id !== undefined && membership.workspace?.id !== null
        ? this.registry.get(membership.workspace.id)
        : undefined;
      if (entity !== undefined && entity !== null) await entity.detachSession(id);
      this.forgetIndex(id);
    });
  }

  /** Put one session back into the workspace accounting and the session sets. */
  async applyRestoration(manifest) {
    const id = manifest.sessionId;
    await this.registry.enqueueOperation(async () => {
      const entity = manifest.workspace?.id !== undefined && manifest.workspace?.id !== null
        ? this.registry.get(manifest.workspace.id)
        : undefined;
      if (entity === undefined || entity === null) throw new Error('原工作区已不存在，恢复记录保留');
      {
        await entity.attachSession(id);
        const successor = manifest.successorSessionId;
        if (typeof successor === 'string' && entity.sessionIds.includes(successor)) {
          await entity.insertSessionBefore(id, successor);
        }
      }
      const state = this.registry.requireState();
      const archivedSessionIds = restoreIndex(state.archivedSessionIds, id, manifest.archivedIndex ?? null);
      const pinnedSessionIds = restoreIndex(state.pinnedSessionIds, id, manifest.pinnedIndex ?? null);
      if (archivedSessionIds.length !== state.archivedSessionIds.length
          || archivedSessionIds.some((entry, index) => entry !== state.archivedSessionIds[index])
          || pinnedSessionIds.length !== state.pinnedSessionIds.length
          || pinnedSessionIds.some((entry, index) => entry !== state.pinnedSessionIds[index])) {
        await this.registry.setState({ ...state, archivedSessionIds, pinnedSessionIds });
      }
    });
  }

  /** Write one restore manifest into its own recovery directory. */
  async writeManifest(manifest) {
    const root = await this.ensureRoot();
    const dir = join(root, manifest.recoveryId);
    await mkdir(dir, { mode: 0o700 });
    await chmod(dir, 0o700);
    await this.writeManifestFile(dir, manifest);
    return dir;
  }

  /** Write the restore manifest atomically with private permissions. */
  async writeManifestFile(dir, manifest) {
    const target = join(dir, RECORD_FILE_NAME);
    const temp = join(dir, TEMP_FILE_NAME);
    await writeFile(temp, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
    await chmod(temp, 0o600);
    await rename(temp, target);
  }

  /** Read one restore manifest, or `null` when it is missing or unusable. */
  async readManifest(dir) {
    let text;
    try {
      if (!(await isRealDirectory(dir))) return null;
      const file = join(dir, RECORD_FILE_NAME);
      const info = await lstat(file);
      if (!info.isFile() || info.isSymbolicLink() || info.size > 65536) return null;
      text = await readFile(file, 'utf8');
    } catch {
      return null;
    }
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch {
      return null;
    }
    if (!isPlainObject(parsed)) return null;
    if (!isExplicitSessionId(parsed.sessionId)) return null;
    if (!isPlainObject(parsed.header) || parsed.header.id !== parsed.sessionId || typeof parsed.header.cwd !== 'string') return null;
    return parsed;
  }

  /** Patch one manifest in place. */
  async updateManifest(dir, patch) {
    const manifest = await this.readManifest(dir);
    if (manifest === null) return null;
    const next = { ...manifest, ...patch };
    await this.writeManifestFile(dir, next);
    return next;
  }

  /**
   * Remove the recovery metadata of one restored session. Only the manifest
   * and the (then empty) recovery directory are touched: the session content
   * itself is never deleted.
   */
  async discardRecord(dir) {
    try {
      await unlink(join(dir, RECORD_FILE_NAME));
    } catch {
      /* the manifest may already be gone */
    }
    try {
      await rmdir(dir);
    } catch {
      /* a non-empty recovery directory keeps its content */
    }
  }

  /** The observed state of one recovery record. */
  async observeRecord(dir, manifest) {
    if (await isRealDirectory(join(dir, SESSION_DIR_NAME))) return 'recycled';
    let original = null;
    try {
      original = await this.resolveSessionDir(manifest.header);
    } catch {
      return 'inconsistent';
    }
    if (await pathExists(original)) return 'present';
    return 'inconsistent';
  }

  /** Every recovery record the bin holds, with its observed state. */
  async listRecords(options = {}) {
    const root = await this.ensureRoot();
    const entries = await readdir(root, { withFileTypes: true });
    const records = [];
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      if (!UUID_PATTERN.test(entry.name)) continue;
      const dir = join(root, entry.name);
      const manifest = await this.readManifest(dir);
      if (manifest === null) continue;
      const state = await this.observeRecord(dir, manifest);
      records.push({
        recoveryId: entry.name,
        id: manifest.sessionId,
        cwd: manifest.header.cwd,
        title: typeof manifest.title === 'string' ? manifest.title : '',
        state,
        createdAt: typeof manifest.createdAt === 'string' ? manifest.createdAt : ''
      });
    }
    records.sort((left, right) => (left.createdAt < right.createdAt ? 1 : left.createdAt > right.createdAt ? -1 : 0));
    return records;
  }

  /**
   * Move one session into the recycle bin.
   *
   * The session directory is renamed whole (its compressed log, its lock file
   * and any sibling artifact travel with it). The kernel write lease of the
   * session is held from before the rename until the registry update finished
   * or was rolled back.
   */
  async remove(id, options = {}) {
    const { signal } = options;
    const fail = (code, reason, extra = {}) => ({ id, ok: false, code, reason, ...extra });

    if (!isExplicitSessionId(id)) {
      return fail('invalid-id', '只接受明确的会话ID，不接受路径或通配符');
    }
    if (signal?.aborted === true) {
      return fail('cancelled', '操作已取消，未修改任何文件');
    }
    const snapshot = await this.statSession(id, signal);
    if (snapshot === undefined) {
      return fail('unknown', '会话不存在或尚未持久化');
    }
    const header = snapshot.header;
    if (header?.cwd === undefined) {
      return fail('no-cwd', '会话没有工作目录，无法安全移入回收站');
    }
    if (options.revision !== undefined && String(options.revision) !== String(snapshot.revision)) {
      return fail('changed', '会话在确认期间已发生变化，请重新列举后再试');
    }
    if (this.isLive(id)) {
      return fail('live', '会话处于活动状态（已加载或运行中），不能移入回收站');
    }
    if ((await this.activityOf(id)).length > 0) {
      return fail('active', '会话仍有后台活动，不能移入回收站');
    }
    let dir;
    try {
      dir = await this.locateSessionDir(header);
    } catch (error) {
      return fail('locate', `无法定位会话目录：${error.message}`);
    }

    let lease;
    try {
      lease = await this.persistence.acquireLease(id, header.cwd, dir);
    } catch (error) {
      return fail('lease-busy', `会话正被其他进程写入，暂时无法移入回收站（${error.message}）`);
    }

    let recordDir = null;
    let moved = false;
    try {
      const again = await this.statSession(id, signal);
      if (again === undefined) {
        return fail('vanished', '会话在锁定期间消失，未做任何修改');
      }
      if (String(again.revision) !== String(snapshot.revision)) {
        return fail('changed', '会话在锁定期间已发生变化，未做任何修改');
      }
      const recheck = await this.plan([id], options);
      if (recheck.rejected.length) return fail(recheck.rejected[0].code, recheck.rejected[0].reason);
      if (signal?.aborted) return fail('cancelled', '操作已取消，未移动任何文件');

      const membership = this.describeMembership(id);
      const recoveryId = randomUUID();
      const manifest = {
        version: 1,
        recoveryId,
        sessionId: id,
        createdAt: new Date().toISOString(),
        state: 'planned',
        header: {
          id: header.id,
          cwd: header.cwd,
          createdAt: header.createdAt,
          version: header.version
        },
        originalDir: dir,
        title: typeof options.title === 'string' ? options.title : '',
        workspace: membership.workspace,
        successorSessionId: membership.successorSessionId,
        archivedIndex: membership.archivedIndex,
        pinnedIndex: membership.pinnedIndex
      };
      recordDir = await this.writeManifest(manifest);
      const parked = join(recordDir, SESSION_DIR_NAME);

      if (signal?.aborted === true) {
        await this.discardRecord(recordDir);
        return fail('cancelled', '操作已取消，未移动任何文件');
      }
      await rename(dir, parked);
      moved = true;

      try {
        await this.applyRemoval(id, membership);
      } catch (error) {
        let rolledBack = false;
        try {
          await rename(parked, dir);
          moved = false;
          rolledBack = true;
        } catch {
          rolledBack = false;
        }
        if (rolledBack) {
          try {
            await this.applyRestoration(manifest);
          } catch {
            return fail('partial', '文件已回滚，但列表状态未恢复；请按恢复ID恢复', { recoveryId, recoverable: true });
          }
          await this.discardRecord(recordDir);
          return fail('index-failed', `注册表更新失败，已回滚到原位置：${error.message}`);
        }
        await this.updateManifest(recordDir, { state: 'moved' });
        return fail('partial', `会话已移入回收站但注册表更新失败（${error.message}）；请用恢复ID ${recoveryId} 手动恢复`, { recoveryId, recoverable: true });
      }

      await this.updateManifest(recordDir, { state: 'moved' });
      this.ctx.emit?.('api-session/removed', id);
      return { id, ok: true, code: 'recycled', recoveryId, title: manifest.title };
    } catch (error) {
      if (moved && recordDir !== null) {
        try {
          await this.updateManifest(recordDir, { state: 'moved' });
        } catch {
          /* the record on disk already describes the parked session */
        }
        return fail('partial', `会话已移入回收站但收尾失败（${error.message}）；该恢复记录仍可恢复`, { recoveryId: recordDir.split(sep).pop(), recoverable: true });
      }
      if (recordDir !== null) await this.discardRecord(recordDir);
      return fail('failed', `移入回收站失败：${error.message}`);
    } finally {
      try {
        await lease?.release?.();
      } catch {
        /* the lease releases itself when the descriptor closes */
      }
    }
  }

  /**
   * Put one recycled session back where it came from.
   *
   * The restore target is recomputed from the persistence backend's locator
   * for the manifest header and must stay inside the session storage root; a
   * manifest that points somewhere else is refused instead of being obeyed.
   */
  async restore(recoveryId, options = {}) {
    const { signal } = options;
    const fail = (code, reason, extra = {}) => ({ recoveryId, ok: false, code, reason, ...extra });

    if (typeof recoveryId !== 'string' || !UUID_PATTERN.test(recoveryId)) {
      return fail('invalid-id', '恢复ID格式不合法');
    }
    if (signal?.aborted === true) {
      return fail('cancelled', '操作已取消，未修改任何文件');
    }
    let root;
    try {
      root = await this.ensureRoot();
    } catch (error) {
      return fail('unavailable', `回收站不可用：${error.message}`);
    }
    const recordDir = join(root, recoveryId);
    const manifest = await this.readManifest(recordDir);
    if (manifest === null) {
      return fail('unknown', '找不到该恢复记录');
    }
    const parked = join(recordDir, SESSION_DIR_NAME);
    const hasParked = await isRealDirectory(parked);
    const id = manifest.sessionId;
    let dir;
    try {
      dir = await this.resolveSessionDir(manifest.header);
    } catch (error) {
      return fail('invalid-record', `恢复记录无法定位原会话：${error.message}`);
    }
    if (typeof manifest.originalDir === 'string' && resolve(manifest.originalDir) !== resolve(dir)) {
      return fail('invalid-record', '恢复记录与会话定位不一致，拒绝按清单中的任意路径恢复');
    }
    const alreadyPresent = !hasParked && await isRealDirectory(dir);
    if (!hasParked && !alreadyPresent) return fail('not-recycled', '该记录没有可恢复的会话目录');
    if (hasParked && await pathExists(dir)) return fail('path-conflict', '原路径已存在，未覆盖任何文件');
    if (this.isLive(id)) {
      return fail('live-conflict', '该会话ID当前处于活动状态，拒绝恢复');
    }
    if ((await this.activityOf(id)).length > 0) {
      return fail('active-conflict', '该会话ID仍有后台活动，拒绝恢复');
    }
    const entity = manifest.workspace?.id !== undefined && manifest.workspace?.id !== null
      ? this.registry.get(manifest.workspace.id)
      : undefined;
    if (entity === undefined || entity === null) {
      return fail('workspace-missing', '原工作区已不存在，请先恢复该项目再恢复会话');
    }

    let lease;
    try {
      lease = await this.persistence.acquireLease(id, manifest.header.cwd, alreadyPresent ? dir : parked);
    } catch (error) {
      return fail('lease-busy', `回收站中的会话正被占用，暂时无法恢复（${error.message}）`);
    }

    let moved = false;
    try {
      if (signal?.aborted) return fail('cancelled', '操作已取消，未修改任何文件');
      if (this.isLive(id) || (await this.activityOf(id)).length > 0) return fail('live-conflict', '会话已被占用，未恢复');
      if (!alreadyPresent) {
        if (await pathExists(dir)) return fail('path-conflict', '原路径已存在，未覆盖任何文件');
        await rename(parked, dir);
        moved = true;
      }
      const snapshot = await this.statSession(id);
      if (!snapshot || snapshot.header.id !== manifest.header.id || snapshot.header.cwd !== manifest.header.cwd || snapshot.header.createdAt !== manifest.header.createdAt) throw new Error('恢复后的会话无法通过持久化校验');
      await this.registry.indexHeader(snapshot.header);
      await this.applyRestoration(manifest);
      await this.discardRecord(recordDir);
      this.ctx.emit?.('api-session/activity', id, Date.now());
      return { recoveryId, ok: true, code: 'restored', id };
    } catch (error) {
      if (moved) {
        try {
          await rename(dir, parked);
          moved = false;
        } catch {
          /* the recovery record still describes where the session went */
        }
      }
      return fail('failed', `恢复未完成，恢复记录已保留：${error.message}`, { recoverable: true });
    } finally {
      try {
        await lease?.release?.();
      } catch {
        /* the lease releases itself when the descriptor closes */
      }
    }
  }
}

export default { SessionRecycle, isExplicitSessionId, optionalService, MAX_DELETE_IDS };
