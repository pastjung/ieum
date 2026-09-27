-- 음성 합성 사용량 카운터 (docs/product/api-spec.md 12절).
-- KEYS[1] = voice:rate:{userId}:{epochMinute}, ARGV[1] = TTL(초)
-- INCR과 EXPIRE를 한 번에 실행해, 두 명령 사이에 연결이 끊겨 TTL 없는 키가 남는 일을 막는다.
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
end
return count
