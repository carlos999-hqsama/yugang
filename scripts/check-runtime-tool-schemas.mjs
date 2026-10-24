// Validate shipped definitions using the pinned real DSH compiler, not a defineTool stub.
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const vendor=pathToFileURL(path.join(process.env.PD_DSH_VENDOR_ROOT || path.join(root,'.rtbuild/dl/dsh'), 'node_modules/@deepseek-ai/dsh-tools/lib/index.js')).href;
const {assertSupportedJsonSchema}=await import(vendor);
const overlay=path.join(root,'android/tools/runtime/overlay/opt/pd');
const urls=new Map();
async function moduleUrl(name){
 if(urls.has(name))return urls.get(name);
 let source=await fs.readFile(path.join(overlay,name),'utf8');
 source=source.replace("'./dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js'",JSON.stringify(vendor));
 for(const dependency of ['plugin-tools.mjs','bot-messages.mjs']){
  if(source.includes(`'./${dependency}'`))source=source.replaceAll(`'./${dependency}'`,JSON.stringify(await moduleUrl(dependency)));
 }
 for(const dependency of ['app-host-bridge.mjs','app-bridge.mjs','skill-disclosure.mjs','source-fidelity.mjs','bot-workspace.mjs','bot-context.mjs'])source=source.replaceAll(`'./${dependency}'`,JSON.stringify(pathToFileURL(path.join(overlay,dependency)).href));
 const url='data:text/javascript;base64,'+Buffer.from(source).toString('base64');urls.set(name,url);return url;
}
const definitions=[];
const mod=await import(await moduleUrl('app-tools.mjs'));
mod.apply({on(){},systemPrompt:{section(){}},tools:{register(tool){definitions.push(tool)}}});
for(const tool of definitions){assertSupportedJsonSchema(tool.parameters);assertSupportedJsonSchema(tool.output.schema)}
const expected = new Set([
 'app_settings_get', 'app_settings_update',
 'bots_list', 'bots_send', 'bots_messages',
 'phone_plugins_list', 'phone_plugins_read', 'phone_plugins_install',
 'phone_plugins_enable', 'phone_plugins_rollback', 'phone_plugins_call',
 'phone_plugins_remove', 'phone_plugins_preview',
]);
const actual = definitions.map(tool => tool.name);
if (actual.length !== expected.size || new Set(actual).size !== actual.length || actual.some(name => !expected.has(name))) {
 throw Error(`Built-in tool registration differs from the core contract: ${actual.join(', ')}`);
}
console.log(`Real DSH schema validation: ${definitions.length} tools passed`);
