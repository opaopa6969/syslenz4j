# MCP 化状況 — syslenz4j

## 概要

- **判定**: `skill-only`（Phase 1 survey.json で決定）
- **namespace**: `syslenz4j`
- **port**: なし（サーバを持たない）
- **割当表**: MCPIFY-phase2-plan.md 行 #69

## 完了

| 項目 | 状態 | 備考 |
|------|------|------|
| Phase 1 調査（survey.json / SURVEY.md） | 完了 | 2026-08-22 |
| docs/mcp/DESIGN.md | 完了 | skill-only 設計 |
| SKILL.md 作成（volta-mcp docs/skills/syslenz4j__embed-syslenz4j/） | 完了 | frontmatter volta.namespace: syslenz4j, version: 1, locality: global |
| README MCP 節追加（README.md / README-ja.md） | 完了 | |
| issue-hub 協調（provides_to 宣言） | 完了 | プロトコル互換は既存確立済み。返答不要で暫定進行 |
| commit & push（syslenz4j repo） | 完了 | |
| commit & push（volta-mcp repo） | 完了 | |
| skill 配信確認（skill__list） | 完了 | |

## サーバ・デプロイ関連（不要）

skill-only であるため、以下は全て不要:

- MCP サーバ実装（Streamable HTTP `/mcp`）
- `/healthz` エンドポイント
- `PORT` 環境変数・`0.0.0.0` bind
- `content-encoding: identity`
- `volta.service.json`（既存 catalog エントリ `id=syslenz4j` は environments 空 のまま）
- systemd unit / run.sh
- `volta__svc_add`（manifest 追加）
- `volta__gateway_routes_diff` / `gateway_routes_apply`
- `catalog__audit_backend`
- `https://<hostname>/healthz` 確認
- `catalog__backend_status` での namespace ready 確認

## 協調（issue-hub）

### syslenz への provides_to

- **関係**: syslenz4j が TCP `SNAPSHOT` プロトコルのサーバ側を実装し、syslenz 本体の `--connect` が消費する
- **現状**: プロトコル互換性は v1.1.1 時点で確立済み（SPEC §6.4 に仕様あり、syslenz 側の `--connect` 実装が安定）
- **合意の要否**: 不要。追加の API 合意は発生しない
- **issue-hub**: https://github.com/opaopa6969/issue-hub/issues/327 に宣言を登録。返答を待たず暫定で進める

## 未決事項

Phase 1 の open_questions に対する対応:

1. **skill の locality**: `global` に設定。ライブラリの組込は汎用手順であり、syslenz4j 固有のホスト環境に依存しないため。持ち主の了承済み（DEPLOY=yes 指示）。
2. **resource の自動生成 vs 手動メンテ**: 手動メンテ。SKILL.md にメトリクス一覧・プロトコル仕様を直接記述。SPEC.md が真実源であり、バージョンアップ時に SKILL.md を更新する運用とする。

## 持ち主への質問

なし。DEPLOY=yes・PUSH=yes の指示に従い、全作業を完了した。
