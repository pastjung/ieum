-- issueActionToken (docs/product/api-spec.md 3.6.1절)
--
-- 같은 사용자·용도의 이전 활성 토큰을 INVALIDATED로 바꾸고 새 토큰을 ACTIVE로 등록하며
-- action-current를 새 jti로 갱신한다. 같은 jti가 이미 있으면 덮어쓰지 않는다.
--
-- 이전 토큰 키를 스크립트가 조립하지 않는 이유:
-- 계약은 "접근하는 모든 키를 KEYS 인자로 명시해 전달하고 스크립트 안에서 키 이름을 조립하지 않는다"고 정한다.
-- 이전 jti는 action-current를 읽어야 알 수 있으므로, 호출자가 먼저 action-current를 읽어
-- 그 jti로 만든 키를 KEYS[3]으로, 읽은 jti를 ARGV[6]으로 넘긴다.
--
-- 그래서 "읽은 시점"과 "쓰는 시점" 사이가 벌어진다. 이 틈을 compare-and-set으로 닫는다.
-- 어떤 쓰기보다 먼저 현재 action-current가 호출자가 읽은 값과 같은지 확인하고,
-- 다르면 아무것도 바꾸지 않고 CONTENTION을 돌려준다. 호출자가 다시 읽어 유한 횟수만큼 재시도한다.
-- 이렇게 하지 않으면 동시 발급에서 나중 요청이 이전 토큰을 무효화하지 못해,
-- current가 아닌데 status가 ACTIVE인 토큰이 남는다.
--
-- 이전 토큰이 없으면 호출자가 KEYS[3]에 KEYS[1]과 같은 키를 넘기고 ARGV[6]을 빈 문자열로 준다.
-- 이때 기대값은 "action-current가 없음"이므로 그 사이에 누가 만들었으면 역시 CONTENTION이다.
--
--   KEYS[1] 신규 token hash    <prefix>:auth:{<userId>}:action:<purpose>:<jti>
--   KEYS[2] action-current     <prefix>:auth:{<userId>}:action-current:<purpose>
--   KEYS[3] 이전 token hash    (없으면 KEYS[1]과 동일한 키)
--   ARGV[1] userId (10진수 문자열, number로 변환하지 않는다)
--   ARGV[2] purpose
--   ARGV[3] jti
--   ARGV[4] tokenHash
--   ARGV[5] expiresAt (epoch seconds, 문자열)
--   ARGV[6] 호출자가 읽은 action-current 값 ('' 이면 "없음"을 기대한다)
-- 반환: 'OK' | 'ALREADY_EXISTS' | 'CONTENTION'
--   CONTENTION은 adapter 내부 재시도용이다. ActionTokenStore의 공개 결과로 새어 나가지 않는다.

local tokenKey = KEYS[1]
local currentKey = KEYS[2]
local previousTokenKey = KEYS[3]

local userId = ARGV[1]
local purpose = ARGV[2]
local jti = ARGV[3]
local tokenHash = ARGV[4]
local expiresAt = ARGV[5]
local expectedCurrent = ARGV[6]

if redis.call('EXISTS', tokenKey) == 1 then
  return 'ALREADY_EXISTS'
end

-- 쓰기 전 compare. GET은 키가 없으면 false를 돌려준다.
local current = redis.call('GET', currentKey)
if expectedCurrent == '' then
  if current then
    return 'CONTENTION'
  end
elseif current ~= expectedCurrent then
  return 'CONTENTION'
end

if expectedCurrent ~= '' then
  if redis.call('HGET', previousTokenKey, 'status') == 'ACTIVE' then
    -- 만료는 앞당기지 않는다. 원래 exp까지 남겨야 재제출을 '없는 토큰'과 구분할 수 있다.
    redis.call('HSET', previousTokenKey, 'status', 'INVALIDATED')
  end
end

redis.call('HSET', tokenKey,
  'userId', userId,
  'purpose', purpose,
  'tokenHash', tokenHash,
  'expiresAt', expiresAt,
  'status', 'ACTIVE')
redis.call('EXPIREAT', tokenKey, expiresAt)

-- action-current는 현재 활성 토큰만 가리킨다. 같은 용도의 다음 발급이 이 값을 덮어쓴다.
redis.call('SET', currentKey, jti)
redis.call('EXPIREAT', currentKey, expiresAt)

return 'OK'
