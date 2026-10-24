/** 首个插件空态：只提供一个入口，不管理教程状态。 */
export function mount(root) {
  root.innerHTML = `
<div class="plugin-guide-empty">
  <div class="plugin-guide-icon" aria-hidden="true"></div>
  <p>还没有创建插件，快来试试吧</p>
  <button type="button" class="btn dk" data-plugin-create>创建插件</button>
</div>`;
  root.addEventListener('click', (event) => {
    if (event.target.closest('[data-plugin-create]')) window.__pd_go?.('harness?plugin=create');
  });
}

export function onShow() {}
export function onHide() {}
export function render() {}
