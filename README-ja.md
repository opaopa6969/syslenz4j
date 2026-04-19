[English version](README.md)

# syslenz4j

[syslenz](https://github.com/opaopa6969/syslenz) 向け Java バインディング — 依存ゼロの JVM メトリクスエクスポーター + Watch API。

MXBean 経由で **JVM の内部情報を収集**し、`syslenz --connect` 互換の TCP ポートで公開します。カスタムアプリケーションメトリクスと閾値ベースのアラート機能を標準搭載。

---

## 目次

- [クイックスタート](#クイックスタート)
- [インストール](#インストール)
- [コアコンセプト](#コアコンセプト)
- [収集メトリクス一覧](#収集メトリクス一覧)
- [Watch API](#watch-api)
- [プロトコル](#プロトコル)
- [Spring Boot 統合](#spring-boot-統合)
- [セキュリティ上の注意](#セキュリティ上の注意)
- [動作要件](#動作要件)

---

## クイックスタート

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;
import org.unlaxer.infra.syslenz4j.Severity;

public class MyApp {
    public static void main(String[] args) {
        // TCP サーバー起動（daemon スレッド、ノンブロッキング）
        SyslenzAgent.startServer(9100);

        // カスタムメトリクス登録
        SyslenzAgent.registry().gauge("queue_size", () -> myQueue.size());
        SyslenzAgent.registry().counter("requests_total", requestCounter::get);

        // ヒープ使用率 80% 超でアラート
        SyslenzAgent.watch("heap_used_pct")
            .greaterThan(80.0)
            .severity(Severity.WARNING)
            .cooldown(30_000)
            .onFire(event -> log.warn("Heap high: {}", event.message()))
            .onResolve(event -> log.info("Heap recovered"))
            .register();

        // ... アプリケーションのコード ...
    }
}
```

ターミナルから接続:

```bash
syslenz --connect localhost:9100
```

---

## インストール

### Maven

```xml
<dependency>
    <groupId>org.unlaxer.infra</groupId>
    <artifactId>syslenz4j</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.unlaxer.infra:syslenz4j:1.1.0")
```

### ソースからビルド

```bash
git clone https://github.com/opaopa6969/syslenz4j.git
cd syslenz4j
mvn package -DskipTests
```

---

## コアコンセプト

syslenz4j の全機能は `SyslenzAgent` 経由で利用します:

| エントリポイント | 用途 |
|----------------|-----|
| `startServer(port)` | TCP サーバー起動、`syslenz --connect` 対応 |
| `printSnapshot()` | 標準出力への1回限りのエクスポート（プラグインモード）|
| `registry()` | カスタムアプリケーションメトリクスの登録 |
| `watch(metric)` | 閾値アラートの fluent ビルダー |

### SyslenzAgent

シングルトンファサード。`MetricRegistry`・`WatchRegistry`・`SyslenzServer` の各インスタンスを1つずつ管理。スレッドセーフ、`startServer()` は冪等。

### MetricRegistry

カスタムメトリクスのサプライヤー関数を保持します。3種類のメトリクスを登録可能:

- **gauge** — 任意の `Number` を返すサプライヤー（キュー深度、キャッシュサイズなど）
- **counter** — 単調増加する `Number`（リクエスト数、書き込みバイト数など）
- **text** — `String` サプライヤー（バージョン、環境ラベルなど）

登録されたメトリクスは、JVM 組込メトリクスと共にすべてのスナップショットに含まれます。

### JvmCollector

標準 JDK の `java.lang.management` MXBean からすべてのメトリクスを読み取ります。ネイティブコード・エージェント・外部ライブラリは不要。収集項目は[収集メトリクス一覧](#収集メトリクス一覧)を参照。

### SyslenzServer

TCP ポートでリッスンし、`SNAPSHOT\n` コマンドに ProcEntry JSON 1行で応答します。daemon スレッドで動作するため、JVM のシャットダウンを妨げません。

### Watch API

Fluent 条件ビルダー。メトリクスが閾値を超えた/回復した際にコールバックを呼び出します。詳細は [Watch API](#watch-api) を参照。

---

## 収集メトリクス一覧

`SNAPSHOT` リクエストのたびにすべてのメトリクスが収集されます。

| 名前 | 型 | ソース MXBean | 説明 |
|-----|---|-------------|-----|
| `heap_used` | Bytes | MemoryMXBean | 現在のヒープ使用量 |
| `heap_committed` | Bytes | MemoryMXBean | OS にコミット済みヒープ |
| `heap_max` | Bytes | MemoryMXBean | 最大ヒープ (-Xmx) |
| `non_heap_used` | Bytes | MemoryMXBean | 非ヒープ（メタスペース・コードキャッシュ等）|
| `non_heap_committed` | Bytes | MemoryMXBean | 非ヒープのコミット済みサイズ |
| `gc_<name>_count` | Integer | GarbageCollectorMXBean | GC コレクター別の回数 |
| `gc_<name>_time` | Duration (s) | GarbageCollectorMXBean | GC コレクター別の経過時間 |
| `gc_total_count` | Integer | GarbageCollectorMXBean | GC 合計回数 |
| `gc_total_time` | Duration (s) | GarbageCollectorMXBean | GC 合計時間 |
| `thread_count` | Integer | ThreadMXBean | 現在のライブスレッド数 |
| `thread_peak` | Integer | ThreadMXBean | 起動以来のピークスレッド数 |
| `thread_daemon` | Integer | ThreadMXBean | デーモンスレッド数 |
| `thread_deadlocked` | Integer | ThreadMXBean | デッドロック中のスレッド数（0 = 正常）|
| `uptime` | Duration (s) | RuntimeMXBean | JVM 起動時間 |
| `vm_name` | Text | RuntimeMXBean | JVM 実装名 |
| `available_processors` | Integer | OperatingSystemMXBean | JVM から利用可能な CPU 数 |
| `system_load_average` | Float | OperatingSystemMXBean | 1分間システム負荷平均 |
| `process_cpu_load` | Float (%) | com.sun.management | プロセス CPU 使用率 0–100% |
| `process_cpu_time` | Duration (s) | com.sun.management | プロセス累計 CPU 時間 |
| `classes_loaded` | Integer | ClassLoadingMXBean | 現在ロード済みクラス数 |
| `classes_total_loaded` | Integer | ClassLoadingMXBean | 起動以来の総ロードクラス数 |
| `classes_unloaded` | Integer | ClassLoadingMXBean | 起動以来のアンロードクラス数 |
| `buffer_direct_used` | Bytes | BufferPoolMXBean | ダイレクトバッファメモリ |
| `buffer_direct_capacity` | Bytes | BufferPoolMXBean | ダイレクトバッファ容量 |
| `buffer_mapped_used` | Bytes | BufferPoolMXBean | メモリマップドバッファメモリ |

`MetricRegistry` で登録したカスタムメトリクスは `app_` プレフィックスが付きます（例: `app_queue_size`）。

---

## Watch API

Fluent ビルダーで閾値条件を定義します。条件が FIRING → RESOLVED（または逆）に遷移した際にコールバックが呼ばれます。

```java
// 単一閾値
SyslenzAgent.watch("heap_used")
    .greaterThan(1_073_741_824L)    // 1 GiB 超
    .severity(Severity.WARNING)
    .cooldown(60_000)               // 60秒間は再発火しない
    .onFire(event -> alertService.send(event.message()))
    .onResolve(event -> alertService.resolve(event.metricName()))
    .register();

// 範囲外チェック
SyslenzAgent.watch("process_cpu_load")
    .outsideRange(0.0, 90.0)
    .severity(Severity.CRITICAL)
    .onFire(event -> ops.page("CPU 異常: " + event.value()))
    .register();

// 複合条件: 両方が同時に成立した時のみ発火
SyslenzAgent.watch("app_queue_size")
    .greaterThan(10_000)
    .and("app_error_rate").greaterThan(5.0)
    .severity(Severity.CRITICAL)
    .onFire(event -> scaleOut())
    .register();
```

### 演算子一覧

| メソッド | 意味 |
|---------|-----|
| `greaterThan(v)` | value > v |
| `lessThan(v)` | value < v |
| `greaterThanOrEqual(v)` | value >= v |
| `lessThanOrEqual(v)` | value <= v |
| `equalTo(v)` | value ≈ v (epsilon 0.0001) |
| `notEqualTo(v)` | value ≇ v |
| `outsideRange(min, max)` | value < min \|\| value > max |
| `insideRange(min, max)` | min <= value <= max |

### 重要度（Severity）

| 値 | 意味 |
|---|-----|
| `Severity.INFO` | 情報。問題ではないが注目すべき |
| `Severity.WARNING` | 警告。対応が必要な状況が発生しつつある |
| `Severity.CRITICAL` | 危険。即座の対応が必要 |

### WatchEvent のフィールド

```java
event.metricName()  // String — 発火したメトリクス名
event.value()       // double — 発火時の値
event.severity()    // Severity
event.state()       // WatchEvent.State.FIRING | RESOLVED
event.timestamp()   // Instant
event.message()     // 人間が読めるサマリー文字列
```

> **既知の制限**: v1.1.0 では複合条件 `.and()` チェーンの実装が不完全です。`CompoundCondition.greaterThan()` が `null` を返すため、fluent チェーンが途中で切断され NPE のリスクがあります。二次条件で動作するのは `GREATER_THAN` と `LESS_THAN` のみです。また、`WatchRegistry.evaluate()` がスナップショット取得パスに組み込まれていないため、Watch コールバックはこのリリースでは自動発火しません。詳細は [GitHub issue #3](https://github.com/opaopa6969/syslenz4j/issues/3) を参照。

---

## プロトコル

TCP サーバーは行指向のテキストプロトコルを使用します:

```
クライアント → サーバー:  SNAPSHOT\n
サーバー → クライアント:  <ProcEntry JSON 1行>\n
```

ProcEntry JSON の構造:

```json
{
  "source": "jvm/pid-12345",
  "fields": [
    {"name": "heap_used", "value": {"Bytes": 524288000}, "unit": null, "description": "Current heap memory usage"},
    {"name": "thread_count", "value": {"Integer": 42}, "unit": "count", "description": "Current live thread count"}
  ]
}
```

接続は複数リクエストにわたって維持するか、1回のやりとりでクローズすることができます。未知のコマンドはサイレントに無視されます。

---

## Spring Boot 統合

```java
@Configuration
public class SyslenzConfig {

    @Value("${syslenz.port:9100}")
    private int port;

    @Bean
    public SyslenzLifecycle syslenzLifecycle(MeterRegistry meterRegistry) {
        return new SyslenzLifecycle(port, meterRegistry);
    }
}

@Component
public class SyslenzLifecycle implements SmartLifecycle {

    private final int port;
    private final MeterRegistry meterRegistry;
    private volatile boolean running;

    public SyslenzLifecycle(int port, MeterRegistry meterRegistry) {
        this.port = port;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void start() {
        // Micrometer のカウンターを syslenz4j にブリッジ
        SyslenzAgent.registry().gauge("http_requests_active",
            () -> meterRegistry.find("http.server.requests").gauge() != null
                ? meterRegistry.find("http.server.requests").gauge().value()
                : 0.0
        );

        SyslenzAgent.watch("process_cpu_load")
            .greaterThan(85.0)
            .severity(Severity.WARNING)
            .onFire(event -> log.warn("CPU 高負荷: {}%", event.value()))
            .register();

        SyslenzAgent.startServer(port);
        running = true;
    }

    @Override
    public void stop() {
        SyslenzAgent.stopServer();
        running = false;
    }

    @Override public boolean isRunning() { return running; }
}
```

---

## セキュリティ上の注意

- `SyslenzServer` はデフォルトですべてのネットワークインターフェース（`0.0.0.0`）にバインドします。**このポートをパブリックインターネットに公開しないでください。** ファイアウォールで制限するか、OS レベルで `127.0.0.1` にバインドしてください。
- TCP エンドポイントには認証がありません。ポートにアクセスできる相手は JVM の内部情報（ヒープサイズ、スレッド数、CPU 使用率、デッドロック状況）をすべて読み取れます。
- コンテナ環境では、内部ネットワークのみにポートを公開してください（Docker の `--network internal`、Kubernetes の `ClusterIP` など）。

---

## 動作要件

- **Java 17** 以上（`record`、`switch` 式を使用）
- 外部ランタイム依存ゼロ — 標準 JDK の `java.lang.management` API のみ使用
