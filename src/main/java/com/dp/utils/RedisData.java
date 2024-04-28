package com.dp.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 优先用组合，少用继承
 *
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
