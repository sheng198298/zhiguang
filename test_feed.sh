#!/bin/bash
# 测试关注推流
REDIS="/d/develop/myRedis/redis-cli.exe -h 192.168.100.128 -a 123456"
API="http://localhost:9001"
PASS="test1234"
TDIR="/tmp/feed_test_$$"
mkdir -p "$TDIR"

PHONES=(13930000001 13930000002 13930000003 13930000004 13930000005)

echo "========== 1. 注入验证码到 Redis =========="
for phone in "${PHONES[@]}"; do
  $REDIS DEL "auth:code:REGISTER:${phone}" 2>/dev/null
  $REDIS HSET "auth:code:REGISTER:${phone}" code "888888" maxAttempts "5" attempts "0"
  $REDIS EXPIRE "auth:code:REGISTER:${phone}" 300
  echo "  $phone → 888888 ✓"
done

echo ""
echo "========== 2. 注册（已存在则登录）=========="
declare -A TOKENS
declare -A USER_IDS

for phone in "${PHONES[@]}"; do
  RESP=$(curl -s -X POST "$API/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"identifierType\":\"PHONE\",\"identifier\":\"$phone\",\"code\":\"888888\",\"password\":\"$PASS\",\"agreeTerms\":true}")

  if echo "$RESP" | python -c "import sys,json; d=json.load(sys.stdin); exit(0 if 'token' in d else 1)" 2>/dev/null; then
    echo "  $phone → 注册成功"
  else
    echo "  $phone → 注册失败($(echo "$RESP" | python -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null))，尝试登录..."
    RESP=$(curl -s -X POST "$API/api/v1/auth/login" \
      -H "Content-Type: application/json" \
      -d "{\"identifierType\":\"PHONE\",\"identifier\":\"$phone\",\"password\":\"$PASS\"}")
  fi

  TOKEN=$(echo "$RESP" | python -c "import sys,json; d=json.load(sys.stdin); print(d['token']['accessToken'])" 2>/dev/null)
  ZUID=$(echo "$RESP" | python -c "import sys,json; d=json.load(sys.stdin); print(d['user']['id'])" 2>/dev/null)
  NICK=$(echo "$RESP" | python -c "import sys,json; d=json.load(sys.stdin); print(d['user']['nickname'])" 2>/dev/null)

  TOKENS[$phone]=$TOKEN
  USER_IDS[$phone]=$ZUID
  echo "    id=$ZUID nickname=$NICK"
done

echo ""
echo "========== 3. 关注（先建立关系）=========="
T1=${TOKENS[13930000001]}
T2=${TOKENS[13930000002]}
T3=${TOKENS[13930000003]}
T4=${TOKENS[13930000004]}
T5=${TOKENS[13930000005]}
U1=${USER_IDS[13930000001]}
U2=${USER_IDS[13930000002]}
U3=${USER_IDS[13930000003]}
U4=${USER_IDS[13930000004]}
U5=${USER_IDS[13930000005]}

follow() { curl -s -X POST "$API/api/v1/relation/follow?toUserId=$2" -H "Authorization: Bearer $1" >/dev/null; }

follow "$T2" "$U1" && echo "  u2→u1 ✓"
follow "$T3" "$U1" && echo "  u3→u1 ✓"
follow "$T4" "$U1" && echo "  u4→u1 ✓"
follow "$T5" "$U1" && echo "  u5→u1 ✓"
follow "$T1" "$U2" && echo "  u1→u2 ✓"
follow "$T1" "$U3" && echo "  u1→u3 ✓"
follow "$T2" "$U3" && echo "  u2→u3 ✓"

echo ""
echo "========== 4. 发布知文（含正文上传）=========="

publish() {
  local token=$1 title=$2 desc=$3 tags=$4 bodyfile=$5

  DRAFT=$(curl -s -X POST "$API/api/v1/knowposts/drafts" -H "Authorization: Bearer $token" -H "Content-Type: application/json")
  PID=$(echo "$DRAFT" | python -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
  if [ -z "$PID" ]; then
    echo "    [FAIL] 创建草稿失败: $DRAFT"
    return
  fi

  # 获取 OSS 预签名上传 URL
  PRESIGN=$(curl -s -X POST "$API/api/v1/storage/presign" \
    -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
    -d "{\"postId\":\"$PID\",\"scene\":\"knowpost_content\",\"contentType\":\"text/markdown\"}")
  OBJKEY=$(echo "$PRESIGN" | python -c "import sys,json; print(json.load(sys.stdin).get('objectKey',''))" 2>/dev/null)
  PUTURL=$(echo "$PRESIGN" | python -c "import sys,json; print(json.load(sys.stdin).get('putUrl',''))" 2>/dev/null)

  if [ -n "$PUTURL" ] && [ "$PUTURL" != "" ]; then
    FILESIZE=$(stat -c%s "$bodyfile" 2>/dev/null || wc -c < "$bodyfile")
    SHA=$(sha256sum "$bodyfile" | cut -d' ' -f1)
    curl -s -X PUT "$PUTURL" -H "Content-Type: text/markdown" --data-binary "@$bodyfile" >/dev/null

    curl -s -X POST "$API/api/v1/knowposts/$PID/content/confirm" \
      -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
      -d "{\"objectKey\":\"$OBJKEY\",\"etag\":\"$SHA\",\"size\":$FILESIZE,\"sha256\":\"$SHA\"}" >/dev/null
    echo "    [OSS] 正文已上传"
  else
    echo "    [OSS] 上传端点不可用，跳过正文"
  fi

  curl -s -X PATCH "$API/api/v1/knowposts/$PID" \
    -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
    -d "$(python -c "
import json
print(json.dumps({'title':'$title','description':'$desc','tags':json.loads('$tags'),'visible':'public','isTop':False}))
")" >/dev/null

  curl -s -X POST "$API/api/v1/knowposts/$PID/publish" -H "Authorization: Bearer $token" >/dev/null
  echo "    postId=$PID  $title"
}

# ----- 写入所有 Markdown 正文文件 -----

cat > "$TDIR/p01_springboot.md" << 'ENDBODY'
# Spring Boot 3.2 新特性一览

## 虚拟线程支持（Virtual Threads）

Spring Boot 3.2 正式支持 JDK 21 的虚拟线程，通过在配置中启用 `spring.threads.virtual.enabled=true` 即可让 Tomcat、Jetty 等内嵌服务器使用虚拟线程处理请求。

### 性能对比
- 平台线程池：200 并发下平均响应时间 350ms，CPU 使用率 65%
- 虚拟线程：200 并发下平均响应时间 180ms，CPU 使用率 42%

虚拟线程尤其适合 IO 密集型场景，比如数据库查询、远程 API 调用等。在开发中需要注意避免 `synchronized` 块中的阻塞操作，因为虚拟线程在 `synchronized` 内部不会 unmount。

## GraalVM 原生镜像改进

3.2 版本大幅改进了对 GraalVM Native Image 的支持，新增了自动 Hint 生成机制，减少了手动配置 `reflect-config.json` 的工作量。启动时间从秒级降至毫秒级，内存占用降低 50%。

## 自动配置优化

新增 `@EnableAutoConfiguration` 的条件匹配缓存，启动时间相比 3.1 减少约 15%。同时改进了 AOT 编译提示，使得预编译更加准确。
ENDBODY

cat > "$TDIR/p02_redis.md" << 'ENDBODY'
# Redis 缓存最佳实践

## 缓存穿透

缓存穿透是指查询一个不存在的数据，缓存层和存储层都不会命中。常见解决方案：

### 布隆过滤器
在缓存之前加一层布隆过滤器，将所有存在的 Key 预先写入布隆过滤器。查询时先经过布隆过滤器判断 Key 是否存在，不存在则直接返回。

```
BloomFilter<String> filter = BloomFilter.create(
    Funnels.stringFunnel(Charset.defaultCharset()),
    1000000,  // 预期插入元素数
    0.01      // 误判率
);
```

### 空值缓存
对于不存在的 Key，也缓存一个空值或特殊标记，设置较短的过期时间如 60 秒。

## 缓存击穿

热点 Key 过期瞬间，大量请求直接打到数据库。解决方案：互斥锁。

```
public String getWithMutex(String key) {
    String value = redis.get(key);
    if (value != null) return value;
    String lockKey = "lock:" + key;
    if (redis.setnx(lockKey, "1", 30, TimeUnit.SECONDS)) {
        try {
            value = db.query(key);
            redis.set(key, value, 3600);
        } finally {
            redis.del(lockKey);
        }
    }
    return value;
}
```

## 缓存雪崩

大量 Key 同时过期导致数据库压力骤增。解决方案：
- 过期时间加随机值：`expire = base + random(0, 600)`
- 多级缓存（Caffeine L1 + Redis L2）
- 限流降级
ENDBODY

cat > "$TDIR/p03_kafka.md" << 'ENDBODY'
# Kafka 入门到精通

## 核心概念

Kafka 是一个分布式的、基于发布/订阅模式的消息队列，主要应用于大数据实时处理领域。

### Topic 与 Partition
- Topic：消息的逻辑分类
- Partition：Topic 的物理分区，每个 Partition 是一个有序的、不可变的消息序列
- Offset：消息在 Partition 中的唯一标识，从 0 开始递增

### ISR 机制（In-Sync Replicas）

ISR 是 Kafka 保证数据可靠性的核心机制。每个 Partition 的 Leader 会维护一个 ISR 列表，包含与 Leader 保持同步的 Follower 副本。

关键配置参数：
- `replica.lag.time.max.ms: 30000` — Follower 落后超过 30s 则踢出 ISR
- `min.insync.replicas: 2` — 最少同步副本数
- `acks: all` — 所有 ISR 确认才返回成功

## 幂等性实现

Kafka 通过 Producer ID（PID）和 Sequence Number 实现幂等性：

1. Producer 启动时向 Broker 申请唯一 PID
2. 每个发往特定 Partition 的消息携带单调递增的 Sequence Number
3. Broker 缓存最近 5 条消息的 (PID, Seq)，对新消息进行去重判断

启用方式：`enable.idempotence=true`
ENDBODY

cat > "$TDIR/p04_react.md" << 'ENDBODY'
# React 18 并发模式详解

## 什么是并发模式

React 18 的并发模式是一个底层架构重写，它让 React 能够同时准备多个版本的 UI，并根据优先级中断和恢复渲染。

### 可中断渲染

在传统 React 同步渲染中，一旦开始渲染，就必须完成整个组件树。并发模式允许 React 在渲染过程中暂停，处理更高优先级的更新，然后再回来继续。

## Suspense 改进

React 18 中 Suspense 不再需要 fallback 是同步组件的要求，现在可以包裹任何需要异步数据的组件：

```
function ProfilePage() {
  return (
    <Suspense fallback={<Loading />}>
      <ProfileDetails />
      <Suspense fallback={<PostsSkeleton />}>
        <ProfileTimeline />
      </Suspense>
    </Suspense>
  );
}
```

## useTransition 与 useDeferredValue

`useTransition` 允许将某些状态更新标记为非紧急更新，保持 UI 响应性。`useDeferredValue` 则适用于数据来自外部且无法控制其更新频率的场景。两者配合使用能显著提升用户体验。
ENDBODY

cat > "$TDIR/p05_typescript.md" << 'ENDBODY'
# TypeScript 高级类型技巧

## 条件类型（Conditional Types）

条件类型根据类型关系进行类型推断，是 TypeScript 类型系统中最强大的特性之一：

```
type IsString<T> = T extends string ? true : false;
type A = IsString<"hello">;  // true
type B = IsString<42>;       // false

type Unwrap<T> = T extends Promise<infer U> ? U : T;
type C = Unwrap<Promise<string>>;  // string
```

## 模板字面量类型

TypeScript 4.1 引入的模板字面量类型，让字符串类型约束更加强大：

```
type EventName<T extends string> = `on${Capitalize<T>}`;
type ClickEvent = EventName<"click">;  // "onClick"

type Route = `/${string}`;
type API = `/api/${string}`;
```

## 递归类型

适用于树形结构的类型定义：

```
type JSONValue =
  | string | number | boolean | null
  | JSONValue[]
  | { [key: string]: JSONValue };
```

这种递归定义能完美描述任意 JSON 数据结构的类型，在 API 响应类型定义中非常实用。
ENDBODY

cat > "$TDIR/p06_docker.md" << 'ENDBODY'
# Docker 容器化部署实战

## 多阶段构建（Multi-stage Build）

多阶段构建能让最终镜像体积减少 60-80%，是生产环境 Docker 化的核心实践：

```
# 构建阶段
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## 层缓存优化

把变化频率低的层放在前面，充分利用 Docker 的构建缓存。先复制依赖描述文件并下载依赖，再复制频繁变动的源码进行编译。

## 资源限制

生产环境必须设置资源限制防止容器消耗过多主机资源。使用 Docker Compose 的 `deploy.resources` 配置 CPU 和内存的 limits 与 reservations。
ENDBODY

cat > "$TDIR/p07_mysql.md" << 'ENDBODY'
# MySQL 索引优化策略

## 联合索引与最左前缀原则

联合索引的列顺序至关重要，必须遵循最左前缀原则：

```
CREATE INDEX idx_user_status_time ON orders(user_id, status, create_time);

-- 命中索引（使用了最左列 user_id）
SELECT * FROM orders WHERE user_id = 100;

-- 命中索引（user_id + status）
SELECT * FROM orders WHERE user_id = 100 AND status = 'paid';

-- 未命中（跳过了最左列）
SELECT * FROM orders WHERE status = 'paid';
```

## 覆盖索引

当查询的所有列都在索引中时，无需回表查询，性能最高：

```
CREATE INDEX idx_cover ON products(category, price, name);
SELECT name, price FROM products WHERE category = '电子' AND price > 1000;
-- Using index（覆盖索引，无回表）
```

## 索引失效场景

常见导致索引失效的情况：
- 对索引列使用函数如 `DATE(create_time)`
- 隐式类型转换如字符串列用数字查询
- LIKE 前置模糊查询 `%keyword`
- OR 条件中有非索引列
ENDBODY

cat > "$TDIR/p08_elasticsearch.md" << 'ENDBODY'
# Elasticsearch 搜索引擎实战

## 倒排索引原理

ES 的核心数据结构是倒排索引，它将文档中的词项映射到文档 ID 列表：

- 词项 "elasticsearch" → [doc1, doc3, doc7]
- 词项 "搜索" → [doc1, doc2, doc5]
- 词项 "性能" → [doc2, doc6, doc8]

查询时先找到词项对应的文档列表，再通过跳表、位图等结构对多个列表取交集，快速定位结果。

## 分词器配置

中文分词推荐使用 IK 分词器：
- ik_smart：粗粒度分词，适合搜索时使用
- ik_max_word：细粒度分词，适合索引时使用，召回率更高

## 查询 DSL 最佳实践

- `filter` 不计算评分，有缓存，应优先用于条件过滤
- 字段权重让标题匹配的文档排名更靠前
- 使用 `bool` 组合多个查询条件而非单一 match
- 合理设置 `minimum_should_match` 控制召回精度
ENDBODY

# ----- 发布 -----

echo "  [u1] 发布..."
publish "$T1" "Spring Boot 3.2 新特性一览" "详解Spring Boot 3.2中虚拟线程、GraalVM原生镜像、自动配置改进等重磅特性" '["java","spring"]' "$TDIR/p01_springboot.md"
publish "$T1" "Redis 缓存最佳实践" "缓存穿透、击穿、雪崩的解决方案与生产环境踩坑经验" '["redis","架构"]' "$TDIR/p02_redis.md"
publish "$T1" "Kafka 入门到精通" "Kafka核心概念、ISR机制、幂等性实现原理深度解析" '["kafka","消息队列"]' "$TDIR/p03_kafka.md"

echo "  [u2] 发布..."
publish "$T2" "React 18 并发模式详解" "深入理解React 18的并发渲染、Suspense、Transitions等核心并发特性" '["react","前端"]' "$TDIR/p04_react.md"
publish "$T2" "TypeScript 高级类型技巧" "条件类型、模板字面量类型、递归类型的实战应用" '["typescript","前端"]' "$TDIR/p05_typescript.md"

echo "  [u3] 发布..."
publish "$T3" "Docker 容器化部署实战" "从Dockerfile编写到多阶段构建、Docker Compose编排的完整流程" '["docker","运维"]' "$TDIR/p06_docker.md"

echo "  [u4] 发布..."
publish "$T4" "MySQL 索引优化策略" "覆盖索引、联合索引、前缀索引的使用原则和优化案例" '["mysql","数据库"]' "$TDIR/p07_mysql.md"

echo "  [u5] 发布..."
publish "$T5" "Elasticsearch 搜索引擎实战" "倒排索引原理、IK分词器配置、查询DSL最佳实践" '["elasticsearch","搜索"]' "$TDIR/p08_elasticsearch.md"

echo ""
echo "========== 5. 验证关注 Feed =========="

check_feed() {
  local label=$1 token=$2 expected=$3
  echo ""
  echo "--- $label (期望约 $expected 条) ---"
  curl -s "$API/api/v1/knowposts/following?size=10" -H "Authorization: Bearer $token" | python -c "
import sys,json
data=json.load(sys.stdin)
items=data.get('items',[])
print(f'实际 {len(items)} 条, hasMore={data.get(\"hasMore\",False)}, cursor={data.get(\"cursor\",\"无\")}')
for it in items:
    print(f'  [{it[\"authorNickname\"]}] {it[\"title\"]}')
"
}

check_feed "u2 的关注 Feed (关注了 u1+u3)" "$T2" 4
check_feed "u1 的关注 Feed (关注了 u2+u3)" "$T1" 3
check_feed "u4 的关注 Feed (只关注了 u1)" "$T4" 3

echo ""
echo "========== 6. 游标分页测试 =========="
echo "--- u2 第1页 (size=2) ---"
PAGE1=$(curl -s "$API/api/v1/knowposts/following?size=2" -H "Authorization: Bearer $T2")
echo "$PAGE1" | python -c "
import sys,json
d=json.load(sys.stdin)
for i in d['items']:
    print(f'  [{i[\"authorNickname\"]}] {i[\"title\"]}')
"
CURSOR=$(echo "$PAGE1" | python -c "import sys,json; print(json.load(sys.stdin).get('cursor',''))")
echo "  cursor=$CURSOR"

echo "--- u2 第2页 ---"
curl -s "$API/api/v1/knowposts/following?size=2&cursor=$CURSOR" -H "Authorization: Bearer $T2" | python -c "
import sys,json
d=json.load(sys.stdin)
for i in d.get('items',[]):
    print(f'  [{i[\"authorNickname\"]}] {i[\"title\"]}')
print(f'共{len(d.get(\"items\",[]))}条 hasMore={d.get(\"hasMore\",False)}')
"

echo ""
echo "========== 完成 =========="
rm -rf "$TDIR"
