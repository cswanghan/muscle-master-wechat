# admin-web

肌松大师 PC 管理后台骨架。Vue 3 + Vite + TypeScript + Element Plus，玉墨主色 `#1E5C4A`。

```bash
npm install
npm run dev      # http://127.0.0.1:5173/health · /catalog · /orders ，代理 /api /actuator → :8080
npm run build
npm run preview  # http://127.0.0.1:4173/health
```

先用 `dev` profile 启动 `server`，否则 Health 页无法显示 UP。
