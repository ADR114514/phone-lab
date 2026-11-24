# tools/

| 脚本 | 用途 |
|---|---|
| `pull-data.ps1` / `pull-data.sh` | 通过 adb 把手机 App 私有目录里的实验数据拉到 `data/raw/<app>/`（需 debuggable 包） |
| `verify-apk.ps1` | 校验 APK 的 minSdk、目标 SDK、ABI、权限清单与 v2+ 签名 |
| `check-docs.py` | 校验全部 Markdown 相对链接（CI 中执行，防止 GitHub 首页出现死链） |
| `device-smoke-test.ps1` | 真机冒烟：安装三 APK、启动抓屏、崩溃检查、拉取实验数据 |
| `push-to-github.ps1` | 一条命令改提交作者并推送到 GitHub（token 不落盘） |
| `split-repos.ps1` | 可选：把单仓拆成三个独立仓库（保留子树历史） |

```bash
# 例：拉取三个项目的实验数据
powershell -File tools/pull-data.ps1 -Package com.mobilelab.powertrace -AppTag power
./tools/pull-data.sh com.mobilelab.chargeaudit topic07
./tools/pull-data.sh com.mobilelab.vibrodiag vibro
```
