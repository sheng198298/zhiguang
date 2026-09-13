#!/bin/bash
# 测试热点榜单（发布 → 点赞/收藏/浏览 → 重算 → 查询 daily/weekly/alltime）
REDIS="/d/develop/myRedis/redis-cli.exe -h 8.163.102.62 -a 123456"
API="http://localhost:9001"
PASS="test1234"

PHONES=(13940000001 13940000002 13940000003)

echo "========== 1. 注入验证码到 Redis =========="
for phone in "${PHONES[@]}"; do
  $REDIS DEL "auth:code:REGISTER:${phone}" >/dev/null 2>&1
  $REDIS HSET "auth:code:REGISTER:${phone}" code "888888" maxAttempts "5" attempts "0" >/dev/null 2>&1
  $REDIS EXPIRE "auth:code:REGISTER:${phone}" 300 >/dev/null 2>&1
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

  if ! echo "$RESP" | python -c "import sys,json; d=json.load(sys.stdin); exit(0 if 'token' in d else 1)" 2>/dev/null; then
    RESP=$(curl -s -X POST "$API/api/v1/auth/login" \
      -H "Content-Type: application/json" \
      -d "{\"identifierType\":\"PHONE\",\"identifier\":\"$phone\",\"password\":\"$PASS\"}")
  fi

  TOKEN=$(echo "$RESP" | python -c "import sys,json; d=json.load(sys.stdin); print(d['token']['accessToken'])" 2>/dev/null)
  ZUID=$(echo "$RESP" | python -c "import sys,json; d=json.load(sys.stdin); print(d['user']['id'])" 2>/dev/null)
  TOKENS[$phone]=$TOKEN
  USER_IDS[$phone]=$ZUID
  echo "  $phone → id=$ZUID"
done

T1=${TOKENS[13940000001]}; U1=${USER_IDS[13940000001]}
T2=${TOKENS[13940000002]}; U2=${USER_IDS[13940000002]}
T3=${TOKENS[13940000003]}; U3=${USER_IDS[13940000003]}

echo ""
echo "========== 3. 发布知文 =========="

publish() {
  local token=$1 title=$2 desc=$3 tags=$4
  DRAFT=$(curl -s -X POST "$API/api/v1/knowposts/drafts" -H "Authorization: Bearer $token" -H "Content-Type: application/json")
  PID=$(echo "$DRAFT" | python -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
  if [ -z "$PID" ] || [ "$PID" = "None" ]; then
    echo "    [FAIL] 创建草稿失败: $DRAFT"
    return 1
  fi
  curl -s -X PATCH "$API/api/v1/knowposts/$PID" -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
    -d "$(python -c "import json; print(json.dumps({'title':'$title','description':'$desc','tags':json.loads('$tags'),'visible':'public','isTop':False}))")" >/dev/null
  curl -s -X POST "$API/api/v1/knowposts/$PID/publish" -H "Authorization: Bearer $token" >/dev/null
  echo "$PID"
}

P1=$(publish "$T1" "Spring Boot 虚拟线程实战" "JDK21虚拟线程在Spring Boot 3.2中的应用与性能对比" '["java","spring"]')
P2=$(publish "$T1" "Redis 缓存穿透击穿雪崩" "三大缓存问题的完整解决方案与生产踩坑" '["redis","架构"]')
P3=$(publish "$T2" "Kafka 幂等性与事务" "消息队列可靠性的核心机制深度解析" '["kafka","消息队列"]')
P4=$(publish "$T3" "MySQL 索引优化最佳实践" "覆盖索引与联合索引的使用原则" '["mysql","数据库"]')

echo "  P1=$P1 (u1)"
echo "  P2=$P2 (u1)"
echo "  P3=$P3 (u2)"
echo "  P4=$P4 (u3)"

echo ""
echo "========== 4. 生成差异化互动 =========="

like() { curl -s -X POST "$API/api/v1/action/like" -H "Authorization: Bearer $1" -H "Content-Type: application/json" -d "{\"entityType\":\"knowpost\",\"entityId\":\"$2\"}" >/dev/null; }
fav()  { curl -s -X POST "$API/api/v1/action/fav"  -H "Authorization: Bearer $1" -H "Content-Type: application/json" -d "{\"entityType\":\"knowpost\",\"entityId\":\"$2\"}" >/dev/null; }
view() { curl -s "$API/api/v1/knowposts/detail/$2" -H "Authorization: Bearer $1" >/dev/null; }

# P1 最热：3 人点赞 + 2 人收藏
like "$T1" "$P1"; like "$T2" "$P1"; like "$T3" "$P1"
fav  "$T1" "$P1"; fav  "$T2" "$P1"

# P2：1 人点赞
like "$T2" "$P2"

# P3：2 人点赞 + 1 收藏
like "$T1" "$P3"; like "$T3" "$P3"
fav  "$T3" "$P3"

# P4：无互动，仅浏览
echo "  互动已生成"

echo ""
echo "========== 5. 浏览（触发 view 计数，去重窗口 12h）=========="
for u in "$T1" "$T2" "$T3"; do
  view "$u" "$P1"; view "$u" "$P2"; view "$u" "$P3"; view "$u" "$P4"
done
# 额外再浏览 P1 两次（用不同用户已在上面，这里让 P1 视图更高：重复浏览同一用户会去重）
echo "  浏览完成（每帖被 3 个用户各浏览 1 次）"

echo ""
echo "========== 6. 等待计数聚合 + 重算 =========="
echo "  等 8 秒（Kafka 计数聚合 ~1s flush + 重算周期）..."
sleep 8

echo ""
echo "========== 7. 查询热榜 =========="
for w in daily weekly alltime; do
  echo "--- /api/v1/hot/$w ---"
  curl -s "$API/api/v1/hot/$w?page=1&size=10" | python -c "
import sys,json
d=json.load(sys.stdin)
items=d.get('items',[])
print(f'共 {len(items)} 条, hasMore={d.get(\"hasMore\",False)}')
for it in items:
    print(f'  score={it[\"heatScore\"]:>8.2f} like={it[\"likeCount\"]} fav={it[\"favCount\"]} view={it[\"viewCount\"]} | {it[\"title\"]}')
"
done

echo ""
echo "========== 完成 =========="
