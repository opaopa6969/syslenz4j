# syslenz4j — Technical Specification

**Version:** 1.1.1
**Artifact:** `org.unlaxer.infra:syslenz4j:1.1.1`
**Repository:** https://github.com/opaopa6969/syslenz4j
**License:** MIT
**Last Updated:** 2026-04-19

---

## Table of Contents

1. [概要 (Overview)](#1-概要-overview)
2. [機能仕様 (Feature Specification)](#2-機能仕様-feature-specification)
3. [データ永続化層 (Data Persistence Layer)](#3-データ永続化層-data-persistence-layer)
4. [ステートマシン (State Machine)](#4-ステートマシン-state-machine)
5. [ビジネスロジック (Business Logic)](#5-ビジネスロジック-business-logic)
6. [API / 外部境界 (API and External Boundaries)](#6-api--外部境界-api-and-external-boundaries)
7. [UI](#7-ui)
8. [設定 (Configuration)](#8-設定-configuration)
9. [依存 (Dependencies)](#9-依存-dependencies)
10. [非機能要件 (Non-Functional Requirements)](#10-非機能要件-non-functional-requirements)
11. [テスト戦略 (Test Strategy)](#11-テスト戦略-test-strategy)
12. [デプロイ / 運用 (Deployment and Operations)](#12-デプロイ--運用-deployment-and-operations)

---

## 1. 概要 (Overview)

### 1.1 目的

syslenz4j は、Java アプリケーションを syslenz モニタリングエコシステムに統合するための軽量ライブラリである。JVM 内部のリソース状態をリアルタイムに可視化し、閾値ベースのアラートをアプリケーションコード内で宣言的に記述できるようにする。

### 1.2 設計思想

- **Zero dependency** — JDK 標準ライブラリのみ使用。外部 JAR を一切持ち込まない
- **Embeddable** — `<dependency>` を追加して数行のコードで組み込める
- **Unobtrusive** — デーモンスレッドで動作し、アプリケーションのシャットダウンを遅延させない
- **Fail-safe** — コールバック例外や MXBean 取得エラーを内部で吸収し、監視側の障害がアプリに伝播しない

### 1.3 提供モード

syslenz4j は 3 つの動作モードを提供する。

| モード | 起動方法 | 用途 |
|--------|---------|------|
| **Server モード** | `SyslenzAgent.startServer(port)` | `syslenz --connect` による継続的ポーリング |
| **Stdout モード** | `SyslenzAgent.printSnapshot()` | syslenz プラグインとして 1 回だけ出力 |
| **Watch API** | `SyslenzAgent.watch(...).register()` | アプリ内閾値監視とコールバック通知 |

### 1.4 バージョン履歴概要

| バージョン | リリース日 | 主な変更 |
|-----------|----------|---------|
| 1.0.0 | 2026-04-10 | 初版。Server モード、Stdout モード、MetricRegistry、JvmCollector |
| 1.1.0 | 2026-04-17 | Watch API 追加 (WatchCondition, WatchRegistry, WatchEvent, Severity) |
| 1.1.1 | 2026-04-19 | バグ修正 3 件 (CompoundCondition チェーン、evaluate 呼び出し、bindAddress オーバーロード) |

### 1.5 パッケージ構成

```
org.unlaxer.infra.syslenz4j
├── SyslenzAgent          # メインエントリポイント (public)
├── MetricRegistry        # カスタムメトリクス登録 (public)
├── WatchCondition        # 監視条件ビルダー (public)
├── WatchEvent            # アラートイベント record (public)
├── Severity              # アラート重要度 enum (public)
├── Operator              # 比較演算子 enum (public)
├── JvmCollector          # MXBean 収集 (public)
├── JsonExporter          # ProcEntry JSON シリアライザ (public)
├── SyslenzServer         # TCP サーバー (package-private constructor)
└── WatchRegistry         # 監視条件管理 (package-private)
```

---

## 2. 機能仕様 (Feature Specification)

### 2.1 JVM メトリクス収集 (JvmCollector)

`JvmCollector` は `java.lang.management` パッケージの MXBean を通じて JVM 状態を収集する。外部依存なし。

#### 2.1.1 収集項目一覧

**メモリ (MemoryMXBean)**

| メトリクス名 | 型 | 単位 | 説明 |
|-------------|-----|------|------|
| `heap_used` | Bytes | bytes | 現在のヒープ使用量 |
| `heap_committed` | Bytes | bytes | OS にコミット済みヒープ |
| `heap_max` | Bytes | bytes | 最大ヒープ (-Xmx) |
| `non_heap_used` | Bytes | bytes | 非ヒープ使用量 (metaspace, code cache 等) |
| `non_heap_committed` | Bytes | bytes | 非ヒープコミット量 |

**ガベージコレクション (GarbageCollectorMXBean)**

各 GC コレクタに対して以下が生成される。コレクタ名は英数字以外を `_` に置換してから小文字化したものがプレフィックスとなる。

| メトリクス名パターン | 型 | 単位 | 説明 |
|--------------------|-----|------|------|
| `gc_<name>_count` | Integer | count | 当該 GC の実行回数 |
| `gc_<name>_time` | Duration | s | 当該 GC の累積時間 (秒) |
| `gc_total_count` | Integer | count | 全 GC 合計回数 |
| `gc_total_time` | Duration | s | 全 GC 合計時間 (秒) |

例: G1 GC の場合 `gc_g1_young_generation_count`, `gc_g1_old_generation_time` 等

**スレッド (ThreadMXBean)**

| メトリクス名 | 型 | 単位 | 説明 |
|-------------|-----|------|------|
| `thread_count` | Integer | count | 現在の生存スレッド数 |
| `thread_peak` | Integer | count | JVM 起動以来のピークスレッド数 |
| `thread_daemon` | Integer | count | 現在のデーモンスレッド数 |
| `thread_deadlocked` | Integer | count | デッドロック中スレッド数 (0 = 正常) |

`thread_deadlocked` は `ThreadMXBean.findDeadlockedThreads()` の返値長から算出する。`null` の場合は 0。

**ランタイム (RuntimeMXBean)**

| メトリクス名 | 型 | 単位 | 説明 |
|-------------|-----|------|------|
| `uptime` | Duration | s | JVM 起動からの経過時間 (秒) |
| `vm_name` | Text | — | JVM 実装名 (例: `OpenJDK 64-Bit Server VM`) |

**OS (OperatingSystemMXBean)**

| メトリクス名 | 型 | 単位 | 説明 |
|-------------|-----|------|------|
| `available_processors` | Integer | count | JVM が利用可能なプロセッサ数 |
| `system_load_average` | Float | — | 1 分間のシステムロードアベレージ (負値の場合は省略) |
| `process_cpu_load` | Float | % | プロセス CPU 使用率 0–100% (com.sun.management 利用可能時のみ) |
| `process_cpu_time` | Duration | s | プロセス累積 CPU 時間 (秒) |

`process_cpu_load` と `process_cpu_time` は `com.sun.management.OperatingSystemMXBean` にキャストできる場合のみ収集される。Oracle/OpenJDK では利用可能。

**クラスローディング (ClassLoadingMXBean)**

| メトリクス名 | 型 | 単位 | 説明 |
|-------------|-----|------|------|
| `classes_loaded` | Integer | count | 現在ロード済みクラス数 |
| `classes_total_loaded` | Integer | count | JVM 起動以来の総ロードクラス数 |
| `classes_unloaded` | Integer | count | JVM 起動以来のアンロードクラス数 |

**バッファプール (BufferPoolMXBean)**

各バッファプール (direct, mapped 等) に対して以下が生成される。プール名は英数字以外を `_` に置換して小文字化。

| メトリクス名パターン | 型 | 単位 | 説明 |
|--------------------|-----|------|------|
| `buffer_<name>_used` | Bytes | bytes | 使用中バッファメモリ |
| `buffer_<name>_capacity` | Bytes | bytes | バッファ総容量 |
| `buffer_<name>_count` | Integer | count | バッファ数 |

負値を返すプール属性はスキップする。

#### 2.1.2 型システム

`JvmCollector.Metric` は以下のフィールドを持つ。

```java
public class Metric {
    public final String name;         // メトリクス識別子
    public final Object value;        // 実値 (Long, Double, String)
    public final String type;         // "Bytes" | "Integer" | "Float" | "Duration" | "Text"
    public final String unit;         // null または単位文字列
    public final String description;  // 人間が読める説明
}
```

### 2.2 カスタムメトリクス (MetricRegistry)

アプリケーションは `SyslenzAgent.registry()` 経由で `MetricRegistry` を取得し、独自メトリクスを登録できる。

#### 2.2.1 登録 API

```java
MetricRegistry reg = SyslenzAgent.registry();

// ゲージ: スナップショット取得のたびに supplier を呼び出す
reg.gauge("queue_depth", () -> queue.size());
reg.gauge("queue_depth", () -> queue.size(), "Processing queue depth");

// カウンタ: 単調増加を期待するゲージ
reg.counter("requests_total", counter::get);
reg.counter("requests_total", counter::get, "Total HTTP requests processed");

// テキスト: バージョン文字列やラベル
reg.text("app_version", () -> "2.3.1");
reg.text("app_version", () -> "2.3.1", "Application version string");

// 削除
reg.remove("queue_depth");
```

#### 2.2.2 内部動作

登録されたメトリクスは `ConcurrentHashMap<String, Registration>` で管理される。スナップショット生成時に `collect()` が呼ばれ、各 supplier が評価される。

- `NumberSupplier` が返す値の型に応じて `Integer` / `Float` に分類する
  - `Long`, `Integer`, `AtomicLong` → `Integer` (count)
  - それ以外の `Number` → `Float`
- カスタムメトリクス名には `collect()` 内で自動的に `app_` プレフィックスが付与される
  - 登録名 `queue_depth` → JSON フィールド名 `app_queue_depth`
- `supplier` が例外を投げた場合は当該メトリクスをサイレントにスキップし、他のメトリクス収集を継続する

#### 2.2.3 スレッド安全性

`ConcurrentHashMap` を使用するため、複数スレッドから同時に `gauge()`, `counter()`, `text()`, `remove()` を呼び出しても安全。

### 2.3 Watch API

Watch API は、メトリクスが閾値条件を満たした場合にコールバックを発火するアラートシステムである。fluent builder パターンで条件を宣言的に記述できる。

#### 2.3.1 ライフサイクル

```
SyslenzAgent.watch(metricName)   ← WatchCondition 生成
    .<operator>(threshold)        ← 比較条件設定
    [.and(metric).<operator>(v)]  ← 複合条件 (optional)
    [.severity(Severity)]         ← 重要度設定 (default: WARNING)
    [.cooldown(ms)]               ← クールダウン設定 (default: 10_000ms)
    [.onFire(Consumer)]           ← 発火コールバック (optional)
    [.onResolve(Consumer)]        ← 解除コールバック (optional)
    .register()                   ← WatchRegistry に登録
```

#### 2.3.2 演算子一覧

`WatchCondition` が提供する比較メソッド:

| メソッド | 演算子 | 発火条件 |
|---------|--------|---------|
| `greaterThan(double v)` | `>` | `value > v` |
| `lessThan(double v)` | `<` | `value < v` |
| `greaterThanOrEqual(double v)` | `>=` | `value >= v` |
| `lessThanOrEqual(double v)` | `<=` | `value <= v` |
| `equalTo(double v)` | `==` | `abs(value - v) < 0.0001` |
| `notEqualTo(double v)` | `!=` | `abs(value - v) >= 0.0001` |
| `outsideRange(double min, double max)` | outside | `value < min \|\| value > max` |
| `insideRange(double min, double max)` | inside | `min <= value <= max` |

`equalTo` の比較には `0.0001` の epsilon を使用し、浮動小数点誤差を吸収する。

#### 2.3.3 評価タイミング

`WatchRegistry.evaluate(Map<String, Double>)` は `SyslenzServer.collectSnapshot()` 内でスナップショット取得のたびに呼ばれる。メトリクス値マップは以下から構築される:

1. `JvmCollector.collect()` の全 Numeric メトリクス (メトリクス名をキーとして使用)
2. `MetricRegistry.collect()` の全 Numeric メトリクス (`app_` プレフィックス付きと、付きなしの両方をマップに格納)

Watch API を Stdout モード (`printSnapshot()`) で使用する場合、`evaluate()` は呼ばれない。Server モードのみ自動評価が行われる。

#### 2.3.4 WatchEvent

条件発火・解除時に `Consumer<WatchEvent>` コールバックへ渡される record:

```java
public record WatchEvent(
    String metricName,   // 監視対象メトリクス名
    double value,        // 発火時の値
    Severity severity,   // 条件に設定した重要度
    State state,         // FIRING または RESOLVED
    Instant timestamp,   // Instant.now() (発火瞬間)
    String message       // 人間が読めるサマリ
)
```

`message()` のフォーマット:
- FIRING: `"[WARNING] heap_used = 850000000.00"`
- RESOLVED: `"[RESOLVED] heap_used = 750000000.00"`

#### 2.3.5 複合条件 (CompoundCondition)

`.and(String otherMetric)` でセカンダリ条件を追加できる。プライマリとセカンダリの両方が同時に成立している場合のみ発火する (AND 結合)。OR や 3 条件以上の連鎖は非対応 (v1.1.1 時点)。

```java
SyslenzAgent.watch("app_queue_size")
    .greaterThan(10_000)
    .and("app_error_rate").greaterThan(5.0)
    .severity(Severity.CRITICAL)
    .onFire(event -> scaleOut())
    .register();
```

`CompoundCondition` は `WatchCondition` の内部クラスであり、`evaluate(double value)` メソッドで独立して評価される。セカンダリ条件がサポートする演算子: `GREATER_THAN`, `LESS_THAN`, `GREATER_THAN_OR_EQUAL`, `LESS_THAN_OR_EQUAL`。その他の演算子は `default: true` にフォールスルーする (v1.1.1 時点の既知制限)。

### 2.4 TCP サーバー (SyslenzServer)

#### 2.4.1 プロトコル

syslenz `--connect` プロトコルに準拠するシンプルなテキストプロトコル:

1. クライアントが TCP 接続を確立する
2. クライアントが `SNAPSHOT\n` を送信する
3. サーバーが ProcEntry JSON を 1 行 (末尾 `\n`) で返す
4. 接続は継続または双方から close される

- コマンドは case-insensitive (`SNAPSHOT`, `snapshot`, `Snapshot` いずれも有効)
- 未知のコマンドはサイレントに無視する
- ソケットタイムアウト: `SO_TIMEOUT = 30_000ms`

#### 2.4.2 実装特性

- `ServerSocket` の `setSoTimeout(1000)` により `running` フラグを 1 秒ごとにチェック
- 単一デーモンスレッド (`"syslenz-server-<port>"`) でブロッキング I/O によりクライアントを逐次処理
- 高スループットが必要な場合はスレッドプールへのラップを推奨 (本ライブラリのスコープ外)
- クライアントハンドリング中の例外は `System.err` に出力するが、サーバーはクラッシュしない
- `stop()` は `ServerSocket.close()` を呼び出し、`acceptLoop` を `SocketException` 経由で終了させる
- `running = false` 後の `SocketException` は無視する (正常停止)

#### 2.4.3 JSON 出力の正規化

`JsonExporter.export()` の出力に含まれる改行文字 (`\n`, `\r`) は `collectSnapshot()` 内で除去され、必ず 1 行の JSON として送信される。

### 2.5 JSON エクスポート (JsonExporter)

外部 JSON ライブラリを一切使用せず、文字列結合のみで syslenz ProcEntry フォーマットを生成する。

#### 2.5.1 出力フォーマット

```json
{
  "source": "jvm/pid-12345",
  "fields": [
    {"name": "heap_used", "value": {"Bytes": 524288000}, "unit": null, "description": "Current heap memory usage"},
    {"name": "gc_g1_young_generation_count", "value": {"Integer": 42}, "unit": "count", "description": "GC count for G1 Young Generation"},
    {"name": "vm_name", "value": {"Text": "OpenJDK 64-Bit Server VM"}, "unit": null, "description": "JVM implementation name"},
    {"name": "app_queue_depth", "value": {"Float": 1234.0}, "unit": null, "description": "Processing queue depth"}
  ]
}
```

- `source` は `ProcessHandle.current().pid()` から取得した PID を含む
- `value` は型タグ付きオブジェクト: `{"Bytes": N}`, `{"Integer": N}`, `{"Float": F}`, `{"Duration": F}`, `{"Text": "..."}` のいずれか
- `unit` は `null` またはクォートされた文字列
- JSON エスケープ: `"`, `\`, `\n`, `\r`, `\t`, U+0000–U+001F をエスケープする

#### 2.5.2 フィールド順序

1. JvmCollector の収集順 (メモリ → GC → スレッド → ランタイム → OS → クラスローディング → バッファプール)
2. MetricRegistry の登録順 (ConcurrentHashMap のイテレーション順, 非決定的)

---

## 3. データ永続化層 (Data Persistence Layer)

**本ライブラリはデータ永続化を行わない。全データはオンメモリかつ揮発性。**

| データ | 格納場所 | 永続化 |
|-------|---------|-------|
| カスタムメトリクス登録 | `MetricRegistry.registrations` (ConcurrentHashMap) | なし |
| Watch 条件リスト | `WatchRegistry.entries` (CopyOnWriteArrayList) | なし |
| Watch 状態 (`wasFiring`, `lastFiredAt`) | `WatchRegistry.WatchEntry` | なし |
| サーバーインスタンス | `SyslenzAgent.serverInstance` (volatile field) | なし |
| JVM メトリクス値 | JVM 内部 (MXBean) | JVM 管理 |

メトリクス履歴・アラート履歴の保存は syslenz 本体 (外部プロセス) の責務であり、syslenz4j のスコープ外。

再起動時には全登録情報が失われる。アプリケーションの初期化コードで毎回 `gauge()`, `watch()` 等を呼び直す必要がある。

---

## 4. ステートマシン (State Machine)

### 4.1 WatchCondition の状態

各 `WatchEntry` は 2 状態のステートマシンを持つ。

```
         [condition matches AND cooldown elapsed]
NOT_FIRING ─────────────────────────────────────► FIRING
    ▲                                                │
    │          [condition no longer matches]         │
    └────────────────────────────────────────────────┘
         onResolve callback called
```

```
NOT_FIRING (wasFiring = false)
    入力条件:
      - condition.evaluate(value) == true
      - (now - lastFiredAt) >= cooldownMs
    遷移: → FIRING
    副作用: wasFiring = true, lastFiredAt = now, onFire コールバック実行

FIRING (wasFiring = true)
    入力条件:
      - condition.evaluate(value) == false
      (または compound 条件が不成立)
    遷移: → NOT_FIRING
    副作用: wasFiring = false, onResolve コールバック実行
```

### 4.2 状態遷移の詳細

#### NOT_FIRING → FIRING (発火)

条件:
1. `condition.evaluate(value) == true` (プライマリ条件成立)
2. `compound != null` の場合は `compound.evaluate(otherValue) == true` も必要
3. `(System.currentTimeMillis() - lastFiredAt) >= cooldownMs`

処理順序:
1. `entry.wasFiring = true`
2. `entry.lastFiredAt = System.currentTimeMillis()`
3. `onFire != null` であれば `onFire.accept(WatchEvent.firing(...))` を呼ぶ
4. コールバック例外は catch して破棄

#### FIRING → NOT_FIRING (解除)

条件:
- `condition.evaluate(value) == false`、または compound 条件が不成立

処理順序:
1. `entry.wasFiring = false`
2. `onResolve != null` であれば `onResolve.accept(WatchEvent.resolved(...))` を呼ぶ
3. コールバック例外は catch して破棄

#### クールダウン挙動

- クールダウンは NOT_FIRING → FIRING 遷移にのみ適用される
- FIRING → NOT_FIRING (解除) にはクールダウンなし
- 解除後に再発火する場合は `lastFiredAt` からの経過時間が再チェックされる
- `cooldown(0)` を設定すると、評価ループのたびに条件が成立すれば発火する

### 4.3 WatchEvent.State (公開 enum)

```java
public enum State {
    FIRING,    // 条件が成立し発火中
    RESOLVED   // 条件が解除された
}
```

v1.1.1 時点では `UNKNOWN` 状態は存在しない。`WatchEntry` の初期状態は暗黙的に NOT_FIRING (`wasFiring = false`) であり、`WatchEvent.State` には反映されない。

### 4.4 評価スレッドと可視性

`WatchRegistry.evaluate()` は `SyslenzServer` の accept スレッドから呼ばれる。`WatchEntry.wasFiring` と `WatchEntry.lastFiredAt` は volatile でないが、`CopyOnWriteArrayList` のイテレーション内で単一スレッドからのみ書き換えられるため実用上問題ない。`clearWatches()` は `CopyOnWriteArrayList.clear()` を呼び出し、アトミックに全エントリを削除する。

---

## 5. ビジネスロジック (Business Logic)

### 5.1 閾値評価 (Threshold Evaluation)

#### 5.1.1 プライマリ条件

`WatchCondition.evaluate(double value)` の実装:

```java
return switch (operator) {
    case GREATER_THAN          -> value > threshold;
    case LESS_THAN             -> value < threshold;
    case GREATER_THAN_OR_EQUAL -> value >= threshold;
    case LESS_THAN_OR_EQUAL    -> value <= threshold;
    case EQUAL                 -> Math.abs(value - threshold) < 0.0001;
    case NOT_EQUAL             -> Math.abs(value - threshold) >= 0.0001;
    case OUTSIDE_RANGE         -> value < rangeMin || value > rangeMax;
    case INSIDE_RANGE          -> value >= rangeMin && value <= rangeMax;
};
```

`EQUAL` / `NOT_EQUAL` は `double` 精度の誤差を考慮し epsilon `0.0001` を使用する。整数値の等値比較では問題ないが、小数点以下 4 桁の精度が必要な場合は `outsideRange(v - 0.00001, v + 0.00001)` 等の代替手段を使う。

#### 5.1.2 セカンダリ条件 (CompoundCondition)

`CompoundCondition.evaluate(double value)` の実装:

```java
return switch (operator) {
    case GREATER_THAN          -> value > threshold;
    case LESS_THAN             -> value < threshold;
    case GREATER_THAN_OR_EQUAL -> value >= threshold;
    case LESS_THAN_OR_EQUAL    -> value <= threshold;
    default                    -> true;  // 既知制限: 他演算子は常に true
};
```

`EQUAL`, `NOT_EQUAL`, `OUTSIDE_RANGE`, `INSIDE_RANGE` は v1.1.1 時点でセカンダリ条件では未実装 (常に true)。

### 5.2 クールダウン (Cooldown)

クールダウンはアラートのノイズ抑制機構。同じ条件が連続して成立してもコールバックを抑制する。

- デフォルト: `10_000ms` (10 秒)
- 設定範囲: `0` 以上の任意の `long` 値 (ミリ秒)
- `cooldown(0)` は「クールダウンなし」を意味する
- `lastFiredAt` の初期値は `0` であるため、登録直後の最初の評価でクールダウンが経過済みと判定される

クールダウンのタイムソース: `System.currentTimeMillis()`。

### 5.3 重要度 (Severity)

`Severity` enum は syslenz 本体の severity 概念と対応する。

| 値 | `label()` | 日本語 | 用途 |
|----|----------|-------|------|
| `INFO` | `"info"` | 情報 | 注目すべきだが問題ではない |
| `WARNING` | `"warning"` | 警告 | 注意が必要、問題が発展しつつある可能性 |
| `CRITICAL` | `"critical"` | 危険 | 即時対応が必要、システムがクラッシュまたは劣化する恐れ |

`WatchCondition` のデフォルト severity は `WARNING`。

### 5.4 複合条件のロジック

`WatchRegistry.evaluate()` における複合条件の評価フロー:

```
1. プライマリ条件 evaluate(value) を評価
2. matches = true AND compound != null の場合:
   a. currentValues.get(compound.metricName) を取得
   b. 値が null の場合: matches = false (セカンダリメトリクスが存在しない)
   c. 値が存在し compound.evaluate(otherValue) が false の場合: matches = false
3. matches の結果に基づきステート遷移を判定
```

セカンダリメトリクスがスナップショットに存在しない場合、複合条件全体は false となり発火しない。

### 5.5 カスタムメトリクス型推論

`MetricRegistry.collect()` における型推論ロジック:

```
if kind == "text":
    → Text メトリクス (StringSupplier から取得)
else:
    val = NumberSupplier.get()
    if val instanceof Long || Integer || AtomicLong:
        → Integer (longValue() で変換, unit: "count")
    else:
        → Float (doubleValue() で変換, unit: null)
```

`NumberSupplier` が `null` を返した場合や例外を投げた場合は当該メトリクスをスキップする。

### 5.6 Watch API と MetricRegistry のメトリクス名マッピング

カスタムメトリクスを Watch API で監視する際の命名規則:

- `MetricRegistry` への登録名: `queue_depth`
- `MetricRegistry.collect()` が生成するフィールド名: `app_queue_depth`
- `WatchCondition` で指定すべき名前: `app_queue_depth`

`SyslenzServer.collectSnapshot()` は以下の両方をマップに格納する:
- `app_queue_depth` → value (プレフィックス付き)
- `queue_depth` → value (プレフィックスなし)

これにより、どちらの名前で `watch()` を呼んでも動作する。

---

## 6. API / 外部境界 (API and External Boundaries)

### 6.1 SyslenzAgent (Public API)

`SyslenzAgent` はライブラリのメインエントリポイントであり、全メソッドが `static` のユーティリティクラス。インスタンス化不可 (private constructor)。

```java
public final class SyslenzAgent {

    // サーバーモード
    public static void startServer(int port)
    public static void startServer(int port, String bindAddress)
    public static void stopServer()

    // Stdout モード
    public static void printSnapshot()

    // カスタムメトリクス
    public static MetricRegistry registry()

    // Watch API
    public static WatchCondition watch(String metricName)
    public static void clearWatches()
}
```

#### `startServer(int port)`

- ポート `port` で TCP サーバーを起動する
- バインドアドレス: `0.0.0.0` (全インタフェース)
- デーモンスレッドで動作し、JVM シャットダウンを妨げない
- 冪等: 既に起動済みの場合は何もしない (double-checked locking)

#### `startServer(int port, String bindAddress)`

- v1.1.1 で追加されたオーバーロード
- `bindAddress` に `"127.0.0.1"` を指定するとループバックのみにバインドされる
- `null` を渡した場合は `"0.0.0.0"` として扱われる

#### `stopServer()`

- サーバーを停止し `serverInstance = null` にリセットする
- 主にテスト用途。本番では通常呼び出さない

#### `printSnapshot()`

- `JvmCollector` + `MetricRegistry` の現在値を ProcEntry JSON として `System.out` に出力する
- `Watch API` の評価は行われない
- syslenz プラグインモードで使用する

#### `registry()`

- グローバルシングルトンの `MetricRegistry` を返す
- スレッドセーフ

#### `watch(String metricName)`

- 新しい `WatchCondition` ビルダーを返す
- `register()` を呼ぶまでは `WatchRegistry` に登録されない

#### `clearWatches()`

- 全 `WatchCondition` の登録をクリアする
- テスト間の状態汚染を防ぐためのテスト teardown 用 API

### 6.2 MetricRegistry (Public API)

```java
public class MetricRegistry {

    @FunctionalInterface
    public interface NumberSupplier { Number get(); }

    @FunctionalInterface
    public interface StringSupplier { String get(); }

    public void gauge(String name, NumberSupplier supplier)
    public void gauge(String name, NumberSupplier supplier, String description)
    public void counter(String name, NumberSupplier supplier)
    public void counter(String name, NumberSupplier supplier, String description)
    public void text(String name, StringSupplier supplier)
    public void text(String name, StringSupplier supplier, String description)
    public void remove(String name)
}
```

同じ `name` で `gauge()` 等を再度呼ぶと上書きとなる (ConcurrentHashMap の `put` セマンティクス)。

### 6.3 WatchRegistry (Internal API)

`WatchRegistry` は package-private。`SyslenzAgent.watches()` は `package-private` メソッドであり、テストパッケージからアクセス可能 (同一パッケージ `org.unlaxer.infra.syslenz4j`)。

```java
class WatchRegistry {
    void add(WatchCondition condition)         // WatchCondition.register() から呼ばれる
    void evaluate(Map<String, Double> values)  // SyslenzServer から呼ばれる
    int firingCount()                          // テスト用ユーティリティ
    void clear()                               // SyslenzAgent.clearWatches() から呼ばれる
}
```

### 6.4 TCP プロトコル (External Boundary)

syslenz4j は syslenz 本体の `--connect` プロトコルの**サーバー側**を実装する。

**プロトコルバージョン:** 暗黙的 (バージョンネゴシエーションなし)

**エンコーディング:** UTF-8

**リクエスト:**
```
SNAPSHOT\n
```
(大文字小文字非区分)

**レスポンス:**
```
{...single-line ProcEntry JSON...}\n
```

ProcEntry JSON スキーマ (TypeScript 風擬似型定義):
```typescript
type ProcEntry = {
  source: string;                  // "jvm/pid-<N>"
  fields: Array<{
    name: string;
    value: { Bytes: number }
           | { Integer: number }
           | { Float: number }
           | { Duration: number }
           | { Text: string };
    unit: string | null;
    description: string;
  }>;
};
```

**接続管理:**
- 1 接続につき複数回 `SNAPSHOT\n` を送信できる
- サーバーは `SO_TIMEOUT = 30_000ms` で非アクティブ接続を切断する
- クライアント側から接続を閉じることも可能

### 6.5 JvmCollector / JsonExporter (Public Utilities)

`JvmCollector` と `JsonExporter` は `public` クラスとして公開されており、`SyslenzAgent` を介さず直接使用できる。

```java
// 直接使用例
List<JvmCollector.Metric> metrics = new JvmCollector().collect();
String json = JsonExporter.export(metrics);
String json2 = JsonExporter.export(metrics, customMetrics);
```

---

## 7. UI

**UI は存在しない。** syslenz4j はライブラリであり、エンドユーザー向けの UI を持たない。

監視データの可視化・アラート管理 UI は syslenz 本体またはその他のモニタリングフロントエンドの責務。

---

## 8. 設定 (Configuration)

### 8.1 TCP ポート

| パラメータ | 型 | デフォルト | 設定方法 |
|----------|-----|----------|---------|
| port | int | なし (必須) | `startServer(port)` の引数 |

有効範囲: 1–65535。OS が許可するポートならいずれも使用可能。慣例的に `9100` が使われる。

### 8.2 バインドアドレス

| パラメータ | 型 | デフォルト | 設定方法 |
|----------|-----|----------|---------|
| bindAddress | String | `"0.0.0.0"` | `startServer(port, bindAddress)` の第 2 引数 |

- `"0.0.0.0"`: 全インタフェースでリッスン (外部からアクセス可能)
- `"127.0.0.1"`: ループバックのみ (ローカル接続のみ許可)
- 任意の IP アドレス文字列を指定可能。不正な値は `InetAddress.getByName()` で `UnknownHostException` が発生する

### 8.3 クールダウン

| パラメータ | 型 | デフォルト | 設定方法 |
|----------|-----|----------|---------|
| cooldownMs | long | `10_000` (10秒) | `.cooldown(ms)` |

Watch 条件ごとに独立して設定する。グローバルなデフォルト変更は非対応。

### 8.4 設定ファイル / 環境変数

**設定ファイルは存在しない。** 全設定はアプリケーションコードで行う。環境変数による設定も非対応。

### 8.5 シングルトン制約

`SyslenzAgent` が管理するグローバルシングルトン:

- `MetricRegistry` — 1 JVM プロセスに 1 インスタンス
- `WatchRegistry` — 1 JVM プロセスに 1 インスタンス
- `SyslenzServer` — 1 JVM プロセスに最大 1 インスタンス

複数ポートで複数サーバーを起動する必要がある場合は、`SyslenzServer` を直接インスタンス化して使用する (内部 API のため互換性保証なし)。

---

## 9. 依存 (Dependencies)

### 9.1 ランタイム依存

**なし。** syslenz4j はゼロ外部依存ライブラリである。

使用する JDK モジュール:
- `java.management` — MXBean API
- `java.base` — 標準 API (ネットワーク、コレクション、java.time 等)

### 9.2 テスト依存

| 依存 | バージョン | スコープ |
|------|----------|---------|
| `org.junit.jupiter:junit-jupiter` | `5.10.2` | `test` |

JUnit Jupiter のみ。テストフレームワーク以外の test スコープ依存はない。

### 9.3 JDK バージョン要件

| 要件 | 値 |
|-----|-----|
| 最低 JDK バージョン | **JDK 17** |
| コンパイル設定 | `maven.compiler.source/target = 17` |

JDK 17 を最低バージョンとする理由:
- `record` 型 (`WatchEvent`) — Java 16 正式化
- `switch` 式 (`evaluate()` メソッド内) — Java 14 正式化
- `ProcessHandle.current().pid()` — Java 9 以降
- `List.of()` — Java 9 以降

JDK 21 以降でも動作する (テスト済みではないが API 上の非互換なし)。

### 9.4 依存の追加について

零依存ポリシーは本ライブラリの核心設計原則。将来のバージョンでもランタイム依存の追加は行わない方針。理由:

1. クラスパス汚染のリスクゼロ
2. バージョン競合なし
3. JAR サイズ最小化
4. セキュリティ面での攻撃面縮小

### 9.5 Maven 座標

```xml
<dependency>
    <groupId>org.unlaxer.infra</groupId>
    <artifactId>syslenz4j</artifactId>
    <version>1.1.1</version>
</dependency>
```

---

## 10. 非機能要件 (Non-Functional Requirements)

### 10.1 スレッド安全性

syslenz4j の全パブリック API はスレッドセーフである。

| コンポーネント | スレッド安全性の仕組み |
|-------------|------------------|
| `MetricRegistry.registrations` | `ConcurrentHashMap` |
| `WatchRegistry.entries` | `CopyOnWriteArrayList` |
| `SyslenzAgent.serverInstance` | `volatile` + double-checked locking |
| `SyslenzServer.running` | `volatile boolean` |
| `SyslenzServer.serverSocket` | `volatile ServerSocket` |
| `WatchEntry.wasFiring` / `lastFiredAt` | 単一スレッド (accept thread) からのみ書き込み |

`WatchEntry` のフィールドは accept スレッドのみが書き換えるため、synchronization 不要。ただし `clearWatches()` が別スレッドから呼ばれる場合は `CopyOnWriteArrayList.clear()` のアトミック性に依存する。

### 10.2 パフォーマンス特性

- **スナップショット生成コスト**: MXBean アクセス (ほぼゼロに近いオーバーヘッド) + 文字列構築。数十マイクロ秒〜数百マイクロ秒オーダーを想定
- **Watch 評価コスト**: O(N) (N = 登録条件数)。各条件は数値比較のみであり、N が数百以下なら無視できる
- **TCP 接続**: syslenz は通常 10–60 秒ごとにポーリングするため、高スループットは不要

### 10.3 メモリフットプリント

- JAR サイズ: 数十 KB オーダー (クラスファイルのみ)
- ランタイムメモリ: `ConcurrentHashMap` エントリ × 登録メトリクス数 + `CopyOnWriteArrayList` エントリ × 登録 Watch 数
- メトリクス値はスナップショット取得のたびに新規 `List<Metric>` を生成する (GC により即時回収)

### 10.4 エラー耐性

- `NumberSupplier` / `StringSupplier` が例外を投げた場合: 当該メトリクスをスキップ、他は継続
- `onFire` / `onResolve` コールバックが例外を投げた場合: サイレントに破棄、サーバースレッドは継続
- `SyslenzServer` のクライアントハンドリング中の例外: `System.err` に出力、次のクライアントを受け付け継続
- MXBean が利用不可の場合 (`com.sun.management` 等): 該当メトリクスを省略、他は継続

### 10.5 シャットダウン挙動

- `SyslenzServer` の accept スレッドはデーモンスレッド
- JVM がシャットダウンを開始すると、非デーモンスレッドが終了した後にデーモンスレッドは強制終了される
- グレースフルシャットダウンが必要な場合は `SyslenzAgent.stopServer()` をシャットダウンフックまたは `PreDestroy` で呼ぶ

```java
// Spring Boot での例
@PreDestroy
void shutdown() {
    SyslenzAgent.stopServer();
}

// JVM シャットダウンフックでの例
Runtime.getRuntime().addShutdownHook(new Thread(SyslenzAgent::stopServer));
```

### 10.6 互換性ポリシー

- **バイナリ互換性**: マイナーバージョン (`1.x.y → 1.x+1.0`) での破壊的変更なし
- **パッチバージョン** (`1.x.y → 1.x.y+1`): バグ修正のみ、API 変更なし
- **メジャーバージョン** (`1.x → 2.0`): 破壊的変更を含む可能性あり
- `package-private` クラス (`WatchRegistry`, `SyslenzServer` コンストラクタ等) は互換性保証外

---

## 11. テスト戦略 (Test Strategy)

### 11.1 テストフレームワーク

JUnit 5 (Jupiter) のみ使用。外部モックライブラリ (Mockito 等) は使用しない。

### 11.2 テストクラス一覧

| テストクラス | 対象 | バージョン追加 |
|------------|------|-------------|
| `WatchRegistryEvaluateTest` | `WatchRegistry.evaluate()` の正常動作 | v1.1.1 |
| `CompoundConditionChainTest` | `CompoundCondition` fluent チェーンのバグ修正確認 | v1.1.1 |
| `LocalhostBindingTest` | `startServer(port, bindAddress)` オーバーロード | v1.1.1 |

### 11.3 テストケース詳細

#### WatchRegistryEvaluateTest

**目的:** v1.1.0 では `evaluate()` が実質デッドコードだったため、v1.1.1 での修正を回帰テストで保護する。

| テスト名 | 検証内容 |
|---------|---------|
| `evaluateFiresCallbackWhenConditionMatches` | `greaterThan(80.0)` 条件に `90.0` を渡した時 `onFire` が呼ばれること、`WatchEvent` のフィールド値が正しいこと |
| `evaluateResolvesWhenConditionClears` | 一度発火後、閾値を下回る値を渡した時 `onResolve` が呼ばれること |
| `firingCountReflectsEvaluationResult` | `evaluate()` 前後で `firingCount()` が正しく変化すること |

#### CompoundConditionChainTest

**目的:** v1.1.0 での `CompoundCondition.greaterThan()` が `null` を返すバグを保護する。

| テスト名 | 検証内容 |
|---------|---------|
| `compoundGreaterThanDoesNotBreakChain` | `.and(metric).greaterThan(v)` 後に続くメソッドチェーンが NPE を投げないこと。両条件成立時に発火すること |
| `compoundConditionDoesNotFireWhenOnlyPrimaryMatches` | セカンダリ条件が不成立の場合は発火しないこと |
| `compoundLessThanChainWorks` | `lessThan` を含む複合条件が正しく動作すること |

#### LocalhostBindingTest

**目的:** `startServer(port, bindAddress)` オーバーロードの動作確認。

| テスト名 | 検証内容 |
|---------|---------|
| `startServerWithLocalhostBindAddressAcceptsConnections` | `127.0.0.1` バインドで接続でき、`SNAPSHOT` コマンドに正しいレスポンスが返ること |
| `startServerDefaultBindsToAllInterfaces` | デフォルト (`startServer(port)`) でも `127.0.0.1` 経由で接続できること |
| `startServerIdempotentWithBindAddress` | 同じポートで 2 回呼んでも例外が発生しないこと (冪等性) |

### 11.4 テスト設計原則

**状態分離:** 各テストメソッドは `@BeforeEach` または `@AfterEach` で以下を呼び出す:
```java
SyslenzAgent.clearWatches();
SyslenzAgent.stopServer();
```

これにより Watch 条件とサーバーインスタンスがテスト間でリークしない。

**ポート競合回避:** `LocalhostBindingTest` は `19191`, `19192`, `19193` 等の高番号ポートを使用し、well-known ポートとの競合を避ける。

**非同期待機:** サーバー起動には `Thread.sleep(200)` を使用する。テスト環境での一時的な遅延に対応するためのシンプルな待機策。

**コールバック検証:** `AtomicBoolean`, `AtomicReference` を使用してコールバック呼び出しを非同期セーフに検証する。

### 11.5 カバレッジ対象外 (意図的)

以下は現時点のテストスコープ外:

- `JvmCollector` の MXBean 収集結果 (実行環境依存)
- `JsonExporter` の出力フォーマット
- クールダウンのタイムアウト待機テスト (テスト時間が長くなるため)
- `System.err` へのエラー出力
- `printSnapshot()` の stdout 出力

### 11.6 CI

GitHub Actions で `mvn test` を実行。JDK 17 以上のマトリクスでテストを行う。テスト結果は PR マージの前提条件。

---

## 12. デプロイ / 運用 (Deployment and Operations)

### 12.1 Maven Central への公開

syslenz4j は Maven Central に公開されている。

**Maven 座標:**
```xml
<dependency>
    <groupId>org.unlaxer.infra</groupId>
    <artifactId>syslenz4j</artifactId>
    <version>1.1.1</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'org.unlaxer.infra:syslenz4j:1.1.1'
```

**公開物:**
- `syslenz4j-1.1.1.jar` — バイナリ
- `syslenz4j-1.1.1-sources.jar` — ソース
- `syslenz4j-1.1.1-javadoc.jar` — Javadoc
- 各ファイルの `.asc` GPG 署名

### 12.2 公開プロセス

1. `pom.xml` の `<revision>` を更新
2. `mvn deploy` — `central-publishing-maven-plugin` (v0.9.0) が Sonatype Central に Push
3. `autoPublish: true` により自動的に Maven Central に公開される
4. GPG 署名: `maven-gpg-plugin` で `loopback` モード署名

### 12.3 組み込み方法 (Server モード)

```java
// アプリケーション起動時に一度呼ぶ
SyslenzAgent.startServer(9100);  // 0.0.0.0:9100 でリッスン

// localhost のみに制限する場合
SyslenzAgent.startServer(9100, "127.0.0.1");

// カスタムメトリクスの登録
SyslenzAgent.registry().gauge("active_sessions", sessionRegistry::size);
SyslenzAgent.registry().counter("total_requests", requestCounter::get);

// アラート条件の設定
SyslenzAgent.watch("heap_used")
    .greaterThan(800_000_000L)
    .severity(Severity.WARNING)
    .cooldown(60_000)
    .onFire(event -> log.warn("Heap pressure: {}", event.message()))
    .register();
```

syslenz 側の設定:
```toml
# syslenz.toml
[[targets]]
connect = "localhost:9100"
```

### 12.4 組み込み方法 (Stdout/プラグインモード)

```java
// syslenz がこのプロセスを子プロセスとして起動し stdout を読む
public static void main(String[] args) {
    SyslenzAgent.printSnapshot();
}
```

syslenz 側の設定:
```toml
[[plugins]]
command = ["java", "-jar", "myapp.jar"]
```

### 12.5 Spring Boot との統合例

```java
@Component
public class MetricsExporter {

    private final MyService myService;

    public MetricsExporter(MyService myService) {
        this.myService = myService;
        configureMetrics();
        configureWatches();
        SyslenzAgent.startServer(9100, "127.0.0.1");
    }

    private void configureMetrics() {
        MetricRegistry reg = SyslenzAgent.registry();
        reg.gauge("pending_jobs", myService::getPendingJobCount);
        reg.counter("processed_jobs", myService::getProcessedJobCount);
        reg.text("build_version", () -> BuildInfo.VERSION);
    }

    private void configureWatches() {
        SyslenzAgent.watch("heap_used")
            .greaterThan(1_073_741_824L)  // 1 GB
            .severity(Severity.CRITICAL)
            .cooldown(30_000)
            .onFire(event -> log.error("Critical heap pressure: {}", event.message()))
            .register();

        SyslenzAgent.watch("thread_deadlocked")
            .greaterThan(0)
            .severity(Severity.CRITICAL)
            .cooldown(0)
            .onFire(event -> alertService.send("DEADLOCK DETECTED: " + event.message()))
            .register();

        SyslenzAgent.watch("app_pending_jobs")
            .greaterThan(5000)
            .and("process_cpu_load").greaterThan(80.0)
            .severity(Severity.WARNING)
            .cooldown(120_000)
            .onFire(event -> log.warn("Job backlog with high CPU"))
            .register();
    }

    @PreDestroy
    void shutdown() {
        SyslenzAgent.stopServer();
        SyslenzAgent.clearWatches();
    }
}
```

### 12.6 セキュリティ考慮事項

- デフォルトの `0.0.0.0` バインドでは外部ネットワークからアクセス可能。本番環境では `127.0.0.1` にバインドするか、ファイアウォールで保護することを推奨
- syslenz4j の TCP サーバーには認証機能がない。信頼できるネットワーク (VPC 内、ループバック) での使用を前提とする
- JVM メトリクスにはシステム情報 (PID 等) が含まれる

### 12.7 JVM メトリクスの典型的な活用パターン

| ユースケース | 使用メトリクス | 条件例 |
|------------|-------------|-------|
| ヒープ枯渇アラート | `heap_used` | `> 1_500_000_000` (1.5 GB) |
| デッドロック検知 | `thread_deadlocked` | `> 0` |
| スレッドリーク監視 | `thread_count` | `> 500` |
| GC 停止時間監視 | `gc_total_time` (連続増加) | `> N` (前回比較は非対応; 外部で計算) |
| CPU 異常 | `process_cpu_load` | `outsideRange(0, 90)` |
| クラスローダーリーク | `classes_loaded` | `> 50000` |
| Direct メモリリーク | `buffer_direct_used` | `> 512_000_000` |

### 12.8 既知の制限事項 (v1.1.1)

| 制限 | 詳細 | 優先度 |
|-----|------|-------|
| セカンダリ条件の演算子が不完全 | `EQUAL`, `NOT_EQUAL`, `OUTSIDE_RANGE`, `INSIDE_RANGE` は compound で常に true | Low |
| `firingCount()` の可視性 | `WatchRegistry` は package-private のため外部から `firingCount()` を取得できない | Low |
| 単一サーバーインスタンス | 複数ポートでサーバーを起動する公開 API がない | Medium |
| Watch の個別削除 | `clearWatches()` は全条件を一括削除; 個別削除は非対応 | Medium |
| Stdout モードでの Watch 評価なし | `printSnapshot()` は Watch の `evaluate()` を呼ばない | Low |
| GC 増加量の検知 | `gc_total_time` の前回差分計算は非対応; 外部ツールで行う必要がある | Low |

---

## Appendix A. アーキテクチャ詳細 (Architecture Deep Dive)

### A.1 コンポーネント相関図

```
┌─────────────────────────────────────────────────────────────────┐
│                       Your Application                          │
│                                                                 │
│  SyslenzAgent.startServer(9100)                                 │
│  SyslenzAgent.registry().gauge("queue_depth", () -> q.size())   │
│  SyslenzAgent.watch("heap_used").greaterThan(800_000_000)        │
│      .severity(WARNING).cooldown(30_000).onFire(cb).register()  │
└─────────────────────────┬───────────────────────────────────────┘
                          │
                ┌─────────▼─────────┐
                │   SyslenzAgent    │  static facade / singleton owner
                │                   │
                │  REGISTRY ────────┼──► MetricRegistry
                │  WATCHES  ────────┼──► WatchRegistry
                │  serverInstance ──┼──► SyslenzServer (lazy-init)
                └─────────┬─────────┘
                          │ startServer() triggers
                ┌─────────▼─────────┐
                │   SyslenzServer   │  daemon thread "syslenz-server-N"
                │                   │
                │  acceptLoop()     │  ServerSocket + 1s SO_TIMEOUT
                │  handleClient()   │  reads SNAPSHOT\n, writes JSON\n
                │  collectSnapshot()│  calls JvmCollector + MetricRegistry
                │                   │  + WatchRegistry.evaluate()
                └──────────┬────────┘
                           │
           ┌───────────────┼───────────────┐
           │               │               │
  ┌────────▼──────┐ ┌──────▼──────┐ ┌─────▼──────────────┐
  │  JvmCollector │ │MetricRegistry│ │   WatchRegistry    │
  │               │ │              │ │                    │
  │ collect()     │ │ collect()    │ │ evaluate(Map)      │
  │   MemoryMXBean│ │ gauge sup.   │ │   WatchEntry[]     │
  │   GC MXBeans  │ │ counter sup. │ │     wasFiring      │
  │   ThreadMXBean│ │ text sup.    │ │     lastFiredAt    │
  │   RuntimeMXB. │ │              │ │     onFire cb      │
  │   OS MXBean   │ └──────────────┘ │     onResolve cb   │
  │   ClassLoad.  │                  └────────────────────┘
  │   BufferPool  │
  └───────┬───────┘
          │  List<Metric>
  ┌───────▼───────┐
  │ JsonExporter  │  string concat only (no lib)
  │               │
  │ export(...)   │  → ProcEntry JSON
  └───────┬───────┘
          │  UTF-8 single-line JSON
  ┌───────▼───────┐
  │ syslenz daemon│  --connect localhost:9100
  └───────────────┘
```

### A.2 スナップショットパイプラインの詳細フロー

`SyslenzServer.collectSnapshot()` が呼ばれたときの内部処理シーケンス:

```
collectSnapshot()
  1. JvmCollector jc = new JvmCollector()
  2. List<Metric> jvmMetrics = jc.collect()
       ├─ collectMemory(metrics)        // MemoryMXBean
       ├─ collectGc(metrics)            // GarbageCollectorMXBean[]
       ├─ collectThreads(metrics)       // ThreadMXBean
       ├─ collectRuntime(metrics)       // RuntimeMXBean
       ├─ collectOs(metrics)            // OperatingSystemMXBean
       ├─ collectClassLoading(metrics)  // ClassLoadingMXBean
       └─ collectBufferPools(metrics)   // BufferPoolMXBean[]

  3. List<Metric> customMetrics = registry.collect()
       └─ foreach Registration in ConcurrentHashMap:
            try { evaluate supplier } catch { skip }
            → "text" → Text Metric
            → Long/Integer/AtomicLong → Integer Metric (unit: "count")
            → other Number → Float Metric

  4. if (watchRegistry != null):
       Map<String, Double> metricValues = new HashMap<>()
       foreach jvmMetric with Number value:
           metricValues.put(name, doubleValue)
       foreach customMetric with Number value:
           metricValues.put("app_" + name, doubleValue)  // with prefix
           metricValues.put(name_without_app, doubleValue)  // without prefix
       watchRegistry.evaluate(metricValues)

  5. String json = JsonExporter.export(jvmMetrics, customMetrics)
  6. return json.replace("\n", "").replace("\r", "")
```

### A.3 TCP サーバー内部スレッドモデル

```
Main Thread (application)
  └─ SyslenzAgent.startServer(9100)
       └─ synchronized(SyslenzAgent.class) {
              SyslenzServer server = new SyslenzServer(9100, "0.0.0.0", ...)
              server.start()
              serverInstance = server
          }

Daemon Thread: "syslenz-server-9100"
  └─ SyslenzServer.acceptLoop()
       └─ ServerSocket.bind(9100)
            └─ loop while (running):
                 try { client = serverSocket.accept() } // 1s SO_TIMEOUT
                 catch SocketTimeoutException { continue }  // check running
                 try { handleClient(client) }
                 catch Exception { System.err.println }
                 finally { client.close() }

  handleClient(socket):
    socket.setSoTimeout(30_000)
    BufferedReader reader ...
    while ((line = reader.readLine()) != null):
        if "SNAPSHOT".equalsIgnoreCase(line.trim()):
            String json = collectSnapshot()
            out.write(json.getBytes("UTF-8"))
            out.write('\n')
            out.flush()
        // else: silently ignore
```

クライアント接続は逐次処理 (sequential)。複数の syslenz インスタンスが同じポートに同時接続した場合、後続接続は前の接続が完了するまで accept されない。通常の監視ユースケース (syslenz が 10–60 秒ごとにポーリング) では問題にならない。

### A.4 Watch API の内部データ構造

```
SyslenzAgent (static)
  └─ WatchRegistry WATCHES (singleton)
       └─ CopyOnWriteArrayList<WatchEntry> entries
            └─ WatchEntry [per register() call]
                 ├─ WatchCondition condition
                 │    ├─ String metricName
                 │    ├─ Operator operator
                 │    ├─ double threshold
                 │    ├─ double rangeMin, rangeMax
                 │    ├─ Severity severity
                 │    ├─ long cooldownMs
                 │    ├─ Consumer<WatchEvent> onFire
                 │    ├─ Consumer<WatchEvent> onResolve
                 │    └─ CompoundCondition compound (nullable)
                 │         ├─ String metricName
                 │         ├─ Operator operator
                 │         └─ double threshold
                 ├─ boolean wasFiring (mutable)
                 └─ long lastFiredAt (mutable, epoch ms)
```

`WatchCondition` は `register()` 後も変更可能なフィールドを持つが、登録後の変更はサポート外の動作 (undefined behavior)。`register()` 後は `WatchCondition` への参照を破棄することを推奨する。

### A.5 MetricRegistry の内部データ構造

```
MetricRegistry
  └─ ConcurrentHashMap<String, Registration> registrations
       └─ Registration
            ├─ String name           // 登録時の名前 ("queue_depth")
            ├─ String description    // 説明文
            ├─ String kind           // "gauge" | "counter" | "text"
            ├─ NumberSupplier numberFn  // gauge/counter 用 (nullable)
            └─ StringSupplier stringFn  // text 用 (nullable)
```

同一名前で再登録すると `ConcurrentHashMap.put()` により上書きされる。古い `Registration` への参照は次の GC で回収される。

---

## Appendix B. JVM メトリクスリファレンス (Full Metric Reference)

v1.1.1 時点で `JvmCollector` が生成する全メトリクスのリファレンス。

### B.1 メモリメトリクス

| メトリクス名 | 型タグ | 単位 | MXBean メソッド | 説明 |
|-------------|-------|------|----------------|------|
| `heap_used` | Bytes | — | `MemoryMXBean.getHeapMemoryUsage().getUsed()` | 現在のヒープ使用量 |
| `heap_committed` | Bytes | — | `MemoryMXBean.getHeapMemoryUsage().getCommitted()` | OS にコミット済みヒープ |
| `heap_max` | Bytes | — | `MemoryMXBean.getHeapMemoryUsage().getMax()` | -Xmx で設定した最大ヒープ |
| `non_heap_used` | Bytes | — | `MemoryMXBean.getNonHeapMemoryUsage().getUsed()` | 非ヒープ使用量 |
| `non_heap_committed` | Bytes | — | `MemoryMXBean.getNonHeapMemoryUsage().getCommitted()` | 非ヒープコミット量 |

ヒープ使用率の計算例 (`heap_used` / `heap_max` × 100) は syslenz4j 側では行わない。syslenz 本体またはダッシュボード側での計算を想定。

### B.2 GC メトリクス

以下はパターンであり、実際のメトリクス名は JVM の GC 実装に依存する。

**G1GC (デフォルト: JDK 9+)**

| メトリクス名 | 説明 |
|-------------|------|
| `gc_g1_young_generation_count` | G1 Young GC 回数 |
| `gc_g1_young_generation_time` | G1 Young GC 累積時間 (秒) |
| `gc_g1_old_generation_count` | G1 Old GC 回数 |
| `gc_g1_old_generation_time` | G1 Old GC 累積時間 (秒) |
| `gc_total_count` | 全 GC 合計回数 |
| `gc_total_time` | 全 GC 合計時間 (秒) |

**ZGC (JDK 15+)**

| メトリクス名 | 説明 |
|-------------|------|
| `gc_zgc_pauses_count` | ZGC ポーズ回数 |
| `gc_zgc_pauses_time` | ZGC ポーズ累積時間 (秒) |
| `gc_zgc_cycles_count` | ZGC サイクル回数 |
| `gc_zgc_cycles_time` | ZGC サイクル累積時間 (秒) |

**Parallel GC**

| メトリクス名 | 説明 |
|-------------|------|
| `gc_ps_scavenge_count` | Parallel Scavenge (Young) 回数 |
| `gc_ps_scavenge_time` | Parallel Scavenge 累積時間 (秒) |
| `gc_ps_marksweep_count` | Parallel MarkSweep (Old) 回数 |
| `gc_ps_marksweep_time` | Parallel MarkSweep 累積時間 (秒) |

*注意: GC コレクタ名はリリース間で変わる可能性がある。安定したモニタリングのためには `gc_total_count` / `gc_total_time` を使用することを推奨。*

### B.3 スレッドメトリクス

| メトリクス名 | 型タグ | 単位 | 正常値の目安 | 異常の兆候 |
|-------------|-------|------|------------|-----------|
| `thread_count` | Integer | count | アプリ依存 (数十〜数百) | 急増 = スレッドリーク |
| `thread_peak` | Integer | count | `thread_count` 以上 | 参考値 (単調増加) |
| `thread_daemon` | Integer | count | `thread_count` の過半数 | 異常低下 = デーモンリーク |
| `thread_deadlocked` | Integer | count | 0 | 1 以上 = 即時対応 |

### B.4 ランタイムメトリクス

| メトリクス名 | 型タグ | 単位 | 説明 | 用途 |
|-------------|-------|------|------|------|
| `uptime` | Duration | s | JVM 起動からの経過秒数 | 再起動検知 |
| `vm_name` | Text | — | JVM 実装名 | 環境確認 |

`vm_name` の例:
- `"OpenJDK 64-Bit Server VM"` — OpenJDK
- `"Java HotSpot(TM) 64-Bit Server VM"` — Oracle JDK
- `"Eclipse OpenJ9 VM"` — OpenJ9

### B.5 OS メトリクス

| メトリクス名 | 型タグ | 単位 | 利用可否 | 説明 |
|-------------|-------|------|---------|------|
| `available_processors` | Integer | count | 常に利用可 | JVM に割り当てられた CPU 数 |
| `system_load_average` | Float | — | Unix 系のみ | 1 分間システムロードアベレージ |
| `process_cpu_load` | Float | % | Oracle/OpenJDK のみ | プロセス CPU 使用率 (0–100) |
| `process_cpu_time` | Duration | s | Oracle/OpenJDK のみ | プロセス累積 CPU 時間 |

Windows では `system_load_average` が `-1` を返すためスキップされる。

### B.6 クラスローディングメトリクス

| メトリクス名 | 型タグ | 単位 | 説明 | クラスリーク検知 |
|-------------|-------|------|------|--------------|
| `classes_loaded` | Integer | count | 現在ロード中クラス数 | 継続的増加 = リーク疑い |
| `classes_total_loaded` | Integer | count | JVM 起動以来の総ロード数 | 参考値 |
| `classes_unloaded` | Integer | count | JVM 起動以来のアンロード数 | 低値 = クラスが解放されていない |

動的クラスローディング (JSP, Groovy スクリプト, OSGi 等) 環境では `classes_loaded` の増加は正常。問題は `classes_unloaded` が 0 に近い状態で `classes_loaded` が増加し続ける場合。

### B.7 バッファプールメトリクス

| メトリクス名パターン | 説明 | Direct バッファの例 |
|-------------------|------|-----------------|
| `buffer_direct_used` | Direct バッファ使用量 (bytes) | NIO, Netty, JDBC ドライバが消費 |
| `buffer_direct_capacity` | Direct バッファ総容量 (bytes) | `-XX:MaxDirectMemorySize` で制限 |
| `buffer_direct_count` | Direct バッファ数 | `ByteBuffer.allocateDirect()` の呼び出し数 |
| `buffer_mapped_used` | Memory-mapped バッファ使用量 | `FileChannel.map()` 使用時 |
| `buffer_mapped_capacity` | Memory-mapped バッファ総容量 | — |
| `buffer_mapped_count` | Memory-mapped バッファ数 | — |

Direct バッファはヒープ外メモリを消費する。`buffer_direct_used` の急増は Off-Heap メモリリークの可能性を示す。

### B.8 カスタムメトリクスの命名規則

アプリケーションが `MetricRegistry` に登録するメトリクスは以下の命名規則を推奨する:

```
<component>_<measurement>[_<unit>]
```

例:
- `orders_pending` — 保留中の注文数
- `cache_hit_rate` — キャッシュヒット率
- `db_connection_pool_active` — アクティブな DB 接続数
- `message_queue_depth` — メッセージキューの深さ
- `api_response_time_ms` — API 応答時間 (ミリ秒)

Watch で参照する際は `app_` プレフィックスを付けて `watch("app_orders_pending")` と指定する。

---

## Appendix C. 設計決定記録 (Architecture Decision Records)

### ADR-001: ゼロ依存ポリシー

**ステータス:** Accepted

**コンテキスト:** モニタリングライブラリは様々なアプリケーションに組み込まれる。外部依存があると以下の問題が生じる:
- クラスパス汚染
- バージョン競合 (Jackson, Netty, SLF4J 等のバージョンが既に固定されているケース)
- セキュリティ脆弱性の transitively な引き込み

**決定:** ランタイム依存は一切持たない。JDK 標準ライブラリのみ使用する。JSON も自前実装。

**結果:** JAR サイズが最小化され、任意の Java プロジェクトに影響なく組み込める。`JsonExporter` の機能は限定的 (型タグ付き union のみサポート) だが、syslenz プロトコルの要件を満たす。

### ADR-002: デーモンスレッドによるサーバー動作

**ステータス:** Accepted

**コンテキスト:** TCP サーバーを daemon thread として動作させるかどうか。

**決定:** `serverThread.setDaemon(true)` を使用する。

**理由:** 監視ライブラリがアプリケーションのシャットダウンを妨げることは許容されない。モニタリングは補助的な機能であり、メインアプリケーションの終了を遅延させてはならない。グレースフルシャットダウンが必要な場合は明示的に `stopServer()` を呼ぶ。

**トレードオフ:** JVM が突然シャットダウンした場合、進行中の接続が途中で切断される。syslenz は次のポーリングで再接続するため実用上問題ない。

### ADR-003: WatchRegistry のグローバルシングルトン

**ステータス:** Accepted

**コンテキスト:** Watch 条件の管理方法。インスタンスベース vs. グローバルシングルトン。

**決定:** `SyslenzAgent` クラスの static フィールドとして 1 JVM に 1 インスタンスのシングルトンを採用。

**理由:** 
- シンプルな API (`SyslenzAgent.watch(...)` が最も直感的)
- サーバーとの統合が自動 (明示的な接続配線不要)
- Spring, CDI 等の DI コンテナへの依存なし

**トレードオフ:** テスト間での状態汚染リスク。`clearWatches()` / `stopServer()` をテスト teardown で呼ぶことで対処。

### ADR-004: CompoundCondition の AND のみサポート

**ステータス:** Accepted

**コンテキスト:** 複合条件として AND, OR, NOT の論理演算子をサポートするか。

**決定:** AND のみサポート。OR と NOT は非対応。

**理由:** 
- ユースケースの多数派は「メトリクス A が高い AND メトリクス B も高い」という AND 条件
- OR 条件は複数の独立した `watch()` 登録で表現できる
- NOT は演算子の組み合わせで表現できる (`notEqualTo`, `outsideRange` 等)
- API をシンプルに保つ

### ADR-005: WatchEvent を record として実装

**ステータス:** Accepted

**コンテキスト:** アラートイベントの表現方法。

**決定:** Java 16+ の `record` 構文を使用。

**理由:**
- イミュータブル性の自動保証
- `equals()`, `hashCode()`, `toString()` が自動生成
- コンパクトな定義
- JDK 17 最低バージョン要件と整合

**結果:** `WatchEvent` は生成後に変更不可。コールバック受信側は安全に保持・共有できる。

### ADR-006: スナップショット時の毎回 JvmCollector インスタンス生成

**ステータス:** Accepted

**コンテキスト:** `JvmCollector` をシングルトンにするか、スナップショットごとに新規生成するか。

**決定:** スナップショットごとに `new JvmCollector()` を生成する。

**理由:**
- `JvmCollector` は stateless であるため、毎回生成してもコスト差はない
- スレッドセーフの心配が不要
- テストが容易 (コンストラクタを呼ぶだけでインスタンスが取得できる)

**トレードオフ:** 若干の GC 圧力 (各スナップショットで 1 オブジェクトをアロケート)。モニタリング頻度は 10–60 秒に 1 回のため無視できる。

---

## Appendix D. トラブルシューティング (Troubleshooting)

### D.1 よくある問題

#### Watch コールバックが発火しない

**原因 1:** Server モードを使用していない。

```java
// これだけでは Watch は動作しない
SyslenzAgent.watch("heap_used").greaterThan(800_000_000).onFire(cb).register();
// ↑ evaluate() が呼ばれない

// Server モードを起動する必要がある
SyslenzAgent.startServer(9100);  // ← これが必要
```

**原因 2:** syslenz がポーリングしていない。Watch は `SNAPSHOT` コマンドが来た時に評価されるため、syslenz の接続がなければコールバックは発火しない。`printSnapshot()` でのテスト: Watch コールバックはデバッグのため手動で `evaluate()` を呼ぶ必要がある。

**原因 3:** `cooldown` が長すぎる。デフォルト 10 秒。テスト中は `cooldown(0)` を使用する。

**原因 4:** メトリクス名が間違っている。カスタムメトリクスを `watch()` する場合は `app_` プレフィックスが必要。

```java
// 正しい例
SyslenzAgent.registry().gauge("queue_depth", () -> q.size());
SyslenzAgent.watch("app_queue_depth").greaterThan(1000).register();  // ← app_ が必要

// または
SyslenzAgent.watch("queue_depth").greaterThan(1000).register();      // これも動作する
```

#### `startServer()` を呼んでもポートが開かない

`startServer()` はデーモンスレッドを起動して即座に返る。ポートが開くまでに数ミリ秒かかる場合がある。テストで即座に接続する場合は `Thread.sleep(100)` 等の待機を挟む。

ポートが既に使用中の場合、サーバーは `System.err` にエラーを出力して停止する。例外はスローされない。

#### 複合条件が期待通りに動作しない

セカンダリ条件の演算子が `EQUAL`, `NOT_EQUAL`, `OUTSIDE_RANGE`, `INSIDE_RANGE` の場合、v1.1.1 時点では常に `true` として扱われる。セカンダリには `greaterThan`, `lessThan`, `greaterThanOrEqual`, `lessThanOrEqual` のみを使用すること。

#### `process_cpu_load` がスナップショットに含まれない

`com.sun.management` が利用できない JVM (IBM J9 等) では収集されない。`instanceof` チェックにより自動的にスキップされる。

#### テスト実行時に `BindException: Address already in use`

テストクラスの `@AfterEach` で `SyslenzAgent.stopServer()` を呼んでいない。または複数のテストが同じポートを使用している。テスト用ポートは 49152–65535 の範囲から選ぶことを推奨。

### D.2 診断方法

**現在のスナップショット内容を確認:**
```bash
# syslenz4j サーバーに接続してスナップショットを取得
echo "SNAPSHOT" | nc localhost 9100
```

**Watch の発火状態を確認:**
```java
// テストや管理エンドポイントから
WatchRegistry watches = SyslenzAgent.watches();
System.out.println("Firing count: " + watches.firingCount());
```

**メトリクス名の確認:**
```java
// スナップショット JSON を標準出力に出してフィールド名を確認
SyslenzAgent.printSnapshot();
```

---

## Appendix E. バージョン間の変更詳細 (Changelog Detail)

### E.1 v1.1.1 (2026-04-19) — バグ修正

#### Fix 1: CompoundCondition.greaterThan() が null を返す問題

**バグ:** `WatchCondition.CompoundCondition.greaterThan()` が `null` を返していた。fluent チェーン `.and(metric).greaterThan(v).severity(...)` の `.severity()` 呼び出しで `NullPointerException` が発生。

**根本原因:** `CompoundCondition` クラス内の演算子設定メソッドが `return parent` を忘れていた (`return null` と同等だった)。

**修正:** `greaterThan()`, `lessThan()`, `greaterThanOrEqual()`, `lessThanOrEqual()` の全メソッドが `WatchCondition` (parent) を返すよう修正。`greaterThanOrEqual`, `lessThanOrEqual` の 2 メソッドは今バージョンで新規追加。

**影響範囲:** `and().greaterThan(v)` 以降のチェーンを使用しているすべてのコード。v1.1.0 では NPE が発生していたため、影響コードは実質的に存在しない (コンパイルはできていたが実行時に必ず失敗)。

#### Fix 2: WatchRegistry.evaluate() がデッドコード

**バグ:** `SyslenzServer.collectSnapshot()` が `watchRegistry.evaluate()` を呼んでいなかった。Watch コールバックが一切発火しない。

**根本原因:** v1.1.0 実装時に `WatchRegistry` の接続を忘れた。

**修正:** `collectSnapshot()` 内でスナップショット収集後にメトリクス値マップを構築し、`watchRegistry.evaluate(metricValues)` を呼ぶよう変更。

**メトリクス値マップの構築ロジック:**
```java
Map<String, Double> metricValues = new HashMap<>();
// JVM メトリクス
for (JvmCollector.Metric m : jvmMetrics) {
    if (m.value instanceof Number) {
        metricValues.put(m.name, ((Number) m.value).doubleValue());
    }
}
// カスタムメトリクス (プレフィックスあり・なしの両方)
for (JvmCollector.Metric m : customMetrics) {
    if (m.value instanceof Number) {
        String key = m.name.startsWith("app_") ? m.name.substring(4) : m.name;
        metricValues.put(m.name, ((Number) m.value).doubleValue());  // app_ あり
        metricValues.put(key, ((Number) m.value).doubleValue());     // app_ なし
    }
}
watchRegistry.evaluate(metricValues);
```

#### Fix 3: startServer(port, bindAddress) オーバーロードの追加

**要求:** ループバックのみにバインドするオプションが必要。デフォルト `0.0.0.0` では外部ネットワークに公開される。

**修正:** `startServer(int port, String bindAddress)` オーバーロードを追加。`SyslenzServer` コンストラクタも対応するオーバーロードに更新。

**後方互換性:** `startServer(int port)` は変更なし (バインドアドレス `"0.0.0.0"`)。

### E.2 v1.1.0 (2026-04-17) — Watch API 追加

Watch API の主要コンポーネントが追加された:

- `WatchCondition` — fluent builder
- `WatchRegistry` — 条件管理と評価
- `WatchEvent` — アラートイベント record
- `Severity` — INFO / WARNING / CRITICAL
- `Operator` — 8 種の比較演算子
- `SyslenzAgent.watch()`, `clearWatches()`, `stopServer()` メソッド追加

また、パッケージ・GroupId・ArtifactId の変更が行われた:

| 項目 | v1.0.0 | v1.1.0+ |
|-----|--------|---------|
| GroupId | `io.syslenz` | `org.unlaxer.infra` |
| ArtifactId | `syslenz-java` | `syslenz4j` |
| Package | `io.syslenz` | `org.unlaxer.infra.syslenz4j` |

### E.3 v1.0.0 (2026-04-10) — 初版

- `SyslenzAgent` (server mode + stdout mode)
- `JvmCollector` (7 カテゴリ、約 25+ メトリクス)
- `MetricRegistry` (gauge, counter, text)
- `SyslenzServer` (TCP サーバー)
- `JsonExporter` (ProcEntry JSON シリアライザ)
- Maven Central 公開

---

## Appendix F. 将来の拡張計画 (Roadmap)

*以下は計画段階の内容であり、確定した仕様ではない。*

### F.1 Watch API の拡充 (想定: v1.2.0)

- セカンダリ条件での全演算子サポート (`EQUAL`, `NOT_EQUAL`, `OUTSIDE_RANGE`, `INSIDE_RANGE`)
- Watch の個別削除 (`WatchHandle.unregister()`)
- 3 条件以上の連鎖 (`and("c").greaterThan(v).and("d").lessThan(v)`)
- OR 条件のサポート (`.or(metricName)`)
- `SyslenzAgent.firingWatches()` — 外部から発火中の Watch リストを取得

### F.2 メトリクス拡充 (想定: v1.2.0)

- `heap_used_pct` の組み込みサポート (heap_used / heap_max × 100 を自動計算)
- JVM フラグ (`-Xmx` の値等) のメタデータ収集
- コンテナ環境でのリソース制限 (`cgroups` ベースの CPU/メモリ制限値)

### F.3 複数サーバーインスタンス (想定: v1.3.0)

- 複数ポートでの同時リッスン
- `SyslenzAgent.addServer(port)` / `removeServer(port)` API

### F.4 非対応の予定

以下は明示的にスコープ外とする:

- **プッシュ型の通知 (Webhook, PagerDuty, Slack 統合)** — コールバック内でユーザーが実装する
- **メトリクス履歴** — syslenz 本体または外部時系列 DB の責務
- **設定ファイル / 環境変数による設定** — コード設定を維持
- **JMX RMI リモート接続** — `--connect` TCP プロトコルのみサポート
- **外部依存の追加** — ゼロ依存ポリシーは不変

---

*このドキュメントは syslenz4j v1.1.1 の実装に基づいて作成された。*
