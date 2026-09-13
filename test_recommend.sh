#!/bin/bash
# 测试个性化推荐 Feed（点赞/收藏 → Kafka 画像聚合 → 千人千面排序）
REDIS="/d/develop/myRedis/redis-cli.exe -h 8.163.102.62 -a 123456"
API="http://localhost:9001"
PASS="test1234"

PHONES=(13950000001 13950000002)

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
  TOKENS[$phone]=$TOKEN
  echo "  $phone → token ok"
done

T1=${TOKENS[13950000001]}
T2=${TOKENS[13950000002]}

echo ""
echo "========== 3. 发布带标签的知文 =========="

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

P1=$(publish "$T1" "Java 并发编程深入" "JUC 与虚拟线程实战" '["java","后端"]')
P2=$(publish "$T1" "Spring 源码解析" "IOC 与 AOP 原理" '["java","spring"]')
P3=$(publish "$T2" "人像摄影技巧" "人像布光与后期" '["摄影","旅行"]')
P4=$(publish "$T2" "风光摄影构图" "风光构图法则" '["摄影","风景"]')

echo "  P1=$P1 P2=$P2 (java/spring)"
echo "  P3=$P3 P4=$P4 (摄影)"

echo ""
echo "========== 4. 差异化行为：u1 点赞 java，u2 点赞摄影 =========="

like() { curl -s -X POST "$API/api/v1/action/like" -H "Authorization: Bearer $1" -H "Content-Type: application/json" -d "{\"entityType\":\"knowpost\",\"entityId\":\"$2\"}" >/dev/null; }
fav()  { curl -s -X POST "$API/api/v1/action/fav"  -H "Authorization: Bearer $1" -H "Content-Type: application/json" -d "{\"entityType\":\"knowpost\",\"entityId\":\"$2\"}" >/dev/null; }

like "$T1" "$P1"; fav "$T1" "$P1"; like "$T1" "$P2"
like "$T2" "$P3"; fav "$T2" "$P3"; like "$T2" "$P4"

echo "  u1 喜欢 java/spring，u2 喜欢 摄影"

echo ""
echo "========== 5. 等待画像聚合（Kafka ~2s，留 6s 余量）=========="
sleep 6

echo ""
echo "========== 6. 查询推荐 Feed（应千人千面）=========="
for pair in "u1|$T1" "u2|$T2"; do
  label=${pair%%|*}
  token=${pair##*|}
  echo "--- $label 的推荐 ---"
  curl -s "$API/api/v1/knowposts/recommend?size=10" -H "Authorization: Bearer $token" | python -c "
import sys,json
d=json.load(sys.stdin)
items=d.get('items',[])
print(f'共 {len(items)} 条, hasMore={d.get(\"hasMore\",False)}')
for it in items:
    print(f'  [{it.get(\"authorNickname\",\"?\")}] {it.get(\"title\",\"?\")} | tags={it.get(\"tags\",[])} | like={it.get(\"likeCount\",0)}')
"
done

echo ""
echo "========== 7. 查看画像 ZSet（可选校验）=========="
for phone in "${PHONES[@]}"; do
  echo "  [$phone] interest/affinity 需要在 redis-cli 里查，此处跳过"
done

echo ""
echo "========== 完成 =========="
