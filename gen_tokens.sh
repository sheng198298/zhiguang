#!/bin/bash
# 生成 JMeter 压测用的 tokens.csv：注册 N 个用户，输出 "token,entityId" 每行一个
# 用法: gen_tokens.sh [用户数] [输出文件]
set -u
N="${1:-20}"
OUT="${2:-tokens.csv}"
API="http://8.134.188.5:9001"
MIDDLE="root@8.138.199.111"
REDIS_CLI='docker exec redis redis-cli -a 123456'
PASS="test1234"
BASE=13900000100

> "$OUT"
OK=0
for ((i=0; i<N; i++)); do
  PHONE=$((BASE+i))
  TOKEN=""
  # 1. 先尝试密码登录（已注册用户无需验证码，也免 SSH，速度快）
  RESP=$(curl -s -m 8 -X POST "$API/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifierType\":\"PHONE\",\"identifier\":\"$PHONE\",\"password\":\"$PASS\"}")
  TOKEN=$(echo "$RESP" | python -c "import sys,json
try:
  print(json.load(sys.stdin).get('token',{}).get('accessToken',''))
except Exception:
  print('')" 2>/dev/null)
  # 2. 登录失败（未注册）则注入验证码 + 注册
  if [ -z "$TOKEN" ]; then
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
  fi
  if [ -n "$TOKEN" ]; then
    echo "$TOKEN,post-$PHONE" >> "$OUT"
    OK=$((OK+1))
  else
    echo "  $PHONE 获取 token 失败: $(echo "$RESP" | head -c 120)"
  fi
done
echo "已生成 $OK/$N 行 -> $OUT (格式: token,entityId)"
