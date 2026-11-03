package com.hihops.hops.global.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 사용자가 입력한 본문 (DATA_TYPES 신규 절 UserContent). 문의 생성·메시지 전송·정정에 쓴다.
 *
 * <p>1~4,000 Unicode 코드 포인트이고, 단독 surrogate와 공백만 있는 입력은 거부한다. 원문은 자르거나 trim하지 않고 그대로 저장한다.
 * null은 통과시키므로 필수 값이면 {@code @NotNull}을 함께 붙인다. 위반하면 fieldErrors의 code는 "UserContent"다.
 */
@Documented
@Constraint(validatedBy = UserContentValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface UserContent {

    String message() default "1~4,000자의 내용을 입력해 주세요.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
