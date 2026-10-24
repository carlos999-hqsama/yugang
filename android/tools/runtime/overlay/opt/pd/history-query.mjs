// Literal session-history search over dsh's live-preferred raw logs.
// This backend keeps the base reads/filters but replaces SQLite FTS: unicode61
// cannot reliably find arbitrary Chinese substrings, and a derived index would
// duplicate user history. Search is deliberately bounded and reports when the
// scan budget is exhausted instead of returning partial results.
import { createHash, randomUUID } from 'node:crypto';
import SessionQueryEngine, {
  SessionQueryError,
  SessionSearchCursor,
  compileSessionTextFilter,
  extractSessionEventText,
  materializeSessionEventResultFilters,
} from './dsh/node_modules/@deepseek-ai/dsh-session-query/lib/index.js';
import { foldSurface } from './dsh/node_modules/@deepseek-ai/dsh-session/lib/index.js';
import { currentSessionMessageProjections } from './dsh/node_modules/@deepseek-ai/dsh-session-format-catalog/lib/types/message-projections.js';

const DEFAULTS = {
  defaultLimit: 20,
  maxLimit: 100,
  snippetChars: 240,
  maxSessions: 5000,
  maxEvents: 100000,
  maxDocuments: 100000,
  maxTextChars: 4000000,
  maxElapsedMs: 5000,
  readWindowMax: 50,
  persistedReadConcurrency: 4,
  preparedSessionCacheSize: 5,
};

function searchError(message, code, cause) {
  return new SessionQueryError(message, code, cause === undefined ? undefined : { cause });
}

function checkAbort(signal) {
  if (signal?.aborted) throw searchError('session-history search aborted', 'SESSION_QUERY_ABORTED', signal.reason);
}

function normalizeQuery(value) {
  if (typeof value !== 'string') throw searchError('session-search query must be text', 'SESSION_QUERY_INVALID_QUERY');
  const query = value.trim().replace(/\s+/gu, ' ');
  if (query.length === 0) throw searchError('session-search query must contain non-whitespace text', 'SESSION_QUERY_INVALID_QUERY');
  if (query.length > 500) throw searchError('session-search query must not exceed 500 characters', 'SESSION_QUERY_INVALID_QUERY');
  if (query.includes('\0')) throw searchError('session-search query must not contain NUL', 'SESSION_QUERY_INVALID_QUERY');
  return query;
}

function positiveInteger(name, value) {
  if (!Number.isSafeInteger(value) || value < 1) throw searchError(`${name} must be a positive safe integer`, 'SESSION_QUERY_INVALID_CONFIG');
  return value;
}

function resolveConfig(config = {}) {
  const resolved = { ...DEFAULTS };
  for (const key of Object.keys(DEFAULTS)) if (config[key] !== undefined) resolved[key] = config[key];
  for (const key of ['defaultLimit', 'maxLimit', 'snippetChars', 'maxSessions', 'maxEvents', 'maxDocuments', 'maxTextChars', 'maxElapsedMs', 'persistedReadConcurrency', 'preparedSessionCacheSize']) {
    positiveInteger(`session-history ${key}`, resolved[key]);
  }
  if (!Number.isInteger(resolved.readWindowMax) || resolved.readWindowMax < 0) throw searchError('session-history readWindowMax must be a non-negative integer', 'SESSION_QUERY_INVALID_CONFIG');
  if (resolved.defaultLimit > resolved.maxLimit) throw searchError('session-history defaultLimit must not exceed maxLimit', 'SESSION_QUERY_INVALID_CONFIG');
  return resolved;
}

function normalizeMetadataFilters(filters = []) {
  const normalized = materializeSessionEventResultFilters(filters);
  if (normalized.some((filter) => filter.kind === 'text')) throw searchError('session-search metadata filters do not accept text clauses', 'SESSION_QUERY_INVALID_FILTER');
  return normalized;
}

function normalizeLimit(value, config) {
  const limit = value ?? config.defaultLimit;
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > config.maxLimit) {
    throw searchError(`session-search limit must be an integer between 1 and ${config.maxLimit}`, 'SESSION_QUERY_INVALID_LIMIT');
  }
  return limit;
}

function inRange(value, filter) {
  return (filter.from === undefined || value >= filter.from) && (filter.to === undefined || value <= filter.to);
}

function matchesMetadata(document, filters) {
  for (const filter of filters) {
    switch (filter.kind) {
      case 'seq': if (!inRange(document.seq, filter)) return false; break;
      case 'time': if (!inRange(document.time, filter)) return false; break;
      case 'type': if (!filter.values.includes(document.type)) return false; break;
      case 'surface': if (!filter.values.includes(document.surface)) return false; break;
      default: return false;
    }
  }
  return true;
}

function textBlocks(content) {
  if (!Array.isArray(content)) return '';
  return content
    .filter((block) => block && block.type === 'text' && typeof block.text === 'string')
    .map((block) => block.text.trim())
    .filter(Boolean)
    .join('\n');
}

// Message events are intentionally reduced to visible text. Reasoning and
// tool-call arguments never enter an agent-facing message search.
function documentText(event) {
  if (event.type === 'user/message') return textBlocks(event.data.content);
  if (event.type === 'assistant/message') return textBlocks(event.data.message.content);
  return extractSessionEventText(event);
}

function surfaceBySeq(events) {
  const folded = foldSurface(events, currentSessionMessageProjections);
  const current = new Set(folded.nodes);
  const shadowed = new Set(folded.replacements.flatMap((replacement) => replacement.shadowedSeqs));
  return (seq) => current.has(seq) ? 'current' : shadowed.has(seq) ? 'shadowed' : 'log-only';
}

function matchInfo(regex, text) {
  regex.lastIndex = 0;
  let first = -1;
  let count = 0;
  for (let match = regex.exec(text); match !== null; match = regex.exec(text)) {
    if (first < 0) first = match.index;
    count += 1;
  }
  return count === 0 ? undefined : { first, count };
}

function snippet(text, matchIndex, maxChars) {
  const chars = Array.from(text);
  if (chars.length <= maxChars) return chars.join('').replace(/\s+/gu, ' ').trim();
  const matchStart = Array.from(text.slice(0, matchIndex)).length;
  const prefix = matchStart > Math.floor(maxChars / 3) ? '…' : '';
  let start = Math.max(0, matchStart - Math.floor(maxChars / 3));
  let suffix = '…';
  let bodyLength = maxChars - prefix.length - suffix.length;
  if (bodyLength < 1) {
    start = matchStart;
    suffix = '';
    bodyLength = Math.max(1, maxChars - prefix.length);
  } else if (matchStart >= start + bodyLength) {
    start = matchStart - bodyLength + 1;
  }
  if (start + bodyLength >= chars.length) {
    suffix = '';
    start = Math.max(0, chars.length - (maxChars - prefix.length));
  }
  return `${prefix}${chars.slice(start, start + bodyLength).join('').replace(/\s+/gu, ' ').trim()}${suffix}`;
}

function compareEvents(a, b) {
  return b.matchCount - a.matchCount
    || a.length - b.length
    || b.time - a.time
    || b.seq - a.seq
    || a.sessionId.localeCompare(b.sessionId);
}

function compareSessionHits(a, b) {
  return b.best.matchCount - a.best.matchCount
    || a.best.length - b.best.length
    || b.best.time - a.best.time
    || a.record.header.id.localeCompare(b.record.header.id)
    || b.best.seq - a.best.seq;
}

function digest(value) {
  return createHash('sha256').update(value).digest('base64url');
}

class ScanBudget {
  constructor(config, signal) {
    this.config = config;
    this.signal = signal;
    this.started = performance.now();
    this.sessions = 0;
    this.events = 0;
    this.documents = 0;
    this.textChars = 0;
  }

  checkpoint() {
    checkAbort(this.signal);
    if (performance.now() - this.started > this.config.maxElapsedMs) {
      this.exceeded(`${this.config.maxElapsedMs}ms elapsed`);
    }
  }

  add(field, amount) {
    this[field] += amount;
    const cap = this.config[`max${field[0].toUpperCase()}${field.slice(1)}`];
    if (this[field] > cap) this.exceeded(`${this[field]} ${field} exceeded ${cap}`);
  }

  exceeded(detail) {
    throw searchError(
      `session-history search stopped at its work limit (${detail}); no partial results were returned. Narrow the session scope or query.`,
      'SESSION_QUERY_WORK_LIMIT',
    );
  }
}

class HistoryQueryEngine extends SessionQueryEngine {
  constructor(ctx, config = {}) {
    const resolved = resolveConfig(config);
    super(ctx, resolved);
    this.config = resolved;
    this.instance = randomUUID();
  }

  async searchSessions(request, exec) {
    const signal = exec?.signal;
    checkAbort(signal);
    const query = normalizeQuery(request.query);
    const filters = normalizeMetadataFilters(request.eventFilters ?? []);
    const limit = normalizeLimit(request.limit, this.config);
    const regex = new RegExp(compileSessionTextFilter(query).source, 'giu');
    const records = await this.filterSessions(request.sessionFilters ?? [], signal);
    checkAbort(signal);
    const budget = new ScanBudget(this.config, signal);
    budget.add('sessions', records.length);
    const scanned = [];
    for (const record of records) {
      budget.checkpoint();
      const snapshot = await this.readSession(record.header.id);
      budget.checkpoint();
      budget.add('events', snapshot.events.length);
      const surfaceOf = surfaceBySeq(snapshot.events);
      let best;
      for (let index = 0; index < snapshot.events.length; index += 1) {
        budget.checkpoint();
        const event = snapshot.events[index];
        const document = {
          seq: event.seq,
          type: event.type,
          time: event.time,
          surface: surfaceOf(event.seq),
        };
        if (!matchesMetadata(document, filters)) continue;
        const text = documentText(event);
        if (!text) continue;
        budget.add('documents', 1);
        budget.add('textChars', text.length);
        const info = matchInfo(regex, text);
        if (info === undefined) continue;
        const candidate = {
          sessionId: record.header.id,
          ...document,
          matchCount: info.count,
          firstMatch: info.first,
          text,
          length: text.length,
        };
        if (best === undefined || compareEvents(candidate, best) < 0) best = candidate;
      }
      scanned.push({ record, length: snapshot.events.length, best });
    }
    checkAbort(signal);
    const signature = digest(JSON.stringify(scanned.map(({ record, length }) => [
      record.header.id,
      record.header.createdAt,
      record.header.cwd ?? null,
      record.live,
      record.persisted,
      length,
    ])));
    const fingerprint = digest(JSON.stringify({ scope: 'sessions', query, sessionFilters: request.sessionFilters ?? [], eventFilters: filters, limit }));
    const offset = this.cursorOffset(request.cursor, 'sessions', fingerprint, signature);
    const hits = scanned
      .filter(({ best }) => best !== undefined)
      .sort(compareSessionHits);
    const page = hits.slice(offset, offset + limit).map(({ record, best }) => ({
      header: record.header,
      live: record.live,
      persisted: record.persisted,
      bestMatch: {
        sessionId: best.sessionId,
        seq: best.seq,
        type: best.type,
        time: best.time,
        surface: best.surface,
        snippet: snippet(best.text, best.firstMatch, this.config.snippetChars),
      },
    }));
    const nextCursor = hits.length > offset + limit
      ? SessionSearchCursor(this.encodeCursor('sessions', fingerprint, signature, offset + limit))
      : undefined;
    return { items: page, ...nextCursor === undefined ? {} : { nextCursor } };
  }

  async searchEvents(request, exec) {
    const signal = exec?.signal;
    checkAbort(signal);
    if (typeof request.sessionId !== 'string' || request.sessionId.length === 0) {
      throw searchError('session-search session id must be non-empty text', 'SESSION_QUERY_INVALID_FILTER');
    }
    const query = normalizeQuery(request.query);
    const filters = normalizeMetadataFilters(request.filters ?? []);
    const limit = normalizeLimit(request.limit, this.config);
    const regex = new RegExp(compileSessionTextFilter(query).source, 'giu');
    const records = await this.filterSessions([{ kind: 'id', values: [request.sessionId] }], signal);
    checkAbort(signal);
    if (records.length === 0) throw searchError(`session "${request.sessionId}" not found`, 'SESSION_QUERY_SESSION_NOT_FOUND');
    const input = records[0];
    const budget = new ScanBudget(this.config, signal);
    budget.add('sessions', 1);
    const snapshot = await this.readSession(input.header.id);
    budget.checkpoint();
    budget.add('events', snapshot.events.length);
    const surfaceOf = surfaceBySeq(snapshot.events);
    const matches = [];
    for (let index = 0; index < snapshot.events.length; index += 1) {
      budget.checkpoint();
      const event = snapshot.events[index];
      const document = {
        seq: event.seq,
        type: event.type,
        time: event.time,
        surface: surfaceOf(event.seq),
      };
      if (!matchesMetadata(document, filters)) continue;
      const text = documentText(event);
      if (!text) continue;
      budget.add('documents', 1);
      budget.add('textChars', text.length);
      const info = matchInfo(regex, text);
      if (info === undefined) continue;
      matches.push({
        sessionId: input.header.id,
        ...document,
        matchCount: info.count,
        firstMatch: info.first,
        text,
        length: text.length,
      });
    }
    checkAbort(signal);
    matches.sort(compareEvents);
    const signature = digest(JSON.stringify([
      input.header.id,
      input.header.createdAt,
      snapshot.events.length,
      snapshot.events.at(-1)?.seq ?? null,
    ]));
    const fingerprint = digest(JSON.stringify({ scope: 'events', sessionId: input.header.id, query, filters, limit }));
    const offset = this.cursorOffset(request.cursor, 'events', fingerprint, signature);
    const page = matches.slice(offset, offset + limit).map((match) => ({
      sessionId: match.sessionId,
      seq: match.seq,
      type: match.type,
      time: match.time,
      surface: match.surface,
      snippet: snippet(match.text, match.firstMatch, this.config.snippetChars),
    }));
    const nextCursor = matches.length > offset + limit
      ? SessionSearchCursor(this.encodeCursor('events', fingerprint, signature, offset + limit))
      : undefined;
    return {
      session: input.header,
      items: page,
      ...nextCursor === undefined ? {} : { nextCursor },
    };
  }

  encodeCursor(scope, fingerprint, signature, offset) {
    return Buffer.from(JSON.stringify({
      version: 1,
      instance: this.instance,
      scope,
      fingerprint,
      signature,
      offset,
    }), 'utf8').toString('base64url');
  }

  cursorOffset(cursor, scope, fingerprint, signature) {
    if (cursor === undefined) return 0;
    if (typeof cursor !== 'string') throw searchError('session-search cursor must be text', 'SESSION_QUERY_INVALID_CURSOR');
    let decoded;
    try {
      decoded = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8'));
    } catch (error) {
      throw searchError('session-search cursor is invalid', 'SESSION_QUERY_INVALID_CURSOR', error);
    }
    if (decoded.version !== 1
      || decoded.instance !== this.instance
      || decoded.scope !== scope
      || decoded.fingerprint !== fingerprint
      || !Number.isSafeInteger(decoded.offset)
      || decoded.offset < 0) {
      throw searchError('session-search cursor does not belong to this request', 'SESSION_QUERY_INVALID_CURSOR');
    }
    if (decoded.signature !== signature) {
      throw searchError('session-search cursor is stale because the relevant history changed', 'SESSION_QUERY_STALE_CURSOR');
    }
    return decoded.offset;
  }
}

export { HistoryQueryEngine };
export default HistoryQueryEngine;
