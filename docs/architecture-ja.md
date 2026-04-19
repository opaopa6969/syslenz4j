[English version](architecture.md)

# アーキテクチャ

syslenz4j は依存ゼロの Java ライブラリで、JVM 内部情報を syslenz 監視デーモンへブリッジします。本ドキュメントでは、コンポーネント構成・データフロー・プロトコル・Watch API の内部設計を説明します。

---

## コンポーネント概要

```
┌─────────────────────────────────────────────────┐
│              アプリケーション                    │
│                                                 │
│  SyslenzAgent.startServer(9100)                 │
│  SyslenzAgent.registry().gauge(...)             │
│  SyslenzAgent.watch(...).greaterThan(...).register() │
└──────────────┬──────────────────────────────────┘
               │
       ┌───────▼───────┐
       │ SyslenzAgent  │  ← シングルトンファサード
       │               │
       │ MetricRegistry│  ← カスタムメトリクスサプライヤー
       │ WatchRegistry │  ← アラート条件
       │ SyslenzServer │  ← TCP リスナー
       └───────┬───────┘
               │
       ┌───────▼──────────────────────────┐
       │        スナップショットパイプライン │
       │                                  │
       │  JvmCollector.collect()          │
       │     └─ MemoryMXBean              │
       │     └─ GarbageCollectorMXBean[]  │
       │     └─ ThreadMXBean              │
       │     └─ RuntimeMXBean             │
       │     └─ OperatingSystemMXBean     │
       │     └─ ClassLoadingMXBean        │
       │     └─ BufferPoolMXBean[]        │
       │                                  │
       │  MetricRegistry.collect()        │
       │     └─ 登録済みゲージ            │
       │     └─ 登録済みカウンター        │
       │     └─ 登録済みテキスト値        │
       │                                  │
       │  JsonExporter.export(...)        │
       └──────────────┬───────────────────┘
                      │  ProcEntry JSON (1行)
              ┌───────▼───────┐
              │ syslenz デーモン│
              │ (--connect)   │
              └───────────────┘
```

---

## MXBean 収集モデル

すべての JVM データは `java.lang.management` から取得します。ネイティブライブラリ・JVM エージェント・リフレクションハックは不要です。

### メモリ (MemoryMXBean)

`MemoryMXBean` は2つの `MemoryUsage` 構造（ヒープと非ヒープ）を提供します。それぞれ `used`・`committed`・`max` の3値を持ちます。syslenz4j は5つの数値を個別メトリクスとして収集します。

非ヒープには、Metaspace・Code Cache・Compressed Class Space が含まれます。非ヒープの `max` は `-1`（無制限）であることが多いため、エクスポートフィールドから意図的に除外しています。

### ガベージコレクション (GarbageCollectorMXBean[])

GC コレクターは複数存在することがあります（例: G1 Young Generation + G1 Old Generation）。syslenz4j はすべての Bean を反復処理し、`gc.getName()` から英数字以外を `_` に置換して安全な名前を生成し、コレクター別のメトリクスを作成します。

集計値（`gc_total_count`・`gc_total_time`）は全コレクターの合計です。

### スレッド (ThreadMXBean)

スナップショットごとに `findDeadlockedThreads()` が呼ばれます。デッドロックがなければ `null`（→ `0` にマップ）、デッドロックがあればスレッド ID の配列が返ります。この操作はやや重いため、スナップショット頻度が非常に高い（1秒未満）場合は、デッドロック検出を低頻度パスに分離することを検討してください。

### OS (OperatingSystemMXBean / com.sun.management)

標準の `OperatingSystemMXBean` は `getSystemLoadAverage()` と `getAvailableProcessors()` を提供します。プロセスレベルの CPU メトリクス（`getProcessCpuLoad()`・`getProcessCpuTime()`）には `com.sun.management.OperatingSystemMXBean` 拡張が必要です。これは Oracle JDK と OpenJDK では利用可能ですが、Java SE 仕様では保証されていません。syslenz4j は `instanceof` チェックを使用し、拡張を提供しない JVM ではこれらのメトリクスをスキップします。

### バッファプール (BufferPoolMXBean[])

`ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)` でダイレクトバッファとメモリマップドバッファプールを列挙します（通常は `direct` と `mapped` の2プール）。各プールは `memoryUsed`・`totalCapacity`・`count` を報告します。

---

## JsonExporter と ProcEntry フォーマット

`JsonExporter` は文字列連結のみで syslenz ProcEntry JSON を生成します（外部 JSON ライブラリ不使用）。これにより依存ゼロを維持できますが、サポートするメトリクス値型は `Bytes`・`Integer`・`Float`・`Duration`・`Text` の5種に限定されます。

各メトリクスフィールドのシリアライズ形式:

```json
{
  "name": "heap_used",
  "value": {"Bytes": 524288000},
  "unit": null,
  "description": "Current heap memory usage"
}
```

`value` フィールドはタグ付きユニオン形式です。JSON のキーが型名になっています。これは syslenz の Rust 側の enum シリアライズ（serde の `externally tagged` 形式）と対応しています。

スナップショット全体のラッパー:

```json
{
  "source": "jvm/pid-<pid>",
  "fields": [ ... ]
}
```

`source` フィールドはスナップショットごとに `ProcessHandle.current().pid()`（Java 9+）で解決されます。

---

## プロトコル

```
TCP 接続（ポート P）
  クライアント → "SNAPSHOT\n"
  サーバー → "<1行 JSON>\n"
  （繰り返しまたはクローズ）
```

- サーバーは `BufferedReader.readLine()` で行を読み取り、末尾の空白を除去し、大文字小文字を区別せずに `SNAPSHOT` を照合します。
- 未知のコマンドはサイレント無視（前方互換性: 将来のプロトコル版で新コマンドを追加可能）。
- レスポンスの JSON は送信前に内部改行を除去し、`\n` 区切りを明確に保ちます。
- 接続タイムアウト: 30秒間の非アクティブでソケットを閉じます。

### サーバースレッドモデル

`SyslenzServer` はブロッキング I/O を使用する単一の daemon スレッドで動作します:

```
acceptLoop（daemon スレッド）
  └─ serverSocket.accept()      ← 1秒ごとに running フラグを確認
       └─ handleClient(socket)  ← そのソケットのすべてのコマンドを処理
            └─ reader.readLine() ループ（EOF またはタイムアウトまで）
```

接続は逐次処理されます。数秒ごとにポーリングする監視ツールには十分です。複数の同時接続が必要な場合は、接続ごとのスレッドプールが必要ですが、v1.1.0 では未実装です。

---

## Watch API 内部設計

### 登録

`SyslenzAgent.watch(metricName)` は `WatchCondition` を構築して返します。ビルダーの `.register()` を呼ぶと `WatchRegistry` に追加されます。`register()` 後のビルダーはすべての設定を不変として保持します。

```
SyslenzAgent.watch("heap_used")
    .greaterThan(1_073_741_824L)
    .severity(Severity.WARNING)
    .cooldown(60_000)
    .onFire(callback)
    .register()
        │
        └─► WatchRegistry.add(WatchCondition)
                └─► entries: CopyOnWriteArrayList<WatchEntry>
```

### 評価

`WatchRegistry.evaluate(Map<String, Double>)` が現在のメトリクスのスナップショットで呼ばれます。登録済みの各条件に対して:

1. マップからメトリクス値を取得。
2. プライマリ演算子を評価。
3. 複合条件が存在する場合（`.and()`）、セカンダリ演算子を評価。
4. **ステートマシン**（`WatchEntry` ごと）:
   - `wasFiring=false` + 条件成立 + クールダウン経過 → `onFire` を発火、`wasFiring=true` にセット
   - `wasFiring=true` + 条件が成立しなくなった → `onResolve` を発火、`wasFiring=false` にセット
   - その他の組み合わせ: コールバック呼び出しなし

クールダウンは、メトリクスが閾値付近で揺れている場合に `onFire` が繰り返し呼ばれるのを防ぎます。

```
WatchEntry ごとのステートマシン:

  NOT_FIRING ──[成立 & クールダウン経過]──► FIRING
     ▲                                        │
     └────────────[成立しなくなった]──────────┘
```

### 既知の問題: evaluate が接続されていない

`WatchRegistry.evaluate()` は定義されていますが、**`SyslenzServer.collectSnapshot()` から呼ばれていません**。そのため、v1.1.0 では Watch コールバックは自動発火しません。修正には、スナップショット収集後にメトリクス値を `Map<String, Double>` として `WatchRegistry.evaluate()` に渡す必要があります。[GitHub issue #3](https://github.com/opaopa6969/syslenz4j/issues/3) で追跡中。

### 既知の問題: CompoundCondition の fluent チェーン

`CompoundCondition.greaterThan()` が `null` を返す（`WatchCondition` ではない）ため、`.and("x").greaterThan(v)` 以降のチェーンで NPE が発生します。これは v1.1.0 のドキュメント化された動作です。[GitHub issue #3](https://github.com/opaopa6969/syslenz4j/issues/3) で追跡中。

---

## JVM 組込モデル

syslenz4j は、監視対象の JVM 内部で動作するように設計されています。別エージェントやサイドカーとしての動作ではありません。

**利点:**
- JMX リモート設定なしですべての MXBean にアクセス可能
- インプロセスによるゼロレイテンシなメトリクス収集
- 管理・再起動が必要な別プロセスなし

**トレードオフ:**
- TCP サーバーは JVM のスレッドプールとメモリを共有するため、JVM がクラッシュするとサーバーも応答を停止する
- メトリクス収集自体による GC 圧力（プリミティブフレンドリーなコードパスで最小化）
- メモリオーバーヘッド: 登録済み条件とメトリクス履歴で約 50〜200 KB

### デプロイモード

| モード | ユースケース | 方法 |
|-------|----------|-----|
| サーバーモード | 長期稼働サービス | `SyslenzAgent.startServer(port)` |
| プラグインモード | バッチジョブ・ワンショットツール | `SyslenzAgent.printSnapshot()` |
| Spring Boot | マネージドライフサイクル | [getting-started.md](getting-started.md) 参照 |

---

## スレッドセーフティ

| コンポーネント | スレッドセーフティ保証 |
|------------|-------------------|
| `SyslenzAgent` | すべての static メソッドはスレッドセーフ。`startServer()` はダブルチェックロッキングにより冪等 |
| `MetricRegistry` | `ConcurrentHashMap` — 登録と `collect()` は任意スレッドから安全 |
| `WatchRegistry` | `CopyOnWriteArrayList` — `add()` と `evaluate()` は並行スレッドから安全 |
| `SyslenzServer` | 単一 daemon スレッド。`start()`/`stop()` は任意スレッドから安全に呼び出し可能 |
| サプライヤーコールバック | ライブラリはサプライヤー呼び出しを同期しません — サプライヤー自身がスレッドセーフである必要があります |
