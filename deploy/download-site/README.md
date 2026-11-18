# 下载站部署说明（宝塔面板 + Nginx）

本目录是同学下载站的源文件，实际运行在服务器上：

- 站点地址：http://189.105.67.241/
- 服务器：Debian 12 + 宝塔面板（BT-Panel 10.0.2）+ Nginx
- 站点根目录：/www/wwwroot/phone-lab-download
- Nginx 配置：/www/server/panel/vhost/nginx/phone-lab-download.conf
  （server_name 189.105.67.241，listen 80，BT 面板的 vhost 目录内，方便面板统一管理）
- 面板地址：https://189.105.67.241:8889/rainy （账号密码由项目负责人保管，不写入仓库）

## 目录结构

~~~
phone-lab-download/
├── index.html            # 下载站首页（三款 App + 手册入口）
├── apk/                  # 三款 debug APK（不入库，部署时上传）
├── docs/                 # manual.{md,html,pdf,docx}
└── assets/               # 界面截图
~~~

## 更新流程

1. 本地修改 index.html / docs / assets；
2. 通过 SFTP 上传到上述服务器目录（保持 www:www 属主、755 权限）；
3. 无需重启 Nginx（静态文件），Nginx 配置改动时执行
   /www/server/nginx/sbin/nginx -t && /www/server/nginx/sbin/nginx -s reload。

## 校验

~~~bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/ -H 'Host: 189.105.67.241'
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/apk/PowerTrace-1.0.0-arm64-v8a-debug.apk -H 'Host: 189.105.67.241'
# 期望均为 200
~~~

## 说明

- 站点只提供静态文件下载，不接收上传、不收集个人信息；
- APK 下载响应带 Content-Disposition: attachment，便于手机浏览器保存；
- 安全组/防火墙需放行 80 端口（本服务器已放行）。
