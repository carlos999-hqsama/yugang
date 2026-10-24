# 共享页面基础库 v1

页面包无HTML，宿主统一创建页面并加载公共资源。入口必须 `export default definePage(...)`，框架路径固定为 `/framework/v1/`。

## 最小页面

将以下代码写入 resources 的 main.js；manifest增加frameworkVersion:1，view为 `{type:"page",entry:"main.js",styles:[]}`。

```js
import {definePage} from '/framework/v1/page.js';
import {Button, Card, Text} from '/framework/v1/js/components.js';
export default definePage({
  data: {count: 0},
  render({root, data, setData}) {
    root.replaceChildren(Card({
      title: '我的计数器',
      children: [
        Text(`当前：${data.count}`),
        Button({text: '加一', onClick: () => setData({count: data.count + 1})})
      ]
    }));
  }
});
```

`data` 是页面初始对象，`setData(patch)` 合并数据并合并一次渲染调度。页面级数据不等于持久化；需要跨重启保留时使用插件状态或明确的业务存储，不把账号和秘密写进去。

页面生命周期使用 init、mount 或 render、start、onShow、onHide、stop、onResize、onUnload、back。传入上下文提供 root、data、setData、navigate(id)、toast(text)。通常只定义 data/render 和需要的钩子；复杂页面可以 mount 原业务视图。返回钩子处理了内部返回就返回true，否则返回false交回宿主。

公共组件为 Button、Card、List、Text、Input，使用框架默认主题；不要复制组件实现和native桥。页面专有CSS只放业务布局，style列表中填写包内路径。若组件参数不确定，先用上述可运行模板，不猜未公开组件。

原生能力统一经 `/framework/v1/js/native.js` 的 native 对象调用，具体能力仍以宿主契约为准。插件页面不会得到Token/Cookie。

远程数据用同一文件里的 `native.fetch`（返回状态、类型与正文）、`native.fetchJson`（直接解析 JSON）、`native.fetchImage`（远程图片转成 data URL，可直接放进 `img.src`）和 `native.fetchStream`（逐块回调并返回可取消句柄）。宿主代发请求并限制超时、大小与方法；页面可按浏览器跨域规则直接使用 HTTPS 请求、图片和媒体；宿主代发用于跨域或 HTTP。远程脚本不允许执行。参数见 [网络请求](network-api.md)。跨插件导航是 `navigate(id)`，不能跳旧宿主业务页。插件停止或更新后旧接口失效，不保留旧请求句柄；页面隐藏时流式请求也会释放。

## 完整最小包结构

```json
{
  "manifest": {"apiVersion":1,"frameworkVersion":1,"id":"my-counter","title":"计数器","version":"1.0.0","kind":"native","capabilities":[]},
  "view":{"type":"page","entry":"main.js","styles":[]},
  "resources":{"main.js":"将上方JavaScript作为此字段的字符串值"}
}
```

使用程序序列化JSON文件，不手工转义长脚本。迭代时通过 phone_plugins_preview(packagePath=...,op="open") 验证未安装草稿的实际页面与交互。需要正式交付时再 phone_plugins_install(packagePath=...)，核对当前 revision、页面入口、操作、返回、切页和重启保存；分别报告草稿预览与安装版的实际验证状态。

宿主模型和翻译用 `native.model` / `native.translate`，受限后台任务用 `native.background`，通知用 `native.notify`；声明对应能力并读取 [服务契约](service-api.md)，不猜参数。
