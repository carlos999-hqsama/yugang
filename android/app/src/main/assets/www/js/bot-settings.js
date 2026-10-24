import {esc} from './util.js';
import {BOT_AVATAR_LABELS,BOT_AVATAR_SHAPES,normalizeBotAvatar,botFish,BOT_COLORS,BOT_COLOR_LABELS,normalizeBotColor} from './bot-list.js';
import {BOT_FISH_TYPES,BOT_FISH_LABELS,normalizeBotFish,fishSvg} from './bot-fish.js';

export const BOT_TOOL_LABELS=Object.freeze({
  files:'文件读写',shell:'命令执行',web:'网页检索',skills:'技能',plugins:'插件管理',
  memory:'本地记忆','app-management':'应用管理','conversation-history':'会话检索'
});
export const PROTECTED_BOT_IDS=Object.freeze(new Set(['default']));
export function botCompactionPercent(value){
  const number=Number(value);
  return Number.isFinite(number)&&number>=.5&&number<=.9?Math.round(number*100):80;
}

const avatarChoices=(selected,fish,color)=>BOT_AVATAR_SHAPES.map(shape=>`
  <label class="bot-avatar-choice" aria-label="${esc(BOT_AVATAR_LABELS[shape])}">
    <input type="radio" name="bot-avatar-shape" data-bot-avatar="${shape}" ${shape===selected?'checked':''}>
    <span class="bot-avatar shape-${shape} tone-${color}" aria-hidden="true">${fishSvg(fish)}</span>
  </label>`).join('');
const fishChoices=(selected,color)=>BOT_FISH_TYPES.map(fish=>`<label class="bot-avatar-choice" aria-label="${esc(BOT_FISH_LABELS[fish])}">
  <input type="radio" name="bot-avatar-fish" data-bot-fish="${fish}" ${fish===selected?'checked':''}>
  <span class="bot-avatar shape-circle tone-${color}" aria-hidden="true">${fishSvg(fish)}</span></label>`).join('');
const colorChoices=selected=>Object.keys(BOT_COLORS).map(color=>`<label class="bot-color-choice" aria-label="${esc(BOT_COLOR_LABELS[color])}"><input type="radio" name="bot-avatar-color" data-bot-color="${color}" ${color===selected?'checked':''}><span class="bot-color-dot tone-${color}" aria-hidden="true"></span></label>`).join('');
const avatarPickerHtml=(avatar,fish,color)=>`<div class="bot-avatar-preview" data-bot-avatar-preview aria-label="组合头像预览"><span class="bot-avatar large shape-${avatar} tone-${color}" aria-hidden="true">${fishSvg(fish)}</span></div>
  <div class="bot-avatar-step">1. 选择鱼类</div><div class="bot-avatar-grid bot-fish-grid" role="radiogroup" aria-label="鱼类图标">${fishChoices(fish,color)}</div>
  <div class="bot-avatar-step">2. 选择形状</div><div class="bot-avatar-grid" role="radiogroup" aria-label="头像形状">${avatarChoices(avatar,fish,color)}</div>
  <div class="bot-avatar-step">3. 选择颜色</div><div class="bot-color-grid" role="radiogroup" aria-label="头像颜色">${colorChoices(color)}</div>`;
function bindAvatarPreview(body){
  const update=event=>{
    const avatar=normalizeBotAvatar(selectedRadio(body.querySelectorAll('[data-bot-avatar]'))?.dataset?.botAvatar);
    const fish=normalizeBotFish(selectedRadio(body.querySelectorAll('[data-bot-fish]'))?.dataset?.botFish);
    const color=normalizeBotColor(selectedRadio(body.querySelectorAll('[data-bot-color]'))?.dataset?.botColor);
    const preview=body.querySelector('[data-bot-avatar-preview]');if(preview)preview.innerHTML=`<span class="bot-avatar large shape-${avatar} tone-${color}" aria-hidden="true">${fishSvg(fish)}</span>`;
    if(event?.target?.dataset?.botFish){for(const input of body.querySelectorAll('[data-bot-avatar]')){const icon=input.closest('.bot-avatar-choice')?.querySelector('.bot-avatar');if(icon)icon.innerHTML=fishSvg(fish);}}
    for(const node of body.querySelectorAll('.bot-avatar')){for(const key of Object.keys(BOT_COLORS))node.classList.remove('tone-'+key);node.classList.add('tone-'+color);}
  };
  body.addEventListener('change',update);
}

const selectedRadio=nodes=>Array.from(nodes).find(node=>node.checked);

export function botSettingsHtml(config,bot,{canCompact=false,canDelete=!PROTECTED_BOT_IDS.has(bot?.id)}={}){
  const avatar=normalizeBotAvatar(bot?.avatar),fish=botFish(bot),color=normalizeBotColor(bot?.color),tools=config.enabledTools||[],percent=botCompactionPercent(config.compactionThreshold);
  return `<div class="bot-settings">
    <fieldset class="bot-settings-section"><legend>Bot</legend>
      <label class="bot-field">名称<input data-bot-name maxlength="80" value="${esc(bot?.name||'')}" placeholder="输入 Bot 名称"></label>
      ${avatarPickerHtml(avatar,fish,color)}
    </fieldset>
    <fieldset class="bot-settings-section"><legend>职责</legend>
      <label class="bot-field">职责提示词<textarea data-bot-instructions maxlength="16000" rows="5" placeholder="这个 Bot 负责什么，怎样工作">${esc(config.instructions||'')}</textarea></label>
      <p>共同人格由全部 Bot 继承，职责提示词只补充工作方式。</p>
      <button type="button" class="btn" data-common-persona>查看／编辑共同人格</button>
    </fieldset>
    <fieldset class="bot-settings-section"><legend>上下文</legend>
      <label class="bot-compaction">自动整理阈值
        <span class="bot-compaction-control"><input type="range" data-bot-compaction min="50" max="90" step="1" value="${percent}"><output data-compaction-output>${percent}%</output></span>
      </label>
      <p class="bot-settings-hint">达到阈值时整理上下文。运行时会考虑模型窗口、工具与安全余量，实际整理可能更早发生。</p>
      <button type="button" class="btn" data-bot-compact ${canCompact?'':'disabled'}>立即整理当前会话</button>
      ${canCompact?'':'<p class="bot-settings-hint">当前 Bot 没有可整理的会话。</p>'}
    </fieldset>
    <fieldset class="bot-settings-section"><legend>管理</legend>
      <div class="bot-management-row"><button type="button" class="btn" data-bot-pin>${bot?.pinned?'取消置顶':'置顶'}</button><button type="button" class="btn" data-bot-delete ${canDelete?'':'disabled'}>删除</button></div>
      <div class="bot-tools">${Object.entries(BOT_TOOL_LABELS).map(([key,label])=>`<label class="bot-tool-choice"><input type="checkbox" data-bot-tool="${key}" ${tools.includes(key)?'checked':''}>${label}</label>`).join('')}</div>
      <p class="bot-settings-hint">${canDelete?'再点一次确认删除。Bot 会从列表移除，工作区文件归档，原始会话数据仍由运行时内部保留。':'默认 Bot 和内置插件 Bot 是受保护身份，不能删除。'}</p>
    </fieldset>
    <p class="bot-settings-error" role="status"></p>
    <button type="button" class="btn dk" data-save-bot>保存</button>
  </div>`;
}

export function bindBotSettings(body,config,bot,actions){
  bindAvatarPreview(body);
  const error=body.querySelector('.bot-settings-error'),common=body.querySelector('[data-common-persona]');
  const compact=body.querySelector('[data-bot-compact]'),pin=body.querySelector('[data-bot-pin]');
  const del=body.querySelector('[data-bot-delete]'),save=body.querySelector('[data-save-bot]');
  const threshold=body.querySelector('[data-bot-compaction]'),thresholdOutput=body.querySelector('[data-compaction-output]');
  const current=()=>({
    name:body.querySelector('[data-bot-name]').value.trim(),
    avatar:normalizeBotAvatar(selectedRadio(body.querySelectorAll('[data-bot-avatar]'))?.dataset?.botAvatar),
    fish:normalizeBotFish(selectedRadio(body.querySelectorAll('[data-bot-fish]'))?.dataset?.botFish),
    color:normalizeBotColor(selectedRadio(body.querySelectorAll('[data-bot-color]'))?.dataset?.botColor),
    instructions:body.querySelector('[data-bot-instructions]').value,
    enabledTools:[...body.querySelectorAll('[data-bot-tool]')].filter(node=>node.checked).map(node=>node.dataset.botTool),
    compactionThreshold:Number(threshold.value)/100
  });
  const dirty=()=>{const value=current();return value.name!==bot.name||value.avatar!==normalizeBotAvatar(bot.avatar)||value.fish!==botFish(bot)||value.color!==normalizeBotColor(bot?.color)||value.instructions!==config.instructions||value.compactionThreshold!==botCompactionPercent(config.compactionThreshold)/100||JSON.stringify(value.enabledTools)!==JSON.stringify(config.enabledTools||[]);};
  common.onclick=()=>actions.openPersona?.();
  body.addEventListener('input',event=>{if(thresholdOutput&&event.target===threshold)thresholdOutput.textContent=`${threshold.value}%`;common.disabled=dirty();common.title=common.disabled?'请先保存当前 Bot 设置':'';});
  if(compact)compact.onclick=async()=>{compact.disabled=true;error.textContent='';try{await actions.compact?.();}catch(e){error.textContent='整理失败：'+e.message;compact.disabled=false;}};
  if(pin)pin.onclick=async()=>{pin.disabled=true;error.textContent='';try{await actions.setPinned?.(!bot.pinned);bot.pinned=!bot.pinned;pin.textContent=bot.pinned?'取消置顶':'置顶';}catch(e){error.textContent='置顶操作失败：'+e.message;}pin.disabled=false;};
  if(del&&!del.disabled){
    let armed=false;
    del.onclick=async()=>{
      if(!armed){armed=true;del.textContent='确认删除';del.classList.add('danger');return;}
      del.disabled=true;error.textContent='';
      try{await actions.deleteBot?.();}
      catch(e){error.textContent='删除失败：'+e.message;del.disabled=false;armed=false;del.textContent='删除';del.classList.remove('danger');}
    };
  }
  save.onclick=async()=>{
    const value=current();save.disabled=true;error.textContent='';
    if(!value.name){error.textContent='名称不能为空';save.disabled=false;return;}
    if(value.name.length>80){error.textContent='名称最多 80 个字';save.disabled=false;return;}
    try{await actions.save?.({...value,memory:config.memory||''});}
    catch(e){error.textContent='保存失败：'+e.message;save.disabled=false;}
  };
  return current;
}

export function openBotSettingsSheet({sheet,config,bot,canCompact=false,actions={}}){
  sheet({title:(bot?.name||'Bot')+' · 设置',body:botSettingsHtml(config,bot,{canCompact,canDelete:!PROTECTED_BOT_IDS.has(bot?.id)}),onMount:body=>bindBotSettings(body,config,bot,actions)});
}

export function botCreationHtml({name='',avatar='circle',fish='goldfish',color='ink'}={}){
  return `<div class="bot-create">
    <label class="bot-field">名称<input data-bot-create-name maxlength="80" value="${esc(name)}" placeholder="输入 Bot 名称"></label>
    <fieldset class="bot-settings-section"><legend>头像</legend>${avatarPickerHtml(normalizeBotAvatar(avatar),normalizeBotFish(fish),normalizeBotColor(color))}</fieldset>
    <p class="bot-settings-error" role="status"></p>
    <button type="button" class="btn dk" data-create-bot>创建</button>
  </div>`;
}

export function promptBotCreation({sheet,closeSheet}){
  return new Promise(resolve=>{
    let done=false;
    const finish=value=>{if(done)return;done=true;closeSheet();resolve(value);};
    sheet({title:'添加 Bot',body:botCreationHtml(),onMount:body=>{
      bindAvatarPreview(body);
      const error=body.querySelector('.bot-settings-error'),button=body.querySelector('[data-create-bot]');
      button.onclick=()=>{
        const name=body.querySelector('[data-bot-create-name]').value.trim();
        const avatar=normalizeBotAvatar(selectedRadio(body.querySelectorAll('[data-bot-avatar]'))?.dataset?.botAvatar);
        if(!name){error.textContent='名称不能为空';return;}
        if(name.length>80){error.textContent='名称最多 80 个字';return;}
        const fish=normalizeBotFish(selectedRadio(body.querySelectorAll('[data-bot-fish]'))?.dataset?.botFish);
        const color=normalizeBotColor(selectedRadio(body.querySelectorAll('[data-bot-color]'))?.dataset?.botColor);
        finish({name,avatar,fish,color});
      };
    },onClose:()=>finish(null)});
  });
}
