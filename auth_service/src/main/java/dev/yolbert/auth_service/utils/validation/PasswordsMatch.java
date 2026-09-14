package dev.yolbert.auth_service.utils.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PasswordsMatchValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordsMatch {

    String message() default "Las contraseñas no coinciden.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
