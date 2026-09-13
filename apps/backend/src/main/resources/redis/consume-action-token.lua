-- consumeActionToken (docs/product/api-spec.md 3.6.1절)
--
-- action-current가 요청 jti를 가리키는지, 상태가 ACTIVE인지, tokenHash가 일치하는지를
-- 한 연산에서 확인한 뒤 토큰을 CONSUMED로 바꾸고 action-current가 더 이상 활성 토큰을
-- 가리키지 않도록 함께 정리한다. 동시 요청 중 하나만 성공한다.
--
-- 검사 순서에는 이유가 있다.
--   1) tokenHash를 먼저 본다. 해시가 다르면 그 요청자는 토큰을 갖고 있지 않다는 뜻이므로
--      토큰의 상태를 알려 주지 않는다.
--   2) 그다음 상태를 판정한다. CONSUMED·INVALIDATED는 각각의 사유로 돌려준다.
--   3) 마지막으로 action-current 일치와 저장된 만료를 확인한다.
--
-- 저장된 값이 검증된 JWT와 어긋나면(userId·purpose 불일치, 필드 누락, expiresAt·TTL 불일치)
-- 정상 소비를 허용하지 않고 NOT_FOUND로 다룬다. 계약에 그 상황의 별도 사유가 없다.
--
--   KEYS[1] token hash      <prefix>:auth:{<userId>}:action:<purpose>:<jti>
--   KEYS[2] action-current  <prefix>:auth:{<userId>}:action-current:<purpose>
--   ARGV[1] userId (10진수 문자열)
--   ARGV[2] purpose
--   ARGV[3] jti
--   ARGV[4] tokenHash
--   ARGV[5] expiresAt (epoch seconds, 문자열)
-- 반환: 'OK' | 'NOT_FOUND' | 'NOT_CURRENT' | 'HASH_MISMATCH' | 'ALREADY_CONSUMED' | 'INVALIDATED'

local tokenKey = KEYS[1]
local currentKey = KEYS[2]

local userId = ARGV[1]
local purpose = ARGV[2]
local jti = ARGV[3]
local tokenHash = ARGV[4]
local expiresAt = ARGV[5]

-- epoch seconds 문자열만 number로 바꾼다. 소수·부호·공백·선행 0이 섞인 값은 손상된 상태다.
local function toEpochSeconds(value)
  if not value or not string.match(value, '^[1-9]%d*$') then
    return nil
  end
  return tonumber(value)
end

if redis.call('EXISTS', tokenKey) == 0 then
  return 'NOT_FOUND'
end

local storedUserId = redis.call('HGET', tokenKey, 'userId')
local storedPurpose = redis.call('HGET', tokenKey, 'purpose')
local storedHash = redis.call('HGET', tokenKey, 'tokenHash')
local storedStatus = redis.call('HGET', tokenKey, 'status')

if not storedUserId or not storedPurpose or not storedHash or not storedStatus then
  return 'NOT_FOUND'
end
if storedUserId ~= userId or storedPurpose ~= purpose then
  return 'NOT_FOUND'
end

-- 1) 토큰을 실제로 갖고 있는가. 여기를 통과하지 못하면 상태를 드러내지 않는다.
if storedHash ~= tokenHash then
  return 'HASH_MISMATCH'
end

-- 2) 단일 사용 판정
if storedStatus == 'CONSUMED' then
  return 'ALREADY_CONSUMED'
end
if storedStatus == 'INVALIDATED' then
  return 'INVALIDATED'
end
if storedStatus ~= 'ACTIVE' then
  return 'NOT_FOUND'
end

-- 3) 가장 최근에 발급된 토큰인가
if redis.call('GET', currentKey) ~= jti then
  return 'NOT_CURRENT'
end

-- 저장 문자열과 제출 문자열을 그대로 비교한다 — 선행 0처럼 숫자로 바꾸면 같아지는 표현을 인정하지 않는다.
local storedExpiresAt = redis.call('HGET', tokenKey, 'expiresAt')
if not storedExpiresAt or storedExpiresAt ~= expiresAt then
  return 'NOT_FOUND'
end
local expectedExpiresAt = toEpochSeconds(expiresAt)
if not expectedExpiresAt or redis.call('EXPIRETIME', tokenKey) ~= expectedExpiresAt then
  return 'NOT_FOUND'
end

-- 여기부터가 상태 전이다. 위 검사를 모두 통과한 요청 하나만 도달한다.
-- 만료는 앞당기지 않는다. CONSUMED 표시가 원래 exp까지 남아야 재제출을 '없는 토큰'과 구분할 수 있다.
redis.call('HSET', tokenKey, 'status', 'CONSUMED')
redis.call('DEL', currentKey)

return 'OK'
