-- 判断是否是自己的锁（比较标识）
if( redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('DEL', KEYS[1])
end
return 0