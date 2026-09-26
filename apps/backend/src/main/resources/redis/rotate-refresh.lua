-- rotateRefresh (docs/product/api-spec.md 3.6.1절)
--
-- 제출된 Refresh를 회전한다. 모든 전제 조건 검사와 상태 전이가 이 스크립트 안에서 끝나므로
-- 동시 요청 중 하나만 OK를 받는다. 호출 전의 사전 조회는 자원 보호용일 뿐이며 여기서 다시 전부 검사한다.
--
-- 검사 순서에는 이유가 있다.
--   1) tokenHash를 먼저 본다. 해시가 다르면 그 요청자는 토큰을 갖고 있지 않다는 뜻이므로
--      계열을 폐기하지 않는다. 해시를 모른 채 계열을 끊을 수 있으면 그 자체가 서비스 거부 수단이 된다.
--   2) 해시가 확인된 뒤에는 재사용(ROTATED·REVOKED 토큰 재제출, 또는 ACTIVE지만 계열의 현재 토큰이 아님)을
--      먼저 판정하고 같은 실행 안에서 계열을 폐기한다. 재사용 탐지가 저장값 손상에 가려지면 안 된다 —
--      expiresAt 필드나 TTL을 망가뜨려 폐기를 피하는 길이 생기기 때문이다.
--   3) 정상 ACTIVE 토큰이고 계열의 현재 토큰일 때만 expiresAt과 key의 실제 만료를 검사한다.
--      이 검사는 상태를 바꾸지 않는다.
--   4) 마지막으로 신규 jti 충돌을 확인한다. 충돌이면 아무것도 바꾸지 않는다.
--
-- 저장된 값이 검증된 JWT와 어긋나면(userId·familyId·expiresAt 불일치, TTL 없음·불일치, status 값이 목록 밖)
-- 정상 회전을 허용하지 않고 NOT_FOUND로 다룬다. 새 반환 사유를 만들지 않는다.
--
--   KEYS[1] 기존 token hash   <prefix>:auth:{<userId>}:refresh:<currentJti>
--   KEYS[2] family hash       <prefix>:auth:{<userId>}:family:<familyId>
--   KEYS[3] 신규 token hash   <prefix>:auth:{<userId>}:refresh:<nextJti>
--   KEYS[4] families zset     <prefix>:auth:{<userId>}:families
--   ARGV[1] userId (10진수 문자열, number로 변환하지 않는다)
--   ARGV[2] familyId
--   ARGV[3] currentJti
--   ARGV[4] currentTokenHash
--   ARGV[5] currentExpiresAt (epoch seconds, 문자열)
--   ARGV[6] nextJti
--   ARGV[7] nextTokenHash
--   ARGV[8] nextExpiresAt (epoch seconds, 문자열)
-- 반환: 'OK' | 'NOT_FOUND' | 'HASH_MISMATCH' | 'REUSED' | 'FAMILY_REVOKED' | 'ALREADY_EXISTS'

local currentTokenKey = KEYS[1]
local familyKey = KEYS[2]
local nextTokenKey = KEYS[3]
local familiesKey = KEYS[4]

local userId = ARGV[1]
local familyId = ARGV[2]
local currentJti = ARGV[3]
local currentTokenHash = ARGV[4]
local currentExpiresAt = ARGV[5]
local nextJti = ARGV[6]
local nextTokenHash = ARGV[7]
local nextExpiresAt = ARGV[8]

local function revokeFamily()
  redis.call('HSET', familyKey, 'status', 'REVOKED')
end

-- epoch seconds 문자열만 number로 바꾼다. 소수·부호·공백·선행 0이 섞인 값은 손상된 상태다.
local function toEpochSeconds(value)
  if not value or not string.match(value, '^[1-9]%d*$') then
    return nil
  end
  return tonumber(value)
end

-- 만료는 앞당기지 않는다. TTL이 없는 key는 GT가 설정을 거부하므로 그때만 그대로 건다.
local function extendExpiry(key, expiresAt)
  if redis.call('TTL', key) < 0 then
    redis.call('EXPIREAT', key, expiresAt)
  else
    redis.call('EXPIREAT', key, expiresAt, 'GT')
  end
end

-- 1) 기존 토큰이 있는가
if redis.call('EXISTS', currentTokenKey) == 0 then
  return 'NOT_FOUND'
end

-- 2) 판정에 필요한 필드가 모두 있는가
local tokenUserId = redis.call('HGET', currentTokenKey, 'userId')
local tokenFamilyId = redis.call('HGET', currentTokenKey, 'familyId')
local tokenHash = redis.call('HGET', currentTokenKey, 'tokenHash')
local tokenStatus = redis.call('HGET', currentTokenKey, 'status')

if not tokenUserId or not tokenFamilyId or not tokenHash or not tokenStatus then
  return 'NOT_FOUND'
end

-- 3) 같은 사용자·계열의 기록인가
if tokenUserId ~= userId or tokenFamilyId ~= familyId then
  return 'NOT_FOUND'
end

-- 4) 토큰을 실제로 갖고 있는가. 여기를 통과하지 못하면 계열에 손대지 않는다.
if tokenHash ~= currentTokenHash then
  return 'HASH_MISMATCH'
end

-- 5) 계열 기록이 온전한가
if redis.call('EXISTS', familyKey) == 0 then
  return 'NOT_FOUND'
end

local familyUserId = redis.call('HGET', familyKey, 'userId')
local familyStatus = redis.call('HGET', familyKey, 'status')
local familyCurrentJti = redis.call('HGET', familyKey, 'currentJti')

if not familyUserId or not familyStatus or not familyCurrentJti then
  return 'NOT_FOUND'
end
if familyUserId ~= userId then
  return 'NOT_FOUND'
end

-- 6) 이미 폐기된 계열
if familyStatus == 'REVOKED' then
  return 'FAMILY_REVOKED'
end
if familyStatus ~= 'ACTIVE' then
  return 'NOT_FOUND'
end

-- 7) 재사용. expiresAt 필드나 TTL이 손상돼 있어도 폐기는 여기서 반드시 일어난다.
if tokenStatus == 'ROTATED' or tokenStatus == 'REVOKED' then
  revokeFamily()
  return 'REUSED'
end
-- 8) 목록에 없는 상태
if tokenStatus ~= 'ACTIVE' then
  return 'NOT_FOUND'
end
-- 9) ACTIVE지만 계열의 현재 토큰이 아니다. 회전 이력이 어긋났다는 뜻이므로 재사용으로 다룬다.
if familyCurrentJti ~= currentJti then
  revokeFamily()
  return 'REUSED'
end

-- 10) 정상 회전 후보에만 적용하는 검사. 상태를 바꾸지 않는다.
-- 저장 문자열과 제출 문자열을 먼저 그대로 비교한다 — 선행 0처럼 숫자로 바꾸면 같아지는 표현을 인정하지 않는다.
local tokenExpiresAt = redis.call('HGET', currentTokenKey, 'expiresAt')
if not tokenExpiresAt or tokenExpiresAt ~= currentExpiresAt then
  return 'NOT_FOUND'
end
-- EXPIRETIME 비교에 필요한 만큼만 숫자로 바꾼다. -1은 TTL 없음, -2는 key 없음 (Redis 7.0+).
local expectedExpiresAt = toEpochSeconds(currentExpiresAt)
if not expectedExpiresAt or redis.call('EXPIRETIME', currentTokenKey) ~= expectedExpiresAt then
  return 'NOT_FOUND'
end

-- 11) 신규 식별자가 이미 쓰였다. 기존 토큰과 계열은 그대로 둔다.
if redis.call('EXISTS', nextTokenKey) == 1 then
  return 'ALREADY_EXISTS'
end

-- 12) 여기부터가 상태 전이다. 위 검사를 모두 통과한 요청 하나만 도달한다.
-- 기존 토큰은 원래 exp까지 남긴다. 만료를 앞당기면 아직 수명이 남은 토큰의 재사용을 판별할 수 없다.
redis.call('HSET', currentTokenKey, 'status', 'ROTATED')

redis.call('HSET', nextTokenKey,
  'userId', userId,
  'familyId', familyId,
  'tokenHash', nextTokenHash,
  'expiresAt', nextExpiresAt,
  'status', 'ACTIVE')
redis.call('EXPIREAT', nextTokenKey, nextExpiresAt)

-- 계열과 계열 인덱스는 여러 토큰이 공유한다. 아직 수명이 남은 토큰보다 먼저 만료되면 안 되므로 연장만 한다.
redis.call('HSET', familyKey, 'currentJti', nextJti)
extendExpiry(familyKey, nextExpiresAt)

-- score는 그 계열에 남은 Refresh 중 가장 늦은 exp다. GT는 더 늦을 때만 갱신하고 없는 member는 새로 넣는다.
redis.call('ZADD', familiesKey, 'GT', nextExpiresAt, familyId)
extendExpiry(familiesKey, nextExpiresAt)

return 'OK'
