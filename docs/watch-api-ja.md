[English version](watch-api.md)

# Watch API

Watch API を使うと、任意のメトリクスに閾値条件を定義し、条件が発火または解除された際にコールバックを受け取ることができます。Fluent ビルダーで `WatchCondition` を構築し、`WatchRegistry` に登録します。

---

## 概要

```
SyslenzAgent.watch("heap_used")     ← メトリクス選択
    .greaterThan(1_073_741_824L)    ← 条件定義
    .severity(Severity.WARNING)     ← 重要度設定
    .cooldown(60_000)               ← 再発火の最小間隔（ms）
    .onFire(event -> ...)           ← 条件が真になった時のコールバック
    .onResolve(event -> ...)        ← 条件がクリアされた時のコールバック
    .register();                    ← WatchRegistry へコミット
```

`register()` を呼ぶたびに、それぞれ独立した状態（発火中 / 未発火）とクールダウンタイマーを持つ Watch が作成されます。

---

## 完全なサンプル

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;
import org.unlaxer.infra.syslenz4j.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlertSetup {

    private static final Logger log = LoggerFactory.getLogger(AlertSetup.class);

    public static void configure() {

        // ── ヒープ圧迫 ────────────────────────────────────────────────────
        SyslenzAgent.watch("heap_used")
            .greaterThan(800_000_000L)          // 800 MB 超
            .severity(Severity.WARNING)
            .cooldown(30_000)
            .onFire(event -> log.warn("[HEAP] {} bytes 使用 — {}", event.value(), event.message()))
            .onResolve(event -> log.info("[HEAP] 回復 — {} bytes", event.value()))
            .register();

        // ── デッドロック検出 ───────────────────────────────────────────────
        SyslenzAgent.watch("thread_deadlocked")
            .greaterThan(0.0)
            .severity(Severity.CRITICAL)
            .cooldown(0)                        // 常に再発火（緊急事態）
            .onFire(event -> {
                log.error("[DEADLOCK] {} スレッドがデッドロック！", (int) event.value());
                alertingService.triggerPagerDuty(event.message());
            })
            .register();

        // ── CPU 範囲チェック ───────────────────────────────────────────────
        SyslenzAgent.watch("process_cpu_load")
            .outsideRange(0.0, 90.0)
            .severity(Severity.WARNING)
            .cooldown(60_000)
            .onFire(event -> log.warn("[CPU] 範囲外: {}%", String.format("%.1f", event.value())))
            .register();

        // ── 複合条件: キューオーバーフロー + エラー ────────────────────────
        SyslenzAgent.watch("app_queue_size")
            .greaterThan(10_000)
            .and("app_error_rate").greaterThan(5.0)
            .severity(Severity.CRITICAL)
            .cooldown(120_000)
            .onFire(event -> scaleOutService.requestScaleOut())
            .register();
    }
}
```

---

## ビルダーリファレンス

### `watch(metricName)`

```java
static WatchCondition SyslenzAgent.watch(String metricName)
```

指定されたメトリクス名の Watch 条件の構築を開始します。メトリクスには JVM 組込メトリクス（例: `heap_used`・`thread_count`）と、`SyslenzAgent.registry()` で登録したカスタムメトリクス（カスタムメトリクスは内部で `app_` プレフィックスが付く）の両方が使用できます。

---

### 演算子メソッド

すべての演算子メソッドはチェーンのために `this` を返します。

#### 単一値演算子

| メソッド | 発火条件 |
|---------|---------|
| `greaterThan(double v)` | `metric > v` |
| `lessThan(double v)` | `metric < v` |
| `greaterThanOrEqual(double v)` | `metric >= v` |
| `lessThanOrEqual(double v)` | `metric <= v` |
| `equalTo(double v)` | `abs(metric - v) < 0.0001` |
| `notEqualTo(double v)` | `abs(metric - v) >= 0.0001` |

#### 範囲演算子

| メソッド | 発火条件 |
|---------|---------|
| `outsideRange(double min, double max)` | `metric < min \|\| metric > max` |
| `insideRange(double min, double max)` | `min <= metric <= max` |

---

### 設定メソッド

#### `.severity(Severity)`

発火した `WatchEvent` に付与される重要度を設定します。

| 値 | `label()` | 意味 |
|---|----------|-----|
| `Severity.INFO` | `"info"` | 情報。問題ではないが注目すべき |
| `Severity.WARNING` | `"warning"` | 警告。対応が必要 |
| `Severity.CRITICAL` | `"critical"` | 危険。即座の対応が必要 |

デフォルト: `Severity.WARNING`。

#### `.cooldown(long milliseconds)`

同じ条件で連続して `onFire` が呼ばれる最小間隔。発火後、条件が解除（`onResolve` 発火）され、かつクールダウン時間が経過しないと次の発火は起きません。

デフォルト: `10_000` ms（10秒）。

`cooldown(0)` にするとこの制限をなくし、条件が成立し続ける限り評価サイクルごとに発火します。

---

### コールバックメソッド

#### `.onFire(Consumer<WatchEvent>)`

条件が**未発火**から**発火中**に遷移した時（クールダウン経過後）に呼ばれます。

```java
.onFire(event -> {
    String msg = event.message();        // "[WARNING] heap_used = 850000000.00"
    double val = event.value();          // 850000000.0
    Severity sev = event.severity();     // Severity.WARNING
    Instant ts = event.timestamp();      // 発火時刻
})
```

#### `.onResolve(Consumer<WatchEvent>)`

条件が**発火中**から**未発火**に遷移した時に呼ばれます。`WatchEvent.state()` は `RESOLVED` になります。

両コールバックはオプションです。`onFire` が null の場合でも条件は追跡されます（`WatchRegistry.firingCount()` に反映）が、発火時にコードは実行されません。

コールバック内で例外がスローされても、キャッチされてサイレントに破棄されます。サーバースレッドはクラッシュしません。

---

### 複合条件

`.and(String otherMetricName)` でセカンダリ条件を追加します。プライマリとセカンダリの両方の条件が同時に成立した時のみ Watch が発火します。

```java
SyslenzAgent.watch("app_queue_size")
    .greaterThan(10_000)
    .and("app_error_rate").greaterThan(5.0)
    .severity(Severity.CRITICAL)
    .onFire(event -> scaleOut())
    .register();
```

> **既知の制限（v1.1.0）**: `CompoundCondition.greaterThan()` が親の `WatchCondition` ではなく `null` を返すため、`.and("x").greaterThan(v)` の後にメソッドを呼ぶと `NullPointerException` が発生します。セカンダリ条件では `greaterThan` と `lessThan` のみサポートされており、その後にメソッドをチェーンしてはいけません。[GitHub #3](https://github.com/opaopa6969/syslenz4j/issues/3) で修正追跡中。

---

### `.register()`

条件を `WatchRegistry` にコミットします。この呼び出し後、`WatchCondition` オブジェクトを再利用・変更してはいけません。

---

## WatchEvent リファレンス

`WatchEvent` は Java の `record` です:

```java
public record WatchEvent(
    String metricName,   // 発火したメトリクス名
    double value,        // 発火・解除時のメトリクス値
    Severity severity,   // 条件に設定された重要度
    State state,         // FIRING または RESOLVED
    Instant timestamp,   // 発火時の Instant.now()
    String message       // 人間が読めるサマリー
)
```

### `WatchEvent.State`

| 値 | 意味 |
|---|-----|
| `FIRING` | 条件が真になった |
| `RESOLVED` | 条件がクリアされた |

### `message()` の形式

- FIRING: `"[WARNING] heap_used = 850000000.00"`
- RESOLVED: `"[RESOLVED] heap_used = 750000000.00"`

---

## WatchRegistry の内部構造

`WatchRegistry` は内部クラスですが、デバッグに役立つ情報として紹介します。

```
WatchRegistry
  entries: CopyOnWriteArrayList<WatchEntry>
    WatchEntry
      condition: WatchCondition
      wasFiring: boolean
      lastFiredAt: long（エポック ms）
```

`evaluate(Map<String, Double> currentValues)` はメトリクス名 → 値のマップで呼ばれ、すべてのエントリを反復して[アーキテクチャドキュメント](architecture-ja.md)で説明したステートマシンを適用します。

### ステートマシン

```
NOT_FIRING（未発火）
  │  [条件成立 AND クールダウン経過]
  ▼
FIRING（発火中）  ──── onFire コールバック呼び出し
  │  [条件が成立しなくなった]
  ▼
NOT_FIRING（未発火）  ──── onResolve コールバック呼び出し
```

クールダウンは NOT_FIRING → FIRING の遷移にのみ適用されます。FIRING → NOT_FIRING にはクールダウンはありません。

---

## 既知の問題（v1.1.0）

1. **`WatchRegistry.evaluate()` が自動的に呼ばれない。** スナップショットパス（`SyslenzServer.collectSnapshot()`）が `evaluate()` を呼び出していません。手動で `evaluate()` を呼ばない限り、本番環境で Watch コールバックは発火しません。v1.2.0 での修正を予定。

2. **`CompoundCondition.greaterThan()` が `null` を返す。** `.and("metric").greaterThan(v)` の後に fluent チェーンが壊れます。セカンダリ条件では `GREATER_THAN` と `LESS_THAN` のみが動作し、その後さらにメソッドをチェーンできません。

3. **セカンダリ条件の演算子が不完全。** `CompoundCondition.evaluate()` は `GREATER_THAN` と `LESS_THAN` のみ処理し、他の演算子はすべて `default: true`（常に真）にフォールスルーします。

3つすべては [GitHub issue #3](https://github.com/opaopa6969/syslenz4j/issues/3) で追跡中。

---

## Tips

**Watch 用のカスタムメトリクスの命名**: メトリクスを分かりやすい名前で登録し、Watch では `app_` プレフィックス付きで指定します（プレフィックスは `MetricRegistry` が内部で付与します）。

```java
// 登録:
SyslenzAgent.registry().gauge("queue_depth", () -> q.size());

// Watch（app_ プレフィックス付きで指定）:
SyslenzAgent.watch("app_queue_depth").greaterThan(1000).register();
```

**Watch 条件のテスト**: テストのティアダウンで `SyslenzAgent.clearWatches()` を呼び、あるテストの条件が別のテストに影響しないようにしてください。

**特定の Watch の削除**: v1.1.0 では Watch ごとの登録解除はできません。`clearWatches()` で全クリアしてから必要な条件のみ再登録してください。
