#!/bin/bash
# 点赞接口压测：注册 N 个用户 → 多 token 并发打 POST /api/v1/action/like
# 用法: test_like_load.sh [并发] [每用户请求数]
set -u
CONC="${1:-20}"          # 并发用户数（也是 token 数）
PER_USER="${2:-30}"      # 每个用户请求次数
API="http://8.134.188.5:9001"
MIDDLE="root@8.138.199.111"
REDIS_CLI='docker exec redis redis-cli -a 123456'
PASS="test1234"
BASE=13900000100
TDIR="/tmp/like_load_$$"
mkdir -p "$TDIR"
TOKENS_FILE="$TDIR/tokens.txt"

echo "========== 1. 注入验证码 + 注册 $CONC 个用户 =========="
> "$TOKENS_FILE"
for ((i=0; i<CONC; i++)); do
  PHONE=$((BASE+i))
  ssh -o BatchMode=yes -o ConnectTimeout=8 "$MIDDLE" \
    "$REDIS_CLI HSET auth:code:REGISTER:$PHONE code 888888 maxAttempts 5 attempts 0 >/dev/null 2>&1; $REDIS_CLI EXPIRE auth:code:REGISTER:$PHONE 600 >/dev/null 2>&1" 2>/dev/null
  RESP=$(curl -s -m 8 -X POST "$API/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"identifierType\":\"PHONE\",\"identifier\":\"$PHONE\",\"code\":\"888888\",\"password\":\"$PASS\",\"agreeTerms\":true}")
  TOKEN=$(echo "$RESP" | python -c "import sys,json
try:
  print(json.load(sys.stdin).get('token',{}).get('accessToken',''))
except Exception:
  print('')" 2>/dev/null)
  if [ -z "$TOKEN" ]; then
    # 已注册则改登录
    RESP=$(curl -s -m 8 -X POST "$API/api/v1/auth/login" \
      -H "Content-Type: application/json" \
      -d "{\"identifierType\":\"PHONE\",\"identifier\":\"$PHONE\",\"password\":\"$PASS\"}")
    TOKEN=$(echo "$RESP" | python -c "import sys,json
try:
  print(json.load(sys.stdin).get('token',{}).get('accessToken',''))
except Exception:
  print('')" 2>/dev/null)
  fi
  if [ -n "$TOKEN" ]; then
    echo "$TOKEN $PHONE" >> "$TOKENS_FILE"
    echo "  $PHONE OK (token_len=${#TOKEN})"
  else
    echo "  $PHONE FAILED: $(echo "$RESP" | head -c 120)"
  fi
done
NTOKENS=$(wc -l < "$TOKENS_FILE")
echo "有效 token 数: $NTOKENS"

if [ "$NTOKENS" -eq 0 ]; then
  echo "没有可用 token，退出"; exit 1
fi

echo ""
echo "========== 2. 并发点赞压测 (并发=$CONC, 每用户=$PER_USER 次) =========="
START_NS=$(date +%s%N)
TOTAL_OK=0; TOTAL_REQ=0
export API TDIR PER_USER

# 每个 worker 处理一行 token，循环打点赞，记录耗时
worker() {
  local line="$1"
  local token phone
  read -r token phone <<< "$line"
  local ok=0 req=0
  for ((k=1; k<=PER_USER; k++)); do
    local eid="post-${phone}-${k}"
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" -m 5 -X POST "$API/api/v1/action/like" \
      -H "Authorization: Bearer $token" \
      -H "Content-Type: application/json" \
      -d "{\"entityType\":\"knowpost\",\"entityId\":\"$eid\"}")
    req=$((req+1))
    [ "$code" = "200" ] && ok=$((ok+1))
  done
  echo "$ok $req"
}

export -f worker
RESULTS=$(cat "$TOKENS_FILE" | xargs -P "$CONC" -I{} bash -c 'worker "{}"' 2>/dev/null)
END_NS=$(date +%s%N)

for r in $RESULTS; do :; done
# 汇总
TOTAL_REQ=$(echo "$RESULTS" | awk '{s+=$2} END{print s+0}')
TOTAL_OK=$(echo "$RESULTS" | awk '{s+=$1} END{print s+0}')
FAIL=$((TOTAL_REQ-TOTAL_OK))
echo ""
echo "========== 3. 结果 =========="
python -c "
elapsed=($END_NS-$START_NS)/1e9
rps=$TOTAL_REQ/elapsed if elapsed>0 else 0
rate=($TOTAL_OK*100.0/$TOTAL_REQ) if $TOTAL_REQ>0 else 0
print(f'总请求: $TOTAL_REQ   成功: $TOTAL_OK   失败: { $TOTAL_REQ-$TOTAL_OK }')
print(f'总耗时: {elapsed:.2f}s')
print(f'吞吐: {rps:.1f} req/s')
print(f'成功率: {rate:.2f}%')
"
rm -rf "$TDIR"
