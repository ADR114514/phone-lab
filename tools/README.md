# tools/

| 脚本 | 用途 |
|---|---|
| `pull-data.ps1` / `pull-data.sh` | 通过 adb 把手机 App 私有目录里的实验数据拉到 `data/raw/<app>/`（需 debuggable 包） |
| `verify-apk.ps1` | 校验 APK 的 minSdk、目标 SDK、ABI、权限清单 |

```bash
# 例：把三台实验数据拉下来
powershell -File tools/pull-data.ps1 -Package com.mobilelab.powertrace -AppTag power
./tools/pull-data.sh com.mobilelab.signalmap wifi
./tools/pull-data.sh com.mobilelab.vibrodiag vibro
```
