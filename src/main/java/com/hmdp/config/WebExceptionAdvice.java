package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

//    为了方便测试，先把全局异常处理器WebExceptionAdvice中的RuntimeException拦截注释掉，方便我们在JMeter中查看请求的异常率
//    （如果不注释直接返回Result.fail()或者抛出RuntimeException，JMeter这边也会显示请求成功）
//    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
