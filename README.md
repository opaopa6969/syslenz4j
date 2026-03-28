# syslenz-java

JVM および アプリケーション のメトリクスを [syslenz](https://github.com/opaopa6969/syslenz) にエクスポートする Java ライブラリです。

## 概要

`syslenz-java` は Java アプリケーション内で動作し、JVM の内部メトリクスをリアルタイムで syslenz に提供します。外部依存ライブラリは不要で、JDK 標準の MXBean のみを使用します。

### アーキテクチャ

```
Java Application
  └─ syslenz-java (ライブラリ)
       ├─ JVM メトリクス収集 (MXBeans)
       ├─ アプリケーション カスタムメトリクス
       └─ TCP サーバー (syslenz --connect 対応)
           または stdout 出力 (プラグインモード)
```

## インストール

### Maven

```xml
<dependency>
    <groupId>io.syslenz</groupId>
    <artifactId>syslenz-java</artifactId>
    <version>1.0.0</version>
</dependency>
```

### ビルド

```bash
cd integrations/syslenz-java
mvn package
```

## 使い方

### サーバーモード (推奨)

アプリケーションの `main()` に以下を追加します:

```java
import io.syslenz.SyslenzAgent;

public class MyApp {
    public static void main(String[] args) {
        // syslenz メトリクスサーバーを起動 (デーモンスレッド)
        SyslenzAgent.startServer(9100);

        // ... アプリケーションのコード ...
    }
}
```

ターミナルから接続:

```bash
syslenz --connect localhost:9100
```

### プラグインモード (stdout)

```java
// 1回のスナップショットを stdout に出力
SyslenzAgent.printSnapshot();
```

### カスタムメトリクスの登録

```java
import io.syslenz.SyslenzAgent;

// ゲージ: 現在の値を返すサプライヤー
SyslenzAgent.registry().gauge("queue_size", () -> myQueue.size());

// カウンター: 単調増加する値
SyslenzAgent.registry().counter("requests_total", requestCounter::get);

// テキスト: ラベルやバージョン情報
SyslenzAgent.registry().text("app_version", () -> "2.3.1");
```

## 収集メトリクス

| カテゴリ | メトリクス | ソース |
|---------|-----------|--------|
| ヒープメモリ | used, committed, max | MemoryMXBean |
| 非ヒープメモリ | used, committed | MemoryMXBean |
| GC | コレクター別 count/time, 合計 | GarbageCollectorMXBean |
| スレッド | count, peak, daemon, deadlock | ThreadMXBean |
| ランタイム | uptime, VM名 | RuntimeMXBean |
| OS | CPU load, processors, system load | OperatingSystemMXBean |
| クラスローディング | loaded, unloaded | ClassLoadingMXBean |
| バッファプール | direct/mapped の使用量 | BufferPoolMXBean |

## プロトコル

TCP サーバーは以下のシンプルなプロトコルを使用します:

1. クライアントが `SNAPSHOT\n` を送信
2. サーバーが ProcEntry JSON を1行で返却
3. 接続は維持またはクローズ可能

---

# syslenz-java (English)

A Java library for exporting JVM and application metrics to [syslenz](https://github.com/opaopa6969/syslenz).

## Overview

`syslenz-java` runs inside your Java application and provides real-time JVM metrics to syslenz. It has zero external dependencies -- only standard JDK MXBeans are used.

### Architecture

```
Java Application
  └─ syslenz-java (library)
       ├─ Collects JVM metrics (MXBeans)
       ├─ Collects application custom metrics
       └─ Serves on TCP port (syslenz --connect)
           or writes to stdout (plugin mode)
```

## Installation

### Maven

```xml
<dependency>
    <groupId>io.syslenz</groupId>
    <artifactId>syslenz-java</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Build from source

```bash
cd integrations/syslenz-java
mvn package
```

## Usage

### Server mode (recommended)

Add to your application's `main()`:

```java
import io.syslenz.SyslenzAgent;

public class MyApp {
    public static void main(String[] args) {
        // Start syslenz metrics server (daemon thread)
        SyslenzAgent.startServer(9100);

        // ... your application code ...
    }
}
```

Then from terminal:

```bash
syslenz --connect localhost:9100
```

### Plugin mode (stdout)

```java
// Print a single snapshot to stdout
SyslenzAgent.printSnapshot();
```

### Registering custom metrics

```java
import io.syslenz.SyslenzAgent;

// Gauge: supplier that returns the current value
SyslenzAgent.registry().gauge("queue_size", () -> myQueue.size());

// Counter: monotonically increasing value
SyslenzAgent.registry().counter("requests_total", requestCounter::get);

// Text: labels, version strings, etc.
SyslenzAgent.registry().text("app_version", () -> "2.3.1");
```

## Collected Metrics

| Category | Metrics | Source |
|----------|---------|--------|
| Heap memory | used, committed, max | MemoryMXBean |
| Non-heap memory | used, committed | MemoryMXBean |
| GC | per-collector count/time, totals | GarbageCollectorMXBean |
| Threads | count, peak, daemon, deadlock | ThreadMXBean |
| Runtime | uptime, VM name | RuntimeMXBean |
| OS | CPU load, processors, system load | OperatingSystemMXBean |
| Class loading | loaded, unloaded | ClassLoadingMXBean |
| Buffer pools | direct/mapped usage | BufferPoolMXBean |

## Protocol

The TCP server uses a simple text protocol:

1. Client sends `SNAPSHOT\n`
2. Server responds with a single-line ProcEntry JSON
3. Connection can be kept open or closed by either side

## Requirements

- Java 11 or later
- No external dependencies
