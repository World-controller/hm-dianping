-- 判断锁中的线程表示是否与指定的表示一致
-- 如果一致则释放锁
if(redis.call('GET', KEYS[1]) == ARGV[1]) then
    return redis.call('DEL', KEYS[1])
end
-- 如果不一致则什么都不做
return 0
