package com.yujian.im.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：不让业务异常以 500 + 堆栈的形式糊到前端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> onBiz(BizException e) {
        // 业务异常是预期内的，用 warn 不用 error，免得污染告警
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> onValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("参数校验失败");
        return R.fail(400, msg);
    }

    @ExceptionHandler(Exception.class)
    public R<Void> onOther(Exception e) {
        // 未知异常必须打全栈，否则线上只能靠猜
        log.error("未处理异常", e);
        return R.fail(500, "服务器开小差了");
    }
}
