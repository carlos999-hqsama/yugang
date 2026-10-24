# 参与开发

保持 Android 宿主/公共页面框架与业务插件的边界：核心框架资源由宿主版本化提供，业务页面资源进入独立插件包。不要把私人配置、账号、聊天记录、记忆、安装注册表、用户数据、签名密钥或构建产物写入源码、APK 或插件包。

发布源码必须通过新的导出目录创建，不推送或复制原开发历史。`scripts/release/export-source.py` 接受固定公开模板、明确源码根目录内符合后缀规则的文本，以及逐项登记的二进制；新增二进制资源时先说明用途、来源和许可，再加入显式准入清单与对应测试。

导出器不是逐文件文本白名单，也不能识别任意文本中的秘密。每次发布仍需审阅导出结果并进行密钥/敏感内容扫描，不能把“导出成功”当作无泄漏证明。

根据修改范围运行 [BUILD.md](BUILD.md) 的相关矩阵，不要把编译成功当作 UI、设备或后台行为验收。至少运行：

```sh
python3 -m unittest discover -s scripts/release-tests
node scripts/page-framework-tests/run.mjs
node scripts/interaction-tests/run.mjs
```

修改插件、运行时、Android 或依赖时，追加对应 Node/JVM/Python 测试和真实设备或服务验收；修改依赖后必须重新做干净缓存构建，并更新依赖锁、许可正文、对应源码和实际构建 inventory。发布记录应说明通过项、未验证项和已知阻塞，不得把可选复现性改进描述成已完成的安全验收。

## 需求、回归与审查证据

先列出本次影响的现有行为和明确需求，再核对直接消费者与测试；不要根据函数名、扫描器告警或模型自述直接改代码。回归候选必须有真实可达的入口，区分协议允许的事件和人为构造的无效状态。能做版本对照时，用同一输入与断言验证旧版失败、新版恢复，同时保留合法输入控制；另一位审查者应主动找反证，而不是重复实现者的结论。

页面改动核对原版截图、尺寸和实际操作；构建成功不能替代滚动、选择、反馈顺序、文件预览、关闭或生命周期验收。测试夹具缺字段、权限设置错误或工具不可用应如实记录，不修改产品行为来迎合夹具，也不计为产品漏洞。未验证与不适用必须单独说明。

移动安全参考 [OWASP MASVS/MASTG](https://mas.owasp.org/)，开源工程实践参考 [OpenSSF OSPS Baseline](https://baseline.openssf.org/)。明确采用的版本与适用条目；这些规范不自动构成认证。质量和漏洞扫描是候选来源，不能替代实际用户流程，也不为消除格式提示重排整个工程或增加假消费者。

For reviewers: trace each changed requirement through its real consumers, reproduce reachable failures with the same before/after input and assertions, and keep benign controls. A separate reviewer should try to disprove each finding. UI and lifecycle claims need actual interaction evidence; builds and model summaries are insufficient. Report invalid fixtures, unverified cases, and non-applicable controls separately. Use OWASP mobile/service requirements and the OpenSSF project baseline with explicit versions and scope, without claiming certification.
