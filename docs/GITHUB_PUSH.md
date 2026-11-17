# GitHub 推送指南（账号拿到后 5 分钟完成）

> 仓库已在本地 `phone-lab/`、分支 `main`、14 个分阶段提交、工作区干净。
> 下面任选一种方式，把作者身份改成你自己的 GitHub 身份后推送。

## 方式 A0（最省事）：给我一个 PAT，我全自动发布

```powershell
powershell -File tools/publish-github.ps1 -Token "<github_pat_...>" -RepoName phone-lab
```

脚本会：校验 token 身份 → 仓库不存在则通过 API 创建 → 把 17 个提交作者改成你的 GitHub 身份 →
推送 main 分支 → 打印仓库地址。token 只在内存中使用，不写入 `.git/config` 或任何文件；
推完请到 https://github.com/settings/tokens 撤销。

## 方式 A（手动指定仓库地址）：一条命令推送

1. 在 GitHub 网页 **New repository** 创建空仓库（不要勾选 README/.gitignore），名字建议 `phone-lab`。
2. 生成 **fine-grained PAT**：Settings → Developer settings → Personal access tokens → Fine-grained tokens →
   只选目标仓库 → Permissions: **Contents = Read and write**。
3. 在本机执行（把尖括号替换成你的信息）：

```powershell
powershell -File tools/push-to-github.ps1 `
  -RepoUrl https://github.com/<你的用户名>/phone-lab.git `
  -AuthorName "<提交显示名>" -AuthorEmail "<GitHub 邮箱>" `
  -Token "<github_pat_xxx>"
```

脚本会：① 把 14 个提交的作者/提交者改成你（保留提交信息与顺序）→ ② 配置 origin →
③ 用 token 推送并清除 token（不写入 .git/config）。
推完建议立刻到 GitHub 撤销这个 PAT。

## 方式 B：手动执行（不用给 token）

```bash
cd phone-lab

# 1) 改提交作者（可选，但面试溯源建议用你自己的身份）
git filter-branch -f --env-filter '
  export GIT_AUTHOR_NAME="你的名字"
  export GIT_AUTHOR_EMAIL="你的邮箱"
  export GIT_COMMITTER_NAME="你的名字"
  export GIT_COMMITTER_EMAIL="你的邮箱"
' -- --all

# 2) 关联远程并推送（浏览器登录后 git 会用凭据管理器）
git remote add origin https://github.com/<你的用户名>/phone-lab.git
git push -u origin main
```

## 方式 C：拆成三个独立仓库（可选）

若课程要求每个课题一个仓库：

```powershell
powershell -File tools/split-repos.ps1 -OutDir E:\phone-function-repos
```

会在 `E:\phone-function-repos` 下生成 `phone-lab-powertrace` / `phone-lab-signalmap` /
`phone-lab-vibrodiag` 三个仓库（各自保留该 App 的提交历史）。注意 `core/` 是本地
Gradle 依赖，独立仓库需要把 `core/` 一起发布或用 subtree 合并进去（脚本末尾有提示）。

## 推送后检查清单

- [ ] 仓库首页 README 正常渲染，三个 App 说明、构建命令、数据字典链接可点
- [ ] `docs/DATA_DICTIONARY.md`、`docs/report/` 三份模板、`analysis/` 都在
- [ ] `Actions` 页出现 `android-ci` 工作流（需要仓库允许 Actions；首次构建约 3–5 分钟）
- [ ] 给仓库加 Topics：`android` `kotlin` `jetpack-compose` `signal-processing` `fft` `wifi` `sensors`
- [ ] 真机数据提交后，按 `data/raw/README.md` 写 `NOTES.md`，WAV 用 Git LFS（`git lfs install` 一次）

## 真实数据提交（第二阶段）

```bash
# 1) 手机导出目录放到 data/raw/<app>/<session>/
# 2) 文本数据直接提交，WAV/MP4 走 LFS
git lfs install
git add data/raw/power/<session>
git commit -m "data(power): 2026-11-18 批次 24 组实测数据"
git push
```