# MCP 化設計 — syslenz4j

## 1. namespace と種別

- **namespace**: `syslenz4j`
- **種別**: `skill-only`（サーバを持たない）

syslenz4j は Java ライブラリであり、全機能が組込先 JVM プロセス内で動作する。MCP バックエンドとして独立プロセスを立てても、そのプロセス自身の JVM メトリクスしか取れず、本来の価値（監視対象 JVM への組込）に合わない。したがって MCP サーバは立てず、組込手順・メトリクス一覧・Watch API・プロトコル仕様を **skill / resource として配る**。

Phase 1（`docs/mcp/survey.json`）の判定を踏襲。割当表 `MCPIFY-phase2-plan.md` 行 #69（namespace=`syslenz4j`, port=—）。

## 2. tools 表

**tool なし。** skill-only であるため MCP tool を提供しない。

## 3. resources 表

skill-only ではバックエンドサーバを持たないため、`<ns>://spec` `<ns>://guide` 等（通常バックエンドが Streamable HTTP で応答する resource）は直接提供しない。代わりに、**skill（`embed-syslenz4j`）の本文**を通じて以下の参照データを配る。エージェントは skill を読んで組込コードを生成し、syslenz 本体（`syslenz` namespace、catalog に既存）経由でメトリクスを観測する。

| データ | skill 内での参照元 | 内容 |
|--------|---------------------|------|
| 能力仕様（spec 相当） | `spec/SPEC.md` §2 | メトリクス一覧・型・ソース MXBean・Watch API |
| 組込手順（guide 相当） | `docs/getting-started.md`, `README-ja.md` | Maven/Gradle 依存追加・`startServer`・Spring Boot 統合 |
| メトリクス一覧 | `README-ja.md` §収集メトリクス一覧 | 30 種以上の JVM メトリクス定義 |
| Watch API リファレンス | `docs/watch-api.md` | 演算子・Severity・コールバック・複合条件 |
| TCP プロトコル仕様 | `spec/SPEC.md` §6.4 | `SNAPSHOT` コマンド・Snapshot JSON 構造 |

## 4. prompts / skills

### skill: `embed-syslenz4j`

| 項目 | 値 |
|------|-----|
| name | `embed-syslenz4j` |
| namespace | `syslenz4j` |
| locality | `global`（Java ライブラリなので汎用） |
| applies_when | `repo.has_file: pom.xml` |
| requires | `tools: []`, `resources: []`（組込先アプリのビルドツールのみ） |
| min_role | `viewer` |
| export | `allowed` |
| version | 1 |

**用途**: Java アプリ（Maven/Gradle）に syslenz4j を組込んで JVM 監視を設定する手順。依存追加・サーバ起動・カスタムメトリクス登録・Watch 条件設定・syslenz 本体からの収集までを案内する。

このリポジトリでしか意味がない手順はない（ライブラリの組込は汎用手順）ため、配布形態は **方法 C**（volta-mcp リポジトリ `docs/skills/syslenz4j__embed-syslenz4j/SKILL.md` に commit & push）を用いる。

## 5. 組み合わせ例

1. **syslenz4j 組込 → syslenz 収集 → catalog 発見**
   - エージェントが `skill__resolve(goal="Java アプリの JVM 監視を設定したい")` で `embed-syslenz4j` を取得
   - skill 本文に従い Java アプリに `SyslenzAgent.startServer(9100)` を追加
   - アプリ起動後、`syslenz --connect localhost:9100` で syslenz 本体がメトリクス収集
   - `catalog__describe_service(id="syslenz")` で syslenz の能力（50+ ソース・27 診断）を発見
   - syslenz の `syslenz__snapshot` / `syslenz__view` で JVM メトリクスを観測

2. **Watch アラート → syslenz TUI 表示**
   - skill に従い `SyslenzAgent.watch("heap_used").greaterThan(1_073_741_824L).severity(Severity.CRITICAL).onFire(cb).register()` を設定
   - 発火中の Watch はスナップショット JSON の `alerts` 配列に含まれる
   - `syslenz --connect` の TUI ステータスバー（`[!!1 CRIT]`）とサイドバーの重要度バッジに表示
   - `syslenz__diagnostics` でシステム全体の診断と合わせて確認

3. **Spring Boot 統合 + Micrometer ブリッジ**
   - skill の Spring Boot 節に従い `SyslenzLifecycle`（`SmartLifecycle`）を追加
   - Micrometer のゲージを `SyslenzAgent.registry().gauge(...)` でブリッジ
   - `syslenz__history` でメトリクス推移を観測、`syslenz__set_alerts` で外部アラートも併用

## 6. 依存と協調

| 方向 | 相手 repo | 能力 | 現状 | 協調の要否 |
|------|----------|------|------|-----------|
| provides_to | `syslenz` (tools-workspace/syslenz) | TCP `SNAPSHOT` プロトコル（syslenz `--connect` が消費するサーバ側を実装） | syslenz は catalog に既存（`id=syslenz`, prod port=3009, systemd runtime） | プロトコル互換性は既に確立済み（v1.1.1）。追加の API 合意は不要。暫定で進める |

issue-hub での協調: syslenz4j 側から syslenz への依存関係は「TCP プロトコル互換」のみであり、これは v1.1.1 時点で既に動作確認済み（SPEC §6.4 にプロトコル仕様あり）。syslenz 側の `--connect` 実装がこのプロトコルを消費する形で安定しているため、新たな合意は不要。念のため issue-hub に provides_to の宣言のみ残す（返答を待たず暫定で進める）。

## 7. 非対応にした候補

Phase 1 からの差分なし。全候補が skill-only の範囲に収まる。

- **MCP サーバ化（library-serve）**: 非対応。独立 JVM プロセスのメトリクスしか取れず、本来の価値（組込先 JVM の監視）に合わない。
- **HTTP API / healthz / volta.service.json / systemd unit**: 非対応。サーバを持たないため不要。
- **gateway ルート追加**: 非対応。ホストされないサービスにルートは不要。

## 8. 参加方法

**skill-only のため、volta への「参加」は skill 配信のみ。**

- **manifest**: 既存の `syslenz4j` catalog エントリ（`id=syslenz4j`, `environments={}`）はそのまま。`mcp.enabled` を立てない（サーバがないため）。
- **ポート**: なし（割当表 port=—）。
- **ホスト**: なし（ホストされない）。
- **runtime**: なし。
- **auth**: なし。

skill の配信方法: volta-mcp リポジトリ `docs/skills/syslenz4j__embed-syslenz4j/SKILL.md` に frontmatter `volta.namespace: syslenz4j` で commit & push（SPEC-skills-over-mcp §7 方法 C）。ファサードが `skill__list` / `skill__resolve` / `volta://skills/syslenz4j/embed-syslenz4j` で配信する。

## 9. テスト方針

skill-only では MCP サーバの e2e テスト（healthz → tools/list → tool 実行）は不要。代わりに:

1. **skill 配信確認**: `skill__list(namespace="syslenz4j")` で `embed-syslenz4j` が一覧に現れること。
2. **skill resolve 確認**: `skill__resolve(goal="Java アプリに JVM 監視を組込む")` で `embed-syslenz4j` が候補に現れること。
3. **skill export 確認**: `skill__export(name="syslenz4j__embed-syslenz4j")` で SKILL.md 全文が取得できること。
4. **既存テストの通過**: `mvn test`（JUnit 5）が通ること。これはライブラリ本体の品質保証であり、MCP 化とは独立。

```bash
# テスト実行
cd /home/opa/work/syslenz4j && mvn test -q
```
