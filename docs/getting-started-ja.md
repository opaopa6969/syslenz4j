[English version](getting-started.md)

# はじめに

このガイドでは、syslenz4j を Java プロジェクトへ追加し、syslenz デーモンへ接続し、Spring Boot と統合する手順を説明します。

---

## 前提条件

- Java 17 以上
- Maven 3.6+ または Gradle 7+
- [syslenz](https://github.com/opaopa6969/syslenz) インストール済み（`--connect` コマンド用）

---

## ステップ 1 — 依存関係を追加する

### Maven

```xml
<dependency>
    <groupId>org.unlaxer.infra</groupId>
    <artifactId>syslenz4j</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Gradle（Kotlin DSL）

```kotlin
dependencies {
    implementation("org.unlaxer.infra:syslenz4j:1.1.0")
}
```

### Gradle（Groovy DSL）

```groovy
dependencies {
    implementation 'org.unlaxer.infra:syslenz4j:1.1.0'
}
```

syslenz4j に推移的依存関係はありません。JDK 標準ライブラリのみを使用します。

---

## ステップ 2 — サーバーを起動する

アプリケーションのエントリポイントに1行追加します:

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;

public class Main {
    public static void main(String[] args) {
        SyslenzAgent.startServer(9100);

        // ... アプリケーションのコード ...
    }
}
```

`startServer()` はノンブロッキングです。daemon スレッドを起動してすぐに戻ります。サーバーが動作していても JVM は通常通りシャットダウンします。

---

## ステップ 3 — syslenz を接続する

ターミナルから:

```bash
syslenz --connect localhost:9100
```

ヒープ使用量・GC 圧力・スレッド数・CPU 負荷などの JVM メトリクスのライブダッシュボードが表示されます。

---

## ステップ 4 — カスタムメトリクスを登録する

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;

// SyslenzAgent.startServer() 呼び出しの前後どちらでも登録可能:

// ゲージ — スナップショットごとに現在値を読み取る
SyslenzAgent.registry().gauge("queue_size", () -> taskQueue.size());
SyslenzAgent.registry().gauge("cache_hit_rate", () -> cache.hitRate());

// カウンター — 単調増加する値
SyslenzAgent.registry().counter("requests_total", requestCounter::get);
SyslenzAgent.registry().counter("errors_total", errorCounter::get);

// テキスト — ラベル・バージョン文字列・環境マーカー
SyslenzAgent.registry().text("app_version", () -> "2.3.1");
SyslenzAgent.registry().text("environment", () -> System.getenv("APP_ENV"));
```

カスタムメトリクスはダッシュボードで `app_` プレフィックス付きで表示されます（例: `app_queue_size`）。

---

## ステップ 5 — Watch 条件を設定する

サーバー起動の前後どちらでも閾値アラートを定義できます:

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;
import org.unlaxer.infra.syslenz4j.Severity;

// ヒープ使用率 80% 超でアラート
// （カスタムゲージでヒープ使用率を計算）
SyslenzAgent.registry().gauge("heap_used_pct",
    () -> {
        var mem = java.lang.management.ManagementFactory.getMemoryMXBean();
        var heap = mem.getHeapMemoryUsage();
        return heap.getMax() > 0 ? (heap.getUsed() * 100.0 / heap.getMax()) : 0.0;
    }
);

SyslenzAgent.watch("heap_used_pct")
    .greaterThan(80.0)
    .severity(Severity.WARNING)
    .cooldown(30_000)                          // 30秒間は再発火しない
    .onFire(event -> log.warn("Heap 高負荷: {}%", event.value()))
    .onResolve(event -> log.info("Heap 回復: {}%", event.value()))
    .register();

SyslenzAgent.watch("thread_deadlocked")
    .greaterThan(0.0)
    .severity(Severity.CRITICAL)
    .onFire(event -> pagerDuty.trigger("デッドロック検出！"))
    .register();
```

> **注意**: v1.1.0 では、`WatchRegistry.evaluate()` がスナップショットパスに組み込まれていないため、Watch コールバックは自動発火しません。既知の問題として [GitHub #3](https://github.com/opaopa6969/syslenz4j/issues/3) で追跡中です。必要な場合は `SyslenzAgent.watches().evaluate(metricsMap)` を手動で呼び出してください。

---

## プラグインモード（標準出力への1回限り出力）

短期間のバッチジョブでメトリクスを1回だけ標準出力に出力したい場合:

```java
SyslenzAgent.printSnapshot();
```

これで ProcEntry JSON 1行が標準出力に出力されます。syslenz にパイプするか、任意の JSON ツールで処理できます。

---

## Spring Boot 統合

### 設定クラスを追加

```java
// src/main/java/com/example/config/SyslenzLifecycle.java
package com.example.config;

import org.unlaxer.infra.syslenz4j.SyslenzAgent;
import org.unlaxer.infra.syslenz4j.Severity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class SyslenzLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SyslenzLifecycle.class);

    @Value("${syslenz.port:9100}")
    private int port;

    private final MeterRegistry meterRegistry;
    private volatile boolean running;

    public SyslenzLifecycle(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void start() {
        // Micrometer のゲージを syslenz4j にブリッジ
        SyslenzAgent.registry().gauge("http_server_active_requests",
            () -> {
                var gauge = meterRegistry.find("http.server.requests").gauge();
                return gauge != null ? gauge.value() : 0.0;
            }
        );

        // Watch 条件
        SyslenzAgent.watch("process_cpu_load")
            .greaterThan(85.0)
            .severity(Severity.WARNING)
            .cooldown(60_000)
            .onFire(event -> log.warn("CPU 高負荷: {}%", String.format("%.1f", event.value())))
            .register();

        SyslenzAgent.watch("thread_deadlocked")
            .greaterThan(0.0)
            .severity(Severity.CRITICAL)
            .onFire(event -> log.error("デッドロック検出 — {} スレッド", (int) event.value()))
            .register();

        SyslenzAgent.startServer(port);
        running = true;
        log.info("syslenz4j をポート {} で起動しました", port);
    }

    @Override
    public void stop() {
        SyslenzAgent.stopServer();
        running = false;
        log.info("syslenz4j を停止しました");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;  // 最後に起動、最初に停止
    }
}
```

### application.properties

```properties
# syslenz 監視ポート（デフォルト: 9100）
syslenz.port=9100
```

### 動作確認

Spring Boot アプリケーションを起動して以下を実行:

```bash
syslenz --connect localhost:9100
```

JVM メトリクスと登録したカスタムメトリクスが表示されます。

---

## 最小限のサンプル（フレームワークなし）

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;
import org.unlaxer.infra.syslenz4j.Severity;
import java.util.concurrent.atomic.AtomicLong;

public class App {

    static final AtomicLong requestCount = new AtomicLong();
    static final AtomicLong errorCount = new AtomicLong();

    public static void main(String[] args) throws InterruptedException {
        // メトリクスサーバー起動
        SyslenzAgent.startServer(9100);

        // カスタムメトリクス登録
        SyslenzAgent.registry().counter("requests_total", requestCount::get);
        SyslenzAgent.registry().counter("errors_total", errorCount::get);
        SyslenzAgent.registry().text("version", () -> "1.0.0");

        // エラースパイクの監視
        SyslenzAgent.watch("app_errors_total")
            .greaterThan(100)
            .severity(Severity.WARNING)
            .onFire(event -> System.err.println("エラースパイク: " + event.value()))
            .register();

        // 作業のシミュレーション
        while (true) {
            requestCount.incrementAndGet();
            Thread.sleep(100);
        }
    }
}
```

---

## トラブルシューティング

**ポートが使用中**: ポート番号を変更するか `lsof -i :9100` で確認してください。

**syslenz が接続できない**: サーバーが起動していることを確認し（`startServer()` で例外が出ていないか）、ファイアウォールのルールを確認してください。

**カスタムメトリクスが表示されない**: `registry().gauge(...)` は `startServer()` の前後どちらでも呼び出せます。カスタムメトリクスは登録時ではなく、`SNAPSHOT` リクエストごとに収集されます。

**Watch コールバックが発火しない**: v1.1.0 の既知の問題です。[GitHub #3](https://github.com/opaopa6969/syslenz4j/issues/3) を参照してください。
