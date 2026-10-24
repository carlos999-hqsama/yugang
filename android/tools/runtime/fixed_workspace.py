"""Pinned DSH phone policy: per-Bot workspaces, permanently full access, no approvals.

This is runtime policy and file organization, not a hostile-code filesystem jail.
The public dependency cache is read-only; only packaged copies are transformed.
"""

def _replace(source, old, new, count=1):
    if source.count(old) != count:
        raise ValueError('Pinned phone workspace policy changed: ' + old[:70])
    return source.replace(old, new)


PHONE_PATH = r"function phoneWorkspace(value) { if (typeof value !== 'string' || !/^\/BOT\/(default|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$/.test(value)) throw new Error('Unknown phone Bot workspace'); return value; }\n".replace("\\n", "\n")

def patch_vendor(rel, source):
    prefix = 'node_modules/@deepseek-ai/'
    if rel == prefix + 'dsh-sandbox-policy/lib/index.js':
        source = _replace(source, 'this.defaultMode = config.mode;', 'this.defaultMode = "danger-full-access";')
        source = _replace(source, 'this.workspaceRoot = resolveWorkspaceRoot(config.workspaceRoot ?? process.cwd());', 'this.workspaceRoot = "/BOT/default";')
        source = _replace(source, 'mode: request.mode ?? (session === void 0 ? void 0 : this.overrideOf(session)) ?? this.defaultMode,', 'mode: "danger-full-access",')
        source = _replace(source, 'workspaceRoot: resolveWorkspaceRoot(session?.header.cwd ?? this.workspaceRoot),', 'workspaceRoot: resolveWorkspaceRoot(session?.[Symbol.for("pd.bot.workspace")] ?? session?.header.cwd ?? this.workspaceRoot),')
        source = _replace(source, 'function setSandboxMode(session, mode) {', 'function setSandboxMode(session, mode) {\n\tif (mode !== "danger-full-access") throw new Error("Phone Bot workspace permission is fixed");')
    elif rel == prefix + 'dsh-compaction-basic/lib/index.js':
        source = _replace(source, 'const policy = resolveTargetPolicy(this.config, target);', 'const policy = phoneBotCompactionPolicy(this.config, target, agent.session);', count=2)
        source += """
function phoneBotCompactionPolicy(config, target, session) {
  const policy = resolveTargetPolicy(config, target);
  const threshold = session?.[Symbol.for('pd.bot.compactionThreshold')];
  return typeof threshold === 'number' && Number.isFinite(threshold) && threshold >= .5 && threshold <= .9
    ? {...policy, thresholdRatio: threshold} : policy;
}
"""
    elif rel == prefix + 'dsh-user-approval/lib/index.js':
        source = _replace(source, 'this.config = config;', 'this.config = { ...config, policy: "never" };')
        source = _replace(source, 'return this.overrideOf(session) ?? this.config.policy ?? "ask";', 'return "never";')
        source = _replace(source, 'function setApprovalPolicy(session, policy) {', 'function setApprovalPolicy(session, policy) {\n\tif (policy !== "never") throw new Error("Phone operation approval policy is fixed");')
    elif rel == prefix + 'dsh-permission-presets/lib/index.js':
        source = _replace(source, 'this.presets = config.presets;', 'this.presets = { "danger-full-access": { sandbox: "danger-full-access", approval: "never", name: "手机工作区" } };')
        source = _replace(source, 'const defaultPreset = config.defaultPreset.get() ?? inferredDefault;', 'const defaultPreset = "danger-full-access";', count=2)
        source = _replace(source, 'return [...Object.keys(this.presets), ...this.autoAdmit === void 0 ? [] : [AUTO_PRESET]];', 'return ["danger-full-access"];')
        source = _replace(source, 'apply(session, name, setApproval) {', 'apply(session, name, setApproval) {\n\t\t\tif (name !== "danger-full-access") throw new Error("Phone Bot workspace permission is fixed");')
        source = _replace(source, 'return this.derive(this.permissionState(session));', 'return "danger-full-access";')
    elif rel in [prefix + name + '/lib/index.js' for name in ('dsh-tool-bash', 'dsh-tool-fs', 'dsh-tool-fs-search')]:
        source = _replace(source, 'exec.agent?.session.header.cwd', 'exec.agent?.session[Symbol.for("pd.bot.workspace")] ?? exec.agent?.session.header.cwd')
    elif rel == prefix + 'dsh-tool-present/lib/index.js':
        source = _replace(source, 'const cwd = exec.agent.session.header.cwd;', 'const cwd = exec.agent.session[Symbol.for("pd.bot.workspace")] ?? exec.agent.session.header.cwd;')
        source = _replace(source, 'files.push({ ...file });', 'files.push({ ...file, path: target.displayPath });')
    elif rel == prefix + 'dsh-tools/lib/index.js':
        source = _replace(source, '...exec.agent?.session.header.cwd !== void 0 ? { cwd: exec.agent.session.header.cwd } : {},', '...(exec.agent?.session[Symbol.for("pd.bot.workspace")] ?? exec.agent?.session.header.cwd) !== void 0 ? { cwd: exec.agent.session[Symbol.for("pd.bot.workspace")] ?? exec.agent.session.header.cwd } : {},')
    elif rel == prefix + 'dsh-tool-skill/lib/index.js':
        source = _replace(source, 'exec.agent?.session.header.cwd', 'exec.agent?.session[Symbol.for("pd.bot.workspace")] ?? exec.agent?.session.header.cwd')
        source = _replace(source, 'agent.session.header.cwd', 'agent.session[Symbol.for("pd.bot.workspace")] ?? agent.session.header.cwd', count=2)
    elif rel == prefix + 'dsh-agent-instructions/lib/index.js':
        source = _replace(source, 'const cwd = session.header.cwd ?? process.cwd();', 'const cwd = session[Symbol.for("pd.bot.workspace")] ?? session.header.cwd ?? process.cwd();')
        source = _replace(source, 'const cwd = agent.session.header.cwd ?? process.cwd();', 'const cwd = agent.session[Symbol.for("pd.bot.workspace")] ?? agent.session.header.cwd ?? process.cwd();')
    elif rel == prefix + 'dsh-agent-loop/lib/index.js':
        source = _replace(source, 'context.agent?.session.header.cwd', 'context.agent?.session[Symbol.for("pd.bot.workspace")] ?? context.agent?.session.header.cwd')
    elif rel == prefix + 'dsh-subagent/lib/index.js':
        source = _replace(source, 'const parentHeader = parent.session.header;', 'const parentHeader = { ...parent.session.header, cwd: parent.session[Symbol.for("pd.bot.workspace")] ?? parent.session.header.cwd };')
    elif rel == prefix + 'dsh-skill-filesystem/lib/index.js':
        source = _replace(source, 'const projectRoot = await findProjectRoot(resolve(cwd), optionalFileSystem(this.ctx));', 'const projectRoot = resolve(cwd);')
        source = _replace(source, 'path: join(projectRoot, ".agents/skills"),', 'path: join(projectRoot, "skills"),')
        source = _replace(source, 'if (this.includeDefaultRoots) roots.push({', 'if (this.includeDefaultRoots && !(cwd?.startsWith("/BOT/") && cwd !== "/BOT/default")) roots.push({')
    elif rel == prefix + 'dsh-api-workspace-controller/lib/index.js':
        source = _replace(source, 'create(request) {\n\t\treturn this.enqueue(async () => {', 'create(request) {\n\t\trequest = { ...request, path: phoneWorkspace(request.path) };\n\t\treturn this.enqueue(async () => {')
        source = _replace(source, 'const workspace = await this.ctx.workspaceRegistry.initializeDefault(async () => {\n\t\t\t\tconst timeout = AbortSignal.timeout(this.config.documentsLookupTimeoutMs);\n\t\t\t\treturn await defaultWorkspaceDirectory(this.config.documentsDirectory, AbortSignal.any([signal, timeout]));\n\t\t\t});', 'const workspace = await this.ctx.workspaceRegistry.initializeDefault(async () => "/BOT/default");')
    elif rel == prefix + 'dsh-api-session-controller/lib/index.js':
        source = _replace(source, 'const cwd = workspace?.path ?? request.cwd ?? this.defaultCwd;', 'const cwd = phoneWorkspace(workspace?.path ?? request.cwd ?? "/BOT/default");')
    if rel in (prefix + 'dsh-api-workspace-controller/lib/index.js', prefix + 'dsh-api-session-controller/lib/index.js'):
        source = PHONE_PATH + source
    return source
