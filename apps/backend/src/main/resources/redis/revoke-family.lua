-- revokeFamily (docs/product/api-spec.md 3.6.1절)
--
-- 계열 hash의 status만 REVOKED로 바꾼다. 토큰 hash의 상태는 건드리지 않는다 —
-- ROTATED 표시는 재사용 탐지의 근거이고, 계약이 revokeFamily의 책임을 family 상태로 한정한다.
-- 계열 차단의 판단 근거는 family.status다.
--
-- 만료를 앞당기지 않는다. 아직 수명이 남은 토큰의 재사용을 계속 판별할 수 있어야 한다.
--   KEYS[1] family hash   <prefix>:auth:{<userId>}:family:<familyId>
-- 반환: 'OK' | 'NOT_FOUND'

local familyKey = KEYS[1]

if redis.call('EXISTS', familyKey) == 0 then
  return 'NOT_FOUND'
end

redis.call('HSET', familyKey, 'status', 'REVOKED')
return 'OK'
