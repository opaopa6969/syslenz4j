---
date: 2026-09-05
repo: syslenz4j
issue: 20
branch: fix/issue-20-compound-secondary-operators
status: completed
---

# DGE Session: Compound Secondary Operators

## Inputs Reviewed

- `README.md`
- `docs/getting-started.md`
- `docs/architecture.md`
- `docs/watch-api.md`
- `src/main/java/org/unlaxer/infra/syslenz4j/*.java`
- `src/test/java/org/unlaxer/infra/syslenz4j/*.java`
- `git log --oneline -n 15`
- GitHub open issues / PRs (`gh issue list`, `gh pr list`)
- tramli primary sources: `opaopa6969/tramli` README, `spec/SPEC.md`, `dge/decisions/DD-040-issue33-appspec-feedback.md`

## Multi-Role Conversation

### Product Manager

"利用者導線は `README -> getting-started -> Watch API` です。ここで compound watch の `>=` / `<=` が使えるように見えるのに、本番では片側条件だけで発火するのは信頼を削ります。監視ライブラリでは false positive のコストが高いです。"

### Library Maintainer

"修正範囲は `WatchCondition.CompoundCondition#evaluate()` に閉じています。公開 API は増えず、既存チェーン構文もそのままです。テスト追加で回帰も抑えられます。"

### SRE User

"Watch は『鳴るべき時だけ鳴る』が前提です。二次条件の境界値が常に true 扱いだと、しきい値調整で吸収できません。運用では誤報が一番困ります。"

### QA Engineer

"正常系だけでなく、境界一致と非発火系が必要です。特に secondary metric 不在と threshold 不一致を分けて固定したいです。"

### Performance Engineer

"評価分岐を 2 ケース増やすだけなので性能影響は無視できる水準です。ロック粒度や snapshot パスの構造も変わりません。"

### Release Manager

"revert が容易かが重要です。この変更は 1 クラス、数テスト、文書差分で完結しており、必要なら 1 commit revert で戻せます。"

## Value Gaps Identified

1. Compound secondary `>=` / `<=` が文書上は使えるのに runtime で正しく評価されない。
2. CI が `mvn compile` / `package` で、`mvn test` を明示していないため意図が伝わりにくい。
3. Spring Boot integration 例はあるが、利用者がそのまま貼るには不自然なダミーコードが混ざる。

## Selection

- Chosen: Gap 1
- Why:
  - 利用者影響が直接的で既知。
  - false positive は observability 製品価値を毀損する。
  - ローカル修正で完結し、後方互換を維持できる。
  - reversible で issue / PR の説明もしやすい。

## tramli / tramli-appspec Fit Check

### Primary-source facts

- tramli は build-time validation を持つ constrained flow engine で、複数 state / transition / processor を宣言的に扱う (`tramli` README, `spec/SPEC.md`)。
- tramli-appspec 由来の要望は tramli 本体の flow-definition / validation 拡張として扱われている (`DD-040`)。

### Repo-structure facts

- syslenz4j は単一 JVM ライブラリで、主要関心は metrics collection, TCP protocol, watch evaluation。
- 永続化された長寿命フロー、複数状態遷移、外部イベント再開、flow store は存在しない。
- 今回の対象不具合は 1 メソッドの条件分岐修正であり、ワークフロー設計問題ではない。

### Decision

- `tramli`: not adopted for this change.
- `tramli-appspec`: not adopted for this change.
- Reason: 問題構造が state machine orchestration ではなく、同期的な比較演算の局所バグだから。tramli を導入すると repo の zero-dependency 方針と責務境界を壊し、課題に対して過剰。
