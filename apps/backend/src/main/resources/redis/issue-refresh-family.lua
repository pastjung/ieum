-- issueRefreshFamily (docs/product/api-spec.md 3.6.1절)
--
-- 새 계열과 그 계열의 첫 Refresh Token을 원자적으로 등록한다.
-- 같은 familyId나 jti가 이미 있으면 아무것도 쓰지 않고 ALREADY_EXISTS를 돌려준다 — 덮어쓰면
-- 이미 발급된 토큰의 상태를 지워 재사용 판별 근거가 사라진다.
--
-- 계열 index(ZSET)에 member만 남아 있는 경우도 충돌로 본다. family hash가 TTL로 먼저 사라져도
-- ZSET member는 그 계열이 쓰인 적이 있다는 기록이므로, 같은 familyId를 새 계열로 다시 쓰면
-- 옛 기록의 score를 덮어쓰게 된다.
--
-- 접근하는 키는 모두 KEYS로 받는다. 스크립트 안에서 키 이름을 조립하지 않는다.
--   KEYS[1] refresh token hash   <prefix>:auth:{<userId>}:refresh:<jti>
--   KEYS[2] family hash          <prefix>:auth:{<userId>}:family:<familyId>
--   KEYS[3] families zset        <prefix>:auth:{<userId>}:families
--   ARGV[1] userId (10진수 문자열, number로 변환하지 않는다)
--   ARGV[2] familyId
--   ARGV[3] jti
--   ARGV[4] tokenHash
--   ARGV[5] expiresAt (epoch seconds, 문자열)
-- 반환: 'OK' | 'ALREADY_EXISTS'

local tokenKey = KEYS[1]
local familyKey = KEYS[2]
local familiesKey = KEYS[3]

local userId = ARGV[1]
local familyId = ARGV[2]
local jti = ARGV[3]
local tokenHash = ARGV[4]
local expiresAt = ARGV[5]

if redis.call('EXISTS', tokenKey) == 1 or redis.call('EXISTS', familyKey) == 1 then
  return 'ALREADY_EXISTS'
end
-- member 하나만 조회한다. ZSET 전체를 훑지 않는다.
if redis.call('ZSCORE', familiesKey, familyId) then
  return 'ALREADY_EXISTS'
end

redis.call('HSET', tokenKey,
  'userId', userId,
  'familyId', familyId,
  'tokenHash', tokenHash,
  'expiresAt', expiresAt,
  'status', 'ACTIVE')
redis.call('EXPIREAT', tokenKey, expiresAt)

redis.call('HSET', familyKey,
  'userId', userId,
  'currentJti', jti,
  'status', 'ACTIVE')
redis.call('EXPIREAT', familyKey, expiresAt)

-- 계열 인덱스는 여러 계열이 공유하므로 만료를 앞당기지 않는다. 현재 TTL보다 늦을 때만 연장한다.
redis.call('ZADD', familiesKey, expiresAt, familyId)
local familiesTtl = redis.call('TTL', familiesKey)
if familiesTtl < 0 then
  redis.call('EXPIREAT', familiesKey, expiresAt)
else
  redis.call('EXPIREAT', familiesKey, expiresAt, 'GT')
end

return 'OK'
