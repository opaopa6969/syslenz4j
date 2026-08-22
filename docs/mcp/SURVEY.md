# MCP 化調査 — syslenz4j

## 概要

syslenz4j は、JVM 内部のメトリクス（ヒープ、GC、スレッド、CPU、クラスローディング、バッファプール等）を `java.lang.management` MXBean 経由で収集し、`syslenz --connect` 互換の TCP ポートで公開する Java ライブラリ。組込先 JVM プロセス内で動作する。Maven Central に `org.unlaxer.infra:syslenz4j:1.1.1` として公開済み。外部依存ゼロ。

3 つの動作モードを持つ:

| モード | エントリポイント | 用途 |
|--------|-----------------|------|
| Server モード | `SyslenzAgent.startServer(port)` | `syslenz --connect` による継続的ポーリング |
| Stdout モード | `SyslenzAgent.printSnapshot()` | 1 回限りの JSON 出力（プラグイン） |
| Watch API | `SyslenzAgent.watch(...).register()` | アプリ内閾値アラート + コールバック |

## 判定と理由

**判定: `skill-only`**

syslenz4j はライブラリであり、全機能が組込先 JVM プロセス内で動作する。エージェントが MCP tool として外部から呼べる操作は存在しない。理由:

1. **プロセス内ライブラリ**: メトリクス収集・Watch 評価・TCP サーバのいずれも、組込先アプリの JVM 内で動く。MCP バックエンドとして独立プロセスを立てても、そのプロセス自身の JVM メトリクスしか取れず、他の Java アプリのメトリクスは取れない。
2. **既存の入口は TCP のみ**: HTTP API、CLI、MCP はいずれも存在しない。`healthz` もない（volta のバックエンド規約を満たさない）。
3. **役割分担は確立済み**: syslenz 本体（catalog に `id=syslenz` で登録済み、prod port=3009）が `--connect` でメトリクスを収集する役割を担う。syslenz4j は「JVM 側のデータソース」であり、独立した MCP エントリポイントを作る必要はない。
4. **知識としての価値はある**: 組込手順、メトリクス一覧、Watch API の使い方、プロトコル仕様は、エージェントが Java アプリに syslenz4j を組込む際の設計支援に使える。これらは skill / resource として配る。

## 公開候補

| kind | name | io / 説明 | 副作用 | 長時間 | 対応ソース |
|------|------|-----------|--------|--------|-----------|
| resource | `spec` | 能力の機械可読仕様（メトリクス一覧・型・ソース MXBean） | read | no | `spec/SPEC.md` §2 |
| resource | `guide` | 組込手順と使い方 | read | no | `docs/getting-started.md` |
| resource | `metrics` | 収集メトリクス一覧（30 種以上の JVM メトリクス定義） | read | no | `README.md` §Collected Metrics, `SPEC.md` §2.1.1 |
| resource | `watch-api` | Watch API リファレンス（演算子・Severity・コールバック） | read | no | `docs/watch-api.md` |
| resource | `protocol` | TCP プロトコル仕様（SNAPSHOT コマンド・Snapshot JSON 構造） | read | no | `spec/SPEC.md` §6.4 |
| skill | `embed-syslenz4j` | Java アプリに syslenz4j を組込んで JVM 監視を設定する手順 | — | — | `getting-started.md` + `README-ja.md` |

## 組み合わせ例

1. **syslenz4j 組込 → syslenz 収集 → catalog 発見**: syslenz4j を組込んだ Java アプリが TCP:9100 でメトリクスを公開 → `syslenz --connect localhost:9100` で syslenz 本体が収集 → `catalog__describe_service(syslenz)` で syslenz の能力を発見。syslenz4j 自体は tool を持たないが、syslenz エコシステムのデータソースとして機能する。
2. **エージェントによる組込コード生成**: エージェントが `syslenz4j://guide` と `syslenz4j://watch-api` を読み、Java アプリに SyslenzLifecycle（Spring Boot）を生成 → デプロイ → syslenz 本体経由でアラートを観測。

## 依存と協調

| 方向 | 相手 repo | 能力 | 現状 |
|------|----------|------|------|
| provides_to | syslenz | TCP `SNAPSHOT` プロトコル（syslenz `--connect` が消費するサーバ側を実装） | syslenz は catalog に既存（`id=syslenz`, prod port=3009, systemd runtime）。syslenz4j は catalog に `id=syslenz4j` として登録されているが `environments` が空（ホストされていない） |

協調に必要な追加作業はなし。syslenz4j はライブラリとして配布されるため、volta 上にホストする必要はない。

## ライブラリのサーバ化

**不要**（`library_serve.needed = false`）。

syslenz4j を独立した MCP サーバプロセスとして立てても、その JVM 自体のメトリクスしか取れない。本来の価値は「監視対象 JVM に組込んで使う」ことにあり、独立プロセス化は目的に合わない。volta 参加に必要な新規実装（healthz、PORT 環境変数、`volta.service.json`、systemd unit、MCP サーバ）はいずれも不要。

## リスク

- **TCP 認証なし**: `SyslenzServer` には認証がない（README のセキュリティ注意書きの通り）。MCP 経由で公開する場合は認証層が必要だが、そもそも MCP 化しないため該当しない。
- **機能重複なし**: syslenz 本体は `/proc`・`/sys`・ネットワーク系。syslenz4j は JVM 系。補完関係であり重複しない。
- **秘密情報**: なし。このリポジトリにトークン・鍵は含まれない。

## 持ち主への質問

1. 組込手順を skill として配る場合、`locality` を `global`（汎用）にするか `service`（syslenz4j 固定）にするか。ライブラリなので `global` が自然だが確認したい。
2. resource（メトリクス一覧・プロトコル仕様）を配る場合、静的 Markdown から自動生成するか手動メンテするか。
