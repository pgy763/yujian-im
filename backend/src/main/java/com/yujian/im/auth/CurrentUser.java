package com.yujian.im.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 打在 Controller 方法参数上，Spring 会把当前登录用户的 userId 注入进来。
 *
 * <p>配合 {@link CurrentUserArgumentResolver} 使用，
 * 好处是 Controller 里不用反复写 request.getAttribute("userId") 再强转。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
